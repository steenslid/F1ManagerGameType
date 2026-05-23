package f1sim

import f1sim.config.AppConfig
import f1sim.db.Database
import f1sim.db.Migrations
import f1sim.http.Server
import f1sim.save.SaveService
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("f1sim.Main")
    log.info("F1 Sim starting...")

    val config = AppConfig.load()
    val db = Database(config)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        db.close()
    })

    // Ensure public schema exists.
    Migrations.initPublicSchema(db)

    val saveService = SaveService(db, config)
    val server = Server(config, db, saveService)
    server.start()

    log.info("F1 Sim ready.")
}
