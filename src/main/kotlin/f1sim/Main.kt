package f1sim

import f1sim.config.AppConfig
import f1sim.db.Database
import f1sim.db.Migrations
import f1sim.game.BoardService
import f1sim.game.DriverMarketService
import f1sim.game.GameService
import f1sim.game.LineupService
import f1sim.game.OffSeasonService
import f1sim.game.RaceWeekendService
import f1sim.game.SponsorMarketService
import f1sim.game.StandingsService
import f1sim.game.TeamRdService
import f1sim.game.UpgradeService
import f1sim.http.Server
import f1sim.save.SaveService
import f1sim.seed.SeedLoader
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

    Migrations.initPublicSchema(db)

    val seedLoader = SeedLoader()
    val saveService = SaveService(db, config, seedLoader)
    val offSeasonService = OffSeasonService(db)
    val driverMarketService = DriverMarketService(db)
    val upgradeService = UpgradeService(db)
    val boardService = BoardService(db)
    val gameService = GameService(db, offSeasonService, driverMarketService, upgradeService, boardService)
    val raceWeekendService = RaceWeekendService(db)
    val standingsService = StandingsService(db)
    val lineupService = LineupService(db)
    val teamRdService = TeamRdService(db)
    val sponsorMarketService = SponsorMarketService(db)
    val server = Server(
        config = config,
        db = db,
        saveService = saveService,
        gameService = gameService,
        raceWeekendService = raceWeekendService,
        standingsService = standingsService,
        driverMarketService = driverMarketService,
        lineupService = lineupService,
        teamRdService = teamRdService,
        sponsorMarketService = sponsorMarketService,
        boardService = boardService,
        upgradeService = upgradeService,
    )
    server.start()

    log.info("F1 Sim ready.")
}
