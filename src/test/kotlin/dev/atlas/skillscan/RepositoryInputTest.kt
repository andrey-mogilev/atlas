package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RepositoryInputTest {
    private val url = "https://github.com/andrey-mogilev/atlas-test"
    private val ssh = "git@github.com:andrey-mogilev/atlas-test.git"
    private val snapshot = Snapshot("main", "a".repeat(40), emptyList())

    @Test fun `GitHub URLs and copied Markdown links resolve to the same target`() {
        val target = scanTarget(url)
        assertEquals(url, target.canonical)
        assertNull(target.localDirectory)
        assertEquals(target, scanTarget("[$url]($url)"))
        assertEquals(target, scanTarget("  `[Atlas test]($url)`  "))
        assertEquals("$url.git", scanTarget("[Repository]($url.git)").canonical)
        assertEquals(2, assertThrows(ScanFailure::class.java) { scanTarget("[Bad](https://)") }.exitCode)
        assertEquals(2, assertThrows(ScanFailure::class.java) { scanTarget("[Bad]($url) extra") }.exitCode)
    }

    @Test fun `inaccessible GitHub HTTPS retries the same repository using SSH`() {
        val calls = mutableListOf<String>()
        val result = scanRemote(url, "release") { transport, branch ->
            calls += transport
            assertEquals("release", branch)
            if (transport == url) throw ScanFailure(3, "inaccessible")
            snapshot
        }
        assertEquals(snapshot, result)
        assertEquals(listOf(url, ssh), calls)
        val withSuffix = mutableListOf<String>()
        scanRemote("$url.git", null) { transport, _ ->
            withSuffix += transport
            if (transport.startsWith("https:")) throw ScanFailure(3, "inaccessible")
            snapshot
        }
        assertEquals(listOf("$url.git", ssh), withSuffix)
    }

    @Test fun `successful HTTPS and branch or content failures do not trigger SSH`() {
        var calls = 0
        assertEquals(snapshot, scanRemote(url, null) { _, _ -> calls++; snapshot })
        assertEquals(1, calls)
        for (code in listOf(1, 2, 4, 5)) {
            calls = 0
            val error = assertThrows(ScanFailure::class.java) {
                scanRemote(url, null) { _, _ -> calls++; throw ScanFailure(code, "original failure") }
            }
            assertEquals(code, error.exitCode)
            assertEquals("original failure", error.message)
            assertEquals(1, calls)
        }
    }

    @Test fun `fallback is restricted to standard HTTPS GitHub repository URLs`() {
        for (other in listOf("https://gitlab.com/org/repo", "http://github.com/org/repo", "https://github.com:8443/org/repo",
            "https://github.com/org/repo/tree/main", ssh, "file:///tmp/repo")) {
            var calls = 0
            assertThrows(ScanFailure::class.java) {
                scanRemote(other, null) { _, _ -> calls++; throw ScanFailure(3, "inaccessible") }
            }
            assertEquals(1, calls, other)
        }
    }

    @Test fun `both transport failures produce an actionable error for the requested URL`() {
        val error = assertThrows(ScanFailure::class.java) {
            scanRemote(url, null) { _, _ -> throw ScanFailure(3, "inaccessible") }
        }
        assertEquals(3, error.exitCode)
        assertTrue(error.message!!.contains(url))
        assertTrue(error.message!!.contains("HTTPS and SSH"))
        assertTrue(error.message!!.contains("credentials"))
    }
}
