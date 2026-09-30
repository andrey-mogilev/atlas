package dev.atlas.skillscan

import java.net.URI

internal fun formatFindings(snapshot: Snapshot, findings: List<Finding>): String {
    if (findings.isEmpty()) return "No SKILL.md files found on ${snapshot.branch} at ${snapshot.commit}."
    val count = findings.size
    val heading = "Found $count ${if (count == 1) "skill" else "skills"} on ${snapshot.branch} at ${snapshot.commit}:"
    return heading + "\n\n" + findings.joinToString("\n\n") { finding ->
        "${finding.path.safeLine()}  [${finding.id}]\n" +
            "  Link: ${finding.sourceLink()}\n" +
            "  Description: ${finding.description.replace(Regex("\\s+"), " ").trim().safeLine().ifEmpty { "(none)" }}"
    }
}

private fun String.safeLine(): String = buildString {
    for (character in this@safeLine) {
        if (character.code < 0x20 || character.code == 0x7f) {
            append("\\u%04x".format(character.code))
        } else {
            append(character)
        }
    }
}

internal fun Finding.sourceLink(): String {
    val scp = Regex("^(?:[^@]+@)?([^:]+):(.+)$").matchEntire(repositoryUrl)
    val host: String
    val port: Int
    val repositoryPath: String
    if (scp != null && !repositoryUrl.contains("://")) {
        host = scp.groupValues[1]
        port = -1
        repositoryPath = "/${scp.groupValues[2].trimStart('/')}"
    } else {
        val uri = URI(repositoryUrl)
        host = uri.host ?: return repositoryUrl
        port = uri.port
        repositoryPath = uri.path
    }
    val marker = when (host.lowercase()) {
        "github.com" -> "/blob/"
        "gitlab.com" -> "/-/blob/"
        "bitbucket.org" -> "/src/"
        else -> return repositoryUrl
    }
    val basePath = repositoryPath.trimEnd('/').removeSuffix(".git")
    val filePath = "$basePath$marker$commit/$path"
    return URI("https", null, host, port, filePath, null, null).toASCIIString()
}
