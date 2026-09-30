package dev.atlas.skillscan

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

internal class SkillDatabase(private val path: Path) {
    fun save(url: String, requestedUrl: String, branch: String, commit: String, skills: List<SkillFile>): List<Finding> {
        try {
            Files.createDirectories(path.parent)
            DriverManager.getConnection("jdbc:sqlite:${path}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                connection.autoCommit = false
                try {
                    createSchema(connection)
                    val now = Instant.now().toString()
                    connection.prepareStatement("INSERT OR IGNORE INTO repositories(canonical_url, created_at, updated_at) VALUES(?, ?, ?)").use {
                        it.setString(1, url); it.setString(2, now); it.setString(3, now); it.executeUpdate()
                    }
                    val repositoryId = connection.scalarLong("SELECT id FROM repositories WHERE canonical_url = ?", url)
                    connection.prepareStatement("INSERT OR IGNORE INTO scans(repository_id, requested_url, scanned_branch, commit_sha, scanned_at) VALUES(?, ?, ?, ?, ?)").use {
                        it.setLong(1, repositoryId); it.setString(2, requestedUrl); it.setString(3, branch)
                        it.setString(4, commit); it.setString(5, now); it.executeUpdate()
                    }
                    val scanId = connection.scalarLong(
                        "SELECT id FROM scans WHERE repository_id = ? AND scanned_branch = ? AND commit_sha = ?",
                        repositoryId, branch, commit
                    )
                    val output = skills.map { skill ->
                        connection.prepareStatement("INSERT OR IGNORE INTO skills(id, repository_id, source_path, created_at, updated_at) VALUES(?, ?, ?, ?, ?)").use {
                            it.setString(1, "skl_${UUID.randomUUID().toString().replace("-", "")}")
                            it.setLong(2, repositoryId); it.setString(3, skill.path); it.setString(4, now); it.setString(5, now)
                            it.executeUpdate()
                        }
                        val skillId = connection.scalarString(
                            "SELECT id FROM skills WHERE repository_id = ? AND source_path = ?", repositoryId, skill.path
                        )
                        val hash = contentHash(skill.content)
                        if (connection.scalarLong("SELECT COUNT(*) FROM skill_versions WHERE skill_id = ? AND scan_id = ?", skillId, scanId) == 0L) {
                            storeContent(connection, skill.content, skill.description)
                            connection.prepareStatement("INSERT INTO skill_versions(skill_id, scan_id, content_hash) VALUES(?, ?, ?)").use {
                                it.setString(1, skillId); it.setLong(2, scanId); it.setString(3, hash); it.executeUpdate()
                            }
                        }
                        Finding(skillId, url, skill.path, skill.description, commit, hash, extractName(skill.content, skill.path))
                    }
                    connection.commit()
                    return output
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                }
            }
        } catch (_: SQLException) {
            throw ScanFailure(5, "could not persist scan to database: $path")
        } catch (e: ScanFailure) {
            throw e
        } catch (_: Exception) {
            throw ScanFailure(5, "could not persist scan to database: $path")
        }
    }

    fun recentScans(limit: Int = 25): List<StoredScan> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return try {
            if (Files.notExists(path)) return emptyList()
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                prepareHistory(connection)
                connection.prepareStatement(
                    """
                    SELECT scans.requested_url, repositories.canonical_url, scans.scanned_branch, scans.commit_sha,
                           scans.scanned_at, COUNT(DISTINCT skill_versions.content_hash), scans.id, COUNT(skill_versions.id)
                    FROM scans
                    JOIN repositories ON repositories.id = scans.repository_id
                    LEFT JOIN skill_versions ON skill_versions.scan_id = scans.id
                    GROUP BY scans.id
                    ORDER BY scans.scanned_at DESC, scans.id DESC
                    LIMIT ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(StoredScan(
                                requestedTarget = rows.getString(1), canonicalTarget = rows.getString(2),
                                branch = rows.getString(3), commit = rows.getString(4),
                                scannedAt = rows.getString(5), skillCount = rows.getInt(6), id = rows.getLong(7), locationCount = rows.getInt(8)
                            ))
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw ScanFailure(5, "could not read scan history from database: $path")
        }
    }

    fun readScan(id: Long): ScanResult? {
        if (Files.notExists(path)) return null
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                prepareHistory(connection)
                connection.prepareStatement("""
                    SELECT requested_url, canonical_url, scanned_branch, commit_sha FROM scans
                    JOIN repositories ON repositories.id = repository_id WHERE scans.id = ?
                """.trimIndent()).use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        val requested = rows.getString(1)
                        val canonical = rows.getString(2)
                        val snapshot = Snapshot(rows.getString(3), rows.getString(4), emptyList())
                        val findings = connection.prepareStatement("""
                            SELECT skills.id, source_path, description, content_hash, content FROM skill_versions
                            JOIN skills ON skills.id = skill_id JOIN skill_contents ON hash = content_hash
                            WHERE scan_id = ? ORDER BY source_path COLLATE BINARY
                        """.trimIndent()).use { query ->
                            query.setLong(1, id)
                            query.executeQuery().use { skills ->
                                buildList {
                                    while (skills.next()) add(Finding(skills.getString(1), canonical,
                                        skills.getString(2), skills.getString(3), snapshot.commit, skills.getString(4),
                                        extractName(skills.getString(5), skills.getString(2))))
                                }
                            }
                        }
                        return ScanResult(requested, canonical, snapshot, findings)
                    }
                }
            }
        } catch (_: SQLException) {
            throw ScanFailure(5, "could not read scan history from database: $path")
        }
    }

    private fun prepareHistory(connection: Connection) {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        connection.autoCommit = false
        try {
            createSchema(connection)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally { connection.autoCommit = true }
    }

    private fun storeContent(connection: Connection, content: String, description: String): String {
        val normalized = normalizedContent(content)
        val hash = contentHash(normalized)
        connection.prepareStatement("INSERT OR IGNORE INTO skill_contents(hash, description, content) VALUES(?, ?, ?)").use {
            it.setString(1, hash); it.setString(2, description); it.setString(3, normalized); it.executeUpdate()
        }
        // Do not silently coalesce different contents even in the event of a digest collision.
        if (connection.scalarString("SELECT content FROM skill_contents WHERE hash = ?", hash) != normalized) {
            throw SQLException("skill content hash collision")
        }
        return hash
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("""
                CREATE TABLE IF NOT EXISTS repositories (
                  id INTEGER PRIMARY KEY,
                  canonical_url TEXT NOT NULL UNIQUE,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS scans (
                  id INTEGER PRIMARY KEY,
                  repository_id INTEGER NOT NULL REFERENCES repositories(id),
                  requested_url TEXT NOT NULL,
                  scanned_branch TEXT NOT NULL,
                  commit_sha TEXT NOT NULL,
                  scanned_at TEXT NOT NULL,
                  UNIQUE(repository_id, scanned_branch, commit_sha)
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS skills (
                  id TEXT PRIMARY KEY,
                  repository_id INTEGER NOT NULL REFERENCES repositories(id),
                  source_path TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  UNIQUE(repository_id, source_path)
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS skill_contents (
                  hash TEXT PRIMARY KEY NOT NULL,
                  description TEXT NOT NULL,
                  content TEXT NOT NULL
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS skill_versions (
                  id INTEGER PRIMARY KEY,
                  skill_id TEXT NOT NULL REFERENCES skills(id),
                  scan_id INTEGER NOT NULL REFERENCES scans(id),
                  content_hash TEXT NOT NULL REFERENCES skill_contents(hash),
                  UNIQUE(skill_id, scan_id)
                )
            """.trimIndent())
        }
        val legacy = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(skill_versions)").use { columns ->
                var found = false
                while (columns.next()) if (columns.getString("name") == "content") found = true
                found
            }
        }
        if (legacy) migrateContents(connection)
    }

    /** Runs inside the caller's transaction: any failure restores the entire original schema/data. */
    private fun migrateContents(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("""
                CREATE TABLE skill_versions_deduplicated (
                  id INTEGER PRIMARY KEY,
                  skill_id TEXT NOT NULL REFERENCES skills(id),
                  scan_id INTEGER NOT NULL REFERENCES scans(id),
                  content_hash TEXT NOT NULL REFERENCES skill_contents(hash),
                  UNIQUE(skill_id, scan_id)
                )
            """.trimIndent())
            statement.executeQuery("SELECT id, skill_id, scan_id, description, content FROM skill_versions ORDER BY id").use { rows ->
                connection.prepareStatement("INSERT INTO skill_versions_deduplicated(id, skill_id, scan_id, content_hash) VALUES(?, ?, ?, ?)").use { insert ->
                    while (rows.next()) {
                        val hash = storeContent(connection, rows.getString(5), rows.getString(4))
                        insert.setLong(1, rows.getLong(1)); insert.setString(2, rows.getString(2))
                        insert.setLong(3, rows.getLong(3)); insert.setString(4, hash); insert.executeUpdate()
                    }
                }
            }
            statement.execute("DROP TABLE skill_versions")
            statement.execute("ALTER TABLE skill_versions_deduplicated RENAME TO skill_versions")
        }
    }
}

internal data class StoredScan(
    val id: Long,
    val requestedTarget: String,
    val canonicalTarget: String,
    val branch: String,
    val commit: String,
    val scannedAt: String,
    val skillCount: Int,
    val locationCount: Int
)

private fun Connection.scalarLong(sql: String, vararg args: Any): Long =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            if (!result.next()) throw SQLException("expected database row")
            result.getLong(1)
        }
    }

private fun Connection.scalarString(sql: String, vararg args: Any): String =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            if (!result.next()) throw SQLException("expected database row")
            result.getString(1)
        }
    }
