package dev.atlas.skillscan

import java.net.URI
import java.nio.file.Path

internal const val CLI_HELP = """Usage: skill-atlas scan <repository-url-or-folder> [options]
       skill-atlas serve [--port <port>]

Scan options (before or after the target):
  --branch <name>             Scan a named branch
  --scan-organizations       Allow a GitHub user or organization URL and scan every
                             repository it owns (also accepted as -scan-organizations)
  --rescan                   With --scan-organizations, scan repositories that were
                             already scanned instead of reporting the saved results
  --verbose                  Show full commit, location IDs and source URLs
  --json                     Emit one JSON object without terminal formatting
  --color auto|always|never   Color mode (default: auto; NO_COLOR disables auto)
  -h, --help                 Show this help

Descriptions wrap to COLUMNS (default 80). Interactive selection is not supported."""

internal data class ScanOptions(val target: String, val branch: String?, val verbose: Boolean, val json: Boolean,
                                val color: String, val scanOwners: Boolean = false, val rescan: Boolean = false)

internal fun parseScanOptions(args: Array<String>): ScanOptions {
    fun invalid(message: String): Nothing = throw ScanFailure(2, "$message; use 'skill-atlas scan --help'")
    if (args.firstOrNull() != "scan") invalid("expected scan <repository-url-or-folder>")
    var target: String? = null
    var branch: String? = null
    var color = "auto"
    val seen = mutableSetOf<String>()
    var i = 1
    while (i < args.size) {
        val raw = args[i++]
        if (raw.startsWith('-')) {
            // The issue that requested organization scanning spelled the option with one leading dash.
            val arg = if (raw == "-scan-organizations") "--scan-organizations" else raw
            if (arg !in setOf("--branch", "--verbose", "--json", "--color", "--scan-organizations", "--rescan")) {
                invalid("unknown option: $raw")
            }
            if (!seen.add(arg)) invalid("duplicate option: $arg")
            if (arg == "--branch" || arg == "--color") {
                val value = args.getOrNull(i++)?.takeIf { it.isNotBlank() && !it.startsWith('-') }
                    ?: invalid("$arg requires a value")
                if (arg == "--branch") branch = value
                else {
                    if (value !in setOf("auto", "always", "never")) invalid("--color must be auto, always, or never")
                    color = value
                }
            }
        } else {
            if (target != null) invalid("expected only one scan target")
            target = raw
        }
    }
    if ("--verbose" in seen && "--json" in seen) invalid("--verbose and --json cannot be combined")
    if ("--rescan" in seen && "--scan-organizations" !in seen) invalid("--rescan requires --scan-organizations")
    if ("--branch" in seen && "--scan-organizations" in seen) {
        invalid("--branch cannot be combined with --scan-organizations; every repository uses its own default branch")
    }
    return ScanOptions(target ?: invalid("missing scan target"), branch, "--verbose" in seen, "--json" in seen, color,
        "--scan-organizations" in seen, "--rescan" in seen)
}

internal data class CliPresentation(val color: Boolean, val hyperlinks: Boolean, val width: Int) {
    fun paint(code: String, text: String): String = if (color) "\u001b[${code}m$text\u001b[0m" else text

    companion object {
        fun fromEnvironment(environment: Map<String, String>, mode: String = "auto",
                            terminal: Boolean = System.console() != null, machineReadable: Boolean = false): CliPresentation {
            val color = !machineReadable && when (mode) {
                "always" -> true
                "never" -> false
                else -> terminal && "NO_COLOR" !in environment && environment["TERM"] != "dumb"
            }
            val supportsLinks = environment["TERM_PROGRAM"] in setOf("iTerm.app", "WezTerm", "vscode") ||
                environment["TERM"] == "xterm-kitty" || environment.containsKey("WT_SESSION")
            return CliPresentation(color, color && terminal && supportsLinks,
                environment["COLUMNS"]?.toIntOrNull()?.takeIf { it in 20..500 } ?: 80)
        }
    }
}

internal fun formatCompact(result: ScanResult, style: CliPresentation): String {
    val groups = groupFindings(result.findings)
    val snapshot = result.snapshot
    val commit = if (snapshot.commit == "<NONE>") "<NONE>" else snapshot.commit.take(12)
    val context = "${snapshot.branch.safeLine()} at ${commit.safeLine()}"
    if (groups.isEmpty()) return "No SKILL.md files found on $context."
    val heading = "Found ${groups.size} ${if (groups.size == 1) "skill" else "skills"} across " +
        "${result.findings.size} ${if (result.findings.size == 1) "location" else "locations"}"
    return style.paint("1;36", heading) + "\n" + style.paint("2", "on $context") + "\n\n" +
        groups.mapIndexed { index, group ->
            val count = if (group.locations.size > 1) style.paint("2", "  [${group.locations.size} locations]") else ""
            val title = style.paint("36", "${index + 1}.") + " " + style.paint("1", group.representative.name.displayText()) + count
            val description = group.description.displayText().ifEmpty { "(no description)" }
            val paths = group.locations.joinToString("\n") { finding ->
                val label = style.paint("2", finding.path.safeLine())
                "    " + if (style.hyperlinks) terminalLink(finding, label) else label
            }
            title + "\n" + wrapDescription(description, style.width - 3).joinToString("\n") { "   $it" } + "\n" + paths
        }.joinToString("\n\n")
}

private fun String.displayText(): String = replace(Regex("[\\r\\n\\t]+"), " ").safeLine().replace(Regex(" +"), " ").trim()

/** Wrap prose by Unicode code points without splitting surrogate pairs; paths remain copyable. */
private fun wrapDescription(text: String, width: Int): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    for (word in text.split(' ')) {
        val points = word.codePoints().toArray()
        if (line.isNotEmpty() && line.codePointCount(0, line.length) + 1 + points.size > width) {
            lines += line
            line = ""
        }
        if (points.size > width) {
            var offset = 0
            while (points.size - offset > width) {
                lines += String(points, offset, width)
                offset += width
            }
            line = String(points, offset, points.size - offset)
        } else line = if (line.isEmpty()) word else "$line $word"
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

private fun terminalLink(finding: Finding, label: String): String {
    val uri = runCatching {
        if (finding.repositoryUrl.startsWith("file:")) Path.of(URI(finding.repositoryUrl)).resolve(finding.path).toUri()
        else URI(finding.sourceLink())
    }.getOrNull() ?: return label
    if (uri.scheme !in setOf("https", "http", "file") || uri.userInfo != null) return label
    val target = uri.toASCIIString()
    if (target.any { it.isISOControl() }) return label
    return "\u001b]8;;$target\u001b\\$label\u001b]8;;\u001b\\"
}

private fun plural(count: Int, singular: String, many: String): String = "$count ${if (count == 1) singular else many}"

private fun OwnerRepositoryOutcome.counts(): String =
    plural(skillCount, "skill", "skills") + " across " + plural(locationCount, "location", "locations")

internal fun formatOwner(result: OwnerScanResult, style: CliPresentation, verbose: Boolean): String {
    val plan = result.plan
    val owner = "${plan.login.safeLine()} (${plan.type.lowercase().safeLine()})"
    if (plan.repositories.isEmpty()) return "No repositories are visible in $owner."
    val heading = "Scanned ${result.scanned.size} of ${plural(plan.repositories.size, "repository", "repositories")} in $owner"
    val totals = plural(result.skillCount, "skill", "skills") + " across " +
        plural(result.locationCount, "location", "locations") + " in " +
        plural(result.scanned.size + result.skipped.size, "repository", "repositories")
    fun entries(outcomes: List<OwnerRepositoryOutcome>, numbered: Boolean, detail: (OwnerRepositoryOutcome) -> String) =
        outcomes.mapIndexed { index, outcome ->
            val label = if (numbered) style.paint("36", "${index + 1}.") + " " else "   "
            val link = if (verbose) "\n      " + style.paint("2", outcome.url.safeLine()) else ""
            label + style.paint("1", outcome.fullName.safeLine()) + "  " + style.paint("2", detail(outcome)) + link
        }.joinToString("\n")
    val sections = mutableListOf(style.paint("1;36", heading) + "\n" + style.paint("2", totals))
    if (result.scanned.isNotEmpty()) sections += entries(result.scanned, true) { it.counts() }
    if (result.skipped.isNotEmpty()) {
        sections += "Reported ${plural(result.skipped.size, "already scanned repository", "already scanned repositories")} " +
            "from saved results; add --rescan to scan them again:\n" + entries(result.skipped, false) { it.counts() }
    }
    if (result.failed.isNotEmpty()) {
        sections += style.paint("31", plural(result.failed.size, "repository", "repositories") + " could not be scanned:") +
            "\n" + entries(result.failed, false) { "code ${it.code}: ${(it.message ?: "").safeLine()}" }
    }
    if (plan.truncated) {
        sections += "Only the first ${plan.repositories.size} repositories were considered; " +
            "raise SKILL_SCAN_MAX_OWNER_REPOSITORIES to include more."
    }
    return sections.joinToString("\n\n")
}

internal fun formatOwnerJson(result: OwnerScanResult): String = json(linkedMapOf(
    "schemaVersion" to 1,
    "kind" to "owner",
    "owner" to result.plan.login,
    "ownerType" to result.plan.type,
    "target" to result.plan.url,
    "repositoryCount" to result.plan.repositories.size,
    "truncated" to result.plan.truncated,
    "scannedCount" to result.scanned.size,
    "skippedCount" to result.skipped.size,
    "failedCount" to result.failed.size,
    "skillCount" to result.skillCount,
    "locationCount" to result.locationCount,
    "repositories" to result.outcomes.map { outcome ->
        linkedMapOf<String, Any?>(
            "name" to outcome.fullName,
            "target" to outcome.url,
            "status" to outcome.status,
            "skillCount" to outcome.skillCount,
            "locationCount" to outcome.locationCount
        ).apply { if (outcome.code != null) { put("code", outcome.code); put("message", outcome.message) } }
    }
))

internal fun formatJson(result: ScanResult): String = json(linkedMapOf(
    "schemaVersion" to 1,
    "target" to result.canonicalTarget,
    "branch" to result.snapshot.branch,
    "commit" to result.snapshot.commit,
    "skillCount" to groupFindings(result.findings).size,
    "locationCount" to result.findings.size,
    "skills" to groupFindings(result.findings).map { group -> linkedMapOf(
        "name" to group.representative.name,
        "description" to group.description,
        "contentHash" to group.representative.contentHash,
        "locations" to group.locations.map { linkedMapOf("id" to it.id, "path" to it.path, "link" to it.sourceLink()) }
    ) }
))
