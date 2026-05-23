package f1sim.save

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks which save is currently loaded. This is the source of truth that
 * `Database.withConnection` reads to set `search_path` per connection borrow.
 *
 * Single-player app: at most one save loaded at any time. AtomicReference is
 * enough — no per-thread state needed.
 */
object SaveSession {
    data class Loaded(val saveId: UUID, val schemaName: String)

    private val state = AtomicReference<Loaded?>(null)

    val current: Loaded?
        get() = state.get()

    val currentSchema: String?
        get() = state.get()?.schemaName

    fun load(saveId: UUID, schemaName: String) {
        state.set(Loaded(saveId, schemaName))
    }

    fun unload() {
        state.set(null)
    }

    fun requireLoaded(): Loaded =
        state.get() ?: error("No save is currently loaded")
}
