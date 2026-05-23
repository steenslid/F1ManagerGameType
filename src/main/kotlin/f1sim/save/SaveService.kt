package f1sim.save

import f1sim.config.AppConfig
import f1sim.db.Database
import f1sim.db.Migrations
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Save lifecycle. Each save is its own Postgres schema.
 *
 * v1 does the minimum: create/list/load/delete with the `game` row written on
 * creation. Reference-data seed loading (drivers, teams, etc.) is a separate
 * concern and will plug in here once seeds exist.
 */
class SaveService(
    private val db: Database,
    private val config: AppConfig,
) {
    private val log = LoggerFactory.getLogger(SaveService::class.java)

    @Serializable
    data class CreateSaveRequest(
        val saveName: String,
        val managerName: String,
        val difficulty: String = "NORMAL",
        val seed: Long? = null,
    )

    @Serializable
    data class SaveSummary(
        val saveId: String,
        val saveName: String,
        val createdAt: String,
        val lastPlayedAt: String,
        val schemaVersion: Int,
    )

    fun list(): List<SaveSummary> = db.withAdminConnection { conn ->
        conn.prepareStatement(
            """
            SELECT save_id, save_name, created_at, last_played_at, schema_version
              FROM public.saves
             ORDER BY last_played_at DESC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SaveSummary(
                                saveId = rs.getObject("save_id", UUID::class.java).toString(),
                                saveName = rs.getString("save_name"),
                                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                                lastPlayedAt = rs.getTimestamp("last_played_at").toInstant().toString(),
                                schemaVersion = rs.getInt("schema_version"),
                            )
                        )
                    }
                }
            }
        }
    }

    fun create(req: CreateSaveRequest): SaveSummary {
        require(req.saveName.isNotBlank()) { "saveName must not be blank" }
        require(req.managerName.isNotBlank()) { "managerName must not be blank" }
        require(req.difficulty in setOf("EASY", "NORMAL", "HARD", "BRUTAL")) {
            "difficulty must be one of EASY/NORMAL/HARD/BRUTAL"
        }

        val saveId = UUID.randomUUID()
        val schemaName = "save_" + saveId.toString().replace("-", "_")
        val seed = req.seed ?: UUID.randomUUID().leastSignificantBits

        // 1. Create the per-save schema and apply its migrations.
        Migrations.createSaveSchema(db, schemaName)

        // 2. Insert the `game` row in the new schema and the registry row in public.
        val now = Timestamp.from(Instant.now())
        try {
            db.withAdminConnection { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        """
                        INSERT INTO public.saves
                          (save_id, schema_name, save_name, created_at, last_played_at, schema_version)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setObject(1, saveId)
                        stmt.setString(2, schemaName)
                        stmt.setString(3, req.saveName)
                        stmt.setTimestamp(4, now)
                        stmt.setTimestamp(5, now)
                        stmt.setInt(6, config.schemaVersion)
                        stmt.executeUpdate()
                    }

                    conn.createStatement().use { it.execute("SET search_path TO \"$schemaName\"") }
                    conn.prepareStatement(
                        """
                        INSERT INTO game
                          (save_id, save_name, schema_version, player_manager_name,
                           difficulty, master_rng_seed, current_season_year)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setObject(1, saveId)
                        stmt.setString(2, req.saveName)
                        stmt.setInt(3, config.schemaVersion)
                        stmt.setString(4, req.managerName)
                        stmt.setString(5, req.difficulty)
                        stmt.setLong(6, seed)
                        stmt.setInt(7, 2026)
                        stmt.executeUpdate()
                    }

                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (t: Throwable) {
            // Rollback schema creation if insert failed
            log.warn("Save creation failed for {}, dropping schema {}", saveId, schemaName, t)
            runCatching { Migrations.dropSaveSchema(db, schemaName) }
            throw t
        }

        log.info("Created save {} ({})", saveId, schemaName)
        return SaveSummary(
            saveId = saveId.toString(),
            saveName = req.saveName,
            createdAt = now.toInstant().toString(),
            lastPlayedAt = now.toInstant().toString(),
            schemaVersion = config.schemaVersion,
        )
    }

    fun load(saveId: UUID): SaveSummary {
        val (schemaName, summary) = db.withAdminConnection { conn ->
            conn.prepareStatement(
                """
                SELECT save_id, schema_name, save_name, created_at, last_played_at, schema_version
                  FROM public.saves
                 WHERE save_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, saveId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) error("No save with id $saveId")
                    val schema = rs.getString("schema_name")
                    val s = SaveSummary(
                        saveId = rs.getObject("save_id", UUID::class.java).toString(),
                        saveName = rs.getString("save_name"),
                        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                        lastPlayedAt = rs.getTimestamp("last_played_at").toInstant().toString(),
                        schemaVersion = rs.getInt("schema_version"),
                    )
                    schema to s
                }
            }
        }

        SaveSession.load(saveId, schemaName)

        // Touch last_played_at
        db.withAdminConnection { conn ->
            conn.prepareStatement(
                "UPDATE public.saves SET last_played_at = now() WHERE save_id = ?"
            ).use { stmt ->
                stmt.setObject(1, saveId)
                stmt.executeUpdate()
            }
        }

        log.info("Loaded save {} ({})", saveId, schemaName)
        return summary
    }

    fun delete(saveId: UUID) {
        val schemaName = db.withAdminConnection { conn ->
            conn.prepareStatement(
                "SELECT schema_name FROM public.saves WHERE save_id = ?"
            ).use { stmt ->
                stmt.setObject(1, saveId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) error("No save with id $saveId")
                    rs.getString("schema_name")
                }
            }
        }

        if (SaveSession.current?.saveId == saveId) {
            SaveSession.unload()
        }

        db.withAdminConnection { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM public.saves WHERE save_id = ?").use { stmt ->
                    stmt.setObject(1, saveId)
                    stmt.executeUpdate()
                }
                conn.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schemaName\" CASCADE") }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }

        log.info("Deleted save {} ({})", saveId, schemaName)
    }
}
