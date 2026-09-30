package dev.atlas.skillscan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class DuplicateSkillsTest {
    @TempDir lateinit var temp: Path
    private val content = "---\ndescription: Shared description.\n---\n# Example\n\nFollow these instructions.\n"
    private val url = "https://github.com/example/skills"
    private val commit = "a".repeat(40)

    @Test fun `identical contents are grouped while IDs paths links and distinct instructions survive`() {
        val database = SkillDatabase(temp.resolve("skills.db"))
        val skills = listOf(
            SkillFile("skills/example/SKILL.md", content, "Shared description."),
            SkillFile(".agents/skills/example/SKILL.md", content.replace("\n", "\r\n"), "Shared description."),
            SkillFile(".claude/skills/example/SKILL.md", content, "Shared description."),
            SkillFile(".codex/skills/example/SKILL.md", content + "Different instructions.\n", "Shared description.")
        )
        val findings = database.save(url, url, "main", commit, skills)
        val groups = groupFindings(findings)
        assertEquals(listOf(3, 1), groups.map { it.locations.size })
        assertEquals(4, findings.map { it.id }.toSet().size)
        val text = formatFindings(Snapshot("main", commit, skills), findings)
        assertTrue(text.startsWith("Found 2 unique skills across 4 locations on main at $commit:"))
        assertEquals(2, Regex("Description: Shared description\\.").findAll(text).count())
        findings.forEach {
            assertTrue(text.contains(it.path))
            assertTrue(text.contains(it.id))
            assertTrue(text.contains(it.sourceLink()))
        }
        assertEquals(findings, database.save(url, url, "main", commit, skills))
        val history = database.recentScans().single()
        assertEquals(2, history.skillCount)
        assertEquals(4, history.locationCount)
        val saved = database.readScan(history.id)!!
        assertEquals(text, formatFindings(saved.snapshot, saved.findings))
        DriverManager.getConnection("jdbc:sqlite:${temp.resolve("skills.db")}").use { db ->
            assertEquals(2, count(db, "skill_contents"))
            assertEquals(4, count(db, "skill_versions"))
        }

        // One copy diverges at a later Git commit: it becomes its own result but keeps its ID.
        val changed = skills.map { if (it.path.startsWith(".claude/")) it.copy(content = content + "New version.") else it }
        val newer = database.save(url, url, "main", "b".repeat(40), changed)
        assertEquals(findings.map { it.id }, newer.map { it.id })
        assertEquals(3, groupFindings(newer).size)
        assertEquals(text, database.readScan(history.id)!!.let { formatFindings(it.snapshot, it.findings) })
    }

    @Test fun `plain-folder rescan does not store unreferenced content while showing current results`() {
        val path = temp.resolve("folder.db")
        val database = SkillDatabase(path)
        val first = listOf(SkillFile("skills/example/SKILL.md", content, "Shared description."))
        database.save(url, url, "local", "<NONE>", first)
        val current = database.save(url, url, "local", "<NONE>", first.map { it.copy(content = content + "Changed.") })
        assertEquals(contentHash(content + "Changed."), current.single().contentHash)
        assertEquals(contentHash(content), database.readScan(database.recentScans().single().id)!!.findings.single().contentHash)
        DriverManager.getConnection("jdbc:sqlite:$path").use { assertEquals(1, count(it, "skill_contents")) }
    }

    @Test fun `normalization ignores only CRLF and grouping stays within repository and commit`() {
        assertEquals(contentHash(content), contentHash(content.replace("\n", "\r\n")))
        assertNotEquals(contentHash(content), contentHash(content.trimEnd()))
        assertNotEquals(contentHash(content), contentHash(content.replace("\n", "\r")))
        val finding = Finding("id", url, "skills/example/SKILL.md", "Shared description.", commit, contentHash(content))
        assertEquals(3, groupFindings(listOf(finding, finding.copy(repositoryUrl = "https://github.com/other/repo"),
            finding.copy(commit = "b".repeat(40)))).size)
    }

    @Test fun `legacy history migrates atomically with IDs timestamps and empty scans preserved`() {
        val path = temp.resolve("legacy.db")
        legacyDatabase(path)
        val database = SkillDatabase(path)
        val history = database.recentScans()
        assertEquals(2, history.size)
        val scan = history.single { it.id == 7L }
        assertEquals("2026-01-01T00:00:00Z", scan.scannedAt)
        assertEquals(1, scan.skillCount)
        assertEquals(2, scan.locationCount)
        val findings = database.readScan(7)!!.findings
        assertEquals(setOf("skl_first", "skl_copy"), findings.map { it.id }.toSet())
        assertEquals(1, groupFindings(findings).size)
        assertEquals(0, history.single { it.id == 8L }.skillCount)
        assertTrue(database.readScan(8)!!.findings.isEmpty())
        assertEquals(history, database.recentScans(), "migration must be idempotent")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            assertEquals(1, count(db, "skill_contents"))
            assertEquals(2, count(db, "skill_versions"))
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM skill_versions ORDER BY id").use {
                    assertTrue(it.next()); assertEquals(11, it.getInt(1))
                    assertTrue(it.next()); assertEquals(12, it.getInt(1))
                }
                statement.executeQuery("SELECT content FROM skill_contents").use { assertTrue(it.next()); assertEquals(content, it.getString(1)) }
                statement.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
            }
        }
        assertEquals(findings.map { it.id }.toSet(), database.save(url, url, "main", commit,
            listOf(SkillFile("skills/example/SKILL.md", content, "Shared description."),
                SkillFile(".agents/skills/example/SKILL.md", content, "Shared description."))).map { it.id }.toSet())
    }

    @Test fun `failed migration leaves the original versions and schema intact`() {
        val path = temp.resolve("legacy.db")
        legacyDatabase(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE skill_contents(hash TEXT PRIMARY KEY, description TEXT NOT NULL, content TEXT NOT NULL)")
                it.execute("CREATE TRIGGER reject_content BEFORE INSERT ON skill_contents BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            }
        }
        assertEquals(5, assertThrows(ScanFailure::class.java) { SkillDatabase(path).recentScans() }.exitCode)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            assertEquals(2, count(db, "skill_versions"))
            assertEquals(0, count(db, "skill_contents"))
            db.createStatement().use {
                it.executeQuery("SELECT content FROM skill_versions WHERE id = 12").use { row ->
                    assertTrue(row.next()); assertEquals(content.replace("\n", "\r\n"), row.getString(1))
                }
                it.executeQuery("SELECT name FROM sqlite_master WHERE name = 'skill_versions_deduplicated'").use { row -> assertFalse(row.next()) }
            }
        }
    }

    private fun count(db: java.sql.Connection, table: String): Int = db.createStatement().use {
        it.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun legacyDatabase(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE repositories(id INTEGER PRIMARY KEY, canonical_url TEXT UNIQUE NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
                statement.execute("CREATE TABLE scans(id INTEGER PRIMARY KEY, repository_id INTEGER NOT NULL REFERENCES repositories(id), requested_url TEXT NOT NULL, scanned_branch TEXT NOT NULL, commit_sha TEXT NOT NULL, scanned_at TEXT NOT NULL, UNIQUE(repository_id, scanned_branch, commit_sha))")
                statement.execute("CREATE TABLE skills(id TEXT PRIMARY KEY, repository_id INTEGER NOT NULL REFERENCES repositories(id), source_path TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(repository_id, source_path))")
                statement.execute("CREATE TABLE skill_versions(id INTEGER PRIMARY KEY, skill_id TEXT NOT NULL REFERENCES skills(id), scan_id INTEGER NOT NULL REFERENCES scans(id), description TEXT NOT NULL, content TEXT NOT NULL, UNIQUE(skill_id, scan_id))")
                statement.execute("INSERT INTO repositories VALUES(1, '$url', '2026-01-01', '2026-01-01')")
                statement.execute("INSERT INTO scans VALUES(7, 1, '$url', 'main', '$commit', '2026-01-01T00:00:00Z')")
                statement.execute("INSERT INTO scans VALUES(8, 1, '$url', 'empty', '${"b".repeat(40)}', '2026-01-02T00:00:00Z')")
                statement.execute("INSERT INTO skills VALUES('skl_first', 1, 'skills/example/SKILL.md', '2026-01-01', '2026-01-01')")
                statement.execute("INSERT INTO skills VALUES('skl_copy', 1, '.agents/skills/example/SKILL.md', '2026-01-01', '2026-01-01')")
            }
            db.prepareStatement("INSERT INTO skill_versions VALUES(?, ?, 7, 'Shared description.', ?)").use {
                it.setInt(1, 11); it.setString(2, "skl_first"); it.setString(3, content); it.executeUpdate()
                it.setInt(1, 12); it.setString(2, "skl_copy"); it.setString(3, content.replace("\n", "\r\n")); it.executeUpdate()
            }
        }
    }
}
