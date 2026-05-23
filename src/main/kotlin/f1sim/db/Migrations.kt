package f1sim.db

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Tiny migration runner. Loads SQL from the classpath and executes it.
 *
 * v1 keeps things simple: two static files, applied unconditionally to a fresh
 * schema. When a real version-tracking story is needed, add a migrations table
 * and a `migrate(targetVersion)` step.
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)

    private const val PUBLIC_SCHEMA_SQL = "/sql/public_schema.sql"
    private const val SAVE_SCHEMA_SQL = "/sql/save_schema.sql"

    /** Initialise the shared `public` schema (saves registry). Idempotent. */
    fun initPublicSchema(db: Database) {
        log.info("Applying public schema migrations")
        val sql = loadResource(PUBLIC_SCHEMA_SQL)
        db.withAdminConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SET search_path TO public")
                stmt.execute(sql)
            }
        }
    }

    /** Create a fresh save schema and apply its starter migrations. */
    fun createSaveSchema(db: Database, schemaName: String) {
        require(schemaName.matches(Regex("^save_[a-z0-9_]+$"))) {
            "Invalid schema name: $schemaName"
        }
        log.info("Creating save schema: {}", schemaName)
        val sql = loadResource(SAVE_SCHEMA_SQL)
        db.withAdminConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA \"$schemaName\"")
                stmt.execute("SET search_path TO \"$schemaName\"")
                stmt.execute(sql)
            }
        }
    }

    /** Drop a save schema and everything in it. Irreversible. */
    fun dropSaveSchema(db: Database, schemaName: String) {
        require(schemaName.matches(Regex("^save_[a-z0-9_]+$"))) {
            "Invalid schema name: $schemaName"
        }
        log.info("Dropping save schema: {}", schemaName)
        db.withAdminConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP SCHEMA IF EXISTS \"$schemaName\" CASCADE")
            }
        }
    }

    private fun loadResource(path: String): String =
        Migrations::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing migration resource: $path")
}
