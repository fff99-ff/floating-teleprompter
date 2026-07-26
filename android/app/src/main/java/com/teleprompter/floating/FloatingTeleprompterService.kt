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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FloatingTeleprompterService : Service() {

    companion object {
        const val ACTION_START = "com.teleprompter.floating.START"
        const val ACTION_STOP = "com.teleprompter.floating.STOP"
        const val CHANNEL_ID = "floating_teleprompter_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

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
    private lateinit var btnSlower: ImageButton
    private lateinit var btnFaster: ImageButton
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView

    private var isMinimized = false
    private var isPlaying = false

    // 语音识别
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var scriptText = ""
    private var lastMatchPos = 0
    private var useVoiceFollow = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences("teleprompter_settings", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                if (!::rootView.isInitialized) {
                    showFloatingWindow()
                }
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
            WindowManager.LayoutParams.TYPE_PHONE
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
        btnSlower = rootView.findViewById(R.id.btnSlower)
        btnFaster = rootView.findViewById(R.id.btnFaster)
        seekSpeed = rootView.findViewById(R.id.seekSpeed)
        tvSpeed = rootView.findViewById(R.id.tvSpeed)

        // 初始化速度
        val speed = prefs.getInt("speed", 35)
        seekSpeed.progress = speed - 1
        tvSpeed.text = speed.toString()
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

        // 添加 JS 接口
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        // 加载 assets 中的 HTML
        webView.loadUrl("file:///android_asset/teleprompter.html")

        // 应用设置到 WebView
        webView.postDelayed({ applySettingsToWebView() }, 500)
    }

    private fun applySettingsToWebView() {
        val script = prefs.getString("script_text", "") ?: ""
        val fontSize = prefs.getInt("font_size", 50)
        val fontColor = prefs.getString("font_color", "#FFFFFF")
        val bgColor = prefs.getString("bg_color", "#000000")
        val bgOpacity = prefs.getInt("bg_opacity", 80)
        val lineHeight = prefs.getInt("line_height", 180) / 100.0
        val speed = prefs.getInt("speed", 35)
        val useTts = prefs.getBoolean("use_tts", true)
        val ttsRate = prefs.getInt("tts_rate", 10) / 10.0
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
                speed: $speed,
                useTts: $useTts,
                ttsRate: $ttsRate,
                mirror: $mirror
            });
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun setupTouchListeners() {
        // 拖动悬浮窗
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        topBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(rootView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    true
                }
                else -> false
            }
        }

        // WebView 上的触摸事件：点击暂停/继续，滑动调速
        var touchStartY = 0f
        var touchStartSpeed = 0
        var touchStartTime = 0L
        var isTouchingWebView = false

        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isTouchingWebView = true
                    touchStartY = event.rawY
                    touchStartSpeed = seekSpeed.progress + 1
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val useTts = prefs.getBoolean("use_tts", true)
                    if (useTts) return@setOnTouchListener true

                    val deltaY = touchStartY - event.rawY
                    if (Math.abs(deltaY) > 10) {
                        val newSpeed = (touchStartSpeed + deltaY * 0.15).toInt()
                            .coerceIn(1, 100)
                        seekSpeed.progress = newSpeed - 1
                        tvSpeed.text = newSpeed.toString()
                        prefs.edit().putInt("speed", newSpeed).apply()
                        webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.setSpeed($newSpeed);", null)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = touchStartY - event.rawY
                    val deltaTime = System.currentTimeMillis() - touchStartTime
                    if (Math.abs(deltaY) < 10 && deltaTime < 250) {
                        togglePlay()
                    }
                    isTouchingWebView = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControlListeners() {
        btnClose.setOnClickListener {
            stopSelf()
        }

        btnMinimize.setOnClickListener {
            toggleMinimize()
        }

        btnExpand.setOnClickListener {
            toggleMinimize()
        }

        btnPlayPause.setOnClickListener {
            togglePlay()
        }

        btnSlower.setOnClickListener {
            val newSpeed = (seekSpeed.progress + 1 - 5).coerceIn(1, 100)
            seekSpeed.progress = newSpeed - 1
            tvSpeed.text = newSpeed.toString()
            prefs.edit().putInt("speed", newSpeed).apply()
            webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.setSpeed($newSpeed);", null)
        }

        btnFaster.setOnClickListener {
            val newSpeed = (seekSpeed.progress + 1 + 5).coerceIn(1, 100)
            seekSpeed.progress = newSpeed - 1
            tvSpeed.text = newSpeed.toString()
            prefs.edit().putInt("speed", newSpeed).apply()
            webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.setSpeed($newSpeed);", null)
        }

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress + 1
                tvSpeed.text = speed.toString()
                if (fromUser) {
                    prefs.edit().putInt("speed", speed).apply()
                    webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.setSpeed($speed);", null)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun togglePlay() {
        isPlaying = !isPlaying
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        android.util.Log.d("VoiceFollow", "togglePlay: isPlaying=$isPlaying")

        if (isPlaying) {
            // 检查是否开启语音跟随
            useVoiceFollow = prefs.getBoolean("use_tts", true)
            scriptText = prefs.getString("script_text", "") ?: ""
            lastMatchPos = 0

            android.util.Log.d("VoiceFollow", "togglePlay: useVoiceFollow=$useVoiceFollow scriptLen=${scriptText.length}")

            if (useVoiceFollow && scriptText.isNotEmpty()) {
                startVoiceRecognition()
            }
        } else {
            stopVoiceRecognition()
        }

        webView.evaluateJavascript(
            "window.AndroidBridge && window.AndroidBridge.${if (isPlaying) "play" else "pause"}();",
            null
        )
    }

    // ==================== 语音识别跟随 ====================
    private fun startVoiceRecognition() {
        android.util.Log.d("VoiceFollow", "startVoiceRecognition called, isPlaying=$isPlaying")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.e("VoiceFollow", "SpeechRecognizer not available")
            Toast.makeText(this, "语音识别不可用，使用手动模式", Toast.LENGTH_SHORT).show()
            useVoiceFollow = false
            return
        }

        // 每次都重新创建 SpeechRecognizer，避免状态残留
        speechRecognizer?.destroy()
        speechRecognizer = null

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        android.util.Log.d("VoiceFollow", "SpeechRecognizer created")

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                android.util.Log.d("VoiceFollow", "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                android.util.Log.d("VoiceFollow", "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                android.util.Log.d("VoiceFollow", "onEndOfSpeech")
                isListening = false
                if (isPlaying && useVoiceFollow) {
                    android.os.Handler(mainLooper).postDelayed({
                        if (isPlaying && useVoiceFollow) restartListening()
                    }, 100)
                }
            }
            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                    else -> "UNKNOWN($error)"
                }
                android.util.Log.e("VoiceFollow", "onError: $errorName")
                isListening = false
                if (isPlaying && useVoiceFollow) {
                    android.os.Handler(mainLooper).postDelayed({
                        if (isPlaying && useVoiceFollow) restartListening()
                    }, 500)
                }
            }
            override fun onResults(results: Bundle?) {
                android.util.Log.d("VoiceFollow", "onResults")
                isListening = false
                handleRecognitionResults(results)
                if (isPlaying && useVoiceFollow) {
                    android.os.Handler(mainLooper).postDelayed({
                        if (isPlaying && useVoiceFollow) restartListening()
                    }, 100)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                android.util.Log.d("VoiceFollow", "onPartialResults")
                handleRecognitionResults(partialResults)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        restartListening()
    }

    private fun restartListening() {
        if (isListening) {
            android.util.Log.d("VoiceFollow", "restartListening skipped: already listening")
            return
        }
        if (speechRecognizer == null) {
            android.util.Log.e("VoiceFollow", "restartListening: speechRecognizer is null")
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
            isListening = true
            android.util.Log.d("VoiceFollow", "startListening called successfully")
        } catch (e: Exception) {
            android.util.Log.e("VoiceFollow", "startListening exception: ${e.message}")
            isListening = false
        }
    }

    private fun stopVoiceRecognition() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {}
    }

    private fun handleRecognitionResults(results: Bundle?) {
        if (results == null || scriptText.isEmpty()) {
            android.util.Log.d("VoiceFollow", "handleResults: results=$results scriptEmpty=${scriptText.isEmpty()}")
            return
        }

        val matches = results.getStringArray(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: results.getStringArray("results_recognition")
            ?: run {
                android.util.Log.d("VoiceFollow", "handleResults: no matches array")
                return
            }

        if (matches.isEmpty()) {
            android.util.Log.d("VoiceFollow", "handleResults: matches empty")
            return
        }

        val spoken = matches[0].replace(" ", "").replace("\n", "").trim()
        android.util.Log.d("VoiceFollow", "handleResults: spoken='$spoken' (len=${spoken.length})")

        if (spoken.isEmpty()) return

        // 在台词中查找匹配位置
        val matchPos = findMatchPosition(scriptText, spoken, lastMatchPos)
        android.util.Log.d("VoiceFollow", "handleResults: matchPos=$matchPos lastMatchPos=$lastMatchPos")

        if (matchPos >= 0) {
            lastMatchPos = matchPos
            // 通知 WebView 滚动到该字符位置
            val spokenText = scriptText.substring(0, matchPos + spoken.length)
                .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
            val js = "window.AndroidBridge && window.AndroidBridge.scrollToText('$spokenText');"
            android.util.Log.d("VoiceFollow", "calling JS: scrollToText length=${spokenText.length}")
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 在台词文本中查找识别到的语音内容位置
     * 使用模糊匹配，从上次匹配位置开始搜索
     */
    private fun findMatchPosition(script: String, spoken: String, startPos: Int): Int {
        val cleanScript = script.replace(" ", "").replace("\n", "").replace("\r", "")

        // 取语音识别结果的最后几个字作为搜索词（更精确）
        val searchLen = minOf(spoken.length, 15)
        val searchWord = spoken.takeLast(searchLen)

        // 在原始台词中搜索（从上次位置之后开始）
        for (i in startPos until script.length - searchWord.length) {
            var match = true
            var scriptIdx = i
            var spokenIdx = 0

            while (scriptIdx < script.length && spokenIdx < searchWord.length) {
                val sc = script[scriptIdx]
                // 跳过空格和换行
                if (sc == ' ' || sc == '\n' || sc == '\r' || sc == '\t' || sc == '，' || sc == '。' || sc == '、' || sc == '！' || sc == '？' || sc == '：' || sc == '；') {
                    scriptIdx++
                    continue
                }
                if (sc != searchWord[spokenIdx]) {
                    match = false
                    break
                }
                scriptIdx++
                spokenIdx++
            }

            if (match && spokenIdx == searchWord.length) {
                return i
            }
        }

        // 如果从头搜索也找不到，返回 -1
        return -1
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

    /**
     * JavaScript 接口：供 WebView 内的 JS 调用原生方法
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(this@FloatingTeleprompterService, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun onPlaybackEnded() {
            isPlaying = false
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
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
        stopVoiceRecognition()
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (::rootView.isInitialized && rootView.isAttachedToWindow) {
            windowManager.removeView(rootView)
        }
        if (::webView.isInitialized) {
            webView.destroy()
        }
    }
}
