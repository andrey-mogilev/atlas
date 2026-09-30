package dev.atlas.skillscan

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun runServer(args: Array<String>): Int {
    try {
        if (args.size != 1 && (args.size != 3 || args[1] != "--port")) {
            throw ScanFailure(2, "usage: skill-atlas serve [--port <0-65535>]")
        }
        val port = if (args.size == 1) 8080 else args[2].toIntOrNull()?.takeIf { it in 0..65535 }
            ?: throw ScanFailure(2, "port must be an integer between 0 and 65535")
        val database = SkillDatabase(databasePath(System.getenv()))
        val stopped = CountDownLatch(1)
        WebServer(port, ScanService.fromEnvironment(), database).use { server ->
            val shutdown = Thread { server.close(); stopped.countDown() }
            Runtime.getRuntime().addShutdownHook(shutdown)
            server.start()
            println("Skill Atlas is running at ${server.origin}/")
            println("Press Ctrl+C to stop. Relative folder paths resolve from ${Path.of("").toAbsolutePath()}.")
            try { stopped.await() } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { }
            }
        }
        return 0
    } catch (e: ScanFailure) {
        System.err.println("error: ${e.message}")
        return e.exitCode
    } catch (_: Exception) {
        System.err.println("error: could not run local web server; check that the port is available")
        return 1
    }
}

/** Only the loopback interface is exposed; the browser must also prove same-origin access. */
internal class WebServer(
    port: Int,
    private val service: ScanService,
    private val database: SkillDatabase,
    private val scan: (String, String?) -> ScanResult = service::scan
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    private val requests = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    private val worker = Executors.newSingleThreadExecutor()
    private val token = UUID.randomUUID().toString()
    private val jobs = linkedMapOf<String, Map<String, Any?>>()
    private var busy = false
    private var closed = false
    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = requests
        server.createContext("/") { exchange ->
            exchange.use {
                try { handle(exchange) }
                catch (e: WebFailure) { respond(exchange, e.status, mapOf("message" to e.message)) }
                catch (e: ScanFailure) { respond(exchange, 500, mapOf("code" to e.exitCode, "message" to e.message)) }
                catch (_: Exception) { respond(exchange, 500, mapOf("code" to 1, "message" to "Unexpected server failure")) }
            }
        }
    }

    fun start() = server.start()

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        server.stop(0)
        worker.shutdownNow()
        requests.shutdownNow()
        // Give the scanner's finally blocks time to reap Git and remove temporary files,
        // including when close is invoked from the JVM shutdown hook.
        try { worker.awaitTermination(5, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun handle(exchange: HttpExchange) {
        val authority = exchange.requestHeaders.getFirst("Host")
        val allowed = setOf("127.0.0.1:${server.address.port}", "localhost:${server.address.port}")
        if (authority !in allowed) throw WebFailure(403, "Unrecognized local host")
        val requestOrigin = exchange.requestHeaders.getFirst("Origin")
        if (requestOrigin != null && requestOrigin != "http://$authority") throw WebFailure(403, "Cross-origin request refused")
        if (exchange.requestHeaders.getFirst("Sec-Fetch-Site") == "cross-site") throw WebFailure(403, "Cross-site request refused")
        val path = exchange.requestURI.path
        if (path.startsWith("/api/") && exchange.requestHeaders.getFirst("X-Atlas-Token") != token) {
            throw WebFailure(403, "Reload the page to start a new session")
        }
        when {
            path == "/" -> {
                requireMethod(exchange, "GET")
                val html = resource("index.html").replace("__TOKEN__", token)
                    .replace("__BASE_DIRECTORY__", htmlEscape(Path.of("").toAbsolutePath().toString()))
                send(exchange, 200, "text/html; charset=utf-8", html)
            }
            path == "/app.js" || path == "/style.css" -> {
                requireMethod(exchange, "GET")
                send(exchange, 200, if (path.endsWith(".js")) "text/javascript; charset=utf-8" else "text/css; charset=utf-8",
                    resource(path.removePrefix("/")))
            }
            path == "/api/scans" -> {
                requireMethod(exchange, "POST")
                val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')
                if (contentType != "application/x-www-form-urlencoded") throw WebFailure(415, "Expected form data")
                val bytes = exchange.requestBody.readNBytes(8193)
                if (bytes.size > 8192) throw WebFailure(413, "Scan request is too large")
                val fields = try {
                    bytes.toString(UTF_8).split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), UTF_8)
                    }
                } catch (_: IllegalArgumentException) { throw WebFailure(400, "Invalid form data") }
                if (fields.keys.any { it !in setOf("target", "branch") }) throw WebFailure(400, "Unknown scan field")
                val target = fields["target"] ?: throw WebFailure(400, "Repository or folder is required")
                val branch = fields["branch"]?.takeUnless { it.isEmpty() }
                val id = submit(target, branch)
                respond(exchange, 202, mapOf("id" to id, "status" to "running"))
            }
            path.startsWith("/api/jobs/") -> {
                requireMethod(exchange, "GET")
                val job = synchronized(this) { jobs[path.removePrefix("/api/jobs/")] }
                    ?: throw WebFailure(404, "Job expired or was not found; saved results are available in history")
                respond(exchange, 200, job)
            }
            path == "/api/history" -> {
                requireMethod(exchange, "GET")
                respond(exchange, 200, database.recentScans(50).map {
                    mapOf("id" to it.id, "target" to it.requestedTarget, "branch" to it.branch,
                        "commit" to it.commit, "scannedAt" to it.scannedAt, "skillCount" to it.skillCount)
                })
            }
            path.startsWith("/api/history/") -> {
                requireMethod(exchange, "GET")
                val id = path.removePrefix("/api/history/").toLongOrNull() ?: throw WebFailure(404, "Scan not found")
                val result = database.readScan(id) ?: throw WebFailure(404, "Scan not found")
                respond(exchange, 200, result.webResult())
            }
            else -> throw WebFailure(404, "Page not found")
        }
    }

    @Synchronized private fun submit(target: String, branch: String?): String {
        if (busy) throw WebFailure(409, "A scan is already running. Wait for it to finish, then try again.")
        if (closed) throw WebFailure(503, "Server is stopping")
        val id = UUID.randomUUID().toString()
        while (jobs.size >= 10) jobs.remove(jobs.keys.first())
        jobs[id] = mapOf("id" to id, "status" to "running")
        busy = true
        worker.submit {
            val response = try {
                mapOf("id" to id, "status" to "completed", "result" to scan(target, branch).webResult())
            } catch (e: ScanFailure) {
                mapOf("id" to id, "status" to "failed", "code" to e.exitCode, "message" to e.message)
            } catch (_: Exception) {
                mapOf("id" to id, "status" to "failed", "code" to 1, "message" to "Unexpected scan failure")
            }
            synchronized(this) { jobs[id] = response; busy = false }
        }
        return id
    }

    private fun requireMethod(exchange: HttpExchange, method: String) {
        if (exchange.requestMethod != method) {
            exchange.responseHeaders.set("Allow", method)
            throw WebFailure(405, "Method not allowed")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, value: Any?) = send(exchange, status, "application/json; charset=utf-8", json(value))

    private fun send(exchange: HttpExchange, status: Int, type: String, body: String) {
        exchange.responseHeaders.apply {
            set("Content-Type", type)
            set("Cache-Control", "no-store")
            set("X-Content-Type-Options", "nosniff")
            set("Referrer-Policy", "no-referrer")
            set("X-Frame-Options", "DENY")
            set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        }
        val bytes = body.toByteArray(UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun resource(name: String): String = javaClass.getResourceAsStream("/web/$name")!!.bufferedReader(UTF_8).use { it.readText() }
}

private class WebFailure(val status: Int, message: String) : RuntimeException(message)

private fun ScanResult.webResult(): Map<String, Any?> = mapOf(
    "target" to requestedTarget, "branch" to snapshot.branch, "commit" to snapshot.commit,
    "text" to formatFindings(snapshot, findings),
    "findings" to findings.map { mapOf("id" to it.id, "path" to it.path, "link" to it.sourceLink(), "description" to it.description) }
)

private fun htmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Encodes response data as JSON; the UI separately renders repository content as text. */
internal fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        value.forEach { char -> when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char.code < 32 || char.isSurrogate() -> append("\\u%04x".format(char.code))
            else -> append(char)
        } }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> error("Unsupported JSON value")
}
