package app.looplive

import android.content.Context
import android.content.SharedPreferences

/** Everything the app remembers. The service reads the same values, so it can restart on its own. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("looplive", Context.MODE_PRIVATE)

    var videoPath: String?
        get() = sp.getString("videoPath", null)
        set(v) = sp.edit().putString("videoPath", v).apply()

    var videoName: String
        get() = sp.getString("videoName", "") ?: ""
        set(v) = sp.edit().putString("videoName", v).apply()

    /** Coded width/height of the stored video, 0 if unknown. */
    var videoWidth: Int
        get() = sp.getInt("videoWidth", 0)
        set(v) = sp.edit().putInt("videoWidth", v).apply()

    var videoHeight: Int
        get() = sp.getInt("videoHeight", 0)
        set(v) = sp.edit().putInt("videoHeight", v).apply()

    var videoRotation: Int
        get() = sp.getInt("videoRotation", 0)
        set(v) = sp.edit().putInt("videoRotation", v).apply()

    var videoHasAudio: Boolean
        get() = sp.getBoolean("videoHasAudio", true)
        set(v) = sp.edit().putBoolean("videoHasAudio", v).apply()

    var videoDurationSec: Long
        get() = sp.getLong("videoDurationSec", 0L)
        set(v) = sp.edit().putLong("videoDurationSec", v).apply()

    var videoSizeBytes: Long
        get() = sp.getLong("videoSizeBytes", 0L)
        set(v) = sp.edit().putLong("videoSizeBytes", v).apply()

    var link: String
        get() = sp.getString("link", "") ?: ""
        set(v) = sp.edit().putString("link", v).apply()

    var shorts: Boolean
        get() = sp.getBoolean("shorts", true)
        set(v) = sp.edit().putBoolean("shorts", v).apply()

    var loop: Boolean
        get() = sp.getBoolean("loop", true)
        set(v) = sp.edit().putBoolean("loop", v).apply()

    var bitrateKbps: Int
        get() = sp.getInt("bitrateKbps", 2500)
        set(v) = sp.edit().putInt("bitrateKbps", v).apply()

    /** True from the moment the user presses Start until the user presses Stop. */
    var wasLive: Boolean
        get() = sp.getBoolean("wasLive", false)
        set(v) = sp.edit().putBoolean("wasLive", v).apply()
}
