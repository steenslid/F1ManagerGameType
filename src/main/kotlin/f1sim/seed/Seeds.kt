package f1sim.seed

import kotlinx.serialization.Serializable

/**
 * Data classes representing the on-disk shape of each seed JSON file.
 *
 * Field naming: camelCase in Kotlin, snake_case in JSON. The Json instance in
 * [SeedLoader] uses [kotlinx.serialization.json.JsonNamingStrategy.SnakeCase]
 * to handle the conversion globally.
 *
 * Optional fields with defaults match the database defaults; omitting them in
 * JSON falls back to those values.
 *
 * Notes:
 *   - Drivers, personnel, and pu_versions have UUID primary keys generated at
 *     load time (not in JSON).
 *   - Reference data (eras, compounds, tracks, suppliers, teams, sponsors)
 *     uses TEXT primary keys provided in JSON.
 */
object SeedFiles {
    const val REGULATION_ERAS = "regulation_eras.json"
    const val TYRE_COMPOUNDS = "tyre_compounds.json"
    const val TRACKS = "tracks.json"
    const val TEAMS = "teams.json"
    const val ENGINE_SUPPLIERS = "engine_suppliers.json"
    const val PU_VERSIONS = "pu_versions.json"
    const val DRIVERS = "drivers.json"
}

@Serializable
data class RegulationEraSeed(
    val id: String,
    val name: String,
    val startYear: Int,
    val endYear: Int? = null,
    val performanceResetSeverity: Double,
    val ersShare: Double,
)

@Serializable
data class TyreCompoundSeed(
    val id: String,
    val name: String,
    val basePaceFactor: Double,
    val degradationRate: Double,
    val longevityLaps: Int,
    val optimalTempMinC: Double,
    val optimalTempMaxC: Double,
)

@Serializable
data class TrackSeed(
    val id: String,
    val name: String,
    val country: String,
    val lengthKm: Double,
    val type: String,
    val demandTopSpeed: Double,
    val demandAcceleration: Double,
    val demandLowSpeedCornering: Double,
    val demandMediumSpeedCornering: Double,
    val demandHighSpeedCornering: Double,
    val demandBraking: Double,
    val demandTyreWear: Double,
    val demandCooling: Double,
    val pitLaneLossSeconds: Double,
    val overtakeDifficulty: Double,
    val rainProbabilityBaseline: Double,
    val temperatureMinC: Double,
    val temperatureMaxC: Double,
)

@Serializable
data class TeamSeed(
    val id: String,
    val name: String,
    val country: String,
    val baseCountry: String,
    val series: String,
    val prestige: Int,
    val isCustomTeam: Boolean = false,
    val cashReserves: Long = 0,
    val heritagePayment: Long = 0,
    val baseOperatingCost: Long = 0,
    val academyInvestment: Long = 0,
    val regulationUnderstanding: Double = 0.5,
    val pitCrewRating: Int = 50,
    val aiAggression: Double = 0.5,
    val aiAmbition: Double = 0.5,
    val aiLoyalty: Double = 0.5,
    val aiFrugality: Double = 0.5,
    val aiDevelopmentFocus: Double = 0.5,
    val boardAmbition: Double = 0.5,
    val boardRealism: Double = 0.5,
)

@Serializable
data class EngineSupplierSeed(
    val id: String,
    val name: String,
    val country: String,
    val enteredYear: Int,
    val exitedYear: Int? = null,
    val worksTeamId: String? = null,
    val isCustomSupplier: Boolean = false,
)

@Serializable
data class PuVersionSeed(
    val supplierId: String,
    val mkVersion: Int,
    val introducedYear: Int,
    val iceTopSpeed: Int,
    val iceFuelEfficiency: Int,
    val iceWeight: Int,
    val ersDeploymentPower: Int,
    val ersRecoveryRate: Int,
    val ersDeploymentEfficiency: Int,
    val reliability: Int,
    val coolingRequirement: Int,
)

@Serializable
data class DriverSeed(
    val name: String,
    val nationality: String,
    val currentAge: Int,
    val currentRacingTeamId: String? = null,
    val reserveForTeamId: String? = null,
    val academyTeamId: String? = null,
    val retired: Boolean = false,
    val currentSalary: Long = 0,
    val contractExpiresYear: Int? = null,
    val contractExpiresRound: Int? = null,
    val developmentPool: Int = 0,
    val morale: Int = 50,
    val statPace: Int,
    val statQualifying: Int,
    val statOvertaking: Int,
    val statDefending: Int,
    val statConsistency: Int,
    val statTyreManagement: Int,
    val statErsDeployment: Int,
    val statWetSkill: Int,
    val statFeedbackQuality: Int,
    val traitPeakAge: Int,
    val traitDeclineRate: Double,
    val traitRetirementThreshold: Double,
    val traitLoyalty: Double,
    val traitTemperament: Double,
    val traitMarketValueModifier: Double,
)
