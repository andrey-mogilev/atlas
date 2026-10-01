package dev.atlas.skillscan

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

internal class ScanFailure(val exitCode: Int, message: String) : RuntimeException(message)

internal data class SkillFile(val path: String, val content: String, val description: String)
internal data class Finding(val id: String, val repositoryUrl: String, val path: String, val description: String, val commit: String,
                            val contentHash: String, val name: String = path.substringBeforeLast('/').substringAfterLast('/'))

fun main(args: Array<String>) {
    exitProcess(runCommand(args))
}

internal fun runCommand(args: Array<String>): Int = if (args.firstOrNull() == "serve") runServer(args) else runCli(args)

internal fun runCli(args: Array<String>, environment: Map<String, String> = System.getenv()): Int {
    // Argument errors remain plain until the complete option set is validated.
    var presentation = CliPresentation(false, false, 80)
    try {
        if (args.toList() in listOf(listOf("--help"), listOf("-h"), listOf("scan", "--help"), listOf("scan", "-h"))) {
            println(CLI_HELP)
            return 0
        }
        val options = parseScanOptions(args)
        presentation = CliPresentation.fromEnvironment(environment, options.color, machineReadable = options.json)
        val service = ScanService.fromEnvironment(environment)
        val owner = githubOwner(scanTarget(options.target).canonical)
        if (owner != null) {
            if (!options.scanOwners) throw ScanFailure(2, "${options.target} identifies a GitHub user or organization, " +
                "not a repository; add --scan-organizations to scan every repository it owns")
            val owned = service.scanOwner(service.ownerPlan(owner), options.rescan)
            println(if (options.json) formatOwnerJson(owned) else formatOwner(owned, presentation, options.verbose))
            return if (owned.failed.isEmpty()) 0 else 1
        }
        val result = service.scan(options.target, options.branch)
        println(when {
            options.json -> formatJson(result)
            options.verbose -> formatFindings(result.snapshot, result.findings)
            else -> formatCompact(result, presentation)
        })
        return 0
    } catch (e: ScanFailure) {
        System.err.println(presentation.paint("31", "error:") + " " + (e.message ?: "scan failed").safeLine())
        return e.exitCode
    } catch (e: Exception) {
        System.err.println(presentation.paint("31", "error:") + " unexpected failure: " + (e.message ?: e.javaClass.simpleName).safeLine())
        return 1
    }
}

internal data class ScanResult(val requestedTarget: String, val canonicalTarget: String, val snapshot: Snapshot, val findings: List<Finding>)

internal const val OWNER_SCANNED = "scanned"
internal const val OWNER_SKIPPED = "skipped"
internal const val OWNER_FAILED = "failed"

internal data class OwnerRepositoryOutcome(
    val fullName: String,
    val url: String,
    val status: String,
    val skillCount: Int = 0,
    val locationCount: Int = 0,
    val code: Int? = null,
    val message: String? = null
)

internal data class OwnerScanResult(val plan: OwnerPlan, val outcomes: List<OwnerRepositoryOutcome>) {
    val scanned: List<OwnerRepositoryOutcome> get() = outcomes.filter { it.status == OWNER_SCANNED }
    val skipped: List<OwnerRepositoryOutcome> get() = outcomes.filter { it.status == OWNER_SKIPPED }
    val failed: List<OwnerRepositoryOutcome> get() = outcomes.filter { it.status == OWNER_FAILED }
    val skillCount: Int get() = outcomes.sumOf { it.skillCount }
    val locationCount: Int get() = outcomes.sumOf { it.locationCount }
}

internal class ScanService(
    private val database: SkillDatabase,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long,
    private val github: GitHubApi = GitHubApi()
) {
    fun scan(requestedTarget: String, branch: String?): ScanResult {
        val target = scanTarget(requestedTarget)
        val scanner = GitScanner(maxFileBytes, maxTotalBytes)
        val snapshot = if (target.localDirectory == null) scanRemote(target.canonical, branch, scanner::scan)
        else scanner.scanLocalDirectory(target.localDirectory, branch)
        val findings = database.save(target.canonical, requestedTarget, snapshot.branch, snapshot.commit, snapshot.skills)
        return ScanResult(requestedTarget, target.canonical, snapshot, findings)
    }

    fun ownerPlan(login: String): OwnerPlan = github.plan(login)

    /** Repositories already saved locally are reported from storage unless a rescan was requested. */
    fun scanOwner(plan: OwnerPlan, rescan: Boolean, onProgress: (OwnerRepositoryOutcome) -> Unit = {}): OwnerScanResult =
        scanOwnedRepositories(plan, if (rescan) emptyMap() else database.repositories().associateBy { it.canonicalTarget },
            { url -> scan(url, null) }, onProgress)

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ScanService = ScanService(
            SkillDatabase(databasePath(environment)),
            positiveLimit("SKILL_SCAN_MAX_FILE_BYTES", 1_048_576, environment),
            positiveLimit("SKILL_SCAN_MAX_TOTAL_BYTES", 10_485_760, environment),
            GitHubApi(githubToken(environment), ownerRepositoryLimit(environment))
        )
    }
}

/** One repository failure is reported for that repository alone and never abandons the remaining ones. */
internal fun scanOwnedRepositories(
    plan: OwnerPlan,
    previous: Map<String, StoredRepository>,
    scan: (String) -> ScanResult,
    onProgress: (OwnerRepositoryOutcome) -> Unit = {}
): OwnerScanResult {
    val outcomes = mutableListOf<OwnerRepositoryOutcome>()
    for (repository in plan.repositories) {
        if (Thread.currentThread().isInterrupted) throw ScanFailure(1, "organization scan was interrupted")
        val stored = previous[repository.url]
        val outcome = if (stored != null) {
            OwnerRepositoryOutcome(repository.fullName, repository.url, OWNER_SKIPPED, stored.skillCount, stored.locationCount)
        } else try {
            val result = scan(repository.url)
            OwnerRepositoryOutcome(repository.fullName, repository.url, OWNER_SCANNED,
                groupFindings(result.findings).size, result.findings.size)
        } catch (failure: ScanFailure) {
            OwnerRepositoryOutcome(repository.fullName, repository.url, OWNER_FAILED,
                code = failure.exitCode, message = failure.message ?: "repository scan failed")
        }
        outcomes += outcome
        onProgress(outcome)
    }
    return OwnerScanResult(plan, outcomes)
}

internal fun githubToken(environment: Map<String, String>): String? =
    environment["SKILL_SCAN_GITHUB_TOKEN"]?.takeUnless { it.isBlank() }
        ?: environment["GITHUB_TOKEN"]?.takeUnless { it.isBlank() }

internal fun ownerRepositoryLimit(environment: Map<String, String>): Int =
    positiveLimit("SKILL_SCAN_MAX_OWNER_REPOSITORIES", DEFAULT_MAX_OWNER_REPOSITORIES.toLong(), environment)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal data class ScanTarget(val canonical: String, val localDirectory: Path?)

internal fun scanTarget(requestedInput: String): ScanTarget {
    // Accept a complete copied Markdown link, optionally wrapped in inline-code backticks.
    // Local paths are left untouched, including valid spaces and punctuation in their names.
    val copiedLink = requestedInput.trim().removeSurrounding("`")
    val input = Regex("\\[[^\\]\\r\\n]*]\\((https?://[^\\s<>]+)\\)")
        .matchEntire(copiedLink)?.groupValues?.get(1) ?: requestedInput
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

/** Retry only the same GitHub repository with the user's existing SSH configuration. */
internal fun scanRemote(url: String, branch: String?, operation: (String, String?) -> Snapshot): Snapshot {
    try {
        return operation(url, branch)
    } catch (failure: ScanFailure) {
        if (failure.exitCode != 3) throw failure
        val uri = runCatching { URI(url) }.getOrNull() ?: throw failure
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port !in setOf(-1, 443) ||
            !Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(uri.path.orEmpty())) throw failure
        val sshUrl = "git@github.com:${uri.path.removePrefix("/").removeSuffix(".git")}.git"
        try {
            return operation(sshUrl, branch)
        } catch (sshFailure: ScanFailure) {
            if (sshFailure.exitCode == 3) throw ScanFailure(3,
                "repository is inaccessible over HTTPS and SSH: $url; check your GitHub access and Git credentials")
            throw ScanFailure(sshFailure.exitCode, sshFailure.message?.replace(sshUrl, url) ?: "repository scan failed: $url")
        }
    }
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
