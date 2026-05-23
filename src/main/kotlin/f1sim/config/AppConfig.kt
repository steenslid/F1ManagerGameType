package f1sim.config

/**
 * App configuration. Read from system properties (-Df1sim.foo=...) or environment.
 * Single-player local app — no secrets management story needed.
 */
data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val httpPort: Int,
    val schemaVersion: Int,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun load(): AppConfig = AppConfig(
            dbUrl = prop("f1sim.db.url", "jdbc:postgresql://localhost:5432/f1sim"),
            dbUser = prop("f1sim.db.user", "f1sim"),
            dbPassword = prop("f1sim.db.password", "f1sim"),
            httpPort = prop("f1sim.http.port", "7777").toInt(),
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )

        private fun prop(key: String, default: String): String =
            System.getProperty(key)
                ?: System.getenv(key.uppercase().replace('.', '_'))
                ?: default
    }
}
