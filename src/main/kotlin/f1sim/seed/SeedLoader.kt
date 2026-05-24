package f1sim.seed

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/**
 * Loads reference and initial-state data into a freshly-created save schema.
 *
 * Insert order in [loadAllInto] respects FK dependencies:
 *   eras, compounds, tracks, sponsors  (no FKs)
 *   races (FK to tracks)
 *   teams (no FKs in v1)
 *   engine_suppliers (FK to teams.works_team_id)
 *   pu_versions (FK to engine_suppliers)
 *   drivers (FKs to teams)
 *   personnel (FK to teams)
 */
class SeedLoader {

    private val log = LoggerFactory.getLogger(SeedLoader::class.java)

    private val json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = true
    }

    fun loadAllInto(conn: Connection) {
        log.info("Seeding save schema")
        loadRegulationEras(conn)
        loadTyreCompounds(conn)
        loadTracks(conn)
        loadSponsors(conn)
        loadRaces(conn)
        loadTeams(conn)
        loadEngineSuppliers(conn)
        loadPuVersions(conn)
        loadDrivers(conn)
        loadPersonnel(conn)
        log.info("Seeding complete")
    }

    private fun loadRegulationEras(conn: Connection) {
        val items = readSeed(SeedFiles.REGULATION_ERAS, RegulationEraSeed.serializer())
        val sql = """
            INSERT INTO regulation_eras
              (id, name, start_year, end_year, performance_reset_severity, ers_share,
               fastest_lap_point)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { e ->
                stmt.setString(1, e.id)
                stmt.setString(2, e.name)
                stmt.setInt(3, e.startYear)
                stmt.setIntOrNull(4, e.endYear)
                stmt.setDouble(5, e.performanceResetSeverity)
                stmt.setDouble(6, e.ersShare)
                stmt.setBoolean(7, e.fastestLapPoint)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  regulation_eras: {} rows", items.size)
    }

    private fun loadTyreCompounds(conn: Connection) {
        val items = readSeed(SeedFiles.TYRE_COMPOUNDS, TyreCompoundSeed.serializer())
        val sql = """
            INSERT INTO tyre_compounds
              (id, name, base_pace_factor, degradation_rate, longevity_laps,
               optimal_temp_min_c, optimal_temp_max_c)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { t ->
                stmt.setString(1, t.id)
                stmt.setString(2, t.name)
                stmt.setDouble(3, t.basePaceFactor)
                stmt.setDouble(4, t.degradationRate)
                stmt.setInt(5, t.longevityLaps)
                stmt.setDouble(6, t.optimalTempMinC)
                stmt.setDouble(7, t.optimalTempMaxC)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  tyre_compounds: {} rows", items.size)
    }

    private fun loadTracks(conn: Connection) {
        val items = readSeed(SeedFiles.TRACKS, TrackSeed.serializer())
        val sql = """
            INSERT INTO tracks
              (id, name, country, length_km, type,
               demand_top_speed, demand_acceleration,
               demand_low_speed_cornering, demand_medium_speed_cornering, demand_high_speed_cornering,
               demand_braking, demand_tyre_wear, demand_cooling,
               pit_lane_loss_seconds, overtake_difficulty,
               rain_probability_baseline, temperature_min_c, temperature_max_c)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { t ->
                stmt.setString(1, t.id)
                stmt.setString(2, t.name)
                stmt.setString(3, t.country)
                stmt.setDouble(4, t.lengthKm)
                stmt.setString(5, t.type)
                stmt.setDouble(6, t.demandTopSpeed)
                stmt.setDouble(7, t.demandAcceleration)
                stmt.setDouble(8, t.demandLowSpeedCornering)
                stmt.setDouble(9, t.demandMediumSpeedCornering)
                stmt.setDouble(10, t.demandHighSpeedCornering)
                stmt.setDouble(11, t.demandBraking)
                stmt.setDouble(12, t.demandTyreWear)
                stmt.setDouble(13, t.demandCooling)
                stmt.setDouble(14, t.pitLaneLossSeconds)
                stmt.setDouble(15, t.overtakeDifficulty)
                stmt.setDouble(16, t.rainProbabilityBaseline)
                stmt.setDouble(17, t.temperatureMinC)
                stmt.setDouble(18, t.temperatureMaxC)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  tracks: {} rows", items.size)
    }

    private fun loadSponsors(conn: Connection) {
        val items = readSeed(SeedFiles.SPONSORS, SponsorSeed.serializer())
        val sql = """
            INSERT INTO sponsors
              (id, name, country, tier, industry, prestige,
               performance_sensitivity, risk_tolerance, prestige_preference,
               budget_min, budget_max)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { s ->
                stmt.setString(1, s.id)
                stmt.setString(2, s.name)
                stmt.setString(3, s.country)
                stmt.setString(4, s.tier)
                stmt.setString(5, s.industry)
                stmt.setInt(6, s.prestige)
                stmt.setDouble(7, s.performanceSensitivity)
                stmt.setDouble(8, s.riskTolerance)
                stmt.setDouble(9, s.prestigePreference)
                stmt.setLong(10, s.budgetMin)
                stmt.setLong(11, s.budgetMax)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  sponsors: {} rows", items.size)
    }

    private fun loadRaces(conn: Connection) {
        val items = readSeed(SeedFiles.RACES, RaceSeed.serializer())
        val sql = """
            INSERT INTO races
              (id, season_year, round, track_id, session_format)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { r ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setInt(2, r.seasonYear)
                stmt.setInt(3, r.round)
                stmt.setString(4, r.trackId)
                stmt.setString(5, r.sessionFormat)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  races: {} rows", items.size)
    }

    private fun loadTeams(conn: Connection) {
        val items = readSeed(SeedFiles.TEAMS, TeamSeed.serializer())
        val sql = """
            INSERT INTO teams
              (id, name, country, base_country, series, prestige, is_custom_team,
               cash_reserves, heritage_payment, base_operating_cost, academy_investment,
               regulation_understanding, pit_crew_rating,
               ai_aggression, ai_ambition, ai_loyalty, ai_frugality, ai_development_focus,
               board_ambition, board_realism)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { t ->
                stmt.setString(1, t.id)
                stmt.setString(2, t.name)
                stmt.setString(3, t.country)
                stmt.setString(4, t.baseCountry)
                stmt.setString(5, t.series)
                stmt.setInt(6, t.prestige)
                stmt.setBoolean(7, t.isCustomTeam)
                stmt.setLong(8, t.cashReserves)
                stmt.setLong(9, t.heritagePayment)
                stmt.setLong(10, t.baseOperatingCost)
                stmt.setLong(11, t.academyInvestment)
                stmt.setDouble(12, t.regulationUnderstanding)
                stmt.setInt(13, t.pitCrewRating)
                stmt.setDouble(14, t.aiAggression)
                stmt.setDouble(15, t.aiAmbition)
                stmt.setDouble(16, t.aiLoyalty)
                stmt.setDouble(17, t.aiFrugality)
                stmt.setDouble(18, t.aiDevelopmentFocus)
                stmt.setDouble(19, t.boardAmbition)
                stmt.setDouble(20, t.boardRealism)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  teams: {} rows", items.size)
    }

    private fun loadEngineSuppliers(conn: Connection) {
        val items = readSeed(SeedFiles.ENGINE_SUPPLIERS, EngineSupplierSeed.serializer())
        val sql = """
            INSERT INTO engine_suppliers
              (id, name, country, entered_year, exited_year, works_team_id, is_custom_supplier)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { s ->
                stmt.setString(1, s.id)
                stmt.setString(2, s.name)
                stmt.setString(3, s.country)
                stmt.setInt(4, s.enteredYear)
                stmt.setIntOrNull(5, s.exitedYear)
                stmt.setStringOrNull(6, s.worksTeamId)
                stmt.setBoolean(7, s.isCustomSupplier)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  engine_suppliers: {} rows", items.size)
    }

    private fun loadPuVersions(conn: Connection) {
        val items = readSeed(SeedFiles.PU_VERSIONS, PuVersionSeed.serializer())
        val sql = """
            INSERT INTO pu_versions
              (id, supplier_id, mk_version, introduced_year,
               ice_top_speed, ice_fuel_efficiency, ice_weight,
               ers_deployment_power, ers_recovery_rate, ers_deployment_efficiency,
               reliability, cooling_requirement)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { p ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, p.supplierId)
                stmt.setInt(3, p.mkVersion)
                stmt.setInt(4, p.introducedYear)
                stmt.setInt(5, p.iceTopSpeed)
                stmt.setInt(6, p.iceFuelEfficiency)
                stmt.setInt(7, p.iceWeight)
                stmt.setInt(8, p.ersDeploymentPower)
                stmt.setInt(9, p.ersRecoveryRate)
                stmt.setInt(10, p.ersDeploymentEfficiency)
                stmt.setInt(11, p.reliability)
                stmt.setInt(12, p.coolingRequirement)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  pu_versions: {} rows", items.size)
    }

    private fun loadDrivers(conn: Connection) {
        val items = readSeed(SeedFiles.DRIVERS, DriverSeed.serializer())
        val sql = """
            INSERT INTO drivers
              (id, name, nationality, current_age,
               current_racing_team_id, reserve_for_team_id, academy_team_id, retired,
               current_salary, contract_expires_year, contract_expires_round,
               development_pool, morale,
               stat_pace, stat_qualifying, stat_overtaking, stat_defending,
               stat_consistency, stat_tyre_management, stat_ers_deployment,
               stat_wet_skill, stat_feedback_quality,
               trait_peak_age, trait_decline_rate, trait_retirement_threshold,
               trait_loyalty, trait_temperament, trait_market_value_modifier)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { d ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, d.name)
                stmt.setString(3, d.nationality)
                stmt.setInt(4, d.currentAge)
                stmt.setStringOrNull(5, d.currentRacingTeamId)
                stmt.setStringOrNull(6, d.reserveForTeamId)
                stmt.setStringOrNull(7, d.academyTeamId)
                stmt.setBoolean(8, d.retired)
                stmt.setLong(9, d.currentSalary)
                stmt.setIntOrNull(10, d.contractExpiresYear)
                stmt.setIntOrNull(11, d.contractExpiresRound)
                stmt.setInt(12, d.developmentPool)
                stmt.setInt(13, d.morale)
                stmt.setInt(14, d.statPace)
                stmt.setInt(15, d.statQualifying)
                stmt.setInt(16, d.statOvertaking)
                stmt.setInt(17, d.statDefending)
                stmt.setInt(18, d.statConsistency)
                stmt.setInt(19, d.statTyreManagement)
                stmt.setInt(20, d.statErsDeployment)
                stmt.setInt(21, d.statWetSkill)
                stmt.setInt(22, d.statFeedbackQuality)
                stmt.setInt(23, d.traitPeakAge)
                stmt.setDouble(24, d.traitDeclineRate)
                stmt.setDouble(25, d.traitRetirementThreshold)
                stmt.setDouble(26, d.traitLoyalty)
                stmt.setDouble(27, d.traitTemperament)
                stmt.setDouble(28, d.traitMarketValueModifier)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  drivers: {} rows", items.size)
    }

    private fun loadPersonnel(conn: Connection) {
        val items = readSeed(SeedFiles.PERSONNEL, PersonnelSeed.serializer())
        val sql = """
            INSERT INTO personnel
              (id, name, nationality, age,
               current_team_id, role, current_salary,
               contract_expires_year, contract_expires_round, retired,
               development_pool,
               skill_leadership, skill_design, skill_strategy,
               skill_crew_management, skill_driver_management,
               trait_peak_age, trait_decline_rate, trait_loyalty)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            items.forEach { p ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, p.name)
                stmt.setString(3, p.nationality)
                stmt.setInt(4, p.age)
                stmt.setStringOrNull(5, p.currentTeamId)
                stmt.setStringOrNull(6, p.role)
                stmt.setLong(7, p.currentSalary)
                stmt.setIntOrNull(8, p.contractExpiresYear)
                stmt.setIntOrNull(9, p.contractExpiresRound)
                stmt.setBoolean(10, p.retired)
                stmt.setInt(11, p.developmentPool)
                stmt.setInt(12, p.skillLeadership)
                stmt.setInt(13, p.skillDesign)
                stmt.setInt(14, p.skillStrategy)
                stmt.setInt(15, p.skillCrewManagement)
                stmt.setInt(16, p.skillDriverManagement)
                stmt.setInt(17, p.traitPeakAge)
                stmt.setDouble(18, p.traitDeclineRate)
                stmt.setDouble(19, p.traitLoyalty)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        log.info("  personnel: {} rows", items.size)
    }

    private fun <T> readSeed(fileName: String, elementSerializer: KSerializer<T>): List<T> {
        val path = "/seeds/$fileName"
        val text = SeedLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing seed resource: $path")
        return json.decodeFromString(ListSerializer(elementSerializer), text)
    }
}

private fun PreparedStatement.setIntOrNull(idx: Int, value: Int?) {
    if (value == null) setNull(idx, Types.INTEGER) else setInt(idx, value)
}

private fun PreparedStatement.setStringOrNull(idx: Int, value: String?) {
    if (value == null) setNull(idx, Types.VARCHAR) else setString(idx, value)
}
