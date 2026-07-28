package com.teleprompter.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.sqrt

class FloatingTeleprompterService : Service() {

    companion object {
        const val ACTION_START = "com.teleprompter.floating.START"
        const val ACTION_STOP = "com.teleprompter.floating.STOP"
        const val CHANNEL_ID = "floating_teleprompter_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

    // 音频采样参数（类主体中定义为 val，避免 companion object private 成员不可外部访问的问题）
    private val SAMPLE_RATE = 44100
    private val BUFFER_SIZE_FACTOR = 2
    private val VOLUME_THRESHOLD = 600f
    private val VOLUME_HIGH = 6000f
    private val BASE_SPEED = 2.0f
    private val MAX_SPEED_MULTIPLIER = 2.5f
    private val SPEED_SMOOTHING = 0.12f
    private val VOLUME_SMOOTHING = 0.3f
    private val MIN_SCROLL_SPEED = 0.8f
    private val STARTUP_DELAY_FRAMES = 5

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var rootView: View
    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var btnMinimize: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnExpand: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var tvStatus: TextView

    private var isMinimized = false
    private var isPlaying = false

    // 麦克风音量检测
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordThread: Thread? = null
    private var bufferSize = 0
    private var silenceCount = 0  // 连续静默帧计数
    private var smoothedVolume = 0f  // 平滑后的音量
    private var startupFrameCount = 0  // 启动帧计数
    private val mainHandler = Handler(Looper.getMainLooper())

    // 滚动控制
    private var scrollSpeed = 0f  // 当前滚动速度（像素/帧）
    private var targetSpeed = 0f  // 目标速度
    private var userSpeedMultiplier = 1.0f  // 用户调整的速度倍数

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences("teleprompter_settings", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2  // fallback
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                if (!::rootView.isInitialized) {
                    showFloatingWindow()
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showFloatingWindow() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            2038
        }

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        layoutParams = WindowManager.LayoutParams(
            screenWidth,
            (screenHeight * 0.35).toInt(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 0
        layoutParams.y = 100

        rootView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)

        initViews()
        setupWebView()
        setupTouchListeners()
        setupControlListeners()

        windowManager.addView(rootView, layoutParams)
    }

    private fun initViews() {
        topBar = rootView.findViewById(R.id.topBar)
        bottomBar = rootView.findViewById(R.id.bottomBar)
        webView = rootView.findViewById(R.id.webView)
        btnMinimize = rootView.findViewById(R.id.btnMinimize)
        btnClose = rootView.findViewById(R.id.btnClose)
        btnExpand = rootView.findViewById(R.id.btnExpand)
        btnPlayPause = rootView.findViewById(R.id.btnPlayPause)
        tvStatus = rootView.findViewById(R.id.tvStatus)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.webChromeClient = WebChromeClient()
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.loadUrl("file:///android_asset/teleprompter.html")

        webView.postDelayed({ applySettingsToWebView() }, 500)
    }

    private fun applySettingsToWebView() {
        val script = prefs.getString("script_text", "") ?: ""
        val fontSize = prefs.getInt("font_size", 50)
        val fontColor = prefs.getString("font_color", "#FFFFFF")
        val bgColor = prefs.getString("bg_color", "#000000")
        val bgOpacity = prefs.getInt("bg_opacity", 80)
        val lineHeight = prefs.getInt("line_height", 180) / 100.0
        val mirror = prefs.getBoolean("mirror", false)

        val escapedScript = script.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val js = """
            window.AndroidBridge && window.AndroidBridge.init({
                script: '$escapedScript',
                fontSize: $fontSize,
                fontColor: '$fontColor',
                bgColor: '$bgColor',
                bgOpacity: ${bgOpacity / 100.0},
                lineHeight: $lineHeight,
                mirror: $mirror
            });
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun setupTouchListeners() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        topBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(rootView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        // WebView 点击暂停/继续
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    togglePlay()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControlListeners() {
        btnClose.setOnClickListener { stopSelf() }
        btnMinimize.setOnClickListener { toggleMinimize() }
        btnExpand.setOnClickListener { toggleMinimize() }
        btnPlayPause.setOnClickListener { togglePlay() }
    }

    private fun togglePlay() {
        isPlaying = !isPlaying
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        if (isPlaying) {
            tvStatus.text = "聆听中..."
            tvStatus.setBackgroundColor(0x804CAF50)
            startVoiceDetection()
            webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.play();", null)
        } else {
            tvStatus.text = "已暂停"
            tvStatus.setBackgroundColor(0x80FF9800)
            stopVoiceDetection()
            webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.pause();", null)
        }
    }

    // ==================== 麦克风音量检测 ====================
    private fun startVoiceDetection() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * BUFFER_SIZE_FACTOR
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("VoiceDetect", "AudioRecord init failed")
                Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_SHORT).show()
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            silenceCount = 0
            smoothedVolume = 0f
            startupFrameCount = 0

            recordThread = Thread {
                val buffer = ShortArray(bufferSize)
                android.util.Log.d("VoiceDetect", "Recording started, bufferSize=$bufferSize")

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    if (read <= 0) continue

                    // 计算 RMS 音量
                    var sum = 0L
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        sum += (v * v).toLong()
                    }
                    val rms = sqrt(sum.toDouble() / read)

                    // 启动延迟：前几帧不响应，避免瞬间误触发
                    if (startupFrameCount < STARTUP_DELAY_FRAMES) {
                        startupFrameCount++
                        Thread.sleep(33)
                        continue
                    }

                    // 音量平滑（指数移动平均）
                    val rawVolume = rms.toFloat()
                    smoothedVolume += (rawVolume - smoothedVolume) * VOLUME_SMOOTHING

                    // 判断是否在说话
                    if (smoothedVolume > VOLUME_THRESHOLD) {
                        // 说话中：根据音量计算滚动速度
                        val ratio = Math.min(1.0, 
                            (smoothedVolume - VOLUME_THRESHOLD) / (VOLUME_HIGH - VOLUME_THRESHOLD))
                        // 使用非线性映射，让低速更灵敏，高速更稳定
                        val curvedRatio = Math.pow(ratio.toDouble(), 0.7).toFloat()
                        targetSpeed = (BASE_SPEED + curvedRatio * BASE_SPEED * MAX_SPEED_MULTIPLIER) * userSpeedMultiplier
                        silenceCount = 0
                    } else {
                        // 静默：逐渐减速（更平缓的过渡）
                        silenceCount++
                        if (silenceCount > 5) {
                            targetSpeed = 0f
                        } else {
                            targetSpeed *= 0.7f  // 每帧衰减 30%
                        }
                    }

                    // 平滑速度变化（更灵敏的响应）
                    scrollSpeed += (targetSpeed - scrollSpeed) * SPEED_SMOOTHING

                    // 确保最小滚动速度（当有声音时）
                    if (smoothedVolume > VOLUME_THRESHOLD && scrollSpeed < MIN_SCROLL_SPEED * userSpeedMultiplier) {
                        scrollSpeed = MIN_SCROLL_SPEED * userSpeedMultiplier
                    }

                    // 通知 WebView 滚动
                    if (scrollSpeed > 0.05f) {
                        val speedStr = String.format(Locale.US, "%.2f", scrollSpeed)
                        mainHandler.post {
                            webView.evaluateJavascript(
                                "window.AndroidBridge && window.AndroidBridge.scroll($speedStr);",
                                null
                            )
                        }
                    }

                    // 控制采样频率（约 30fps）
                    Thread.sleep(33)
                }

                android.util.Log.d("VoiceDetect", "Recording stopped")
            }

            recordThread?.start()
        } catch (e: SecurityException) {
            android.util.Log.e("VoiceDetect", "No mic permission: ${e.message}")
            Toast.makeText(this, "没有麦克风权限", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("VoiceDetect", "startVoiceDetection error: ${e.message}")
        }
    }

    private fun stopVoiceDetection() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {}
        try {
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        recordThread = null
        scrollSpeed = 0f
        targetSpeed = 0f
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        if (isMinimized) {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            webView.visibility = View.GONE
            btnExpand.visibility = View.VISIBLE

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)

            layoutParams.width = (56 * resources.displayMetrics.density).toInt()
            layoutParams.height = (56 * resources.displayMetrics.density).toInt()
        } else {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            webView.visibility = View.VISIBLE
            btnExpand.visibility = View.GONE

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)

            layoutParams.width = dm.widthPixels
            layoutParams.height = (dm.heightPixels * 0.35).toInt()
        }
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(this@FloatingTeleprompterService, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun onPlaybackEnded() {
            mainHandler.post {
                isPlaying = false
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                tvStatus.text = "已结束"
                stopVoiceDetection()
            }
        }

        @JavascriptInterface
        fun setSpeedMultiplier(multiplier: Float) {
            userSpeedMultiplier = multiplier.coerceIn(0.2f, 3.0f)
        }

        @JavascriptInterface
        fun getSpeedMultiplier(): Float {
            return userSpeedMultiplier
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("TeleprompterJS", message)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rootView.isInitialized && !isMinimized) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)
            layoutParams.width = dm.widthPixels
            layoutParams.height = (dm.heightPixels * 0.35).toInt()
            windowManager.updateViewLayout(rootView, layoutParams)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopVoiceDetection()
        if (::rootView.isInitialized && rootView.isAttachedToWindow) {
            windowManager.removeView(rootView)
        }
        if (::webView.isInitialized) {
            webView.destroy()
        }
    }
}
