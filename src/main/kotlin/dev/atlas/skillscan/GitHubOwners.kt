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

/** Statuses with which GitHub says an endpoint is not for these credentials. */
private val OUT_OF_CREDENTIAL_SCOPE = setOf(401, 403, 404)

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
 * One repository listing endpoint. [wrapped] marks a response that carries its entries under a
 * `repositories` key rather than being an array; [skippable] marks one that the credentials may
 * not have at all, which is absence rather than failure.
 */
private data class RepositoryListing(val url: String, val skippable: Boolean = false, val wrapped: Boolean = false)

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
        val repositories = linkedMapOf<String, OwnerRepository>()
        var truncated = false
        for (listing in repositoryListings(canonicalLogin, type)) {
            if (readListing(listing, canonicalLogin, repositories)) truncated = true
        }
        return OwnerPlan(canonicalLogin, type, "https://github.com/$canonicalLogin",
            repositories.values.sortedWith { a, b -> compareUtf8(a.fullName, b.fullName) }, truncated)
    }

    /**
     * Which endpoints can see an account's repositories depends on the credentials:
     *
     * - the organization endpoint already includes the private and internal repositories a token
     *   can see, so it is the only listing needed for an organization;
     * - the public user endpoint never includes private repositories. The token's own account is
     *   listed through `affiliation=owner` instead, and any other user's private repositories are
     *   reachable when the token holder collaborates on them, so `affiliation=collaborator` is
     *   merged into the public listing. Organization membership cannot contribute to a user's
     *   listing, because those repositories are owned by the organization;
     * - a GitHub App installation token, which is what a workflow's `GITHUB_TOKEN` is, has no user
     *   identity at all. Its repositories are visible only through the installation listing, so
     *   that listing is merged in whenever a token cannot name a user.
     */
    private fun repositoryListings(login: String, type: String): List<RepositoryListing> {
        val own = if (token == null) null else authenticatedLogin()
        val listings = mutableListOf<RepositoryListing>()
        when {
            type == "Organization" -> listings += RepositoryListing("$GITHUB_API_ORIGIN/orgs/$login/repos?")
            own != null && own.equals(login, ignoreCase = true) ->
                listings += RepositoryListing("$GITHUB_API_ORIGIN/user/repos?affiliation=owner&")
            else -> {
                listings += RepositoryListing("$GITHUB_API_ORIGIN/users/$login/repos?")
                if (own != null) {
                    listings += RepositoryListing("$GITHUB_API_ORIGIN/user/repos?affiliation=collaborator&")
                }
            }
        }
        if (token != null && own == null) {
            listings += RepositoryListing("$GITHUB_API_ORIGIN/installation/repositories?",
                skippable = true, wrapped = true)
        }
        return listings
    }

    /**
     * Reads one listing into the merged set and reports whether a limit cut it short. A skippable
     * listing is skipped only when its first page says the credentials do not have that endpoint,
     * so a token with no GitHub App installation still scans its primary listing. Every other
     * failure, including one on a later page, propagates: a listing that stopped early must never
     * be reported as a complete plan.
     */
    private fun readListing(
        listing: RepositoryListing,
        login: String,
        into: MutableMap<String, OwnerRepository>
    ): Boolean {
        var page = 1
        while (true) {
            val url = "${listing.url}per_page=$REPOSITORY_PAGE_SIZE&page=$page"
            val body = if (listing.skippable && page == 1) readIfAvailable(url, login) ?: return false
            else read(url, login)
            val decoded = decode(body)
            val entries = (if (listing.wrapped) (decoded as? Map<*, *>)?.get("repositories") else decoded) as? List<*>
                ?: throw ScanFailure(1, "GitHub returned an unexpected repository listing for $login")
            for (entry in entries) {
                val repository = (entry as? Map<*, *>)?.let { ownerRepository(it, login) } ?: continue
                if (repository.url in into) continue
                if (into.size >= maxRepositories) return true
                into[repository.url] = repository
            }
            if (entries.size < REPOSITORY_PAGE_SIZE) return false
            if (page >= MAX_REPOSITORY_PAGES) return true
            page++
        }
    }

    /**
     * Identifies the token's own account. An installation token has no such account and says so
     * explicitly, which is absence rather than failure; any other failure is reported, so a
     * transient error cannot quietly downgrade the scan to the public listing.
     */
    private fun authenticatedLogin(): String? {
        val body = readIfAvailable("$GITHUB_API_ORIGIN/user", "the authenticated account") ?: return null
        return ((decode(body) as? Map<*, *>)?.get("login") as? String)?.takeUnless { it.isBlank() }
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

    private fun read(url: String, login: String): String = interpret(fetch(url), login)

    /**
     * Reads an endpoint these credentials may simply not have, returning null when they do not.
     * Only an explicit "not for you" answer counts as absence: a rate limit shares HTTP 403 with
     * it but is transient, and every other failure is reported as usual, so an incomplete listing
     * can never be mistaken for a complete one.
     */
    private fun readIfAvailable(url: String, login: String): String? {
        val response = fetch(url)
        if (response.status in OUT_OF_CREDENTIAL_SCOPE && !response.body.contains("rate limit", true)) return null
        return interpret(response, login)
    }

    private fun fetch(url: String): ApiResponse = try {
        get(url, token)
    } catch (failure: ScanFailure) {
        throw failure
    } catch (_: Exception) {
        throw ScanFailure(3, "GitHub could not be reached at $GITHUB_API_ORIGIN")
    }

    private fun interpret(response: ApiResponse, login: String): String = when (response.status) {
        200 -> response.body
        404 -> throw ScanFailure(3, "GitHub user or organization not found: $login")
        401, 403, 429 -> throw ScanFailure(3, "GitHub refused the request for $login (HTTP ${response.status}); " +
            "check SKILL_SCAN_GITHUB_TOKEN or GITHUB_TOKEN, or wait for the API rate limit to reset")
        else -> throw ScanFailure(1, "GitHub returned an unexpected response for $login (HTTP ${response.status})")
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
