package com.morningblocker.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.SessionClock
import com.morningblocker.scheduling.SessionClockState
import com.morningblocker.scheduling.ZonedSessionWindow
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Async, atomic configuration persistence. Never silently discard damaged or newer data. */
class PolicyRepository(private val store: DataStore<Preferences>) {
    /** Latest configured policy, which may be deferred. Enforcement must use activeSessionAt. */
    val policies: Flow<BlockingPolicy?> = store.data.map { decodeState(it).policy }

    suspend fun load(): BlockingPolicy? = policies.first()

    suspend fun activeSessionAt(now: ClockReading): RestrictionSession? {
        val updated = store.edit { preferences ->
            val resolved = recoverState(decodeState(preferences), now)
            writeSnapshot(preferences, resolved)
        }
        val state = decodeState(updated)
        return state.session?.takeIf { state.clock?.completed == false }
    }

    suspend fun save(policy: BlockingPolicy, now: ClockReading) {
        store.edit { preferences ->
            // Capture the old policy first, even if this edit is the first operation in its window.
            val state = recoverState(decodeState(preferences), now)
            val today = now.instant.atZone(now.zone).toLocalDate()
            val protectedDate = state.session?.date?.takeIf { state.clock?.completed == false || !it.isBefore(today) }
            val effectiveFrom = protectedDate?.let { maxOf(it, today).plusDays(1) } ?: today
            preferences[SCHEMA] = CURRENT_SCHEMA
            writePolicy(preferences, ROOT, policy)
            preferences[EFFECTIVE_FROM] = effectiveFrom.toString()
            writeSnapshot(preferences, state)
        }
    }

    private data class SavedState(
        val policy: BlockingPolicy?,
        val effectiveFrom: LocalDate = LocalDate.MIN,
        val session: RestrictionSession? = null,
        val clock: SessionClockState? = null,
    )

    private fun recoverState(saved: SavedState, now: ClockReading): SavedState {
        val today = now.instant.atZone(now.zone).toLocalDate()
        var state = saved
        if (state.session != null) {
            val session = state.session
            val clock = state.clock?.let { SessionClock.recover(it, now) } ?: run {
                // Legacy local-time snapshots lack a historical zone/boot anchor. Resolve once.
                val window = SessionClock.resolve(session, now.zone)
                    ?: throw IOException("Legacy session has no recoverable window in the current zone")
                SessionClock.start(window, now)
            }
            state = state.copy(clock = clock)
            if (!clock.completed || !today.isAfter(session.date)) return state
        }
        val policy = state.policy ?: return state
        if (today.isBefore(state.effectiveFrom) || policy.blockedPackages.isEmpty() ||
            today.dayOfWeek !in policy.schedule.activeDays) return state
        val session = RestrictionSession(today, policy)
        val window = SessionClock.resolve(session, now.zone) ?: return state
        if (now.instant.isBefore(window.startsAt)) return state
        // An elapsed window also counts as started, even if monitoring wasn't running then.
        return state.copy(session = session, clock = SessionClock.start(window, now))
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
            val hasClock = preferences.asMap().keys.any { it.name.startsWith("clock_") }
            val clock = if (hasClock) {
                requireNotNull(session) { "Clock checkpoint requires a frozen session" }
                readClock(preferences)
            } else null
            SavedState(policy, effectiveFrom, session, clock)
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
        state.clock?.let {
            preferences[CLOCK_ZONE] = it.window.zone.id
            preferences[CLOCK_START] = it.window.startsAt.toString()
            preferences[CLOCK_END] = it.window.endsAt.toString()
            preferences[CLOCK_BOOT] = it.bootCount
            preferences[CLOCK_ELAPSED] = it.anchorElapsedMillis
            preferences[CLOCK_REMAINING] = it.remaining.toString()
            preferences[CLOCK_DEADLINE] = it.recoveryDeadline.toString()
            preferences[CLOCK_COMPLETED] = it.completed
        }
    }

    private fun readClock(preferences: Preferences): SessionClockState = SessionClockState(
        window = ZonedSessionWindow(
            Instant.parse(requireNotNull(preferences[CLOCK_START]) { "Missing clock start" }),
            Instant.parse(requireNotNull(preferences[CLOCK_END]) { "Missing clock end" }),
            ZoneId.of(requireNotNull(preferences[CLOCK_ZONE]) { "Missing clock zone" }),
        ),
        bootCount = requireNotNull(preferences[CLOCK_BOOT]) { "Missing clock boot count" },
        anchorElapsedMillis = requireNotNull(preferences[CLOCK_ELAPSED]) { "Missing clock anchor" },
        remaining = Duration.parse(requireNotNull(preferences[CLOCK_REMAINING]) { "Missing clock remainder" }),
        recoveryDeadline = Instant.parse(requireNotNull(preferences[CLOCK_DEADLINE]) { "Missing clock deadline" }),
        completed = requireNotNull(preferences[CLOCK_COMPLETED]) { "Missing completion marker" },
    )

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
        val CLOCK_ZONE = stringPreferencesKey("clock_zone")
        val CLOCK_START = stringPreferencesKey("clock_start")
        val CLOCK_END = stringPreferencesKey("clock_end")
        val CLOCK_BOOT = intPreferencesKey("clock_boot_count")
        val CLOCK_ELAPSED = longPreferencesKey("clock_anchor_elapsed")
        val CLOCK_REMAINING = stringPreferencesKey("clock_remaining")
        val CLOCK_DEADLINE = stringPreferencesKey("clock_recovery_deadline")
        val CLOCK_COMPLETED = booleanPreferencesKey("clock_completed")
    }
}
