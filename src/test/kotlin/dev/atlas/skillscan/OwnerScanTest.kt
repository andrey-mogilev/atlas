package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class OwnerScanTest {
    private val plain = CliPresentation(false, false, 80)

    private fun account(login: String, type: String) = """{"login":"$login","type":"$type","public_repos":3}"""

    private fun listing(vararg fullNames: String) = fullNames.joinToString(",", "[", "]") {
        """{"name":"${it.substringAfter('/')}","full_name":"$it","html_url":"https://github.com/$it","private":false}"""
    }

    private fun api(maxRepositories: Int = DEFAULT_MAX_OWNER_REPOSITORIES, requests: MutableList<String> = mutableListOf(),
                    responses: (String) -> ApiResponse) = GitHubApi(null, maxRepositories) { url, _ ->
        requests += url
        responses(url)
    }

    private fun stored(url: String, skills: Int, locations: Int) = StoredRepository(
        1, url, 1, url, "main", "a".repeat(40), "2026-01-01T00:00:00Z", skills, locations)

    private fun plan(vararg fullNames: String, truncated: Boolean = false) = OwnerPlan("acme", "Organization",
        "https://github.com/acme", fullNames.map { OwnerRepository(it, "https://github.com/$it") }, truncated)

    @Test fun `only single-segment GitHub web URLs identify an owner`() {
        assertEquals("acme", githubOwner("https://github.com/acme"))
        assertEquals("acme", githubOwner("http://github.com/acme"))
        assertEquals("Acme-Org", githubOwner(canonicalUrl("https://GitHub.com/Acme-Org/")))
        assertEquals("a", githubOwner("https://github.com/a"))
        for (other in listOf("https://github.com/acme/repo", "https://github.com/acme/repo.git",
            "https://gitlab.com/acme", "https://github.com:8443/acme", "git@github.com:acme",
            "https://user@github.com/acme", "file:///tmp/acme", "https://github.com/-acme",
            "https://github.com/acme-", "https://github.com/acme.test", "https://github.com/" + "a".repeat(40))) {
            assertNull(githubOwner(other), other)
        }
    }

    @Test fun `an owner plan lists every page of repositories in deterministic order`() {
        val requests = mutableListOf<String>()
        val first = listing(*(1..100).map { "acme/repo-%03d".format(it) }.toTypedArray())
        val resolved = api(requests = requests) { url ->
            when {
                url.endsWith("/users/acme") -> ApiResponse(200, account("acme", "Organization"))
                url.endsWith("&page=1") -> ApiResponse(200, first)
                url.endsWith("&page=2") -> ApiResponse(200, listing("acme/alpha", "acme/alpha"))
                else -> ApiResponse(200, "[]")
            }
        }.plan("acme")
        assertEquals("Organization", resolved.type)
        assertEquals("https://github.com/acme", resolved.url)
        assertEquals(listOf("$GITHUB_API_ORIGIN/users/acme",
            "$GITHUB_API_ORIGIN/orgs/acme/repos?per_page=100&page=1",
            "$GITHUB_API_ORIGIN/orgs/acme/repos?per_page=100&page=2"), requests)
        assertEquals(101, resolved.repositories.size, "a repeated repository must be listed once")
        assertEquals("acme/alpha", resolved.repositories.first().fullName)
        assertEquals("https://github.com/acme/repo-001", resolved.repositories[1].url)
        assertFalse(resolved.truncated)
    }

    @Test fun `a user account uses the user listing and respects the repository limit`() {
        val requests = mutableListOf<String>()
        val resolved = api(2, requests) { url ->
            if (url.endsWith("/users/dev")) ApiResponse(200, account("dev", "User"))
            else ApiResponse(200, listing("dev/one", "dev/two", "dev/three"))
        }.plan("dev")
        assertEquals("User", resolved.type)
        assertEquals("$GITHUB_API_ORIGIN/users/dev/repos?per_page=100&page=1", requests[1])
        assertEquals(listOf("dev/one", "dev/two"), resolved.repositories.map { it.fullName })
        assertTrue(resolved.truncated)
    }

    @Test fun `GitHub failures are reported without exposing credentials`() {
        val missing = assertThrows(ScanFailure::class.java) { api { ApiResponse(404, "{}") }.plan("ghost") }
        assertEquals(3, missing.exitCode)
        assertTrue(missing.message!!.contains("not found: ghost"))
        val refused = assertThrows(ScanFailure::class.java) { api { ApiResponse(403, "{}") }.plan("acme") }
        assertEquals(3, refused.exitCode)
        assertTrue(refused.message!!.contains("GITHUB_TOKEN"))
        val unexpected = assertThrows(ScanFailure::class.java) { api { ApiResponse(500, "{}") }.plan("acme") }
        assertEquals(1, unexpected.exitCode)
        val unreachable = assertThrows(ScanFailure::class.java) { api { error("socket closed") }.plan("acme") }
        assertEquals(3, unreachable.exitCode)
        assertFalse(unreachable.message!!.contains("socket closed"))
        val malformed = assertThrows(ScanFailure::class.java) { api { ApiResponse(200, "{not: [json") }.plan("acme") }
        assertEquals(1, malformed.exitCode)
    }

    @Test fun `already scanned repositories are reported from storage unless a rescan is requested`() {
        val previous = mapOf("https://github.com/acme/saved" to stored("https://github.com/acme/saved", 3, 4))
        val scanned = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val result = scanOwnedRepositories(plan("acme/fresh", "acme/saved"), previous, { url ->
            scanned += url
            ScanResult(url, url, Snapshot("main", "a".repeat(40), emptyList()),
                listOf(Finding("skl_1", url, "skills/one/SKILL.md", "One.", "a".repeat(40), "hash")))
        }) { progress += "${it.fullName}:${it.status}" }

        assertEquals(listOf("https://github.com/acme/fresh"), scanned)
        assertEquals(listOf("acme/fresh:scanned", "acme/saved:skipped"), progress)
        assertEquals(listOf(1, 3), result.outcomes.map { it.skillCount })
        assertEquals(listOf(1, 4), result.outcomes.map { it.locationCount })
        assertEquals(4, result.skillCount)
        assertEquals(5, result.locationCount)

        val rescanned = mutableListOf<String>()
        scanOwnedRepositories(plan("acme/fresh", "acme/saved"), emptyMap(), { url ->
            rescanned += url
            ScanResult(url, url, Snapshot("main", "a".repeat(40), emptyList()), emptyList())
        })
        assertEquals(listOf("https://github.com/acme/fresh", "https://github.com/acme/saved"), rescanned)
    }

    @Test fun `one unscannable repository does not abandon the remaining ones`() {
        val result = scanOwnedRepositories(plan("acme/broken", "acme/good"), emptyMap(), { url ->
            if (url.endsWith("broken")) throw ScanFailure(4, "default branch could not be resolved: $url")
            ScanResult(url, url, Snapshot("main", "b".repeat(40), emptyList()),
                listOf(Finding("skl_2", url, "skills/two/SKILL.md", "Two.", "b".repeat(40), "hash")))
        })
        assertEquals(1, result.failed.size)
        assertEquals(4, result.failed.single().code)
        assertEquals(listOf("acme/good"), result.scanned.map { it.fullName })
        assertEquals(1, result.skillCount)
    }

    @Test fun `owner options are parsed and conflicting combinations are refused`() {
        val options = parseScanOptions(arrayOf("scan", "https://github.com/acme", "--scan-organizations", "--rescan"))
        assertTrue(options.scanOwners)
        assertTrue(options.rescan)
        assertTrue(parseScanOptions(arrayOf("scan", "-scan-organizations", "https://github.com/acme")).scanOwners)
        assertFalse(parseScanOptions(arrayOf("scan", "./folder")).scanOwners)
        for (args in listOf(
            arrayOf("scan", "https://github.com/acme", "--rescan"),
            arrayOf("scan", "https://github.com/acme", "--scan-organizations", "--branch", "main"),
            arrayOf("scan", "https://github.com/acme", "--scan-organizations", "-scan-organizations"),
            arrayOf("scan", "https://github.com/acme", "--scan-organization")
        )) {
            assertEquals(2, assertThrows(ScanFailure::class.java) { parseScanOptions(args) }.exitCode, args.joinToString(" "))
        }
        assertTrue(CLI_HELP.contains("--scan-organizations"))
        assertTrue(CLI_HELP.contains("--rescan"))
    }

    @Test fun `an owner URL without the option is refused before any network request`() {
        val errors = java.io.ByteArrayOutputStream()
        val original = System.err
        System.setErr(java.io.PrintStream(errors, true, "UTF-8"))
        val code = try {
            runCli(arrayOf("scan", "https://github.com/acme"), mapOf("NO_COLOR" to "1"))
        } finally { System.setErr(original) }
        assertEquals(2, code)
        val message = errors.toString("UTF-8")
        assertTrue(message.contains("--scan-organizations"), message)
        assertTrue(message.contains("not a repository"), message)
    }

    @Test fun `owner reports summarize scanned skipped and failed repositories`() {
        val result = OwnerScanResult(plan("acme/one", "acme/two", "acme/three", truncated = true), listOf(
            OwnerRepositoryOutcome("acme/one", "https://github.com/acme/one", OWNER_SCANNED, 2, 3),
            OwnerRepositoryOutcome("acme/two", "https://github.com/acme/two", OWNER_SKIPPED, 1, 1),
            OwnerRepositoryOutcome("acme/three", "https://github.com/acme/three", OWNER_FAILED,
                code = 4, message = "branch not found")
        ))
        val report = formatOwner(result, plain, false)
        assertTrue(report.startsWith("Scanned 1 of 3 repositories in acme (organization)"), report)
        assertTrue(report.contains("3 skills across 4 locations in 2 repositories"), report)
        assertTrue(report.contains("1. acme/one  2 skills across 3 locations"), report)
        assertTrue(report.contains("Reported 1 already scanned repository"), report)
        assertTrue(report.contains("1 repository could not be scanned:"), report)
        assertTrue(report.contains("acme/three  code 4: branch not found"), report)
        assertTrue(report.contains("SKILL_SCAN_MAX_OWNER_REPOSITORIES"), report)
        assertFalse(report.contains("https://github.com/acme/one"), "compact output omits repository URLs")
        assertTrue(formatOwner(result, plain, true).contains("https://github.com/acme/one"))

        val data = Yaml().load<Map<String, Any?>>(formatOwnerJson(result))
        assertEquals("owner", data["kind"])
        assertEquals("acme", data["owner"])
        assertEquals("Organization", data["ownerType"])
        assertEquals(3, data["repositoryCount"])
        assertEquals(true, data["truncated"])
        assertEquals(listOf(1, 1, 1), listOf(data["scannedCount"], data["skippedCount"], data["failedCount"]))
        assertEquals(3, data["skillCount"])
        assertEquals(4, data["locationCount"])
        val failed = (data["repositories"] as List<*>).last() as Map<*, *>
        assertEquals("failed", failed["status"])
        assertEquals(4, failed["code"])
        assertEquals("branch not found", failed["message"])
    }

    @Test fun `an owner without visible repositories is reported explicitly`() {
        assertEquals("No repositories are visible in acme (organization).",
            formatOwner(OwnerScanResult(plan(), emptyList()), plain, false))
    }
}
