package f1sim.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import f1sim.config.AppConfig
import f1sim.save.SaveSession
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Wraps HikariCP. The schema-per-save model is handled by setting `search_path`
 * on each borrowed connection inside [withConnection], based on [SaveSession].
 *
 * Why not HikariCP's `connectionInitSql`? It's set once at pool config time, but
 * the active save changes at runtime. Doing it per-borrow is cheap (one SET
 * statement) and always correct.
 */
class Database(config: AppConfig) : AutoCloseable {

    private val log = LoggerFactory.getLogger(Database::class.java)

    private val dataSource: HikariDataSource = HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 8
        minimumIdle = 1
        poolName = "f1sim-pool"
        // Default search_path to public; per-borrow we override to the loaded save's schema.
        connectionInitSql = "SET search_path TO public"
    }.let(::HikariDataSource)

    init {
        log.info("DB pool initialised: {}", config.dbUrl)
    }

    /**
     * Borrows a connection from the pool and ensures `search_path` points at the
     * currently-loaded save's schema (or just `public` if none is loaded).
     * Connections are auto-closed back to the pool.
     */
    fun <T> withConnection(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            val schema = SaveSession.currentSchema
            val pathSql = if (schema != null) {
                "SET search_path TO \"$schema\", public"
            } else {
                "SET search_path TO public"
            }
            conn.createStatement().use { it.execute(pathSql) }
            block(conn)
        }
    }

    /**
     * For administrative operations that must run regardless of the loaded save
     * (creating schemas, dropping schemas, running migrations against a specific
     * schema by name). Caller is responsible for any SET search_path.
     */
    fun <T> withAdminConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    override fun close() {
        dataSource.close()
    }
}
