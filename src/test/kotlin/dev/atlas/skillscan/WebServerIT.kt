package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Runs the shaded JAR, catching missing resources and command/lifecycle wiring. */
class WebServerIT {
    @TempDir lateinit var temp: Path
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val jar = Path.of("target/skill-scan-0.1.0.jar").toAbsolutePath().toString()

    @Test fun `packaged server serves assets scans relative folder and stops`() {
        Files.createDirectories(temp.resolve("source/skills/example"))
        Files.writeString(temp.resolve("source/skills/example/SKILL.md"), "# Example\n\nPackaged web scan.")
        Files.createDirectories(temp.resolve("source/.agents/skills/example"))
        Files.writeString(temp.resolve("source/.agents/skills/example/SKILL.md"), "# Example\r\n\r\nPackaged web scan.")
        val log = temp.resolve("server.log")
        val process = ProcessBuilder(java, "-jar", jar, "serve", "--port", "0")
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply { environment()["SKILL_SCAN_DB_PATH"] = temp.resolve("scan.db").toString() }.start()
        try {
            var origin: String? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline && process.isAlive) {
                origin = Regex("running at (http://127\\.0\\.0\\.1:\\d+)/").find(Files.readString(log))?.groupValues?.get(1)
                if (origin != null) break
                Thread.sleep(25)
            }
            assertNotNull(origin, Files.readString(log))
            val client = HttpClient.newHttpClient()
            fun get(path: String, token: String? = null): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(3))
                token?.let { request.header("X-Atlas-Token", it) }
                return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            }
            val html = get("/")
            assertEquals(200, html.statusCode())
            assertTrue(html.body().contains("Scan a source"))
            assertEquals(200, get("/app.js").statusCode())
            assertEquals(200, get("/style.css").statusCode())
            val token = Regex("name=\"atlas-token\" content=\"([^\"]+)\"").find(html.body())!!.groupValues[1]
            val response = client.send(HttpRequest.newBuilder(URI("$origin/api/scans"))
                .timeout(Duration.ofSeconds(3)).header("X-Atlas-Token", token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("target=" + URLEncoder.encode("./source", Charsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(202, response.statusCode(), response.body())
            val id = Yaml().load<Map<String, Any>>(response.body())["id"]
            var completed = false
            while (System.nanoTime() < deadline) {
                val job = Yaml().load<Map<String, Any>>(get("/api/jobs/$id", token).body())
                if (job["status"] != "running") {
                    assertEquals("completed", job["status"], job.toString())
                    val result = job["result"] as Map<*, *>
                    val findings = result["findings"] as List<*>
                    assertEquals(1, findings.size)
                    assertEquals(2, result["locationCount"])
                    val locations = (findings.single() as Map<*, *>)["locations"] as List<*>
                    assertEquals(2, locations.size)
                    assertEquals(2, locations.map { (it as Map<*, *>)["id"] }.toSet().size)
                    val cli = ProcessBuilder(java, "-jar", jar, "scan", "./source", "--verbose")
                        .directory(temp.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
                        .apply { environment()["SKILL_SCAN_DB_PATH"] = temp.resolve("scan.db").toString() }.start()
                    try {
                        assertTrue(cli.waitFor(5, TimeUnit.SECONDS))
                        assertEquals(0, cli.exitValue())
                        assertEquals(result["text"].toString() + System.lineSeparator(), cli.inputStream.bufferedReader().readText())
                    } finally { if (cli.isAlive) cli.destroyForcibly() }
                    val history = Yaml().load<List<Map<String, Any>>>(get("/api/history", token).body()).single()
                    assertEquals(1, history["skillCount"])
                    assertEquals(2, history["locationCount"])
                    assertEquals(result, Yaml().load<Map<String, Any>>(get("/api/history/${history["id"]}", token).body()))
                    assertTrue(result["text"].toString().contains("Found 1 unique skill across 2 locations"))
                    assertEquals(1, Regex("Description: Packaged web scan\\.").findAll(result["text"].toString()).count())
                    completed = true
                    break
                }
                Thread.sleep(25)
            }
            assertTrue(completed, "Packaged scan did not finish")
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail<Unit>("Packaged server did not shut down")
            }
        }
    }

    @Test fun `packaged command rejects invalid serve arguments`() {
        for (args in listOf(listOf("--port", "-1"), listOf("--port", "65536"), listOf("--port", "no"), listOf("--host", "0.0.0.0"), listOf("--port"))) {
            val process = ProcessBuilder(listOf(java, "-jar", jar, "serve") + args).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(2, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }
}
