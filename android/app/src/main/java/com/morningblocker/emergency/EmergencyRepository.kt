package com.morningblocker.emergency

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import java.io.IOException

/** All commands, context sampling, recovery, and usage updates are serialized in one store. */
class EmergencyRepository(
    private val store: DataStore<Preferences>,
    private val context: suspend () -> EmergencyContext,
) {
    suspend fun status(): EmergencyState = execute(Command.STATUS)
    suspend fun request(): EmergencyState = execute(Command.REQUEST)
    suspend fun confirm(): EmergencyState = execute(Command.CONFIRM)
    suspend fun cancel(): EmergencyState = execute(Command.CANCEL)

    private enum class Command { STATUS, REQUEST, CONFIRM, CANCEL }
    private data class Record(val state: EmergencyState?, val usedSessions: Set<String>)

    private suspend fun execute(command: Command): EmergencyState {
        var rejection: IllegalStateException? = null
        val updated = store.edit { preferences ->
            val record = decode(preferences)
            // Sample inside serialization, not before waiting for another command's write.
            val current = context()
            val saved = record.state?.takeIf { it.sessionId == current.sessionId }
                ?: EmergencyState(
                    current.sessionId, current.clock.bootCount, current.clock.elapsedRealtimeMillis,
                    used = current.sessionId in record.usedSessions,
                )
            val recovered = EmergencyAccess.advance(saved, current)
            rejection = null
            val next = try {
                when (command) {
                    Command.STATUS -> recovered
                    Command.REQUEST -> EmergencyAccess.request(recovered, current)
                    Command.CONFIRM -> EmergencyAccess.confirm(recovered, current)
                    Command.CANCEL -> EmergencyAccess.cancel(recovered, current)
                }
            } catch (error: IllegalStateException) {
                // Commit required boot/window recovery even when a user command is rejected.
                rejection = error
                recovered
            }
            val used = if (next.used) record.usedSessions + next.sessionId else record.usedSessions
            encode(preferences, next, used)
        }
        rejection?.let { throw it }
        return requireNotNull(decode(updated).state)
    }

    private fun decode(preferences: Preferences): Record {
        if (preferences.asMap().isEmpty()) return Record(null, emptySet())
        return try {
            val schema = requireNotNull(preferences[SCHEMA]) { "Missing emergency schema" }
            if (schema != CURRENT_SCHEMA) throw IOException("Unsupported emergency schema: $schema")
            val state = EmergencyState(
                sessionId = requireNotNull(preferences[SESSION]) { "Missing emergency session" },
                bootCount = requireNotNull(preferences[BOOT]) { "Missing boot identity" },
                lastElapsedMillis = requireNotNull(preferences[ELAPSED]) { "Missing elapsed anchor" },
                phase = EmergencyPhase.valueOf(requireNotNull(preferences[PHASE]) { "Missing phase" }),
                used = requireNotNull(preferences[USED]) { "Missing usage flag" },
                readyAtElapsedMillis = preferences[READY],
                unlockUntilElapsedMillis = preferences[UNTIL],
            )
            val used = requireNotNull(preferences[USED_SESSIONS]) { "Missing consumed-session ledger" }
            require(used.all { it.isNotBlank() }) { "Invalid consumed-session identity" }
            require(state.used == (state.sessionId in used)) { "Emergency usage flag and ledger disagree" }
            Record(state, used)
        } catch (error: RuntimeException) {
            throw CorruptionException("Saved emergency-access state is invalid", error)
        }
    }

    private fun encode(preferences: MutablePreferences, state: EmergencyState, usedSessions: Set<String>) {
        preferences[SCHEMA] = CURRENT_SCHEMA
        preferences[SESSION] = state.sessionId
        preferences[BOOT] = state.bootCount
        preferences[ELAPSED] = state.lastElapsedMillis
        preferences[PHASE] = state.phase.name
        preferences[USED] = state.used
        preferences[USED_SESSIONS] = usedSessions
        state.readyAtElapsedMillis?.let { preferences[READY] = it } ?: preferences.remove(READY)
        state.unlockUntilElapsedMillis?.let { preferences[UNTIL] = it } ?: preferences.remove(UNTIL)
    }

    private companion object {
        const val CURRENT_SCHEMA = 1
        val SCHEMA = intPreferencesKey("override_schema")
        val SESSION = stringPreferencesKey("override_session")
        val BOOT = intPreferencesKey("override_boot_count")
        val ELAPSED = longPreferencesKey("override_last_elapsed")
        val PHASE = stringPreferencesKey("override_phase")
        val USED = booleanPreferencesKey("override_used")
        val READY = longPreferencesKey("override_ready_at")
        val UNTIL = longPreferencesKey("override_until")
        val USED_SESSIONS = stringSetPreferencesKey("override_used_sessions")
    }
}
