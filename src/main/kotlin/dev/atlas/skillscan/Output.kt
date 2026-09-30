package dev.atlas.skillscan

import java.net.URI

internal fun formatFindings(snapshot: Snapshot, findings: List<Finding>): String {
    if (findings.isEmpty()) return "No SKILL.md files found on ${snapshot.branch.safeLine()} at ${snapshot.commit.safeLine()}."
    val groups = groupFindings(findings)
    val count = groups.size
    val locations = if (count == findings.size) "" else " across ${findings.size} locations"
    val unique = if (count == findings.size) "" else "unique "
    val heading = "Found $count $unique${if (count == 1) "skill" else "skills"}$locations on ${snapshot.branch.safeLine()} at ${snapshot.commit.safeLine()}:"
    return heading + "\n\n" + groups.joinToString("\n\n") { group ->
        val finding = group.representative
        val otherLocations = if (group.locations.size == 1) "" else "\n  Also found at:\n" +
            group.locations.drop(1).joinToString("\n") {
                "    ${it.path.safeLine()}  [${it.id.safeLine()}]\n      Link: ${it.sourceLink().safeLine()}"
            }
        "${finding.path.safeLine()}  [${finding.id.safeLine()}]\n" +
            "  Link: ${finding.sourceLink().safeLine()}\n" +
            "  Description: ${finding.description.replace(Regex("\\s+"), " ").trim().safeLine().ifEmpty { "(none)" }}" + otherLocations
    }
}

internal fun String.safeLine(): String = buildString {
    for (character in this@safeLine) {
        if (character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()) {
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
