package f1sim.http

import f1sim.config.AppConfig
import f1sim.db.Database
import f1sim.game.DriverMarketService
import f1sim.game.GameService
import f1sim.game.LineupService
import f1sim.game.RaceWeekendService
import f1sim.game.StandingsService
import f1sim.game.TeamRdService
import f1sim.http.routes.DriverMarketRoutes
import f1sim.http.routes.DriverRoutes
import f1sim.http.routes.GameRoutes
import f1sim.http.routes.LineupRoutes
import f1sim.http.routes.OffSeasonRoutes
import f1sim.http.routes.PersonnelRoutes
import f1sim.http.routes.PowerUnitRoutes
import f1sim.http.routes.RaceResultsRoutes
import f1sim.http.routes.RaceRoutes
import f1sim.http.routes.RaceWeekendRoutes
import f1sim.http.routes.ReferenceRoutes
import f1sim.http.routes.SaveRoutes
import f1sim.http.routes.SponsorRoutes
import f1sim.http.routes.SprintResultsRoutes
import f1sim.http.routes.StandingsRoutes
import f1sim.http.routes.TeamRdRoutes
import f1sim.http.routes.TeamRoutes
import f1sim.http.routes.TeamSponsorshipRoutes
import f1sim.http.routes.TrackRoutes
import f1sim.save.SaveService
import io.javalin.Javalin
import org.slf4j.LoggerFactory

class Server(
    private val config: AppConfig,
    private val db: Database,
    private val saveService: SaveService,
    private val gameService: GameService,
    private val raceWeekendService: RaceWeekendService,
    private val standingsService: StandingsService,
    private val driverMarketService: DriverMarketService,
    private val lineupService: LineupService,
    private val teamRdService: TeamRdService,
) {
    private val log = LoggerFactory.getLogger(Server::class.java)
    private lateinit var app: Javalin

    fun start() {
        app = Javalin.create { cfg ->
            cfg.showJavalinBanner = false
            cfg.http.defaultContentType = "application/json"
            cfg.bundledPlugins.enableCors { cors ->
                cors.addRule { it.anyHost() }
            }
        }

        SaveRoutes(saveService).register(app)
        GameRoutes(gameService).register(app)
        TeamRoutes(db).register(app)
        DriverRoutes(db).register(app)
        PersonnelRoutes(db).register(app)
        TrackRoutes(db).register(app)
        RaceRoutes(db).register(app)
        RaceResultsRoutes(db).register(app)
        SprintResultsRoutes(db).register(app)
        RaceWeekendRoutes(raceWeekendService).register(app)
        StandingsRoutes(standingsService).register(app)
        OffSeasonRoutes(db).register(app)
        DriverMarketRoutes(driverMarketService).register(app)
        LineupRoutes(lineupService).register(app)
        TeamRdRoutes(teamRdService).register(app)
        TeamSponsorshipRoutes(db).register(app)
        PowerUnitRoutes(db).register(app)
        SponsorRoutes(db).register(app)
        ReferenceRoutes(db).register(app)

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
