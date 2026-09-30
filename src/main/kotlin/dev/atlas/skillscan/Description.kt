package dev.atlas.skillscan

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

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
