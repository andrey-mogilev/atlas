package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class ScannerTest {
    @TempDir lateinit var temp: Path

    @Test fun `description uses YAML and falls back to Markdown`() {
        assertEquals("Release guidance", extractDescription("---\nname: release\ndescription: Release guidance\n---\n# Release\nBody"))
        assertEquals("First paragraph continues here.", extractDescription("---\ninvalid: [\n---\n# Heading\n\nFirst paragraph\ncontinues here."))
        assertEquals("", extractDescription("# Empty"))
    }

    @Test fun `formatted output has path link and description on separate lines`() {
        val commit = "a".repeat(40)
        val snapshot = Snapshot("main", commit, emptyList())
        val finding = Finding("skl_123", "https://github.com/acme/agent-skills.git", "skills/release/SKILL.md", "Release guidance", commit)
        assertEquals(
            "Found 1 skill on main at $commit:\n\n" +
                "skills/release/SKILL.md  [skl_123]\n" +
                "  Link: https://github.com/acme/agent-skills/blob/$commit/skills/release/SKILL.md\n" +
                "  Description: Release guidance",
            formatFindings(snapshot, listOf(finding))
        )
        assertEquals("No SKILL.md files found on main at $commit.", formatFindings(snapshot, emptyList()))
        assertEquals(
            "https://github.com/acme/agent-skills/blob/$commit/skills/release/SKILL.md",
            finding.copy(repositoryUrl = "git@github.com:acme/agent-skills.git").sourceLink()
        )
        assertEquals("https://example.org/acme/skills.git", finding.copy(repositoryUrl = "https://example.org/acme/skills.git").sourceLink())
    }

    @Test fun `scans selected branch and stores stable ids and versions`() {
        val repo = temp.resolve("source")
        git("init", "-b", "trunk", repo.toString())
        git("-C", repo.toString(), "config", "user.email", "test@example.org")
        git("-C", repo.toString(), "config", "user.name", "Test")
        Files.createDirectories(repo.resolve("alpha"))
        Files.createDirectories(repo.resolve("zeta"))
        Files.createDirectories(repo.resolve("other"))
        Files.writeString(repo.resolve("alpha/SKILL.md"), "---\ndescription: Alpha skill\n---\n# Alpha\n")
        Files.writeString(repo.resolve("zeta/SKILL.md"), "# Zeta\n\nZeta details.\n")
        Files.writeString(repo.resolve("other/skill.md"), "Ignored")
        Files.createSymbolicLink(repo.resolve("zeta/link/SKILL.md").also { Files.createDirectories(it.parent) }, repo.resolve("alpha/SKILL.md"))
        git("-C", repo.toString(), "add", ".")
        git("-C", repo.toString(), "commit", "-qm", "initial")
        val trunkCommit = git("-C", repo.toString(), "rev-parse", "HEAD").trim()
        git("-C", repo.toString(), "switch", "-qc", "feature")
        Files.writeString(repo.resolve("alpha/SKILL.md"), "---\ndescription: Updated skill\n---\n")
        git("-C", repo.toString(), "add", ".")
        git("-C", repo.toString(), "commit", "-qm", "update")
        git("-C", repo.toString(), "switch", "-q", "trunk")

        val url = repo.toUri().toString()
        val scanner = GitScanner(1_048_576, 10_485_760)
        val database = SkillDatabase(temp.resolve("scan.db"))
        val trunk = scanner.scan(url, null)
        assertEquals("trunk", trunk.branch)
        assertEquals(trunkCommit, trunk.commit)
        assertEquals(listOf("alpha/SKILL.md", "zeta/SKILL.md"), trunk.skills.map { it.path })
        assertEquals(listOf("Alpha skill", "Zeta details."), trunk.skills.map { it.description })
        val first = database.save(canonicalUrl(url), canonicalUrl(url), trunk.branch, trunk.commit, trunk.skills)
        val again = database.save(canonicalUrl(url), canonicalUrl(url), trunk.branch, trunk.commit, trunk.skills)
        assertEquals(first, again)

        val feature = scanner.scan(url, "feature")
        val next = database.save(canonicalUrl(url), canonicalUrl(url), feature.branch, feature.commit, feature.skills)
        assertEquals(first.first().id, next.first().id)
        assertEquals("Updated skill", next.first().description)
        assertFalse(next.first().commit == trunkCommit)
        DriverManager.getConnection("jdbc:sqlite:${temp.resolve("scan.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM scans").use { assertEquals(2, it.getCount()) }
                statement.executeQuery("SELECT count(*) FROM skills").use { assertEquals(2, it.getCount()) }
                statement.executeQuery("SELECT count(*) FROM skill_versions").use { assertEquals(4, it.getCount()) }
            }
        }
        assertEquals(4, assertThrows(ScanFailure::class.java) { scanner.scan(url, "missing") }.exitCode)
    }

    @Test fun `inaccessible repository fails before database write`() {
        val url = temp.resolve("missing").toUri().toString()
        assertEquals(3, assertThrows(ScanFailure::class.java) { GitScanner(1024, 1024).scan(url, null) }.exitCode)
        assertTrue(Files.notExists(temp.resolve("scan.db")))
    }

    private fun java.sql.ResultSet.getCount(): Int {
        assertTrue(next())
        return getInt(1)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
