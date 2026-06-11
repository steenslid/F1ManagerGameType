package f1sim.game

import f1sim.db.Database
import f1sim.http.NotFoundException
import f1sim.save.SaveSession
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Personnel market: the player hires and fires their department heads. Staff
 * are no longer inert — a strong Technical Director speeds car development and
 * a strong Race Engineer accelerates young-driver growth (see OffSeasonService),
 * so the people you put in post genuinely change your team's trajectory.
 *
 * One person per role per team (PRINCIPAL, TECHNICAL_DIRECTOR, CHIEF_STRATEGIST,
 * CREW_CHIEF, RACE_ENGINEER). Hire a free agent into an empty slot; release the
 * current holder to free the slot. Salaries already flow into the operating-cost
 * tick, so a star hire is a real wage commitment. No RNG, no schema change.
 */
class PersonnelMarketService(private val db: Database) {

    private val log = LoggerFactory.getLogger(PersonnelMarketService::class.java)

    private data class Role(val key: String, val label: String, val skill: String)
    private val roles = listOf(
        Role("PRINCIPAL", "Team Principal", "leadership"),
        Role("TECHNICAL_DIRECTOR", "Technical Director", "design"),
        Role("CHIEF_STRATEGIST", "Chief Strategist", "strategy"),
        Role("CREW_CHIEF", "Crew Chief", "crewManagement"),
        Role("RACE_ENGINEER", "Race Engineer", "driverManagement"),
    )
    private val roleKeys = roles.map { it.key }.toSet()
    private fun roleLabel(key: String?) = roles.firstOrNull { it.key == key }?.label ?: (key ?: "—")
    private fun roleSkill(key: String?) = roles.firstOrNull { it.key == key }?.skill

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @Serializable
    data class StaffMarketDto(
        val playerTeamId: String? = null,
        val slots: List<SlotDto> = emptyList(),
        val available: List<StaffDto> = emptyList(),
    )

    @Serializable
    data class SlotDto(
        val role: String,
        val label: String,
        val skill: String,        // the primary skill key for this role
        val member: StaffDto? = null,
    )

    @Serializable
    data class StaffDto(
        val id: String,
        val name: String,
        val nationality: String,
        val age: Int,
        val role: String? = null,
        val salary: Long,
        val skills: SkillsDto,
    )

    @Serializable
    data class SkillsDto(
        val leadership: Int,
        val design: Int,
        val strategy: Int,
        val crewManagement: Int,
        val driverManagement: Int,
    )

    @Serializable
    data class HireRequest(val personnelId: String, val role: String)

    @Serializable
    data class ReleaseRequest(val personnelId: String)

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    fun view(): StaffMarketDto {
        SaveSession.requireLoaded()
        return db.withConnection { conn -> readMarket(conn) }
    }

    fun hire(req: HireRequest): StaffMarketDto {
        SaveSession.requireLoaded()
        val role = req.role.uppercase()
        require(role in roleKeys) { "role must be one of $roleKeys" }
        val personnelId = parseId(req.personnelId)

        return db.withConnection { conn ->
            conn.autoCommit = false
            try {
                val ctx = readContext(conn)
                val playerTeamId = ctx.playerTeamId
                    ?: error("No player team selected — cannot hire staff")

                // Slot must be empty.
                check(!roleFilled(conn, playerTeamId, role)) {
                    "You already have a ${roleLabel(role)} — release them first."
                }
                // Target must be a free agent.
                val isFree = conn.prepareStatement(
                    "SELECT 1 FROM personnel WHERE id = ? AND NOT retired AND current_team_id IS NULL"
                ).use { stmt ->
                    stmt.setObject(1, personnelId)
                    stmt.executeQuery().use { it.next() }
                }
                if (!isFree) throw NotFoundException("Personnel $personnelId is not a free agent")

                conn.prepareStatement(
                    """
                    UPDATE personnel SET
                      current_team_id = ?,
                      role = ?,
                      contract_expires_year = ?,
                      contract_expires_round = ?
                     WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, playerTeamId)
                    stmt.setString(2, role)
                    stmt.setInt(3, ctx.seasonYear + CONTRACT_YEARS)
                    stmt.setInt(4, CONTRACT_END_ROUND)
                    stmt.setObject(5, personnelId)
                    stmt.executeUpdate()
                }
                conn.commit()
                log.info("Hired personnel {} as {} for {}", personnelId, role, playerTeamId)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
            readMarket(conn)
        }
    }

    fun release(req: ReleaseRequest): StaffMarketDto {
        SaveSession.requireLoaded()
        val personnelId = parseId(req.personnelId)
        return db.withConnection { conn ->
            val playerTeamId = readContext(conn).playerTeamId
                ?: error("No player team selected")
            val updated = conn.prepareStatement(
                "UPDATE personnel SET current_team_id = NULL, role = NULL WHERE id = ? AND current_team_id = ?"
            ).use { stmt ->
                stmt.setObject(1, personnelId)
                stmt.setString(2, playerTeamId)
                stmt.executeUpdate()
            }
            if (updated == 0) throw NotFoundException("No staff member $personnelId on your team")
            readMarket(conn)
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    private data class Context(val playerTeamId: String?, val seasonYear: Int)

    private fun readContext(conn: Connection): Context {
        return conn.prepareStatement("SELECT player_team_id, current_season_year FROM game").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "No game row" }
                Context(rs.getString("player_team_id"), rs.getInt("current_season_year"))
            }
        }
    }

    private fun roleFilled(conn: Connection, teamId: String, role: String): Boolean {
        return conn.prepareStatement(
            "SELECT 1 FROM personnel WHERE current_team_id = ? AND role = ? AND NOT retired"
        ).use { stmt ->
            stmt.setString(1, teamId)
            stmt.setString(2, role)
            stmt.executeQuery().use { it.next() }
        }
    }

    private fun readMarket(conn: Connection): StaffMarketDto {
        val ctx = readContext(conn)
        val playerTeamId = ctx.playerTeamId ?: return StaffMarketDto(
            slots = roles.map { SlotDto(it.key, it.label, it.skill) },
        )

        val current = conn.prepareStatement("$SELECT_COLUMNS WHERE current_team_id = ? AND NOT retired").use { stmt ->
            stmt.setString(1, playerTeamId)
            stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(mapStaff(rs)) } }
        }
        val byRole = current.associateBy { it.role }
        val slots = roles.map { r -> SlotDto(r.key, r.label, r.skill, byRole[r.key]) }

        val available = conn.prepareStatement(
            "$SELECT_COLUMNS WHERE current_team_id IS NULL AND NOT retired ORDER BY name ASC"
        ).use { stmt ->
            stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(mapStaff(rs)) } }
        }

        return StaffMarketDto(playerTeamId = playerTeamId, slots = slots, available = available)
    }

    private fun mapStaff(rs: ResultSet) = StaffDto(
        id = rs.getObject("id", UUID::class.java).toString(),
        name = rs.getString("name"),
        nationality = rs.getString("nationality"),
        age = rs.getInt("age"),
        role = rs.getString("role"),
        salary = rs.getLong("current_salary"),
        skills = SkillsDto(
            leadership = rs.getInt("skill_leadership"),
            design = rs.getInt("skill_design"),
            strategy = rs.getInt("skill_strategy"),
            crewManagement = rs.getInt("skill_crew_management"),
            driverManagement = rs.getInt("skill_driver_management"),
        ),
    )

    private fun parseId(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw NotFoundException("No personnel with id $raw")
    }

    private companion object {
        const val CONTRACT_YEARS = 3
        const val CONTRACT_END_ROUND = 24
        const val SELECT_COLUMNS = """
            SELECT id, name, nationality, age, role, current_salary,
                   skill_leadership, skill_design, skill_strategy,
                   skill_crew_management, skill_driver_management
              FROM personnel
        """
    }
}
