package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class CliOutputTest {
    private val plain = CliPresentation(false, false, 40)
    private val commit = "a".repeat(40)
    private fun result(name: String = "Release notes", description: String = "Prepare release notes."): ScanResult {
        val paths = listOf("skills/release/SKILL.md", ".agents/skills/release/SKILL.md")
        val findings = paths.mapIndexed { index, path ->
            Finding("skl_$index", "https://github.com/acme/skills", path, description, commit, "hash", name)
        }
        return ScanResult("input", "https://github.com/acme/skills", Snapshot("main", commit, emptyList()), findings)
    }

    @Test fun `compact output is numbered grouped and hides machine metadata`() {
        assertEquals("""
            Found 1 skill across 2 locations
            on main at aaaaaaaaaaaa

            1. Release notes  [2 locations]
               Prepare release notes.
                .agents/skills/release/SKILL.md
                skills/release/SKILL.md
        """.trimIndent(), formatCompact(result(), plain))
        assertFalse(formatCompact(result(), plain).contains("skl_"))
        assertTrue(formatCompact(result(description = ""), plain).contains("(no description)"))
        val empty = result().copy(findings = emptyList())
        assertEquals("No SKILL.md files found on main at aaaaaaaaaaaa.", formatCompact(empty, plain))
    }

    @Test fun `wraps descriptions including long words and supplementary Unicode`() {
        val text = "Use this skill for preparing reliable release notes. " + "x".repeat(90) + " " + "😀".repeat(45)
        val lines = formatCompact(result(description = text), plain).lines().filter { it.startsWith("   ") && !it.startsWith("    ") }
        assertTrue(lines.size > 5)
        assertTrue(lines.all { it.codePointCount(0, it.length) <= 40 })
        assertFalse(lines.any { it.last().isHighSurrogate() })
    }

    @Test fun `names use front matter then heading then directory without accepting fenced headings`() {
        val path = "skills/fallback/SKILL.md"
        assertEquals("Named", extractName("\uFEFF---\r\nname: Named\r\n---\r\n# Title", path))
        assertEquals("C#", extractName("---\nname: []\n---\n# C#", path))
        assertEquals("Title", extractName("---\nname: [broken\n---\n## Title ##", path))
        assertEquals("Real", extractName("```md\n# Example\n```\n# Real", path))
        assertEquals("fallback", extractName("No heading.", path))
        assertEquals("Title", extractName("---\nname: ' '\n---\n# Title", path))
    }

    @Test fun `color policy respects pipes no color overrides dumb terminals and JSON`() {
        val env = mapOf("TERM_PROGRAM" to "iTerm.app", "COLUMNS" to "100")
        fun style(mode: String = "auto", terminal: Boolean = true, extra: Map<String, String> = emptyMap(), json: Boolean = false) =
            CliPresentation.fromEnvironment(env + extra, mode, terminal, json)
        assertTrue(style().color)
        assertTrue(style().hyperlinks)
        assertEquals(100, style().width)
        assertFalse(style(terminal = false).color)
        assertFalse(style(extra = mapOf("NO_COLOR" to "")).color)
        assertFalse(style(extra = mapOf("TERM" to "dumb")).color)
        assertTrue(style("always", false, mapOf("NO_COLOR" to "1")).color)
        assertFalse(style("always", false).hyperlinks)
        assertFalse(style("never").color)
        assertFalse(style("always", json = true).color)
        assertEquals(80, style(extra = mapOf("COLUMNS" to "-1")).width)
        assertEquals(80, style(extra = mapOf("COLUMNS" to "invalid")).width)
    }

    @Test fun `terminal controls are escaped and only trusted hyperlinks are emitted`() {
        val hostile = result("Name\u001b[31m\u009b\u202e", "Description\u001b]8;;evil\u0007")
        val output = formatCompact(hostile, plain)
        assertFalse(output.any { it == '\u001b' || it == '\u009b' || it == '\u202e' || it == '\u0007' })
        assertTrue(output.contains("\\u001b"))
        val links = formatCompact(result(), CliPresentation(true, true, 80))
        assertTrue(links.contains("\u001b[1mRelease notes"))
        assertTrue(links.contains("\u001b]8;;https://github.com/acme/skills/blob/$commit/"))
        val ssh = result().copy(findings = result().findings.map { it.copy(repositoryUrl = "git@github.com:acme/skills.git") })
        assertTrue(formatCompact(ssh, CliPresentation(true, true, 80)).contains("\u001b]8;;https://github.com/"))
        val local = result().copy(findings = result().findings.map { it.copy(repositoryUrl = "file:///tmp/with%20space") })
        assertTrue(formatCompact(local, CliPresentation(true, true, 80)).contains("\u001b]8;;file:///tmp/with%20space/"))
        val unsafe = result().copy(findings = result().findings.map { it.copy(repositoryUrl = "git://example.org/repo") })
        assertFalse(formatCompact(unsafe, CliPresentation(true, true, 80)).contains("\u001b]8"))
    }

    @Test fun `JSON has full identifiers all locations and raw metadata without terminal escapes`() {
        val data = result("Name\u001b", "Line one\nLine two")
        val encoded = formatJson(data)
        assertFalse(encoded.contains('\u001b'))
        val decoded = Yaml().load<Map<String, Any>>(encoded)
        assertEquals(1, decoded["schemaVersion"])
        assertEquals(commit, decoded["commit"])
        assertEquals(1, decoded["skillCount"])
        assertEquals(2, decoded["locationCount"])
        val skill = (decoded["skills"] as List<*>).single() as Map<*, *>
        assertEquals("Name\u001b", skill["name"])
        assertEquals("Line one\nLine two", skill["description"])
        assertEquals(2, (skill["locations"] as List<*>).size)
        assertEquals(emptyList<Any>(), Yaml().load<Map<String, Any>>(formatJson(data.copy(findings = emptyList())))["skills"])
    }

    @Test fun `options work in either order and reject ambiguous or incomplete commands`() {
        assertEquals(ScanOptions("repo", "release", true, false, "never"),
            parseScanOptions(arrayOf("scan", "--verbose", "--branch", "release", "repo", "--color", "never")))
        assertTrue(parseScanOptions(arrayOf("scan", "repo", "--json")).json)
        for (args in listOf(emptyList(), listOf("scan"), listOf("scan", "repo", "extra"),
            listOf("scan", "repo", "--branch"), listOf("scan", "repo", "--color", "bad"),
            listOf("scan", "repo", "--json", "--verbose"), listOf("scan", "repo", "--json", "--json"),
            listOf("scan", "repo", "--unknown"), listOf("scan", "repo", "--branch", "--json"))) {
            assertEquals(2, assertThrows(ScanFailure::class.java) { parseScanOptions(args.toTypedArray()) }.exitCode)
        }
    }
}
