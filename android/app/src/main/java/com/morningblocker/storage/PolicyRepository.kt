package com.morningblocker.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Async, atomic configuration persistence. Never silently discard damaged or newer data. */
class PolicyRepository(private val store: DataStore<Preferences>) {
    /** Latest configured policy, which may be deferred. Enforcement must use activeSessionAt. */
    val policies: Flow<BlockingPolicy?> = store.data.map { decodeState(it).policy }

    suspend fun load(): BlockingPolicy? = policies.first()

    suspend fun activeSessionAt(now: LocalDateTime): RestrictionSession? {
        val updated = store.edit { preferences ->
            val resolved = captureStartedSession(decodeState(preferences), now)
            resolved.session?.let { writeSession(preferences, it) }
        }
        return decodeState(updated).session?.takeIf {
            !now.isBefore(it.window.startsAt) && now.isBefore(it.window.endsAt)
        }
    }

    suspend fun save(policy: BlockingPolicy, now: LocalDateTime) {
        store.edit { preferences ->
            // Capture the old policy first, even if this edit is the first operation in its window.
            val state = captureStartedSession(decodeState(preferences), now)
            val today = now.toLocalDate()
            val effectiveFrom = state.session?.date?.takeIf { !it.isBefore(today) }?.plusDays(1) ?: today
            preferences[SCHEMA] = CURRENT_SCHEMA
            writePolicy(preferences, ROOT, policy)
            preferences[EFFECTIVE_FROM] = effectiveFrom.toString()
            state.session?.let { writeSession(preferences, it) }
        }
    }

    private data class SavedState(
        val policy: BlockingPolicy?,
        val effectiveFrom: LocalDate = LocalDate.MIN,
        val session: RestrictionSession? = null,
    )

    private fun captureStartedSession(state: SavedState, now: LocalDateTime): SavedState {
        val today = now.toLocalDate()
        // Retain the last session after expiry, preventing a second session on that date.
        if (state.session != null && !today.isAfter(state.session.date)) return state
        val policy = state.policy ?: return state
        if (today.isBefore(state.effectiveFrom) || policy.blockedPackages.isEmpty() ||
            today.dayOfWeek !in policy.schedule.activeDays || now.toLocalTime().isBefore(policy.schedule.startTime)) return state
        // An elapsed window also counts as started, even if monitoring wasn't running then.
        return state.copy(session = RestrictionSession(today, policy))
    }

    private fun decodeState(preferences: Preferences): SavedState {
        if (preferences.asMap().isEmpty()) return SavedState(null)
        return try {
            val version = requireNotNull(preferences[SCHEMA]) { "Missing policy schema" }
            if (version != CURRENT_SCHEMA) throw IOException("Unsupported saved policy schema: $version")
            val policy = readPolicy(preferences, ROOT)
            // Existing schema-1 configuration files lack the optional session metadata.
            val effectiveFrom = preferences[EFFECTIVE_FROM]?.let(LocalDate::parse) ?: LocalDate.MIN
            val hasSession = preferences.asMap().keys.any { it.name.startsWith("session_") }
            val session = if (hasSession) {
                RestrictionSession(
                    LocalDate.parse(requireNotNull(preferences[SESSION_DATE]) { "Missing session date" }),
                    readPolicy(preferences, SESSION),
                )
            } else null
            SavedState(policy, effectiveFrom, session)
        } catch (error: RuntimeException) {
            // Parsing, validation, and preference-type mismatches are persisted-data failures.
            throw CorruptionException("Saved blocking policy is invalid", error)
        }
    }

    private fun readPolicy(preferences: Preferences, keys: PolicyKeys): BlockingPolicy {
        val start = LocalTime.parse(requireNotNull(preferences[keys.start]) { "Missing start time" })
        val end = LocalTime.parse(requireNotNull(preferences[keys.end]) { "Missing end time" })
        val days = requireNotNull(preferences[keys.days]) { "Missing active days" }.map { DayOfWeek.valueOf(it) }.toSet()
        return BlockingPolicy(
            MorningSchedule(start, end, days),
            requireNotNull(preferences[keys.packages]) { "Missing selected packages" },
        )
    }

    private fun writePolicy(preferences: MutablePreferences, keys: PolicyKeys, policy: BlockingPolicy) {
        preferences[keys.start] = policy.schedule.startTime.toString()
        preferences[keys.end] = policy.schedule.endTime.toString()
        preferences[keys.days] = policy.schedule.activeDays.map { it.name }.toSet()
        preferences[keys.packages] = policy.blockedPackages
    }

    private fun writeSession(preferences: MutablePreferences, session: RestrictionSession) {
        preferences[SESSION_DATE] = session.date.toString()
        writePolicy(preferences, SESSION, session.policy)
    }

    private class PolicyKeys(prefix: String) {
        val start = stringPreferencesKey("${prefix}schedule_start")
        val end = stringPreferencesKey("${prefix}schedule_end")
        val days = stringSetPreferencesKey("${prefix}schedule_days")
        val packages = stringSetPreferencesKey("${prefix}blocked_packages")
    }

    private companion object {
        const val CURRENT_SCHEMA = 1
        val SCHEMA = intPreferencesKey("policy_schema")
        val ROOT = PolicyKeys("")
        val SESSION = PolicyKeys("session_")
        val EFFECTIVE_FROM = stringPreferencesKey("policy_effective_from")
        val SESSION_DATE = stringPreferencesKey("session_date")
    }
}
