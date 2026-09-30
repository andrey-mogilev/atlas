package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebServerTest {
    @TempDir lateinit var temp: Path
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val database get() = SkillDatabase(temp.resolve("skills.db"))
    private fun service(db: SkillDatabase = database) = ScanService(db, 1024, 4096)

    @Test fun `web results equal CLI formatting and saved results survive restart`() {
        val folder = temp.resolve("source with spaces")
        Files.createDirectories(folder.resolve("skills/example"))
        Files.writeString(folder.resolve("skills/example/SKILL.md"), "# Skill\n\n<script>alert(1)</script> Guidance 🧭.")
        val expected = service().scan(folder.toString(), null)
        var scanId: Number? = null
        WebServer(0, service(), database).use { server ->
            server.start()
            val token = token(server)
            val job = submit(server, token, folder.toString())
            assertEquals("completed", job["status"])
            val result = job["result"] as Map<*, *>
            assertEquals(formatFindings(expected.snapshot, expected.findings), result["text"])
            assertEquals("local", result["branch"])
            assertEquals("<NONE>", result["commit"])
            assertEquals("Skill", ((result["findings"] as List<*>).single() as Map<*, *>)["name"])
            val history = Yaml().load<List<Map<String, Any>>>(request(server, "/api/history", token).body())
            assertEquals(1, history.size)
            scanId = history.single()["id"] as Number
            val saved = data(request(server, "/api/history/$scanId", token))
            assertEquals(result, saved)
        }
        WebServer(0, service(), database).use { restarted ->
            restarted.start()
            val result = data(request(restarted, "/api/history/$scanId", token(restarted)))
            assertEquals(formatFindings(expected.snapshot, expected.findings), result["text"])
        }
    }

    @Test fun `empty scan succeeds and failures preserve CLI codes without saved records`() {
        val empty = Files.createDirectory(temp.resolve("empty"))
        WebServer(0, service(), database).use { server ->
            server.start()
            val token = token(server)
            assertEquals("[]", request(server, "/api/history", token).body())
            assertFalse(Files.exists(temp.resolve("skills.db")), "reading an empty history must not create the database")
            val result = submit(server, token, empty.toString())["result"] as Map<*, *>
            assertEquals("No SKILL.md files found on local at <NONE>.", result["text"])
            assertEquals(2, submit(server, token, empty.toString(), "main")["code"])
            assertEquals(3, submit(server, token, temp.resolve("missing").toString())["code"])
            assertEquals(2, submit(server, token, "https://")["code"])
            Files.createDirectories(empty.resolve("skills/large"))
            Files.writeString(empty.resolve("skills/large/SKILL.md"), "x".repeat(1025))
            assertEquals(1, submit(server, token, empty.toString())["code"])
            assertEquals(1, database.recentScans().size)
        }
    }

    @Test fun `Git branches use the same IDs and persisted versions as the CLI`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "trunk")
        git(repo, "config", "user.email", "test@example.org")
        git(repo, "config", "user.name", "Test")
        Files.createDirectories(repo.resolve("skills/example"))
        Files.writeString(repo.resolve("skills/example/SKILL.md"), "# Example\n\nOriginal.")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "initial")
        val original = service().scan(repo.toString(), null)
        git(repo, "switch", "-qc", "feature")
        Files.writeString(repo.resolve("skills/example/SKILL.md"), "# Example\n\nChanged.")
        git(repo, "commit", "-qam", "update")
        WebServer(0, service(), database).use { server ->
            server.start()
            val token = token(server)
            val result = submit(server, token, repo.toString(), "feature")["result"] as Map<*, *>
            assertEquals("feature", result["branch"])
            val finding = (result["findings"] as List<*>).single() as Map<*, *>
            assertEquals(original.findings.single().id, finding["id"])
            assertEquals("Changed.", finding["description"])
            assertEquals(4, submit(server, token, repo.toString(), "missing")["code"])
            assertEquals(2, database.recentScans().size)
            val storedOriginal = database.recentScans().single { it.branch == "trunk" }
            assertEquals("Original.", database.readScan(storedOriginal.id)!!.findings.single().description)
        }
    }

    @Test fun `persistence failure is visible and does not look like success`() {
        val db = SkillDatabase(Files.createDirectory(temp.resolve("not-a-database")))
        WebServer(0, service(db), db).use { server ->
            server.start()
            val job = submit(server, token(server), Files.createDirectory(temp.resolve("folder")).toString())
            assertEquals("failed", job["status"])
            assertEquals(5, job["code"])
        }
    }

    @Test fun `server protects local data and scan submissions`() {
        WebServer(0, service(), database).use { server ->
            server.start()
            val token = token(server)
            assertEquals(403, request(server, "/api/history").statusCode())
            assertEquals(403, request(server, "/api/scans", token, "target=x", "https://evil.example").statusCode())
            assertEquals(403, request(server, "/", origin = "https://evil.example").statusCode())
            assertEquals(405, request(server, "/api/scans", token).statusCode())
            assertEquals(404, request(server, "/api/history/123", token).statusCode())
            assertEquals(413, request(server, "/api/scans", token, "target=" + "x".repeat(8192)).statusCode())
            assertEquals(415, request(server, "/api/scans", token, "{}", type = "application/json").statusCode())
            assertEquals(400, request(server, "/api/scans", token, "target=%QQ").statusCode())
            assertEquals(400, request(server, "/api/scans", token, "target=x&database=/tmp/arbitrary.db").statusCode())
            val root = request(server, "/")
            assertTrue(root.headers().firstValue("Content-Security-Policy").get().contains("frame-ancestors 'none'"))
            assertEquals("no-store", root.headers().firstValue("Cache-Control").get())
            assertTrue(root.body().contains("id=\"skill-filter\""))
            val script = request(server, "/app.js")
            assertEquals(200, script.statusCode())
            assertTrue(script.body().contains("finding.name.toLocaleLowerCase()"))
            assertTrue(script.body().contains("finding.description.toLocaleLowerCase()"))
            assertEquals(200, request(server, "/style.css").statusCode())
            Socket("127.0.0.1", URI(server.origin).port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
            }
            assertFalse(Files.exists(temp.resolve("skills.db")))
        }
    }

    @Test fun `only one scan runs and status and history remain responsive`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        WebServer(0, service(), database) { target, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ScanResult(target, target, Snapshot("local", "<NONE>", emptyList()), emptyList())
        }.use { server ->
            server.start()
            val token = token(server)
            try {
                val first = request(server, "/api/scans", token, "target=x")
                assertEquals(202, first.statusCode())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals("running", data(request(server, "/api/jobs/${data(first)["id"]}", token))["status"])
                assertEquals(409, request(server, "/api/scans", token, "target=y").statusCode())
                assertEquals(200, request(server, "/api/history", token).statusCode())
                release.countDown()
                assertEquals("completed", completed(server, token, data(first)["id"].toString())["status"])
            } finally { release.countDown() }
        }
    }

    @Test fun `shutdown waits for interrupted scan cleanup`() {
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val server = WebServer(0, service(), database) { _, _ ->
            entered.countDown()
            try {
                CountDownLatch(1).await()
                error("Scan should have been interrupted")
            } finally { cleaned.countDown() }
        }
        server.use {
            server.start()
            assertEquals(202, request(server, "/api/scans", token(server), "target=x").statusCode())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        }
        assertEquals(0L, cleaned.count)
    }

    private fun git(repo: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun token(server: WebServer): String = Regex("name=\"atlas-token\" content=\"([^\"]+)\"")
        .find(request(server, "/").body())!!.groupValues[1]

    private fun request(server: WebServer, path: String, token: String? = null, body: String? = null,
                        origin: String? = null, type: String = "application/x-www-form-urlencoded"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(server.origin + path)).timeout(Duration.ofSeconds(5))
        token?.let { builder.header("X-Atlas-Token", it) }
        origin?.let { builder.header("Origin", it) }
        body?.let { builder.header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(it)) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun data(response: HttpResponse<String>): Map<String, Any?> = Yaml().load(response.body())

    private fun submit(server: WebServer, token: String, target: String, branch: String = ""): Map<String, Any?> {
        val response = request(server, "/api/scans", token,
            "target=${URLEncoder.encode(target, Charsets.UTF_8)}&branch=${URLEncoder.encode(branch, Charsets.UTF_8)}")
        assertEquals(202, response.statusCode(), response.body())
        return completed(server, token, data(response)["id"].toString())
    }

    private fun completed(server: WebServer, token: String, id: String): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val job = data(request(server, "/api/jobs/$id", token))
            if (job["status"] != "running") return job
            Thread.sleep(25)
        }
        throw AssertionError("Scan did not finish")
    }
}
