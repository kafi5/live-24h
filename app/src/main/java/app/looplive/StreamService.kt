package app.looplive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.generic.GenericFromFile
import java.io.File
import java.io.IOException

/**
 * Keeps the stream alive in the background.
 *
 * - Runs as a foreground service (mediaPlayback), so Android keeps it running with the screen off.
 * - Holds a CPU wake lock and a Wi-Fi lock while live.
 * - Reconnects with a growing delay when the connection drops, the network returns or the stream stalls.
 * - Restarts itself if the system kills the process while the user still wants to be live.
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "app.looplive.START"
        const val ACTION_STOP = "app.looplive.STOP"

        private const val TAG = "LoopLive"
        private const val CHANNEL_ID = "live"
        private const val NOTIF_ID = 1

        /** Failed attempts before giving up, only while the stream has never connected (wrong link or key). */
        private const val MAX_FAILED_ATTEMPTS = 40
        private const val STALL_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val RESYNC_INTERVAL_MS = 60 * 60 * 1000L
        private const val AUDIO_BITRATE = 128 * 1024

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var engine: GenericFromFile? = null
    @Volatile private var gen = 0

    private var wantLive = false
    private var attempt = 0
    private var reconnectScheduled = false
    @Volatile private var lastBitrateAt = 0L
    private var engineStartedAt = 0L
    private var everConnected = false
    private var lastResyncAt = 0L
    private var lastVideoTime = 0.0
    private var lastNotifAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("Stopped.")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!goForeground(true)) return START_NOT_STICKY
                beginLive()
            }
            else -> {
                // The system restarted the service after killing the process.
                if (prefs.wasLive) {
                    if (!goForeground(false)) return START_NOT_STICKY
                    beginLive()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        releaseEngine()
        releaseLocks()
        unregisterNetwork()
        StreamStore.phase = Phase.IDLE
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun goForeground(userInitiated: Boolean): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground service: ${e.message}")
            StreamStore.message = "Android did not allow the background service to start. Open the app and try again."
            if (userInitiated) prefs.wasLive = false // a blocked automatic restart keeps the wish to stay live
            stopSelf()
            false
        }
    }

    private fun beginLive() {
        if (wantLive) return
        val path = prefs.videoPath
        if (path == null || !File(path).exists()) {
            stopEverything("Choose a video first.")
            return
        }
        wantLive = true
        prefs.wasLive = true
        StreamStore.reset()
        StreamStore.phase = Phase.CONNECTING
        attempt = 0
        everConnected = false
        lastResyncAt = SystemClock.elapsedRealtime()
        reconnectScheduled = false
        acquireLocks()
        registerNetwork()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
        connect()
    }

    private fun stopEverything(message: String) {
        wantLive = false
        prefs.wasLive = false
        main.removeCallbacksAndMessages(null)
        releaseEngine()
        releaseLocks()
        unregisterNetwork()
        StreamStore.phase = Phase.IDLE
        StreamStore.message = message
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- connection

    private val reconnectRunnable = Runnable { connect() }

    private fun connect() {
        if (!wantLive) return
        reconnectScheduled = false
        releaseEngine()

        val path = prefs.videoPath
        val url = prefs.link.trim()
        if (path == null || !File(path).exists()) {
            stopEverything("The video file is missing. Choose it again.")
            return
        }

        StreamStore.phase = if (attempt == 0) Phase.CONNECTING else Phase.RECONNECTING
        updateNotification(force = true)

        val g = gen
        try {
            val e = GenericFromFile(
                applicationContext,
                Checker(g),
                VideoDecoderInterface {
                    main.post {
                        if (g == gen && wantLive && !prefs.loop) stopEverything("Video finished.")
                    }
                },
                AudioDecoderInterface { }
            )
            engine = e // from here on releaseEngine() can always clean it up
            e.setLoopMode(prefs.loop)
            val codecCallback = object : CodecErrorCallback {
                override fun onCodecError(type: CodecUtil.CodecTypeError, error: MediaCodec.CodecException) {
                    main.post { if (g == gen) scheduleReconnect("Encoder error, restarting.") }
                }
            }
            e.setEncoderErrorCallback(codecCallback)
            e.setDecoderErrorCallback(codecCallback)

            val videoOk = e.prepareVideo(path, prefs.bitrateKbps * 1000, 0)
            if (!videoOk) {
                stopEverything("This phone could not prepare the video. Try an H.264 MP4 video.")
                return
            }
            // A video without an audio track is fine: only video is sent.
            var audioOk = false
            try {
                audioOk = e.prepareAudio(path, AUDIO_BITRATE)
            } catch (ex: Exception) {
                Log.w(TAG, "No usable audio track: ${ex.message}")
            }
            e.getStreamClient().setOnlyVideo(!audioOk)

            engineStartedAt = SystemClock.elapsedRealtime()
            lastBitrateAt = engineStartedAt
            lastVideoTime = 0.0
            e.startStream(url)
        } catch (ex: IOException) {
            stopEverything("Could not read the video file.")
        } catch (ex: Exception) {
            Log.e(TAG, "connect failed: ${ex.message}")
            scheduleReconnect("Could not start: ${ex.message}")
        }
    }

    private inner class Checker(private val g: Int) : ConnectChecker {
        override fun onConnectionStarted(url: String) {}

        override fun onConnectionSuccess() {
            main.post {
                if (g == gen && wantLive) {
                    attempt = 0
                    everConnected = true
                    StreamStore.phase = Phase.LIVE
                    StreamStore.message = ""
                    lastBitrateAt = SystemClock.elapsedRealtime()
                    updateNotification(force = true)
                }
            }
        }

        override fun onConnectionFailed(reason: String) {
            main.post { if (g == gen) scheduleReconnect("Connection failed: $reason") }
        }

        override fun onNewBitrate(bitrate: Long) {
            if (g == gen) {
                lastBitrateAt = SystemClock.elapsedRealtime()
                StreamStore.bitrateKbps = bitrate / 1000
            }
        }

        override fun onDisconnect() {
            main.post { if (g == gen) scheduleReconnect("Disconnected from the server.") }
        }

        override fun onAuthError() {
            main.post { if (g == gen) scheduleReconnect("Authentication error. Check your stream key.") }
        }

        override fun onAuthSuccess() {}
    }

    private fun scheduleReconnect(reason: String) {
        if (!wantLive || reconnectScheduled) return
        reconnectScheduled = true
        attempt++
        StreamStore.reconnects++
        StreamStore.message = reason
        if (!everConnected && attempt > MAX_FAILED_ATTEMPTS) {
            stopEverything("Stopped after $MAX_FAILED_ATTEMPTS failed attempts. Check your link and stream key. Last error: $reason")
            return
        }
        StreamStore.phase = Phase.RECONNECTING
        releaseEngine()
        val delay = minOf(60_000L, 3_000L * attempt)
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
        updateNotification(force = true)
    }

    private fun releaseEngine() {
        gen++ // callbacks from the old engine are ignored from now on
        val e = engine
        engine = null
        if (e != null) {
            try {
                e.stopStream() // also frees encoders and decoders when the stream never started
            } catch (ex: Exception) {
                Log.w(TAG, "stopStream: ${ex.message}")
            }
        }
    }

    /** Runs every second while live: counts loops, detects a stalled stream, refreshes the notification. */
    private val tick = object : Runnable {
        override fun run() {
            if (!wantLive) return
            val now = SystemClock.elapsedRealtime()
            val e = engine
            if (e != null && StreamStore.phase != Phase.LIVE && now - engineStartedAt > CONNECT_TIMEOUT_MS) {
                scheduleReconnect("Could not connect within 45 seconds.")
            }
            if (e != null && StreamStore.phase == Phase.LIVE) {
                try {
                    val t = e.videoTime
                    if (t + 0.5 < lastVideoTime) StreamStore.loops++
                    lastVideoTime = t
                } catch (ex: Exception) {
                    // loop counter is cosmetic
                }
                if (now - lastResyncAt > RESYNC_INTERVAL_MS) {
                    // Audio and video loop on their own clocks; line them up once an hour.
                    lastResyncAt = now
                    try {
                        e.reSyncFile()
                    } catch (ex: Exception) {
                        Log.w(TAG, "reSync: ${ex.message}")
                    }
                }
                if (!e.isStreaming) {
                    scheduleReconnect("Stream stopped unexpectedly.")
                } else if (now - lastBitrateAt > STALL_TIMEOUT_MS) {
                    scheduleReconnect("No data was sent for 30 seconds.")
                }
            }
            updateNotification(force = false)
            main.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- locks and network

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "looplive:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock: ${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // LOW_LATENCY only works with the screen on, so the older high-performance mode is used on purpose.
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "looplive:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release: ${e.message}")
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock release: ${e.message}")
        }
        wifiLock = null
    }

    private fun registerNetwork() {
        if (netCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Internet is back: do not wait for the backoff timer.
                    main.post {
                        if (wantLive && reconnectScheduled) {
                            main.removeCallbacks(reconnectRunnable)
                            connect()
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback: ${e.message}")
        }
    }

    private fun unregisterNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "network callback release: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Live stream", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shown while LoopLive is streaming"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun statusText(): String = when (StreamStore.phase) {
        Phase.LIVE -> "LIVE  ${StreamStore.uptime()}  loops ${StreamStore.loops}"
        Phase.CONNECTING -> "Connecting..."
        Phase.RECONNECTING -> "Reconnecting (try $attempt)  ${StreamStore.uptime()}"
        Phase.IDLE -> "Stopped"
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("LoopLive")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifAt < 10_000) return
        lastNotifAt = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(statusText()))
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }
}
