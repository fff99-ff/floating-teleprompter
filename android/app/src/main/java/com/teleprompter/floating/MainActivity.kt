package com.teleprompter.floating

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Views
    private lateinit var editScript: EditText
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button
    private lateinit var btnImport: Button
    private lateinit var btnSample: Button
    private lateinit var tvStatus: TextView

    // Display settings
    private lateinit var seekFontSize: SeekBar
    private lateinit var tvFontSize: TextView
    private lateinit var btnFontColor: Button
    private lateinit var colorFontPreview: View
    private lateinit var btnBgColor: Button
    private lateinit var colorBgPreview: View
    private lateinit var seekBgOpacity: SeekBar
    private lateinit var tvBgOpacity: TextView
    private lateinit var seekLineHeight: SeekBar
    private lateinit var tvLineHeight: TextView

    // Playback settings
    private lateinit var switchMirror: SwitchMaterial

    // Color values
    private var fontColor = "#FFFFFF"
    private var bgColor = "#000000"

    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("teleprompter_settings", Context.MODE_PRIVATE)

        initViews()
        loadSettings()
        setupListeners()

        filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it) }
        }

        updateButtonText()
    }

    private fun initViews() {
        editScript = findViewById(R.id.editScript)
        btnStart = findViewById(R.id.btnStart)
        btnClear = findViewById(R.id.btnClear)
        btnImport = findViewById(R.id.btnImport)
        btnSample = findViewById(R.id.btnSample)
        tvStatus = findViewById(R.id.tvStatus)

        seekFontSize = findViewById(R.id.seekFontSize)
        tvFontSize = findViewById(R.id.tvFontSize)
        btnFontColor = findViewById(R.id.btnFontColor)
        colorFontPreview = findViewById(R.id.colorFontPreview)
        btnBgColor = findViewById(R.id.btnBgColor)
        colorBgPreview = findViewById(R.id.colorBgPreview)
        seekBgOpacity = findViewById(R.id.seekBgOpacity)
        tvBgOpacity = findViewById(R.id.tvBgOpacity)
        seekLineHeight = findViewById(R.id.seekLineHeight)
        tvLineHeight = findViewById(R.id.tvLineHeight)

        switchMirror = findViewById(R.id.switchMirror)
    }

    private fun loadSettings() {
        // 台词文本
        editScript.setText(prefs.getString("script_text", SAMPLE_TEXT))

        // 字体大小 (20-150, SeekBar 0-130)
        val fontSize = prefs.getInt("font_size", 50)
        seekFontSize.progress = fontSize - 20
        tvFontSize.text = fontSize.toString()

        // 字体颜色
        fontColor = prefs.getString("font_color", "#FFFFFF")!!
        colorFontPreview.setBackgroundColor(Color.parseColor(fontColor))

        // 背景颜色
        bgColor = prefs.getString("bg_color", "#000000")!!
        colorBgPreview.setBackgroundColor(Color.parseColor(bgColor))

        // 背景透明度
        val bgOpacity = prefs.getInt("bg_opacity", 80)
        seekBgOpacity.progress = bgOpacity
        tvBgOpacity.text = "$bgOpacity%"

        // 行高 (1.0-3.0, SeekBar 0-200, 实际值 = (progress+100)/100)
        val lineHeight = prefs.getInt("line_height", 180)
        seekLineHeight.progress = lineHeight - 100
        tvLineHeight.text = String.format("%.1f", lineHeight / 100.0)

        // 镜像
        switchMirror.isChecked = prefs.getBoolean("mirror", false)
    }

    private fun setupListeners() {
        // 文本变化监听
        editScript.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("script_text", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 字体大小
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 20
                tvFontSize.text = value.toString()
                prefs.edit().putInt("font_size", value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 字体颜色
        btnFontColor.setOnClickListener { showColorPicker("font") }

        // 背景颜色
        btnBgColor.setOnClickListener { showColorPicker("bg") }

        // 背景透明度
        seekBgOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBgOpacity.text = "$progress%"
                prefs.edit().putInt("bg_opacity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 行高
        seekLineHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 100
                tvLineHeight.text = String.format("%.1f", value / 100.0)
                prefs.edit().putInt("line_height", value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 镜像
        switchMirror.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mirror", isChecked).apply()
        }

        // 清空
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空文本")
                .setMessage("确定要清空所有文本吗？")
                .setPositiveButton("确定") { _, _ ->
                    editScript.text.clear()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 导入文件
        btnImport.setOnClickListener {
            filePicker.launch(arrayOf("text/*", "*/*"))
        }

        // 示例文本
        btnSample.setOnClickListener {
            showTemplatePicker()
        }

        // 启动悬浮窗
        btnStart.setOnClickListener {
            if (editScript.text.isBlank()) {
                Toast.makeText(this, "请先输入台词文本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isFloatingServiceRunning()) {
                stopFloatingService()
            } else {
                checkOverlayPermissionAndStart()
            }
        }
    }

    private fun showTemplatePicker() {
        val templateNames = TEMPLATES.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择台词模板")
            .setItems(templateNames) { _, which ->
                val (_, content) = TEMPLATES[which]
                editScript.setText(content)
                prefs.edit().putString("script_text", content).apply()
                Toast.makeText(this, "已加载模板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("自定义", null)
            .show()
    }

    private fun showColorPicker(type: String) {
        val colors = arrayOf(
            "#FFFFFF", "#FFFF00", "#00FF00", "#00FFFF",
            "#FF9500", "#FF3B30", "#FF00FF", "#007AFF",
            "#000000", "#8E8E93", "#34C759", "#5856D6"
        )
        val names = arrayOf(
            "白色", "黄色", "绿色", "青色",
            "橙色", "红色", "品红", "蓝色",
            "黑色", "灰色", "亮绿", "紫色"
        )

        AlertDialog.Builder(this)
            .setTitle(if (type == "font") "选择字体颜色" else "选择背景颜色")
            .setItems(names) { _, which ->
                val color = colors[which]
                if (type == "font") {
                    fontColor = color
                    colorFontPreview.setBackgroundColor(Color.parseColor(color))
                    prefs.edit().putString("font_color", color).apply()
                } else {
                    bgColor = color
                    colorBgPreview.setBackgroundColor(Color.parseColor(color))
                    prefs.edit().putString("bg_color", color).apply()
                }
            }
            .show()
    }

    private fun importFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                editScript.setText(text)
                prefs.edit().putString("script_text", text).apply()
                Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermissionAndStart() {
        // 检查麦克风权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMicrophonePermission()
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_title)
                    .setMessage(R.string.permission_msg)
                    .setPositiveButton(R.string.permission_btn) { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                startFloatingService()
            }
        } else {
            startFloatingService()
        }
    }

    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(this)
                .setTitle("需要麦克风权限")
                .setMessage("提词器需要麦克风权限来检测你的朗读声音，跟随朗读自动滚动字幕。")
                .setPositiveButton("授权") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE)
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "麦克风权限已授予", Toast.LENGTH_SHORT).show()
                checkOverlayPermissionAndStart()
            } else {
                Toast.makeText(this, "需要麦克风权限才能跟随朗读滚动", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                startFloatingService()
            } else {
                Toast.makeText(this, "权限未授予，无法启动悬浮窗", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingTeleprompterService::class.java)
        intent.action = FloatingTeleprompterService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonText()
        Toast.makeText(this, "悬浮提词器已启动", Toast.LENGTH_SHORT).show()
        // 返回桌面，让悬浮窗显示在其他应用上面
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingTeleprompterService::class.java)
        intent.action = FloatingTeleprompterService.ACTION_STOP
        startService(intent)
        updateButtonText()
    }

    private fun isFloatingServiceRunning(): Boolean {
        return FloatingTeleprompterService.isRunning
    }

    private fun updateButtonText() {
        if (isFloatingServiceRunning()) {
            btnStart.text = getString(R.string.btn_stop)
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "悬浮提词器运行中"
        } else {
            btnStart.text = getString(R.string.btn_start)
            tvStatus.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonText()
    }

    companion object {
        private const val OVERLAY_PERMISSION_CODE = 1001
        private const val MIC_PERMISSION_CODE = 1002

        const val SAMPLE_TEXT = """欢迎使用悬浮提词器！

这是你的第一条示例台词。

你可以：
1. 输入任意长度的文本
2. 调节字体大小、颜色和背景透明度
3. 开启语音跟随，字幕会跟着你的朗读自动滚动
4. 悬浮在任何应用上方，包括相机录像

操作提示：
• 点击播放按钮开始
• 朗读台词，字幕自动跟随
• 拖动顶部栏移动悬浮窗
• 点击最小化按钮缩小为图标

祝你录制顺利！"""

        val TEMPLATES = arrayOf(
            "自我介绍" to """大家好，我是[你的名字]，非常荣幸能够有机会在这里向大家介绍自己。

我来自[城市/地区]，目前从事[职业/行业]相关的工作。

在我的职业生涯中，我一直秉持着[核心理念/价值观]，致力于为[目标人群/客户]提供[价值/服务]。

今天能够站在这里，我希望能够[表达的目的/主题]，与大家共同探讨[话题/方向]。

谢谢大家的聆听！""",
            "演讲开场白" to """尊敬的各位来宾，女士们，先生们：

大家好！

非常感谢大家在百忙之中抽出宝贵的时间，来参加今天的[活动/会议名称]。

我是今天的主讲人[你的名字/身份]，今天我演讲的主题是——[演讲主题]。

在接下来的[时间]里，我将从以下[数量]个方面，与大家分享我的见解和思考：

首先，[第一点/方面]；
其次，[第二点/方面]；
最后，[第三点/方面]。

希望今天的分享能够给大家带来启发和收获。""",
            "短视频开场白" to """嘿，屏幕前的你好呀！

欢迎来到我的频道！

如果你是第一次刷到我的视频，记得点个关注，下期视频更精彩！

今天我要和大家分享的是——[视频主题]。

在开始之前，先给大家[简单介绍/看点预告]。

准备好了吗？我们开始吧！""",
            "产品介绍" to """大家好，今天给大家推荐一款非常好用的产品——[产品名称]。

[产品名称]是一款[产品类型/定位]，它能够[核心功能/价值]。

接下来我将从以下几个方面为大家详细介绍：

第一，[卖点1/功能介绍]。[详细描述]

第二，[卖点2/功能介绍]。[详细描述]

第三，[卖点3/功能介绍]。[详细描述]

与同类产品相比，[产品名称]的优势在于[核心优势]。

如果你也对这款产品感兴趣，欢迎通过[购买渠道/联系方式]了解更多详情！""",
            "直播开场" to """哈喽，直播间的宝宝们，大家晚上好呀！

欢迎来到今天的直播间！

我是今天的主播[你的名字]，记得点亮关注，粉丝团走一走！

今天我们要聊的话题是——[直播主题]。

在直播过程中，大家可以在评论区积极互动，有问题随时提问，我都会一一解答！

接下来，让我们正式进入今天的话题，[引入主题内容]！""",
            "古诗朗诵" to """《[诗名]》
[作者]

[第一句]
[第二句]
[第三句]
[第四句]

[第五句]
[第六句]
[第七句]
[第八句]

[注释/赏析]""",
            "新闻播报" to """观众朋友们大家好，欢迎收看今天的新闻联播。

首先是今日头条：

[新闻标题1]
[新闻内容1]

[新闻标题2]
[新闻内容2]

[新闻标题3]
[新闻内容3]

接下来是国际新闻：

[国际新闻内容]

最后是体育新闻：

[体育新闻内容]

以上就是今天新闻的全部内容，感谢大家的收看！""",
            "示例台词" to """欢迎使用悬浮提词器！

这是你的第一条示例台词。

你可以：
1. 输入任意长度的文本
2. 调节字体大小、颜色和背景透明度
3. 开启语音跟随，字幕会跟着你的朗读自动滚动
4. 悬浮在任何应用上方，包括相机录像

操作提示：
• 点击播放按钮开始
• 朗读台词，字幕自动跟随
• 拖动顶部栏移动悬浮窗
• 点击最小化按钮缩小为图标

祝你录制顺利！"""
        )
    }
}
