package com.morningblocker.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Async, atomic configuration persistence. Never silently discard damaged or newer data. */
class PolicyRepository(private val store: DataStore<Preferences>) {
    val policies: Flow<BlockingPolicy?> = store.data.map(::decode)

    suspend fun load(): BlockingPolicy? = policies.first()

    suspend fun save(policy: BlockingPolicy) {
        store.edit { preferences ->
            // Validate existing data inside the transaction before replacing it.
            // Repair/migration requires an explicit future operation, not an accidental save.
            decode(preferences)
            preferences[SCHEMA] = CURRENT_SCHEMA
            preferences[START] = policy.schedule.startTime.toString()
            preferences[END] = policy.schedule.endTime.toString()
            preferences[DAYS] = policy.schedule.activeDays.map { it.name }.toSet()
            preferences[PACKAGES] = policy.blockedPackages
        }
    }

    private fun decode(preferences: Preferences): BlockingPolicy? {
        if (preferences.asMap().isEmpty()) return null
        return try {
            val version = requireNotNull(preferences[SCHEMA]) { "Missing policy schema" }
            if (version != CURRENT_SCHEMA) throw IOException("Unsupported saved policy schema: $version")
            val start = LocalTime.parse(requireNotNull(preferences[START]) { "Missing start time" })
            val end = LocalTime.parse(requireNotNull(preferences[END]) { "Missing end time" })
            val days = requireNotNull(preferences[DAYS]) { "Missing active days" }.map { DayOfWeek.valueOf(it) }.toSet()
            val packages = requireNotNull(preferences[PACKAGES]) { "Missing selected packages" }
            BlockingPolicy(MorningSchedule(start, end, days), packages)
        } catch (error: RuntimeException) {
            // Parsing, validation, and preference-type mismatches are persisted-data failures.
            throw CorruptionException("Saved blocking policy is invalid", error)
        }
    }

    private companion object {
        const val CURRENT_SCHEMA = 1
        val SCHEMA = intPreferencesKey("policy_schema")
        val START = stringPreferencesKey("schedule_start")
        val END = stringPreferencesKey("schedule_end")
        val DAYS = stringSetPreferencesKey("schedule_days")
        val PACKAGES = stringSetPreferencesKey("blocked_packages")
    }
}
