package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DuplicateSkillsIT {
    @TempDir lateinit var temp: Path

    @Test fun `packaged CLI groups duplicate Git skills and separates a changed copy at the next commit`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "main")
        git(repo, "config", "user.email", "test@example.org")
        git(repo, "config", "user.name", "Test")
        git(repo, "config", "core.autocrlf", "false")
        val content = "---\ndescription: Duplicate guidance.\n---\n# Skill\n\nOriginal instructions.\n"
        val paths = listOf("skills/example/SKILL.md", ".agents/skills/copy/SKILL.md", ".claude/skills/example/SKILL.md")
        paths.forEachIndexed { index, path ->
            Files.createDirectories(repo.resolve(path).parent)
            Files.writeString(repo.resolve(path), if (index == 2) content.replace("\n", "\r\n") else content)
        }
        Files.createDirectories(repo.resolve("docs"))
        Files.writeString(repo.resolve("docs/SKILL.md"), content)
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "duplicates")
        val first = scan(repo)
        assertTrue(first.startsWith("Found 1 unique skill across 3 locations on main at "))
        assertEquals(1, Regex("Description: Duplicate guidance\\.").findAll(first).count())
        assertFalse(first.contains("docs/SKILL.md"))
        val ids = ids(first)
        assertEquals(paths.toSet(), ids.keys)
        assertEquals(3, ids.values.toSet().size)
        assertEquals(first, scan(repo), "repeat scans must preserve grouping and IDs")

        Files.writeString(repo.resolve(paths[2]), content + "Additional instructions.\n")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "diverged copy")
        val changed = scan(repo)
        assertTrue(changed.startsWith("Found 2 unique skills across 3 locations on main at "))
        assertEquals(ids, ids(changed))
        assertEquals(2, Regex("Description: Duplicate guidance\\.").findAll(changed).count())
    }

    private fun ids(text: String): Map<String, String> = Regex("(?m)^\\s*(\\S+/SKILL\\.md)  \\[(skl_[a-f0-9]+)]$")
        .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private fun scan(repo: Path): String {
        val log = temp.resolve("cli.log")
        val errors = temp.resolve("cli.err")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            Path.of("target/skill-scan-0.1.0.jar").toAbsolutePath().toString(), "scan", repo.toString())
            .redirectOutput(log.toFile()).redirectError(errors.toFile())
            .apply { environment()["SKILL_SCAN_DB_PATH"] = temp.resolve("skills.db").toString() }.start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI scan timed out")
            assertEquals(0, process.exitValue(), Files.readString(errors))
            return Files.readString(log)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun git(repo: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}
