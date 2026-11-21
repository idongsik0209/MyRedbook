package com.example.cdplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*

class MainActivity : Activity() {
    private val ACTION_PERM = "com.example.cdplayer.USB_PERMISSION"
    private val BG = Color.parseColor("#101216")
    private val PANEL = Color.parseColor("#1E222A")
    private val RED = Color.parseColor("#E53935")
    private val TEXT = Color.parseColor("#EDEFF2")
    private val SUB = Color.parseColor("#8B919C")

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var trackText: TextView
    private lateinit var totalText: TextView
    private lateinit var seek: SeekBar
    private lateinit var disc: DiscView
    private lateinit var spin: ObjectAnimator
    private lateinit var playBtn: ImageButton
    private lateinit var listView: ListView
    private lateinit var adapter: BaseAdapter

    private var scsi: UsbScsi? = null
    private var tracks: List<Track> = emptyList()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var paused = false
    @Volatile private var seekLba = -1
    private var dragging = false
    private var current = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else status.text = "USB 권한이 거부되었습니다."
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    stopPlayback()
                    scsi?.close(); scsi = null
                    tracks = emptyList(); adapter.notifyDataSetChanged()
                    status.text = "드라이브가 분리되었습니다."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val f = IntentFilter().apply {
            addAction(ACTION_PERM); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        connect()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); connect() }

    override fun onDestroy() {
        stopPlayback()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        scsi?.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    private fun circleBtn(icon: Int, sizeDp: Int, fill: Int, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(sizeDp / 4)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }

        // 헤더
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "CD PLAYER"; setTextColor(RED); textSize = 15f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.2f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "다시 읽기"; setTextColor(SUB); textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { forceReload() }
        })
        root.addView(header)

        status = TextView(this).apply { setTextColor(SUB); textSize = 12f; text = "CD 드라이브를 연결하세요." }
        root.addView(status)

        // 디스크
        disc = DiscView(this)
        spin = ObjectAnimator.ofFloat(disc, "rotation", 0f, 360f).apply {
            duration = 3500; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        }
        root.addView(disc, LinearLayout.LayoutParams(dp(230), dp(230)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20); bottomMargin = dp(16)
        })

        // 시간 / 트랙
        trackText = TextView(this).apply {
            setTextColor(RED); textSize = 12f; gravity = Gravity.CENTER
            letterSpacing = 0.2f; text = "READY"
        }
        timeText = TextView(this).apply {
            setTextColor(TEXT); textSize = 52f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); text = "0:00"
        }
        root.addView(trackText, LinearLayout.LayoutParams(-1, -2))
        root.addView(timeText, LinearLayout.LayoutParams(-1, -2))

        // 시크바
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(RED)
            thumbTintList = ColorStateList.valueOf(RED)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3F48"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = fmt(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val t = tracks.getOrNull(current) ?: return
                    seekLba = t.startLba + sb.progress * 75
                }
            })
        }
        totalText = TextView(this).apply { setTextColor(SUB); textSize = 12f; gravity = Gravity.END; text = "0:00" }
        root.addView(seek, LinearLayout.LayoutParams(-1, -2))
        root.addView(totalText, LinearLayout.LayoutParams(-1, -2))

        // 컨트롤
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12)) }
        bar.addView(circleBtn(R.drawable.ic_stop, 52, PANEL) { stopPlayback(); status.text = "정지" })
        bar.addView(circleBtn(R.drawable.ic_prev, 56, PANEL) { if (current > 0) play(current - 1) })
        playBtn = circleBtn(R.drawable.ic_play, 76, RED) { togglePause() }
        bar.addView(playBtn)
        bar.addView(circleBtn(R.drawable.ic_next, 56, PANEL) { if (current + 1 < tracks.size) play(current + 1) })
        root.addView(bar)

        // 트랙 목록
        adapter = object : BaseAdapter() {
            override fun getCount() = tracks.size
            override fun getItem(p: Int) = tracks[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val t = tracks[p]
                val active = p == current
                val col = if (active) RED else TEXT
                val row = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    if (active) setBackgroundColor(PANEL)
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = "%02d".format(t.number); setTextColor(if (active) RED else SUB); textSize = 15f
                }, LinearLayout.LayoutParams(dp(40), -2))
                row.addView(TextView(this@MainActivity).apply {
                    text = "Track %02d".format(t.number); setTextColor(col); textSize = 16f
                    if (active) typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this@MainActivity).apply {
                    text = fmt((t.endLba - t.startLba) / 75); setTextColor(SUB); textSize = 14f
                })
                return row
            }
        }
        listView = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> play(pos) }
        }
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refreshState() {
        val active = current >= 0 && !paused
        playBtn.setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        if (active) {
            if (!spin.isStarted) spin.start() else if (spin.isPaused) spin.resume()
        } else if (current >= 0) {
            if (spin.isStarted && !spin.isPaused) spin.pause()
        } else spin.cancel()
        adapter.notifyDataSetChanged()
    }

    private fun forceReload() {
        stopPlayback()
        scsi?.close(); scsi = null
        tracks = emptyList(); adapter.notifyDataSetChanged()
        connect()
    }

    private fun connect() {
        if (scsi != null && tracks.isNotEmpty()) return
        val dev: UsbDevice? = usb.deviceList.values.firstOrNull { UsbScsi.hasMassStorage(it) }
        if (dev == null) { status.text = "USB 드라이브를 찾지 못했습니다. (OTG 연결/전원 확인)"; return }
        if (!usb.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(dev, pi)
            status.text = "USB 권한을 허용해 주세요."
            return
        }
        status.text = "드라이브 여는 중..."
        Thread {
            scsi?.close()
            val s = UsbScsi.open(usb, dev)
            if (s == null) { ui.post { status.text = "드라이브를 열 수 없습니다." }; return@Thread }
            scsi = s
            var ready = false
            for (i in 1..20) {
                ui.post { status.text = "디스크 준비 대기 중... ($i/20)" }
                if (s.testUnitReady()) { ready = true; break }
                Thread.sleep(1000)
            }
            if (!ready) { ui.post { status.text = "디스크가 준비되지 않았습니다. (디스크/전원 확인)" }; return@Thread }
            val t = s.readToc()
            ui.post {
                tracks = t; adapter.notifyDataSetChanged()
                status.text = if (t.isEmpty()) "오디오 트랙이 없습니다." else "트랙 ${t.size}개 • 곡을 눌러 재생"
                trackText.text = if (t.isEmpty()) "NO DISC" else "READY"
            }
        }.start()
    }

    private fun togglePause() {
        if (current < 0) { if (tracks.isNotEmpty()) play(0); return }
        paused = !paused
        refreshState()
    }

    private fun stopPlayback() {
        gen++; current = -1; paused = false
        timeText.text = "0:00"; seek.progress = 0
        trackText.text = if (tracks.isEmpty()) "READY" else "STOPPED"
        refreshState()
    }

    private fun play(index: Int) {
        val s = scsi ?: return
        val track = tracks.getOrNull(index) ?: return
        gen++
        val g = gen
        current = index; paused = false; seekLba = -1
        val total = (track.endLba - track.startLba) / 75
        trackText.text = "TRACK %02d / %02d".format(track.number, tracks.size)
        totalText.text = fmt(total)
        seek.max = total; seek.progress = 0; timeText.text = "0:00"
        refreshState()

        Thread {
            val minBuf = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, 44100 * 4))
                .build()
            at.play()
            val chunk = 26
            val buf = ByteArray(chunk * 2352)
            var lba = track.startLba
            var failed = false
            var wasPaused = false

            fun tick(l: Int) {
                val el = (l - track.startLba) / 75
                ui.post {
                    if (g == gen) {
                        if (!dragging) { timeText.text = fmt(el); seek.progress = el }
                    }
                }
            }

            while (g == gen && lba < track.endLba) {
                val sk = seekLba
                if (sk >= 0) {
                    seekLba = -1
                    lba = sk.coerceIn(track.startLba, track.endLba - 1)
                    at.pause(); at.flush()
                    if (!paused) at.play()
                    tick(lba)
                }
                if (paused) {
                    if (!wasPaused) { at.pause(); wasPaused = true }
                    Thread.sleep(100); continue
                }
                if (wasPaused) { at.play(); wasPaused = false }
                val n = minOf(chunk, track.endLba - lba)
                var ok = false
                for (attempt in 1..3) { if (s.readCdda(lba, n, buf)) { ok = true; break } }
                if (!ok) { failed = true; break }
                at.write(buf, 0, n * 2352)
                lba += n
                tick(lba)
            }
            val finished = g == gen && !failed
            if (g == gen) at.stop() else { at.pause(); at.flush() }
            at.release()
            if (failed && g == gen) ui.post { status.text = "읽기 오류 (디스크 상태/전원 확인)"; stopPlayback() }
            else if (finished) ui.post {
                if (index + 1 < tracks.size) play(index + 1)
                else { stopPlayback(); status.text = "재생 끝" }
            }
        }.start()
    }
}
