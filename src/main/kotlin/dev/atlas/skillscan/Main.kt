package dev.atlas.skillscan

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

internal class ScanFailure(val exitCode: Int, message: String) : RuntimeException(message)

internal data class SkillFile(val path: String, val content: String, val description: String)
internal data class Finding(val id: String, val repositoryUrl: String, val path: String, val description: String, val commit: String)

fun main(args: Array<String>) {
    exitProcess(runCommand(args))
}

internal fun runCommand(args: Array<String>): Int = if (args.firstOrNull() == "serve") runServer(args) else runCli(args)

internal fun runCli(args: Array<String>, environment: Map<String, String> = System.getenv()): Int {
    try {
        if (args.size !in 2..4 || args[0] != "scan" || (args.size == 4 && args[2] != "--branch") || args.size == 3) {
            throw ScanFailure(2, "usage: skill-atlas scan <repository-url-or-folder> [--branch <branch-name>]")
        }
        val result = ScanService.fromEnvironment(environment).scan(args[1], args.getOrNull(3))
        println(formatFindings(result.snapshot, result.findings))
        return 0
    } catch (e: ScanFailure) {
        System.err.println("error: ${e.message}")
        return e.exitCode
    } catch (e: Exception) {
        System.err.println("error: unexpected failure: ${e.message ?: e.javaClass.simpleName}")
        return 1
    }
}

internal data class ScanResult(val requestedTarget: String, val canonicalTarget: String, val snapshot: Snapshot, val findings: List<Finding>)

internal class ScanService(private val database: SkillDatabase, private val maxFileBytes: Long, private val maxTotalBytes: Long) {
    fun scan(requestedTarget: String, branch: String?): ScanResult {
        val target = scanTarget(requestedTarget)
        val scanner = GitScanner(maxFileBytes, maxTotalBytes)
        val snapshot = if (target.localDirectory == null) scanner.scan(target.canonical, branch)
        else scanner.scanLocalDirectory(target.localDirectory, branch)
        val findings = database.save(target.canonical, requestedTarget, snapshot.branch, snapshot.commit, snapshot.skills)
        return ScanResult(requestedTarget, target.canonical, snapshot, findings)
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ScanService = ScanService(
            SkillDatabase(databasePath(environment)),
            positiveLimit("SKILL_SCAN_MAX_FILE_BYTES", 1_048_576, environment),
            positiveLimit("SKILL_SCAN_MAX_TOTAL_BYTES", 10_485_760, environment)
        )
    }
}

internal data class ScanTarget(val canonical: String, val localDirectory: Path?)

internal fun scanTarget(input: String): ScanTarget {
    if (input.isBlank() || input.startsWith('-') || input.contains('\u0000') || input.any { it == '\n' || it == '\r' }) {
        throw ScanFailure(2, "malformed repository URL or folder path")
    }
    if (input.contains("://") || input.startsWith("file:") || Regex("^[^@/:\\s]+@[^/:\\s]+:[^\\s]+$").matches(input)) {
        return ScanTarget(canonicalUrl(input), null)
    }
    val directory = try {
        Path.of(input).toAbsolutePath().normalize()
    } catch (_: Exception) {
        throw ScanFailure(2, "malformed folder path")
    }
    if (!Files.isDirectory(directory)) throw ScanFailure(3, "local folder is inaccessible: $directory")
    return ScanTarget(directory.toUri().toString().trimEnd('/'), directory)
}

internal fun positiveLimit(name: String, default: Long, environment: Map<String, String>): Long {
    val value = environment[name] ?: return default
    return value.toLongOrNull()?.takeIf { it > 0 }
        ?: throw ScanFailure(2, "$name must be a positive integer")
}

internal fun databasePath(environment: Map<String, String>): Path {
    val override = environment["SKILL_SCAN_DB_PATH"]
    if (override != null) {
        if (override.isBlank()) throw ScanFailure(2, "SKILL_SCAN_DB_PATH must not be empty")
        return Path.of(override).toAbsolutePath().normalize()
    }
    val os = System.getProperty("os.name").lowercase()
    val home = Path.of(System.getProperty("user.home"))
    val dir = when {
        os.contains("mac") -> home.resolve("Library/Application Support/skill-scan")
        os.contains("win") -> Path.of(environment["LOCALAPPDATA"] ?: home.resolve("AppData/Local").toString()).resolve("skill-scan")
        else -> Path.of(environment["XDG_DATA_HOME"] ?: home.resolve(".local/share").toString()).resolve("skill-scan")
    }
    return dir.resolve("skills.db")
}

internal fun canonicalUrl(input: String): String {
    if (input.isBlank() || input.startsWith('-') || input.contains('\u0000') || input.any { it == '\n' || it == '\r' }) {
        throw ScanFailure(2, "malformed repository URL")
    }
    val scp = Regex("^([^@/:\\s]+@)?([^/:\\s]+):([^\\s]+)$").matchEntire(input)
    if (scp != null && !input.contains("://")) {
        val user = scp.groupValues[1]
        val host = scp.groupValues[2].lowercase()
        val path = scp.groupValues[3].trimEnd('/')
        if (path.isEmpty() || path.startsWith('-')) throw ScanFailure(2, "malformed repository URL")
        return "$user$host:$path"
    }
    try {
        val uri = URI(input)
        if (uri.scheme !in setOf("https", "http", "ssh", "git", "file")) throw ScanFailure(2, "unsupported repository URL scheme")
        if (uri.fragment != null || uri.query != null || uri.path.isNullOrBlank()) throw ScanFailure(2, "malformed repository URL")
        if (uri.scheme != "file" && uri.host.isNullOrBlank()) throw ScanFailure(2, "malformed repository URL")
        val path = uri.path.trimEnd('/').ifEmpty { throw ScanFailure(2, "malformed repository URL") }
        return URI(uri.scheme.lowercase(), null, uri.host?.lowercase(), uri.port, path, null, null).toASCIIString()
    } catch (e: ScanFailure) {
        throw e
    } catch (_: Exception) {
        throw ScanFailure(2, "malformed repository URL")
    }
}
