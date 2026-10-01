package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CliFormattingIT {
    @TempDir lateinit var temp: Path

    @Test fun `packaged CLI supports compact verbose color JSON help and errors`() {
        val source = Files.createDirectory(temp.resolve("source"))
        for (path in listOf("skills/release/SKILL.md", ".agents/skills/copy/SKILL.md")) {
            Files.createDirectories(source.resolve(path).parent)
            Files.writeString(source.resolve(path), "---\nname: Release notes\ndescription: Shared description.\n---\n# Instructions\n")
        }
        val compact = run("scan", source.toString())
        assertEquals(0, compact.code)
        assertTrue(compact.out.contains("1. Release notes  [2 locations]"))
        assertEquals(1, Regex("Shared description").findAll(compact.out).count())
        assertFalse(compact.out.contains('\u001b'))
        assertFalse(compact.out.contains("skl_"))
        val verbose = run("scan", source.toString(), "--verbose")
        assertTrue(verbose.out.contains("Also found at:"))
        assertTrue(verbose.out.contains("skl_"))
        assertTrue(run("scan", source.toString(), "--color", "always").out.contains("\u001b["))
        val encoded = run("scan", "--json", source.toString(), "--color", "always")
        assertEquals(0, encoded.code)
        assertFalse(encoded.out.contains('\u001b'))
        val data = Yaml().load<Map<String, Any>>(encoded.out)
        assertEquals(1, data["skillCount"])
        assertEquals(2, data["locationCount"])
        val group = (data["skills"] as List<*>).single() as Map<*, *>
        val locations = group["locations"] as List<*>
        assertTrue(locations.all { verbose.out.contains((it as Map<*, *>)["id"].toString()) })
        val database = SkillDatabase(temp.resolve("scan.db"))
        assertEquals("Release notes", database.readScan(database.recentScans().single().id)!!.findings.first().name)
        for (args in listOf(arrayOf("--help"), arrayOf("scan", "--help"))) {
            val help = run(*args)
            assertEquals(0, help.code)
            assertTrue(help.out.contains("--color"))
        }
        val failure = run("scan", source.toString(), "--json", "--verbose")
        assertEquals(2, failure.code)
        assertEquals("", failure.out)
        assertTrue(failure.err.startsWith("error:"))
        val owner = run("scan", "https://github.com/andrey-mogilev")
        assertEquals(2, owner.code, owner.err)
        assertEquals("", owner.out)
        assertTrue(owner.err.contains("--scan-organizations"), owner.err)
        val ownerBranch = run("scan", "https://github.com/andrey-mogilev", "--scan-organizations", "--branch", "main")
        assertEquals(2, ownerBranch.code)
        assertEquals("", ownerBranch.out)
        val error = run("scan", temp.resolve("missing\u001b[31m").toString(), "--color", "never")
        assertEquals(3, error.code)
        assertEquals("", error.out)
        assertFalse(error.err.contains('\u001b'))
        val empty = Files.createDirectory(temp.resolve("empty"))
        assertTrue(run("scan", empty.toString()).out.startsWith("No SKILL.md files found"))
        assertEquals(emptyList<Any>(), Yaml().load<Map<String, Any>>(run("scan", empty.toString(), "--json").out)["skills"])
    }

    private data class Output(val code: Int, val out: String, val err: String)
    private fun run(vararg args: String): Output {
        val out = temp.resolve("stdout")
        val err = temp.resolve("stderr")
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            Path.of("target/skill-scan-0.1.0.jar").toAbsolutePath().toString()) + args
        val process = ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).apply {
            environment()["SKILL_SCAN_DB_PATH"] = temp.resolve("scan.db").toString()
            environment()["NO_COLOR"] = "1"
        }.start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI timed out")
            return Output(process.exitValue(), Files.readString(out), Files.readString(err))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
