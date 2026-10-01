package dev.atlas.skillscan

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val GITHUB_API_ORIGIN = "https://api.github.com"
internal const val DEFAULT_MAX_OWNER_REPOSITORIES = 500
private const val REPOSITORY_PAGE_SIZE = 100
private const val MAX_REPOSITORY_PAGES = 100
private const val MAX_API_RESPONSE_BYTES = 2L * 1024 * 1024
private val DEFAULT_API_DEADLINE: Duration = Duration.ofSeconds(30)

internal data class OwnerRepository(val fullName: String, val url: String)

internal data class OwnerPlan(
    val login: String,
    val type: String,
    val url: String,
    val repositories: List<OwnerRepository>,
    val truncated: Boolean
)

internal data class ApiResponse(val status: Int, val body: String)

/**
 * A GitHub URL with a single path segment names a user or organization rather than a repository.
 * Detection is offline so that the CLI can refuse such a target without any network request.
 */
internal fun githubOwner(canonicalUrl: String): String? {
    val uri = runCatching { URI(canonicalUrl) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("https", "http")) return null
    if (uri.host?.lowercase() != "github.com") return null
    if (uri.port !in setOf(-1, 80, 443)) return null
    if (uri.userInfo != null) return null
    val login = uri.path.orEmpty().trim('/')
    // GitHub logins are alphanumeric with inner hyphens and at most 39 characters.
    if (!Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?").matches(login)) return null
    return login
}

/** Reads the repository list a GitHub account owns; it never fetches or executes repository content. */
internal class GitHubApi(
    private val token: String? = null,
    private val maxRepositories: Int = DEFAULT_MAX_OWNER_REPOSITORIES,
    private val get: (String, String?) -> ApiResponse = ::githubApiRequest
) {
    fun plan(login: String): OwnerPlan {
        val account = decode(read("$GITHUB_API_ORIGIN/users/$login", login)) as? Map<*, *>
            ?: throw ScanFailure(1, "GitHub returned an unexpected account description for $login")
        val canonicalLogin = (account["login"] as? String)?.takeUnless { it.isBlank() } ?: login
        val type = (account["type"] as? String)?.takeUnless { it.isBlank() } ?: "User"
        val listing = repositoryListing(canonicalLogin, type)
        val repositories = linkedMapOf<String, OwnerRepository>()
        var truncated = false
        var page = 1
        while (true) {
            val entries = decode(read("${listing}per_page=$REPOSITORY_PAGE_SIZE&page=$page", canonicalLogin)) as? List<*>
                ?: throw ScanFailure(1, "GitHub returned an unexpected repository listing for $canonicalLogin")
            for (entry in entries) {
                val repository = (entry as? Map<*, *>)?.let { ownerRepository(it, canonicalLogin) } ?: continue
                if (repository.url in repositories) continue
                if (repositories.size >= maxRepositories) {
                    truncated = true
                    break
                }
                repositories[repository.url] = repository
            }
            if (truncated || entries.size < REPOSITORY_PAGE_SIZE) break
            if (page >= MAX_REPOSITORY_PAGES) {
                truncated = true
                break
            }
            page++
        }
        return OwnerPlan(canonicalLogin, type, "https://github.com/$canonicalLogin",
            repositories.values.sortedWith { a, b -> compareUtf8(a.fullName, b.fullName) }, truncated)
    }

    /**
     * The organization endpoint already includes the private repositories a token can see, but the
     * public user endpoint never does. An account that is the token's own is therefore listed
     * through the authenticated endpoint; another account's private repositories cannot be listed
     * at all, so a public listing stays correct for every other user.
     */
    private fun repositoryListing(login: String, type: String): String = when {
        type == "Organization" -> "$GITHUB_API_ORIGIN/orgs/$login/repos?"
        token != null && authenticatedLogin().equals(login, ignoreCase = true) ->
            "$GITHUB_API_ORIGIN/user/repos?affiliation=owner&"
        else -> "$GITHUB_API_ORIGIN/users/$login/repos?"
    }

    private fun authenticatedLogin(): String? {
        val self = decode(read("$GITHUB_API_ORIGIN/user", "the authenticated account")) as? Map<*, *> ?: return null
        return (self["login"] as? String)?.takeUnless { it.isBlank() }
    }

    private fun ownerRepository(entry: Map<*, *>, login: String): OwnerRepository? {
        val fullName = (entry["full_name"] as? String)?.takeUnless { it.isBlank() } ?: return null
        // full_name is always "<owner>/<name>", so this keeps a listing to the requested account.
        if (!fullName.startsWith("$login/", ignoreCase = true)) return null
        val link = (entry["html_url"] as? String)?.takeUnless { it.isBlank() } ?: return null
        val url = runCatching { canonicalUrl(link) }.getOrNull() ?: return null
        // Only repository URLs are usable; anything else cannot be scanned as a repository.
        if (githubOwner(url) != null) return null
        return OwnerRepository(fullName, url)
    }

    private fun read(url: String, login: String): String {
        val response = try {
            get(url, token)
        } catch (failure: ScanFailure) {
            throw failure
        } catch (_: Exception) {
            throw ScanFailure(3, "GitHub could not be reached at $GITHUB_API_ORIGIN")
        }
        return when (response.status) {
            200 -> response.body
            404 -> throw ScanFailure(3, "GitHub user or organization not found: $login")
            401, 403, 429 -> throw ScanFailure(3, "GitHub refused the request for $login (HTTP ${response.status}); " +
                "check SKILL_SCAN_GITHUB_TOKEN or GITHUB_TOKEN, or wait for the API rate limit to reset")
            else -> throw ScanFailure(1, "GitHub returned an unexpected response for $login (HTTP ${response.status})")
        }
    }

    /** JSON is read as YAML with a safe constructor: no Java types are instantiated from the response. */
    private fun decode(body: String): Any? {
        val options = LoaderOptions().apply { codePointLimit = MAX_API_RESPONSE_BYTES.toInt() }
        return try {
            Yaml(SafeConstructor(options)).load<Any?>(body)
        } catch (_: Exception) {
            throw ScanFailure(1, "GitHub returned a response that could not be read")
        }
    }
}

/**
 * The request timeout only bounds the response headers, so the deadline is applied to the whole
 * exchange: a server that sends headers and then stalls its body would otherwise block the caller
 * forever, holding the sole scan worker or a web request thread.
 */
internal fun githubApiRequest(
    url: String,
    token: String?,
    deadline: Duration = DEFAULT_API_DEADLINE,
    maxBytes: Long = MAX_API_RESPONSE_BYTES
): ApiResponse {
    val request = HttpRequest.newBuilder(URI(url))
        .timeout(deadline)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "skill-atlas")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .GET()
        .build()
    val pending = apiClient.sendAsync(request, boundedText(maxBytes))
    val response = try {
        pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        pending.cancel(true)
        throw ScanFailure(3, "GitHub did not answer within ${deadline.toSeconds()} seconds")
    } catch (interrupted: InterruptedException) {
        pending.cancel(true)
        Thread.currentThread().interrupt()
        throw ScanFailure(1, "Reading from GitHub was interrupted")
    } catch (failed: ExecutionException) {
        throw failed.cause as? ScanFailure ?: failed
    }
    return ApiResponse(response.statusCode(), response.body())
}

private val apiClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
}

private fun boundedText(maxBytes: Long): HttpResponse.BodyHandler<String> =
    HttpResponse.BodyHandler { BoundedTextSubscriber(maxBytes) }

/** Collects a response body as text and fails as soon as it grows past the limit. */
private class BoundedTextSubscriber(private val maxBytes: Long) : HttpResponse.BodySubscriber<String> {
    private val text = CompletableFuture<String>()
    private val buffer = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<String> = text

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        for (chunk in item) {
            if (buffer.size().toLong() + chunk.remaining() > maxBytes) {
                subscription?.cancel()
                text.completeExceptionally(ScanFailure(1, "GitHub response exceeded the configured limit"))
                return
            }
            val bytes = ByteArray(chunk.remaining())
            chunk.get(bytes)
            buffer.write(bytes)
        }
    }

    override fun onError(throwable: Throwable) {
        text.completeExceptionally(throwable)
    }

    override fun onComplete() {
        text.complete(buffer.toByteArray().toString(UTF_8))
    }
}
