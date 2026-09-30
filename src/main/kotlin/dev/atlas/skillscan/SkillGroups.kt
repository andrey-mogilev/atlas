package dev.atlas.skillscan

import java.security.MessageDigest

internal fun normalizedContent(content: String): String = content.replace("\r\n", "\n")

internal fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizedContent(content).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal data class SkillGroup(val locations: List<Finding>) {
    val representative: Finding get() = locations.first()
    val description: String get() = representative.description
}

/** Group only equal contents from the same repository/commit; retain every path's stable ID. */
internal fun groupFindings(findings: List<Finding>): List<SkillGroup> = findings
    .sortedWith { a, b -> compareUtf8(a.path, b.path) }
    .groupBy { Triple(it.repositoryUrl, it.commit, it.contentHash) }
    .values.map { SkillGroup(it) }
