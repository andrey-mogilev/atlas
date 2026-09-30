package dev.atlas.skillscan

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

internal fun extractName(content: String, path: String): String {
    val lines = content.removePrefix("\uFEFF").lines()
    var bodyStart = 0
    if (lines.firstOrNull()?.trim() == "---") {
        val closing = (1 until lines.size).firstOrNull { lines[it].trim() in setOf("---", "...") }
        if (closing != null) {
            bodyStart = closing + 1
            try {
                val options = LoaderOptions().apply { codePointLimit = 1_048_576; maxAliasesForCollections = 50 }
                val values = Yaml(options).load<Any?>(lines.subList(1, closing).joinToString("\n")) as? Map<*, *>
                val name = values?.get("name")
                if (name is String || name is Number || name is Boolean) {
                    name.toString().trim().takeIf { it.isNotEmpty() }?.let { return it }
                }
            } catch (_: Exception) { /* Fall back to the Markdown title. */ }
        }
    }
    // Only consider headings outside fenced code blocks.
    var fence: String? = null
    for (line in lines.drop(bodyStart)) {
        val trimmed = line.trim()
        val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
        if (marker != null) {
            if (fence == null) fence = marker
            else if (marker.first() == fence.first() && marker.length >= fence.length && trimmed == marker) fence = null
            continue
        }
        if (fence != null) continue
        val heading = Regex("^#{1,6}\\s+(.+)$").matchEntire(trimmed)?.groupValues?.get(1)
            ?.replace(Regex("\\s+#+\\s*$"), "")
        if (!heading.isNullOrBlank()) return heading.trim()
    }
    return path.substringBeforeLast('/').substringAfterLast('/')
}

internal fun extractDescription(content: String): String {
    val lines = content.removePrefix("\uFEFF").lines()
    var bodyStart = 0
    if (lines.firstOrNull()?.trim() == "---") {
        val closing = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
        if (closing != null) {
            bodyStart = closing + 1
            val frontMatter = lines.subList(1, closing).joinToString("\n")
            try {
                val options = LoaderOptions().apply { codePointLimit = 1_048_576; maxAliasesForCollections = 50 }
                val values = Yaml(options).load<Any?>(frontMatter) as? Map<*, *>
                val description = values?.get("description")
                if (description is String || description is Number || description is Boolean) {
                    return description.toString().trim()
                }
            } catch (_: Exception) {
                // Invalid front matter is treated as absent metadata.
            }
        }
    }
    val body = lines.drop(bodyStart)
    var index = 0
    while (index < body.size && body[index].isBlank()) index++
    if (index < body.size && body[index].trimStart().startsWith("# ")) index++
    while (index < body.size) {
        val line = body[index].trim()
        if (line.isEmpty() || line.startsWith('#') || line.startsWith("```") || line.startsWith("~~~") ||
            line.startsWith('>') || line.startsWith('-') || line.startsWith('*') || Regex("^\\d+[.)]\\s").containsMatchIn(line)) {
            index++
            continue
        }
        val paragraph = mutableListOf<String>()
        while (index < body.size && body[index].isNotBlank()) {
            paragraph += body[index].trim()
            index++
        }
        return paragraph.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
    return ""
}
