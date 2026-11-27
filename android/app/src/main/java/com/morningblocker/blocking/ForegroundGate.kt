package com.morningblocker.blocking

interface BlockingSurface {
    fun show(block: RestrictionDecision.Blocked): Boolean
    fun hide()
}

/** Applies native policy decisions on the service's main thread. */
class ForegroundGate(private val surface: BlockingSurface) {
    var displayedBlock: RestrictionDecision.Blocked? = null
        private set
    val isBlocking: Boolean get() = displayedBlock != null

    /** False means foreground detection or presentation needs recovery. */
    fun applyDecision(decision: RestrictionDecision): Boolean = when (decision) {
        RestrictionDecision.Inactive, is RestrictionDecision.Allowed -> {
            close()
            true
        }
        is RestrictionDecision.UnknownForeground -> false
        is RestrictionDecision.Blocked -> {
            val previous = displayedBlock
            if (previous?.packageName == decision.packageName && previous.session.window == decision.session.window) {
                true
            } else if (surface.show(decision)) {
                displayedBlock = decision
                true
            } else {
                close()
                false
            }
        }
    }

    fun close() {
        if (isBlocking) surface.hide()
        displayedBlock = null
    }
}
