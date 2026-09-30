package dev.atlas.skillscan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class Snapshot(val branch: String, val commit: String, val skills: List<SkillFile>)
private data class GitResult(val code: Int, val output: ByteArray)
private data class GitRead(val bytes: ByteArray, val tooLarge: Boolean)
private data class TreeEntry(val objectId: String, val path: String)

internal class GitScanner(private val maxFileBytes: Long, private val maxTotalBytes: Long) {
    fun scan(url: String, branchOverride: String?): Snapshot {
        val branch = if (branchOverride == null) defaultBranch(url) else {
            validateBranch(branchOverride)
            branchOverride
        }
        val advertised = remoteBranchHead(url, branch)
        val temp = Files.createTempDirectory("skill-scan-")
        try {
            git(listOf("init", "--bare", "--quiet", temp.toString()), maxOutput = 16_384).requireSuccess(1, "could not prepare temporary Git repository")
            git(listOf("-C", temp.toString(), "remote", "add", "origin", url), maxOutput = 16_384)
                .requireSuccess(1, "could not prepare temporary Git remote")
            git(listOf("-C", temp.toString(), "fetch", "--quiet", "--filter=blob:none", "--depth=1", "--no-tags", "origin",
                "refs/heads/$branch"), maxOutput = 16_384).requireSuccess(3, "repository is inaccessible: ${canonicalUrl(url)}")
            val fetched = gitText(listOf("-C", temp.toString(), "rev-parse", "FETCH_HEAD^{commit}"), 4,
                "selected branch does not resolve to a commit")
            if (fetched != advertised) throw ScanFailure(4, "branch moved during scan; retry $branch in ${canonicalUrl(url)}")
            val entries = listSkillEntries(temp, fetched)
            var totalBytes = 0L
            val skills = entries.map { entry ->
                val size = gitText(listOf("-C", temp.toString(), "cat-file", "-s", entry.objectId), 1,
                    "could not read SKILL.md: ${entry.path}").toLongOrNull()
                    ?: throw ScanFailure(1, "invalid SKILL.md size: ${entry.path}")
                if (size > maxFileBytes || size > maxTotalBytes - totalBytes) {
                    throw ScanFailure(1, "SKILL.md content exceeds configured size limit: ${entry.path}")
                }
                val bytes = git(listOf("-C", temp.toString(), "cat-file", "blob", entry.objectId),
                    maxOutput = size + 1).requireSuccess(1, "could not read SKILL.md: ${entry.path}").output
                totalBytes += bytes.size
                val content = decodeUtf8(bytes) ?: throw ScanFailure(1, "SKILL.md is not UTF-8: ${entry.path}")
                SkillFile(entry.path, content, extractDescription(content))
            }
            return Snapshot(branch, fetched, skills)
        } finally {
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun defaultBranch(url: String): String {
        val result = git(listOf("ls-remote", "--symref", url, "HEAD"), maxOutput = 65_536)
            .requireSuccess(3, "repository is inaccessible: ${canonicalUrl(url)}")
        val symref = result.output.toString(StandardCharsets.UTF_8).lineSequence()
            .firstOrNull { it.startsWith("ref: refs/heads/") && it.endsWith("\tHEAD") }
            ?: throw ScanFailure(4, "default branch could not be resolved: ${canonicalUrl(url)}")
        val branch = symref.removePrefix("ref: refs/heads/").substringBefore('\t')
        validateBranch(branch)
        return branch
    }

    private fun validateBranch(branch: String) {
        if (branch.isBlank() || branch.startsWith('-') || branch.startsWith("refs/") || branch.any { it.isISOControl() }) {
            throw ScanFailure(2, "invalid branch name: $branch")
        }
        val result = git(listOf("check-ref-format", "--branch", branch), maxOutput = 4096)
        if (result.code != 0) throw ScanFailure(2, "invalid branch name: $branch")
    }

    private fun remoteBranchHead(url: String, branch: String): String {
        val result = git(listOf("ls-remote", "--heads", url, "refs/heads/$branch"), maxOutput = 65_536)
            .requireSuccess(3, "repository is inaccessible: ${canonicalUrl(url)}")
        val match = result.output.toString(StandardCharsets.UTF_8).lineSequence()
            .mapNotNull { Regex("^([0-9a-f]{40})\\trefs/heads/(.+)$").matchEntire(it) }
            .firstOrNull { it.groupValues[2] == branch }
            ?: throw ScanFailure(4, "branch not found or has no commit: $branch in ${canonicalUrl(url)}")
        return match.groupValues[1]
    }

    private fun listSkillEntries(temp: Path, commit: String): List<TreeEntry> {
        val bytes = git(listOf("-C", temp.toString(), "ls-tree", "-r", "-z", "--full-tree", commit),
            maxOutput = 64L * 1024 * 1024).requireSuccess(1, "could not inspect repository tree").output
        val entries = mutableListOf<TreeEntry>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index].toInt() != 0) continue
            val line = decodeUtf8(bytes.copyOfRange(start, index)) ?: throw ScanFailure(1, "repository has non-UTF-8 paths")
            start = index + 1
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val metadata = line.substring(0, tab).split(' ')
            val path = line.substring(tab + 1)
            if (metadata.size == 3 && metadata[0] in setOf("100644", "100755")) {
                if (metadata[1] == "blob" && path.substringAfterLast('/') == "SKILL.md") {
                    entries += TreeEntry(metadata[2], path)
                }
            }
        }
        if (start != bytes.size) throw ScanFailure(1, "invalid repository tree output")
        return entries.sortedWith { a, b -> compareUtf8(a.path, b.path) }
    }
}

private fun compareUtf8(a: String, b: String): Int {
    val left = a.toByteArray(StandardCharsets.UTF_8)
    val right = b.toByteArray(StandardCharsets.UTF_8)
    for (i in 0 until minOf(left.size, right.size)) {
        val difference = (left[i].toInt() and 0xff) - (right[i].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return left.size - right.size
}

private fun decodeUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { null }

private fun GitResult.requireSuccess(code: Int, message: String): GitResult {
    if (this.code != 0) throw ScanFailure(code, message)
    return this
}

private fun gitText(args: List<String>, code: Int, message: String): String =
    git(args, maxOutput = 16_384).requireSuccess(code, message).output.toString(StandardCharsets.UTF_8).trim()

private fun git(args: List<String>, maxOutput: Long): GitResult {
    val builder = ProcessBuilder(listOf("git") + args)
    builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
    builder.environment()["GCM_INTERACTIVE"] = "never"
    val process = try { builder.start() } catch (_: Exception) { throw ScanFailure(1, "Git is not available") }
    process.outputStream.close()
    val executor = Executors.newFixedThreadPool(2)
    try {
        val out = executor.submit<GitRead> { readBounded(process.inputStream, maxOutput) }
        val err = executor.submit<GitRead> { readBounded(process.errorStream, 65_536) }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw ScanFailure(1, "Git operation timed out")
        }
        try {
            val output = out.get(2, TimeUnit.SECONDS)
            val error = err.get(2, TimeUnit.SECONDS)
            if (output.tooLarge || error.tooLarge) throw ScanFailure(1, "Git output exceeded the configured limit")
            return GitResult(process.exitValue(), output.bytes)
        } catch (_: TimeoutException) {
            throw ScanFailure(1, "Git output could not be read")
        }
    } catch (e: java.util.concurrent.ExecutionException) {
        throw ScanFailure(1, "Git output exceeded the configured limit")
    } finally {
        if (process.isAlive) process.destroyForcibly()
        executor.shutdownNow()
    }
}

private fun readBounded(stream: java.io.InputStream, max: Long): GitRead {
    val result = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var tooLarge = false
    stream.use {
        while (true) {
            val count = it.read(chunk)
            if (count < 0) break
            if (result.size().toLong() + count > max) tooLarge = true
            if (!tooLarge) result.write(chunk, 0, count)
        }
    }
    return GitRead(result.toByteArray(), tooLarge)
}
