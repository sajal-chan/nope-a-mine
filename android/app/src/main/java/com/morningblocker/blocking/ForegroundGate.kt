package com.morningblocker.blocking

interface BlockingSurface {
    fun show(): Boolean
    fun hide()
}

/** Phase 1 only: no schedule, persistence, or JavaScript dependency. Main-thread confined. */
class ForegroundGate(private val surface: BlockingSurface) {
    var isBlocking = false
        private set

    /** False means the snapshot or presentation is unresolved and may need retrying. */
    fun onForegroundApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return if (packageName == "com.instagram.android") {
            if (!isBlocking) isBlocking = surface.show()
            isBlocking
        } else {
            close()
            true
        }
    }

    fun close() {
        if (isBlocking) surface.hide()
        isBlocking = false
    }
}
