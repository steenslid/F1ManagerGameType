package f1sim.http

import f1sim.config.AppConfig
import f1sim.db.Database
import f1sim.http.routes.GameRoutes
import f1sim.http.routes.SaveRoutes
import f1sim.save.SaveService
import io.javalin.Javalin
import org.slf4j.LoggerFactory

/**
 * Javalin wrapper. All routes are registered here in one place.
 */
class Server(
    private val config: AppConfig,
    private val db: Database,
    private val saveService: SaveService,
) {
    private val log = LoggerFactory.getLogger(Server::class.java)
    private lateinit var app: Javalin

    fun start() {
        app = Javalin.create { cfg ->
            cfg.showJavalinBanner = false
            cfg.http.defaultContentType = "application/json"
            cfg.bundledPlugins.enableCors { cors ->
                cors.addRule { it.anyHost() } // Vue dev server on localhost:5173 etc.
            }
        }

        SaveRoutes(saveService).register(app)
        GameRoutes(db).register(app)

        app.get("/api/health") { ctx ->
            ctx.contentType("application/json")
            ctx.result("""{"data":{"status":"ok"},"errors":[],"meta":{"elapsed_ms":0}}""")
        }

        app.start(config.httpPort)
        log.info("HTTP server listening on http://localhost:{}", config.httpPort)
    }

    fun stop() {
        if (::app.isInitialized) app.stop()
    }
}
