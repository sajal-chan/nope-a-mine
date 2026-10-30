package com.morningblocker.storage

import com.morningblocker.scheduling.MorningSchedule
import java.util.Collections

/** Saved configuration only; active-session snapshots are a separate concern. */
class BlockingPolicy(val schedule: MorningSchedule, blockedPackages: Set<String>) {
    val blockedPackages: Set<String> = Collections.unmodifiableSet(blockedPackages.toSet())

    init {
        require(this.blockedPackages.all { PACKAGE_NAME.matches(it) }) {
            "Blocked applications must use package identifiers, not labels or activity names"
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
