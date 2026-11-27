package com.morningblocker.blocking

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.storage.BlockingPolicy
import com.morningblocker.storage.PolicyConfiguration
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.DateTimeException

object ScheduleConfigurationCodec {
    fun decode(input: ReadableMap?): BlockingPolicy {
        requireNotNull(input) { "A schedule is required" }
        val keys = mutableSetOf<String>()
        val iterator = input.keySetIterator()
        while (iterator.hasNextKey()) keys += iterator.nextKey()
        require(keys == setOf("startTime", "endTime", "weekdays", "packages")) { "Supply only startTime, endTime, weekdays and packages" }
        fun time(key: String): LocalTime {
            require(input.getType(key) == ReadableType.String) { "$key must be a time in HH:mm format" }
            val value = input.getString(key).orEmpty()
            require(Regex("[0-9]{2}:[0-9]{2}").matches(value)) { "$key must use 24-hour HH:mm format" }
            return try { LocalTime.parse(value) } catch (error: DateTimeException) {
                throw IllegalArgumentException("$key must be a valid 24-hour time", error)
            }
        }
        val start = time("startTime")
        val end = time("endTime")
        require(input.getType("weekdays") == ReadableType.Array) { "Weekdays must be an array" }
        val days = requireNotNull(input.getArray("weekdays"))
        val selectedDays = (0 until days.size()).map { index ->
            require(days.getType(index) == ReadableType.Number) { "Weekdays must be ISO numbers 1 to 7" }
            val day = days.getDouble(index)
            require(day.isFinite() && day in 1.0..7.0 && day % 1.0 == 0.0) { "Weekdays must be ISO numbers 1 to 7" }
            DayOfWeek.of(day.toInt())
        }
        require(selectedDays.distinct().size == selectedDays.size) { "Do not repeat weekdays" }
        require(input.getType("packages") == ReadableType.Array) { "Selected apps must be an array" }
        val apps = requireNotNull(input.getArray("packages"))
        val selectedApps = (0 until apps.size()).map { index ->
            require(apps.getType(index) == ReadableType.String) { "Selected apps must use supported package identifiers" }
            requireNotNull(apps.getString(index)).also {
                require(it in SupportedApps.labels) { "Unsupported app: $it" }
            }
        }
        require(selectedApps.distinct().size == selectedApps.size) { "Do not repeat apps" }
        return BlockingPolicy(MorningSchedule(start, end, selectedDays.toSet()), selectedApps.toSet())
    }

    private fun policy(value: BlockingPolicy): Map<String, Any?> = mapOf(
        "startTime" to value.schedule.startTime.toString(),
        "endTime" to value.schedule.endTime.toString(),
        "weekdays" to value.schedule.activeDays.map { it.value }.sorted(),
        "packages" to value.blockedPackages.sorted(),
    )

    /** Plain values are unit-testable; the module converts them to WritableNativeMap for JNI. */
    fun encode(view: PolicyConfiguration): Map<String, Any?> = mapOf(
        "policy" to view.policy?.let(::policy),
        "activeSession" to view.activeSession?.let { policy(it.policy) + ("date" to it.date.toString()) },
        "effectiveFrom" to view.effectiveFrom?.toString(),
        "changesDeferred" to view.changesDeferred,
        "supportedApps" to SupportedApps.labels.map { (packageName, label) -> mapOf("packageName" to packageName, "label" to label) },
    )
}
