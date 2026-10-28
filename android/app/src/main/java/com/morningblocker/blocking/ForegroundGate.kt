package com.morningblocker.blocking

interface BlockingSurface {
    fun show(): Boolean
    fun hide()
}

/** Phase 1 only: no schedule, persistence, or JavaScript dependency. Main-thread confined. */
class ForegroundGate(private val surface: BlockingSurface) {
    var isBlocking = false
        private set

    fun onForegroundApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        if (packageName == "com.instagram.android") {
            if (!isBlocking) isBlocking = surface.show()
        } else {
            close()
        }
    }

    fun close() {
        if (isBlocking) surface.hide()
        isBlocking = false
    }
}
