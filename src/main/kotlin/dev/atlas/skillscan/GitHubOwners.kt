package dev.atlas.skillscan

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

internal const val GITHUB_API_ORIGIN = "https://api.github.com"
internal const val DEFAULT_MAX_OWNER_REPOSITORIES = 500
private const val REPOSITORY_PAGE_SIZE = 100
private const val MAX_REPOSITORY_PAGES = 100
private const val MAX_API_RESPONSE_BYTES = 2L * 1024 * 1024

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
        val listing = if (type == "Organization") "$GITHUB_API_ORIGIN/orgs/$canonicalLogin/repos"
        else "$GITHUB_API_ORIGIN/users/$canonicalLogin/repos"
        val repositories = linkedMapOf<String, OwnerRepository>()
        var truncated = false
        var page = 1
        while (true) {
            val entries = decode(read("$listing?per_page=$REPOSITORY_PAGE_SIZE&page=$page", canonicalLogin)) as? List<*>
                ?: throw ScanFailure(1, "GitHub returned an unexpected repository listing for $canonicalLogin")
            for (entry in entries) {
                val repository = (entry as? Map<*, *>)?.let(::ownerRepository) ?: continue
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

    private fun ownerRepository(entry: Map<*, *>): OwnerRepository? {
        val fullName = (entry["full_name"] as? String)?.takeUnless { it.isBlank() } ?: return null
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

internal fun githubApiRequest(url: String, token: String?): ApiResponse {
    val request = HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "skill-atlas")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .GET()
        .build()
    val response = apiClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    return ApiResponse(response.statusCode(), response.body().use(::readBoundedText))
}

private val apiClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
}

private fun readBoundedText(stream: InputStream): String {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = stream.read(chunk)
        if (count < 0) break
        if (buffer.size().toLong() + count > MAX_API_RESPONSE_BYTES) {
            throw ScanFailure(1, "GitHub response exceeded the configured limit")
        }
        buffer.write(chunk, 0, count)
    }
    return buffer.toByteArray().toString(UTF_8)
}
