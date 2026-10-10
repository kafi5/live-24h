package app.looplive

import android.os.SystemClock
import java.util.Locale

enum class Phase { IDLE, CONNECTING, LIVE, RECONNECTING }

/** Live status shared between the service (writer) and the screen (reader). */
object StreamStore {
    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var startedAt: Long = 0L
    @Volatile var loops: Int = 0
    @Volatile var reconnects: Int = 0
    @Volatile var bitrateKbps: Long = 0L
    @Volatile var message: String = ""

    fun reset() {
        phase = Phase.IDLE
        startedAt = SystemClock.elapsedRealtime()
        loops = 0
        reconnects = 0
        bitrateKbps = 0L
        message = ""
    }

    /** hh:mm:ss since the user pressed Start. */
    fun uptime(): String {
        val s = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    }
}
