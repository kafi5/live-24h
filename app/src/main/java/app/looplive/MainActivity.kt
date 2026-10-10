package app.looplive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var liveChip: LinearLayout
    private lateinit var chipState: TextView
    private lateinit var chipTime: TextView
    private lateinit var chipLoops: TextView
    private lateinit var statusMsg: TextView
    private lateinit var pickBtn: TextView
    private lateinit var fileLine: TextView
    private lateinit var fileNote: TextView
    private lateinit var linkInput: EditText
    private lateinit var fmtShorts: TextView
    private lateinit var fmtLong: TextView
    private lateinit var goBtn: TextView
    private lateinit var hintText: TextView
    private lateinit var batteryNote: TextView

    @Volatile private var copying = false
    private var askedNotifications = false
    private var askedBattery = false
    private val defaultHint = "Make sure your video and streaming link are correct before starting."

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVideo(uri)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        continueStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        liveChip = findViewById(R.id.liveChip)
        chipState = findViewById(R.id.chipState)
        chipTime = findViewById(R.id.chipTime)
        chipLoops = findViewById(R.id.chipLoops)
        statusMsg = findViewById(R.id.statusMsg)
        pickBtn = findViewById(R.id.pickBtn)
        fileLine = findViewById(R.id.fileLine)
        fileNote = findViewById(R.id.fileNote)
        linkInput = findViewById(R.id.linkInput)
        fmtShorts = findViewById(R.id.fmtShorts)
        fmtLong = findViewById(R.id.fmtLong)
        goBtn = findViewById(R.id.goBtn)
        hintText = findViewById(R.id.hintText)
        batteryNote = findViewById(R.id.batteryNote)

        val brand = SpannableString("LoopLive")
        brand.setSpan(ForegroundColorSpan(0xFFFF3D5A.toInt()), 4, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.brandName).text = brand

        linkInput.setText(prefs.link)
        linkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.link = s?.toString()?.trim() ?: ""
            }
        })

        fmtShorts.setOnClickListener { setFormat(true) }
        fmtLong.setOnClickListener { setFormat(false) }
        pickBtn.setOnClickListener {
            if (StreamStore.phase == Phase.IDLE && !copying) {
                pickVideo.launch(arrayOf("video/*"))
            }
        }
        goBtn.setOnClickListener { onGoClicked() }
        batteryNote.setOnClickListener { requestBatteryExemption() }
        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener { showSettings() }

        applyFormat()
        renderFile()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(refreshLoop)
        ui.post(refreshLoop)
    }

    override fun onStop() {
        ui.removeCallbacks(refreshLoop)
        super.onStop()
    }

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- screen state

    private fun refresh() {
        val phase = StreamStore.phase
        val live = phase != Phase.IDLE

        liveChip.visibility = if (live) View.VISIBLE else View.GONE
        when (phase) {
            Phase.LIVE -> {
                chipState.text = "LIVE"
                chipLoops.text = "Loops ${StreamStore.loops}  |  ${StreamStore.bitrateKbps} kbps"
            }
            Phase.CONNECTING -> {
                chipState.text = "Connecting..."
                chipLoops.text = ""
            }
            Phase.RECONNECTING -> {
                chipState.text = "Reconnecting"
                chipLoops.text = "Retries ${StreamStore.reconnects}"
            }
            Phase.IDLE -> {}
        }
        if (live) chipTime.text = StreamStore.uptime()

        val msg = StreamStore.message
        statusMsg.visibility = if (msg.isNotEmpty()) View.VISIBLE else View.GONE
        statusMsg.text = msg

        goBtn.text = if (live) "Stop Live" else "Start Live"
        goBtn.setBackgroundResource(if (live) R.drawable.bg_go_live else R.drawable.bg_go)
        goBtn.setTextColor(if (live) 0xFFFFB3BD.toInt() else 0xFFFFFFFF.toInt())

        val editable = !live && !copying
        linkInput.isEnabled = editable
        pickBtn.alpha = if (editable) 1f else 0.5f
        fmtShorts.isEnabled = editable
        fmtLong.isEnabled = editable
        goBtn.alpha = if (copying) 0.5f else 1f

        if (live) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        batteryNote.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE
    }

    private fun showHint(text: String, error: Boolean) {
        hintText.text = text
        hintText.setTextColor(if (error) 0xFFFF9AA8.toInt() else 0xFF95A2C6.toInt())
    }

    private fun setFormat(shorts: Boolean) {
        if (StreamStore.phase != Phase.IDLE) return
        prefs.shorts = shorts
        applyFormat()
        renderFile()
    }

    private fun applyFormat() {
        val shorts = prefs.shorts
        fmtShorts.setBackgroundResource(if (shorts) R.drawable.bg_seg_on else R.drawable.bg_seg_off)
        fmtLong.setBackgroundResource(if (shorts) R.drawable.bg_seg_off else R.drawable.bg_seg_on)
        fmtShorts.setTextColor(if (shorts) 0xFFFFFFFF.toInt() else 0xFF95A2C6.toInt())
        fmtLong.setTextColor(if (shorts) 0xFF95A2C6.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun renderFile() {
        val path = prefs.videoPath
        if (path == null || !File(path).exists()) {
            fileLine.text = "No video selected yet."
            fileNote.visibility = View.GONE
            return
        }
        val mb = prefs.videoSizeBytes / 1048576.0
        fileLine.text = String.format(
            Locale.US,
            "%s | %dx%d | %s | %.1f MB",
            prefs.videoName, prefs.videoWidth, prefs.videoHeight, formatDuration(prefs.videoDurationSec), mb
        )

        val notes = mutableListOf<String>()
        val w = prefs.videoWidth
        val h = prefs.videoHeight
        if (w > 0 && h > 0) {
            if (prefs.shorts && w >= h) {
                notes += "This video is landscape. Shorts works best with a vertical (9:16) video."
            }
            if (!prefs.shorts && h > w) {
                notes += "This video is vertical. Long Video works best with a landscape (16:9) video."
            }
        }
        if (prefs.videoRotation == 90 || prefs.videoRotation == 270) {
            notes += "This video has a rotation flag. If it shows sideways on YouTube, export it again without rotation."
        }
        if (!prefs.videoHasAudio) {
            notes += "This video has no audio track, so the stream will be silent."
        }
        if (notes.isEmpty()) {
            fileNote.visibility = View.GONE
        } else {
            fileNote.text = notes.joinToString("\n")
            fileNote.visibility = View.VISIBLE
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ---------------------------------------------------------------- video import

    private fun importVideo(uri: Uri) {
        if (copying) return
        copying = true
        fileNote.visibility = View.GONE
        fileLine.text = "Copying video... 0%"
        refresh()

        Thread {
            var error: String? = null
            try {
                var name = "video"
                var size = -1L
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                if (size > 0 && filesDir.usableSpace < size + 50L * 1024 * 1024) {
                    throw IOException("Not enough free storage on the phone to copy this video.")
                }

                val tmp = File(filesDir, "source.tmp")
                val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open the video.")
                input.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastPct = -1
                        var n = ins.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val pct = (done * 100 / size).toInt()
                                if (pct != lastPct && pct % 2 == 0) {
                                    lastPct = pct
                                    runOnUiThread { fileLine.text = "Copying video... $pct%" }
                                }
                            }
                            n = ins.read(buf)
                        }
                    }
                }

                val dest = File(filesDir, "source_video")
                var w = 0
                var h = 0
                var rotation = 0
                var durationSec = 0L
                var hasAudio = false
                val r = MediaMetadataRetriever()
                try {
                    // Check the copy before it replaces the video that is already saved.
                    r.setDataSource(tmp.absolutePath)
                    val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (!hasVideo || w <= 0 || h <= 0) throw IOException("This file does not look like a video.")
                    rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    durationSec =
                        (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
                    hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                } finally {
                    r.release()
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Could not save the video.")
                prefs.videoWidth = w
                prefs.videoHeight = h
                prefs.videoRotation = rotation
                prefs.videoDurationSec = durationSec
                prefs.videoHasAudio = hasAudio
                prefs.videoName = name
                prefs.videoSizeBytes = dest.length()
                prefs.videoPath = dest.absolutePath
            } catch (e: Exception) {
                error = e.message ?: "Could not copy the video."
                File(filesDir, "source.tmp").delete()
            }

            val failure = error
            runOnUiThread {
                copying = false
                if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                renderFile()
                refresh()
            }
        }.start()
    }

    // ---------------------------------------------------------------- start / stop

    private fun validateLink(link: String): String? {
        val t = link.trim()
        if (t.isEmpty()) return "Paste your streaming link first."
        if (!t.startsWith("rtmp://", ignoreCase = true) && !t.startsWith("rtmps://", ignoreCase = true)) {
            return "The link must start with rtmp:// or rtmps://"
        }
        val uri = Uri.parse(t)
        if (uri.host.isNullOrEmpty()) return "The link looks incomplete."
        if (uri.pathSegments.size < 2) {
            return "Add your stream key at the end of the link, like .../live2/your-stream-key"
        }
        return null
    }

    private fun onGoClicked() {
        if (StreamStore.phase != Phase.IDLE) {
            StreamService.stop(this)
            return
        }
        if (copying) {
            Toast.makeText(this, "Please wait, the video is still being copied.", Toast.LENGTH_SHORT).show()
            return
        }
        val path = prefs.videoPath
        if (path == null || !File(path).exists()) {
            showHint("Choose a video first.", true)
            return
        }
        val problem = validateLink(prefs.link)
        if (problem != null) {
            showHint(problem, true)
            linkInput.requestFocus()
            return
        }
        showHint(defaultHint, false)
        StreamStore.message = ""

        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStart()
    }

    private fun continueStart() {
        if (!askedBattery && !isIgnoringBattery()) {
            askedBattery = true
            AlertDialog.Builder(this)
                .setTitle("Keep it running all day")
                .setMessage(
                    "For a 24-hour stream, allow LoopLive to use the battery without limits. " +
                        "Otherwise your phone may stop the stream when the screen is off."
                )
                .setPositiveButton("Allow") { _, _ ->
                    requestBatteryExemption()
                    StreamService.start(this)
                }
                .setNegativeButton("Not now") { _, _ -> StreamService.start(this) }
                .setOnCancelListener { StreamService.start(this) }
                .show()
            return
        }
        StreamService.start(this)
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Battery and set LoopLive to Unrestricted.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        if (StreamStore.phase != Phase.IDLE) {
            Toast.makeText(this, "Stop the live stream to change settings.", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val options = listOf(
            1500 to "Low: 1500 kbps, about 0.7 GB per hour",
            2500 to "Medium: 2500 kbps, about 1.1 GB per hour",
            4500 to "High: 4500 kbps, about 2 GB per hour",
            6000 to "Max: 6000 kbps, about 2.7 GB per hour"
        )

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val title = TextView(this)
        title.text = "Video quality"
        title.setTextColor(0xFFE9EDF9.toInt())
        title.textSize = 15f
        box.addView(title)

        val group = RadioGroup(this)
        options.forEachIndexed { index, (kbps, label) ->
            val rb = RadioButton(this)
            rb.id = 1000 + index
            rb.text = label
            rb.textSize = 14f
            rb.setTextColor(0xFFE9EDF9.toInt())
            rb.isChecked = prefs.bitrateKbps == kbps
            group.addView(rb)
        }
        if (group.checkedRadioButtonId == -1) group.check(1001)
        box.addView(group)

        val loop = CheckBox(this)
        loop.text = "Loop the video (stream keeps running until you stop it)"
        loop.setTextColor(0xFFE9EDF9.toInt())
        loop.textSize = 14f
        loop.isChecked = prefs.loop
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (12 * dp).toInt()
        box.addView(loop, lp)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val idx = group.checkedRadioButtonId - 1000
                if (idx in options.indices) prefs.bitrateKbps = options[idx].first
                prefs.loop = loop.isChecked
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
