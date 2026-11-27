package com.morningblocker.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.ScheduleRules
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Async, atomic configuration persistence. Never silently discard damaged or newer data. */
class PolicyRepository(private val store: DataStore<Preferences>) {
    /** Latest configured policy, which may be deferred. Enforcement must use activeSessionAt. */
    val policies: Flow<BlockingPolicy?> = store.data.map { decodeState(it).policy }

    suspend fun load(): BlockingPolicy? = policies.first()

    suspend fun activeSessionAt(now: ClockReading): RestrictionSession? = configurationAt(now).activeSession

    suspend fun configurationAt(now: ClockReading): PolicyConfiguration {
        val updated = store.edit { preferences ->
            val resolved = recoverState(decodeState(preferences), now)
            writeSnapshot(preferences, resolved)
        }
        return configuration(decodeState(updated), now)
    }

    private fun configuration(state: SavedState, now: ClockReading): PolicyConfiguration {
        val local = now.instant.atZone(now.zone).toLocalDateTime()
        val frozen = state.session
        // Re-entry after a clock rollback uses the same frozen policy, not pending edits.
        val active = if (frozen != null && !local.toLocalDate().isAfter(frozen.date) &&
            ScheduleRules.activeSession(frozen.policy.schedule, local) != null) {
            RestrictionSession(local.toLocalDate(), frozen.policy)
        } else null
        return PolicyConfiguration(
            state.policy, active,
            if (state.policy == null) null else state.effectiveFrom.takeUnless { it == LocalDate.MIN } ?: local.toLocalDate(),
            state.policy != null && state.effectiveFrom.isAfter(local.toLocalDate()),
        )
    }

    suspend fun save(policy: BlockingPolicy, now: ClockReading) {
        saveConfiguration(policy, now)
    }

    suspend fun saveConfiguration(policy: BlockingPolicy, now: ClockReading): PolicyConfiguration {
        val updated = store.edit { preferences ->
            // Capture the old policy first, even if this edit is the first operation in its window.
            val state = recoverState(decodeState(preferences), now)
            val today = now.instant.atZone(now.zone).toLocalDate()
            val protectedDate = state.session?.date?.takeIf { !it.isBefore(today) }
            val effectiveFrom = protectedDate?.plusDays(1) ?: today
            preferences[SCHEMA] = CURRENT_SCHEMA
            writePolicy(preferences, ROOT, policy)
            preferences[EFFECTIVE_FROM] = effectiveFrom.toString()
            writeSnapshot(preferences, state)
            // First-time in-window setup becomes active in this same committed view.
            writeSnapshot(preferences, recoverState(decodeState(preferences), now))
        }
        return configuration(decodeState(updated), now)
    }

    private data class SavedState(
        val policy: BlockingPolicy?,
        val effectiveFrom: LocalDate = LocalDate.MIN,
        val session: RestrictionSession? = null,
    )

    private fun recoverState(saved: SavedState, now: ClockReading): SavedState {
        val local = now.instant.atZone(now.zone).toLocalDateTime()
        val today = local.toLocalDate()
        if (saved.session != null && !today.isAfter(saved.session.date)) return saved
        val policy = saved.policy ?: return saved
        if (today.isBefore(saved.effectiveFrom) || policy.blockedPackages.isEmpty() ||
            today.dayOfWeek !in policy.schedule.activeDays ||
            local.toLocalTime().isBefore(policy.schedule.startTime)) return saved
        val session = RestrictionSession(today, policy)
        // An elapsed window also counts as started, even if monitoring wasn't running then.
        return saved.copy(session = session)
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
            // Duration checkpoints belong to the retired morning-clock policy. Ignore them.
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

    private fun writeSnapshot(preferences: MutablePreferences, state: SavedState) {
        state.session?.let { writeSession(preferences, it) }
        // Lazy migration keeps configuration/session protection while removing obsolete timers.
        preferences.asMap().keys.filter { it.name.startsWith("clock_") }.forEach {
            preferences.remove(it)
        }
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
