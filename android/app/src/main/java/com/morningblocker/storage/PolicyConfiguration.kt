package com.morningblocker.storage

import com.morningblocker.scheduling.RestrictionSession
import java.time.LocalDate

/** One atomic view of editable configuration and the independently frozen effective session. */
data class PolicyConfiguration(
    val policy: BlockingPolicy?,
    val activeSession: RestrictionSession?,
    val effectiveFrom: LocalDate?,
    val changesDeferred: Boolean,
)
