package com.yl.aigg.ai_gg666

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    private enum class LlmApiFormat {
        OPENAI_CHAT_COMPLETIONS,
        OPENAI_RESPONSES,
        ANTHROPIC_MESSAGES;

        companion object {
            fun fromValue(value: String?): LlmApiFormat {
                return when (value) {
                    "openai_responses" -> OPENAI_RESPONSES
                    "anthropic_messages" -> ANTHROPIC_MESSAGES
                    else -> OPENAI_CHAT_COMPLETIONS
                }
            }
        }
    }

    private data class LlmRuntimeConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val apiFormat: LlmApiFormat,
        val temperature: Double,
        val maxTokens: Int,
        val timeoutSeconds: Int,
        val streamEnabled: Boolean,
    )

    private data class ToolCallSpec(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    )

    private data class PendingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // 搜索状态
    private var searchResults: List<Map<String, Any>> = emptyList()
    private var searchDataType = "dword"
    private val selectedIndices = mutableSetOf<Int>() // 选中的结果索引
    // 搜索输入框的值（保持不丢失）
    private var savedSearchInput = ""
    private var savedFilterInput = ""
    private var savedRangeMin = ""
    private var savedRangeMax = ""
    private var savedScrollY = 0

    // AI 对话历史（持久化在内存中，防止切换后消失）
    private val chatMessages = mutableListOf<Pair<String, String>>() // (sender, message)
    private var isAiResponding = false

    // 记住上次打开的面板
    private var lastPanel = ""
    private var overlayShowSystemProcesses = false

    private val overlayTextPrimary = Color.parseColor("#F7F2FA")
    private val overlayTextSecondary = Color.parseColor("#DDD1ED")
    private val overlayTextMuted = Color.parseColor("#B3A7C4")
    private val overlaySurface = Color.parseColor("#4A4458")
    private val overlaySurfaceRaised = Color.parseColor("#5A536A")
    private val overlaySurfaceCard = Color.parseColor("#696178")
    private val overlaySurfaceCardAlt = Color.parseColor("#564F65")
    private val overlayStroke = Color.parseColor("#7A7289")
    private val overlayAccent = Color.parseColor("#CBB9E3")
    private val overlayAccentStrong = Color.parseColor("#F1E7FF")
    private val overlaySuccess = Color.parseColor("#4ADE80")
    private val overlayWarning = Color.parseColor("#F6C45E")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NativeToolApi.initialize(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBall()
        // 恢复上次打开的面板
        lastPanel = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).getString("last_panel", "") ?: ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        isRunning = false
        removeBall()
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "GG-AI 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("GG-AI").setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("GG-AI").setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        }
    }

    private fun alphaColor(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun parseUiColor(hex: String, fallback: Int = overlayAccent): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun panelShellDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(overlaySurface, 248))
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), alphaColor(accentColor, 120))
        }
    }

    private fun panelHeaderDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(overlaySurfaceRaised, 252))
            cornerRadii = floatArrayOf(
                dp(16).toFloat(), dp(16).toFloat(),
                dp(16).toFloat(), dp(16).toFloat(),
                0f, 0f,
                0f, 0f
            )
            setStroke(dp(1), alphaColor(accentColor, 90))
        }
    }

    private fun panelContentDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                dp(16).toFloat(), dp(16).toFloat(),
                dp(16).toFloat(), dp(16).toFloat()
            )
            setColor(alphaColor(overlaySurfaceRaised, 246))
        }
    }

    private fun cardDrawable(
        accentColor: Int = overlayAccent,
        emphasized: Boolean = false,
        compact: Boolean = false
    ): GradientDrawable {
        val fill = if (emphasized) overlaySurfaceCard else overlaySurfaceCardAlt
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(if (compact) 10 else 12).toFloat()
            setStroke(dp(1), alphaColor(accentColor, if (emphasized) 120 else 82))
        }
    }

    private fun filledButtonDrawable(accentColor: Int = overlayAccent, compact: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(accentColor, 212))
            cornerRadius = dp(if (compact) 8 else 10).toFloat()
            setStroke(dp(1), alphaColor(Color.WHITE, 72))
        }
    }

    private fun softButtonDrawable(accentColor: Int = overlayAccent, active: Boolean = false, compact: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (active) alphaColor(accentColor, 56) else overlaySurfaceCardAlt)
            cornerRadius = dp(if (compact) 8 else 10).toFloat()
            setStroke(dp(1), alphaColor(accentColor, if (active) 140 else 92))
        }
    }

    private fun inputDrawable(accentColor: Int = overlayAccent): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(overlaySurfaceCardAlt, 244))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), alphaColor(accentColor, 120))
        }
    }

    private fun railStripDrawable(accentColor: Int = overlayAccent): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(overlaySurfaceCardAlt, 252))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), alphaColor(accentColor, 86))
        }
    }

    private fun railNavDrawable(accentColor: Int = overlayAccent, selected: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (selected) alphaColor(accentColor, 64) else Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), alphaColor(accentColor, if (selected) 130 else 56))
        }
    }

    private fun chipDrawable(fillColor: Int, strokeColor: Int = fillColor, radiusDp: Int = 7): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun panelHandleDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(alphaColor(overlayTextPrimary, 116))
            cornerRadius = dp(999).toFloat()
        }
    }

    private fun overlayDivider(accentColor: Int = overlayAccent): View {
        return View(this).apply {
            setBackgroundColor(alphaColor(accentColor, 64))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }
    }

    private fun styleInputField(editText: EditText, accentColor: Int = overlayAccent) {
        editText.setTextColor(overlayTextPrimary)
        editText.setHintTextColor(overlayTextMuted)
        editText.background = inputDrawable(accentColor)
        editText.setPadding(dp(10), dp(8), dp(10), dp(8))
        editText.textSize = 13f
    }

    private fun styleSpinner(spinner: Spinner, accentColor: Int = overlayAccent) {
        spinner.background = inputDrawable(accentColor)
        spinner.setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun themedSpinnerAdapter(items: Array<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(overlayTextPrimary)
                    textSize = 12f
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(overlayTextPrimary)
                    textSize = 12f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = cardDrawable(overlayAccent, compact = true)
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun buildInfoCard(title: String, subtitle: String, accentColor: Int = overlayAccent): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(accentColor, emphasized = true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(overlayTextPrimary)
                textSize = 13f
            })
            addView(TextView(this@OverlayService).apply {
                text = subtitle
                setTextColor(overlayTextSecondary)
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun buildPanelHandle(widthDp: Int = 28): View {
        return View(this).apply {
            background = panelHandleDrawable()
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        }
    }

    private fun attachPanelDrag(handle: View) {
        var pix = 0
        var piy = 0
        var ptx = 0f
        var pty = 0f
        var isDragging = false
        val dm = resources.displayMetrics

        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    pix = panelParams?.x ?: 0
                    piy = panelParams?.y ?: 0
                    ptx = e.rawX
                    pty = e.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    isDragging = true
                    val dx = (e.rawX - ptx).toInt()
                    val dy = (e.rawY - pty).toInt()
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val panelW = panelParams?.width ?: 0
                    val panelH = panelParams?.height ?: 0

                    val newX = (pix + dx).coerceIn(-panelW / 2, screenW - panelW / 2)
                    val newY = (piy + dy).coerceIn(0, screenH - panelH)

                    panelParams?.x = newX
                    panelParams?.y = newY
                    try {
                        wm?.updateViewLayout(panel, panelParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    private fun compactChip(
        text: String,
        textColor: Int,
        fillColor: Int,
        strokeColor: Int = alphaColor(fillColor, 180),
        mono: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 9f
            background = chipDrawable(fillColor, strokeColor)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            if (mono) typeface = Typeface.MONOSPACE
        }
    }

    private fun searchTypeAccent(type: String): Int {
        return when (type.lowercase()) {
            "float" -> Color.parseColor("#8EC5FF")
            "double" -> Color.parseColor("#BFA7FF")
            "byte" -> Color.parseColor("#7DD3A6")
            "word" -> Color.parseColor("#E3C06A")
            "qword" -> Color.parseColor("#F59DB0")
            "aob" -> Color.parseColor("#F1C27D")
            "addr" -> Color.parseColor("#B5C0D0")
            else -> Color.parseColor("#B7D7FF")
        }
    }

    private fun formatTypedValue(value: Any?, type: String): String {
        val suffix = when (type.lowercase()) {
            "byte" -> "B;"
            "word" -> "W;"
            "dword" -> "D;"
            "qword" -> "Q;"
            "float" -> "F;"
            "double" -> "E;"
            "aob" -> ";"
            else -> ";"
        }
        val rendered = when (value) {
            is Float -> String.format("%.4f", value)
            is Double -> String.format("%.6f", value)
            else -> value?.toString() ?: "?"
        }
        return "$rendered$suffix"
    }

    private fun createSearchSection(title: String, subtitle: String? = null, accentColor: Int = overlayAccent): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(accentColor, emphasized = true)
            setPadding(dp(10), dp(9), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(overlayTextPrimary)
                textSize = 12f
            })
            if (!subtitle.isNullOrEmpty()) {
                addView(TextView(this@OverlayService).apply {
                    text = subtitle
                    setTextColor(overlayTextSecondary)
                    textSize = 10f
                    setPadding(0, dp(3), 0, dp(8))
                })
            }
        }
    }

    private fun buildSearchModeStrip(): View {
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }

        fun modeChip(label: String, mode: String): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(overlayTextPrimary)
                textSize = 10f
                gravity = Gravity.CENTER
                background = railNavDrawable(overlayAccent, selected = currentSearchMode == mode)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    currentSearchMode = mode
                    showSearchPanel()
                }
            }
        }

        row.addView(modeChip("精确", "exact"))
        row.addView(modeChip("模糊", "fuzzy"))
        row.addView(modeChip("范围", "range"))
        row.addView(modeChip("地址", "addr"))
        row.addView(modeChip("机器码", "machine"))
        scroll.addView(row)
        return scroll
    }

    // ==================== 悬浮球 ====================

    private fun createBall() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ballView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(alphaColor(overlaySurfaceRaised, 246))
                setStroke(dp(1), alphaColor(overlayAccentStrong, 110))
            }
            elevation = dp(10).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))

            addView(View(this@OverlayService).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(alphaColor(overlaySurface, 232))
                    setStroke(dp(1), alphaColor(Color.WHITE, 46))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })

            addView(View(this@OverlayService).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(alphaColor(overlayAccentStrong, 32))
                }
                layoutParams = FrameLayout.LayoutParams(dp(30), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(5)
                }
            })

            addView(ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.xfc)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(overlayAccentStrong)
                layoutParams = FrameLayout.LayoutParams(
                    dp(26),
                    dp(26),
                    Gravity.CENTER
                )
            })
        }
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        ballParams = WindowManager.LayoutParams(
            dp(50),
            dp(50),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { 
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            x = prefs.getInt("ball_x", 0)
            y = prefs.getInt("ball_y", dp(200))
        }

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        ballView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = ballParams?.x ?: 0; iy = ballParams?.y ?: 0; tx = e.rawX; ty = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - tx) > 10 || kotlin.math.abs(e.rawY - ty) > 10) dragging = true
                    ballParams?.x = ix + (e.rawX - tx).toInt(); ballParams?.y = iy + (e.rawY - ty).toInt()
                    getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
                        .putInt("ball_x", ballParams?.x ?: 0)
                        .putInt("ball_y", ballParams?.y ?: dp(200))
                        .apply()
                    try { wm?.updateViewLayout(ballView, ballParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) showLastOrMainMenu(); true }
                else -> false
            }
        }
        try { wm?.addView(ballView, ballParams) } catch (_: Exception) {}
    }

    private fun removeBall() {
        closePanel()
        try { ballView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        ballView = null
    }

    // ==================== 面板管理 ====================

    private fun saveLastPanel(name: String) {
        lastPanel = name
        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit().putString("last_panel", name).apply()
    }

    private fun closePanel() {
        // 关闭前保存滚动位置（仅当面板有 ScrollView 时才更新，避免菜单面板覆盖搜索面板的滚动位置）
        try {
            fun findScrollView(v: android.view.View): android.widget.ScrollView? {
                if (v is android.widget.ScrollView) return v
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val found = findScrollView(v.getChildAt(i))
                        if (found != null) return found
                    }
                }
                return null
            }
            panel?.let { findScrollView(it)?.let { sv -> if (sv.scrollY > 0) savedScrollY = sv.scrollY } }
        } catch (_: Exception) {}
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}
        panel = null; panelParams = null
    }

    private fun showPanel(view: View, w: Int = 280, h: Int = 400) {
        closePanel()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val panelW = dp(w).coerceAtMost(screenW - dp(20))
        val panelH = dp(h).coerceAtMost(screenH - dp(20))
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        panelParams = WindowManager.LayoutParams(
            panelW,
            panelH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2
            y = (screenH - panelH) / 2
        }
        panel = view
        try { wm?.addView(panel, panelParams) } catch (_: Exception) {}
    }
    
    // 创建可获得焦点的面板（用于输入法）
    private fun showFocusablePanel(view: View, w: Int = 280, h: Int = 400) {
        closePanel()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val panelW = dp(w).coerceAtMost(screenW - dp(20))
        val panelH = dp(h).coerceAtMost(screenH - dp(20))
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        panelParams = WindowManager.LayoutParams(
            panelW,
            panelH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2
            y = (screenH - panelH) / 2
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        panel = view
        try { wm?.addView(panel, panelParams) } catch (_: Exception) {}
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    // ==================== 可拖动面板包装 ====================

    private fun makeDraggablePanel(title: String, contentBuilder: (LinearLayout) -> Unit, w: Int = 280, h: Int = 400, onBack: (() -> Unit)? = null, titleIcon: Int? = null, bgColor: String = "#FDFBF7") {
        val accentColor = parseUiColor(bgColor, overlayAccent)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelShellDrawable(accentColor)
            elevation = dp(12).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val topHandle = buildPanelHandle()
        root.addView(topHandle)

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = 0
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelHeaderDrawable(accentColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        if (titleIcon != null) {
            titleBar.addView(ImageView(this).apply {
                setImageResource(titleIcon)
                setColorFilter(overlayAccentStrong)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            })
        }
        val titleText = TextView(this).apply {
            text = title
            setTextColor(overlayTextPrimary)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        if (title != "GG-AI Modifier") {
            titleBar.addView(TextView(this).apply {
                text = "◂"
                setTextColor(overlayTextPrimary)
                textSize = 14f
                gravity = Gravity.CENTER
                background = softButtonDrawable(accentColor, active = true, compact = true)
                minWidth = dp(34)
                minHeight = dp(30)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onBack?.invoke() ?: showMainMenu() }
            })
        }

        root.addView(titleBar)

        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            background = panelContentDrawable()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        contentBuilder(contentArea)
        root.addView(contentArea)

        attachPanelDrag(topHandle)
        attachPanelDrag(titleBar)

        // 根据面板类型选择显示方式
        if (title == "AI 对话" || title == "🤖 AI 对话") {
            showFocusablePanel(root, w, h)
        } else {
            showPanel(root, w, h)
        }
    }

    // ==================== 主菜单 ====================

    private fun showLastOrMainMenu() {
        when (lastPanel) {
            "process" -> showProcessPanel()
            "search" -> showSearchPanel()
            "chat" -> showAIChatPanel()
            "script" -> showScriptPanel()
            else -> showMainMenu()
        }
    }

    private fun showMainMenu() {
        saveLastPanel("menu")
        makeDraggablePanel("GG-AI Modifier", { content ->
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = railStripDrawable()
                layoutParams = LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(8)
                }
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }

            fun railItem(iconRes: Int, label: String, selected: Boolean = false, onClick: () -> Unit): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = railNavDrawable(selected = selected)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener { onClick() }
                    addView(ImageView(this@OverlayService).apply {
                        setImageResource(iconRes)
                        setColorFilter(if (selected) overlayAccentStrong else overlayTextSecondary)
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    })
                    addView(TextView(this@OverlayService).apply {
                        text = label
                        setTextColor(if (selected) overlayTextPrimary else overlayTextSecondary)
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(5), 0, 0)
                    })
                }
            }

            rail.addView(railItem(R.drawable.xfc, "主页", selected = true) {})
            rail.addView(railItem(R.drawable.jingcheng, "进程") { showProcessPanel() })
            rail.addView(railItem(R.drawable.neichun, "搜索") { showSearchPanel() })
            rail.addView(railItem(R.drawable.ai, "AI") { showAIChatPanel() })
            rail.addView(railItem(R.drawable.jiaoben, "脚本") { showScriptPanel() })
            rail.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            })
            rail.addView(railItem(R.drawable.gb_xfc, "退出") { stopSelf() })
            body.addView(rail)

            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
            }
            right.addView(buildInfoCard("悬浮控制台", "附加进程、搜索数值、AI 工具联动", overlayAccent))

            val sv = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            list.addView(menuBtn("附加进程", R.drawable.jingcheng) { showProcessPanel() })
            list.addView(menuBtn("内存搜索", R.drawable.neichun) { showSearchPanel() })
            list.addView(menuBtn("AI 对话", R.drawable.ai) { showAIChatPanel() })
            list.addView(menuBtn("脚本库", R.drawable.jiaoben) { showScriptPanel() })
            sv.addView(list)
            right.addView(sv)

            val bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = railStripDrawable()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            bottomBar.addView(TextView(this).apply {
                text = "GG-AI / Rail Overlay"
                setTextColor(overlayTextSecondary)
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottomBar.addView(iconBtn(R.drawable.ck_gb) { closePanel() })
            right.addView(bottomBar)

            body.addView(right)
            content.addView(body)
        }, 320, 430, titleIcon = R.drawable.xfc, bgColor = "#CBB9E3")
    }

    // ==================== 进程面板 ====================

    private fun showProcessPanel() {
        saveLastPanel("process")
        makeDraggablePanel("选择游戏进程", { content ->
            val status = TextView(this).apply { text = "正在扫描..."; setTextColor(overlayTextPrimary); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(6)) }
            content.addView(status)

            val sv = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
            sv.addView(list)

            val toggleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), dp(8))
                background = cardDrawable(parseUiColor("#4DD0E1"), compact = true)
            }
            toggleRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@OverlayService).apply {
                    text = "显示系统/预装应用"
                    setTextColor(overlayTextPrimary)
                    textSize = 12f
                })
                addView(TextView(this@OverlayService).apply {
                    text = "默认只显示用户安装的第三方应用"
                    setTextColor(overlayTextSecondary)
                    textSize = 10f
                    setPadding(0, dp(2), 0, 0)
                })
            })
            val systemSwitch = Switch(this).apply {
                isChecked = overlayShowSystemProcesses
                setOnCheckedChangeListener { _, isChecked ->
                    overlayShowSystemProcesses = isChecked
                    loadProcs(list, status)
                }
            }
            toggleRow.addView(systemSwitch)
            content.addView(toggleRow)
            content.addView(sv)

            // 底部按钮
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(6), dp(12), dp(10))
                background = cardDrawable(parseUiColor("#4DD0E1"), compact = true)
            }
            bar.addView(iconBtn(R.drawable.shuaxing) { loadProcs(list, status) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)

            loadProcs(list, status)
        }, 316, 470, titleIcon = R.drawable.jingcheng, bgColor = "#4DD0E1")
    }

    private fun loadProcs(list: LinearLayout, status: TextView) {
        status.text = "正在扫描..."; list.removeAllViews()
        Thread {
            val procs = ProcessManager.getProcessList(
                this@OverlayService,
                includeSystem = overlayShowSystemProcesses
            )
            handler.post {
                status.text = "找到 ${procs.size} 个" + if (overlayShowSystemProcesses) "进程" else "第三方应用"
                for (proc in procs) {
                    val name = proc["processName"] as String; val pkg = proc["packageName"] as String; val pid = proc["pid"] as Int
                    val icon = ProcessManager.getAppIconDrawable(this@OverlayService, pkg)
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = cardDrawable(parseUiColor("#4DD0E1"), emphasized = true)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    item.addView(createProcessIconView(icon))
                    item.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(10)
                        }
                        addView(TextView(this@OverlayService).apply {
                            text = name
                            setTextColor(overlayTextPrimary)
                            textSize = 14f
                        })
                        addView(TextView(this@OverlayService).apply {
                            text = "$pkg | PID: $pid"
                            setTextColor(overlayTextSecondary)
                            textSize = 11f
                        })
                        if ((proc["isSystem"] as? Boolean) == true) {
                            addView(TextView(this@OverlayService).apply {
                                text = if ((proc["isPreinstalled"] as? Boolean) == true) "预装/系统应用" else "系统进程"
                                setTextColor(overlayWarning)
                                textSize = 10f
                                setPadding(0, dp(4), 0, 0)
                            })
                        }
                    })
                    item.setOnClickListener {
                        Thread {
                            val ok = MemoryEngine.attachProcess(pid)
                            handler.post {
                                status.text = if (ok) "✅ 已附加: $name" else "❌ 附加失败"
                                // 附加成功后，通知主应用更新状态，并重置搜索结果
                                if (ok) {
                                    saveAttachedProcess(pid, pkg, name)
                                    searchResults = emptyList()
                                }
                            }
                        }.start()
                    }
                    list.addView(item)
                }
            }
        }.start()
    }

    private fun createProcessIconView(icon: Drawable?): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = softButtonDrawable(parseUiColor("#4DD0E1"), compact = true)
            if (icon != null) {
                addView(ImageView(this@OverlayService).apply {
                    setImageDrawable(icon)
                    layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            } else {
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(android.R.drawable.sym_def_app_icon)
                    setColorFilter(overlayAccentStrong)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })
            }
        }
    }
    
    // 保存附加的进程信息，供主应用读取
    private fun saveAttachedProcess(pid: Int, packageName: String, processName: String) {
        try {
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("attached_pid", pid)
                putString("attached_package", packageName)
                putString("attached_name", processName)
                putLong("attached_time", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 搜索面板 ====================

    private var currentSearchMode = "exact"

    private fun currentSearchModeTitle(): String {
        return when (currentSearchMode) {
            "exact" -> "精确搜索"
            "fuzzy" -> "模糊搜索"
            "range" -> "范围扫描"
            "addr" -> "地址读取"
            "machine" -> "机器码搜索"
            else -> "内存搜索"
        }
    }

    private fun currentSearchModeSubtitle(): String {
        return when (currentSearchMode) {
            "exact" -> "输入目标值后扫描，可继续在已有结果上过滤"
            "fuzzy" -> "先建立快照，再比较数值变化趋势"
            "range" -> "按最小值和最大值区间检索候选内存"
            "addr" -> "直接读取指定地址附近的数据表现"
            "machine" -> "按 AOB / 通配机器码模式匹配地址"
            else -> "搜索内存中的可疑数据"
        }
    }

    private fun renderSearchEmptyState(container: LinearLayout, message: String = "暂无结果") {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = message
            setTextColor(overlayTextMuted)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(28), dp(10), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    private fun showSearchPanel() {
        saveLastPanel("search")
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.density
        val isLandscape = screenW > screenH

        val panelWDp = if (isLandscape) ((screenW * 0.88f) / density).toInt().coerceIn(650, 900) else 320
        val panelHDp = if (isLandscape) ((screenH * 0.93f) / density).toInt().coerceIn(380, 550) else 520

        makeDraggablePanel("内存搜索", { content ->
            val pid = MemoryEngine.getAttachedPid()
            val types = arrayOf("dword", "float", "double", "byte", "word", "qword")

            if (isLandscape) {
                val mainRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }

                val leftPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = railStripDrawable()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f).apply {
                        marginEnd = dp(8)
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }

                val status = TextView(this).apply {
                    text = if (pid != null) "PID $pid 已附加" else "未附加进程"
                    setTextColor(if (pid != null) overlaySuccess else overlayWarning)
                    textSize = 11f
                }
                val typeSpinner = Spinner(this).apply {
                    adapter = themedSpinnerAdapter(types)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                styleSpinner(typeSpinner)

                val rightPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = railStripDrawable()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                val actionBarContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    tag = "search_action_bar_container"
                }
                val rsv = ScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val rl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                rsv.addView(rl)

                val statusSection = createSearchSection("搜索上下文", "附加进程与数据类型").apply {
                    val row = LinearLayout(this@OverlayService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(typeSpinner)
                    addView(row)
                }
                leftPanel.addView(statusSection)
                leftPanel.addView(buildSearchModeStrip())

                val inputSection = createSearchSection(currentSearchModeTitle(), currentSearchModeSubtitle())
                buildSearchInputArea(inputSection, status, typeSpinner, rl, 0)
                leftPanel.addView(inputSection)

                leftPanel.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                })

                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = cardDrawable(overlayAccent, compact = true)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                bar.addView(iconBtn(R.drawable.shuaxing) {
                    searchResults = emptyList()
                    selectedIndices.clear()
                    status.text = "已重置"
                    renderSearchEmptyState(rl)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                leftPanel.addView(bar)
                mainRow.addView(leftPanel)

                rightPanel.addView(TextView(this).apply {
                    text = "搜索结果"
                    setTextColor(overlayTextSecondary)
                    textSize = 11f
                    setPadding(dp(2), 0, 0, dp(6))
                })
                rightPanel.addView(actionBarContainer)

                if (searchResults.isNotEmpty()) {
                    status.text = "${searchResults.size} 个结果"
                    updateSearchResults(rl, searchResults, actionBarContainer)
                } else {
                    renderSearchEmptyState(rl)
                }

                rightPanel.addView(rsv)
                if (savedScrollY > 0) {
                    rsv.post { rsv.scrollY = savedScrollY; savedScrollY = 0 }
                }
                mainRow.addView(rightPanel)

                content.addView(mainRow)
                content.tag = rl
            } else {
                val status = TextView(this).apply {
                    text = if (pid != null) "PID $pid 已附加" else "未附加进程"
                    setTextColor(if (pid != null) overlaySuccess else overlayWarning)
                    textSize = 11f
                }
                val typeSpinner = Spinner(this).apply {
                    adapter = themedSpinnerAdapter(types)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                styleSpinner(typeSpinner)

                val rl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val actionBarContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    tag = "search_action_bar_container"
                }

                val statusSection = createSearchSection("搜索上下文", "附加进程与数据类型").apply {
                    val row = LinearLayout(this@OverlayService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(typeSpinner)
                    addView(row)
                }
                content.addView(statusSection)
                content.addView(buildSearchModeStrip())

                val inputSection = createSearchSection(currentSearchModeTitle(), currentSearchModeSubtitle())
                buildSearchInputArea(inputSection, status, typeSpinner, rl, 0)
                content.addView(inputSection)
                content.addView(actionBarContainer)

                val rsv = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
                rsv.addView(rl)
                content.tag = rl
                if (searchResults.isNotEmpty()) {
                    status.text = "${searchResults.size} 个结果"
                    updateSearchResults(rl, searchResults, actionBarContainer)
                } else {
                    renderSearchEmptyState(rl)
                }
                content.addView(rsv)
                if (savedScrollY > 0) {
                    rsv.post { rsv.scrollY = savedScrollY; savedScrollY = 0 }
                }

                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = cardDrawable(overlayAccent, compact = true)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                bar.addView(iconBtn(R.drawable.shuaxing) {
                    searchResults = emptyList()
                    selectedIndices.clear()
                    status.text = "已重置"
                    renderSearchEmptyState(rl)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(bar)
            }
        }, panelWDp, panelHDp, titleIcon = R.drawable.neichun, bgColor = "#CBB9E3")
    }

    private fun buildSearchInputArea(parent: LinearLayout, status: TextView, typeSpinner: Spinner, rl: LinearLayout?, inputPadding: Int) {
        fun getResultList(): LinearLayout? = rl ?: (parent.tag as? LinearLayout)
        fun fieldLabel(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(overlayTextSecondary)
                textSize = 10f
                setPadding(inputPadding, dp(2), inputPadding, dp(4))
            }
        }
        fun inputRow(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(inputPadding, dp(2), inputPadding, dp(2))
            }
        }
        fun buttonLp(weight: Float = 0f): LinearLayout.LayoutParams {
            return if (weight > 0f) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    marginEnd = dp(6)
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
            }
        }

        when (currentSearchMode) {
            "exact" -> {
                parent.addView(fieldLabel("目标值"))
                val row = inputRow()
                val inp = EditText(this).apply {
                    hint = "输入数值"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                styleInputField(inp)
                row.addView(inp)
                row.addView(smallBtn("搜索") {
                    val v = inp.text.toString()
                    if (v.isEmpty()) { status.text = "❌请输入数值"; return@smallBtn }
                    savedSearchInput = v
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    val dtype = typeSpinner.selectedItem.toString(); searchDataType = dtype
                    status.text = "搜索中..."
                    val resultList = getResultList()
                    resultList?.let { renderSearchEmptyState(it, "扫描中...") }
                    searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val nv: Any = if (dtype == "float" || dtype == "double") v.toDoubleOrNull() ?: 0.0 else v.toLongOrNull() ?: 0
                        val res = MemoryEngine.searchExact(nv, dtype)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, buttonLp())
                parent.addView(row)

                parent.addView(fieldLabel("过滤新值"))
                val fRow = inputRow()
                val fInp = EditText(this).apply {
                    hint = "新值(过滤)"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedFilterInput)
                }
                styleInputField(fInp)
                fRow.addView(fInp)
                fRow.addView(smallBtn("过滤") {
                    val v = fInp.text.toString()
                    if (v.isEmpty() || searchResults.isEmpty()) { status.text = "❌请先搜索"; return@smallBtn }
                    savedFilterInput = v
                    status.text = "过滤中..."
                    Thread {
                        val t = System.currentTimeMillis()
                        val nv: Any = if (searchDataType == "float" || searchDataType == "double") v.toDoubleOrNull() ?: 0.0 else v.toLongOrNull() ?: 0
                        val res = MemoryEngine.filterResults(searchResults.mapNotNull { (it["addressInt"] as? Number)?.toLong() }, nv, searchDataType)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, buttonLp())
                parent.addView(fRow)
            }
            "fuzzy" -> {
                parent.addView(fieldLabel("首次使用先建立快照"))
                val primeRow = inputRow()
                primeRow.addView(smallBtn("全扫描建快照") {
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    val dtype = typeSpinner.selectedItem.toString()
                    searchDataType = dtype
                    status.text = "全扫描中..."
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.primeFuzzySnapshot(dtype)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                parent.addView(primeRow)
                parent.addView(fieldLabel("变化比较"))
                val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                fun fuzzyBtn(label: String, cmp: String, color: String): Button {
                    return Button(this).apply {
                        text = label; textSize = 10f; setTextColor(overlayTextPrimary)
                        background = filledButtonDrawable(parseUiColor(color), compact = true)
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(4)
                            bottomMargin = dp(4)
                        }
                        setOnClickListener {
                            if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@setOnClickListener }
                            val dtype = typeSpinner.selectedItem.toString(); searchDataType = dtype
                            status.text = "模糊比较中..."
                            Thread {
                                try {
                                    val t = System.currentTimeMillis()
                                    val res = MemoryEngine.searchFuzzy(cmp, dtype)
                                    searchResults = res
                                    notifySearchComplete(res.size, t)
                                } catch (_: Exception) {}
                            }.start()
                        }
                    }
                }
                val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r1.addView(fuzzyBtn("变大", "increased", "#CE7A72"))
                r1.addView(fuzzyBtn("变小", "decreased", "#82A06D"))
                r2.addView(fuzzyBtn("没变", "unchanged", "#7A8AB3"))
                r2.addView(fuzzyBtn("改变", "changed", "#B78B63"))
                grid.addView(r1)
                grid.addView(r2)
                parent.addView(grid)
            }
            "range" -> {
                parent.addView(fieldLabel("最小值 ~ 最大值"))
                val row = inputRow()
                val minInp = EditText(this).apply {
                    hint = "Min"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedRangeMin)
                }
                styleInputField(minInp)
                val maxInp = EditText(this).apply {
                    hint = "Max"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedRangeMax)
                }
                styleInputField(maxInp)
                row.addView(minInp)
                row.addView(TextView(this).apply { text = "~"; setTextColor(overlayTextSecondary); setPadding(dp(4), dp(6), dp(4), dp(6)) })
                row.addView(maxInp)
                row.addView(smallBtn("扫描") {
                    val lo = minInp.text.toString().toLongOrNull()
                    val hi = maxInp.text.toString().toLongOrNull()
                    if (lo == null || hi == null) { status.text = "❌输入范围"; return@smallBtn }
                    savedRangeMin = minInp.text.toString()
                    savedRangeMax = maxInp.text.toString()
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    val dtype = typeSpinner.selectedItem.toString(); searchDataType = dtype
                    status.text = "范围扫描..."
                    val resultList = getResultList()
                    resultList?.let { renderSearchEmptyState(it, "扫描中...") }
                    searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchByRange(lo, hi, dtype)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, buttonLp())
                parent.addView(row)
            }
            "addr" -> {
                parent.addView(fieldLabel("十六进制地址"))
                val row = inputRow()
                val inp = EditText(this).apply {
                    hint = "0x728B3A4D"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                styleInputField(inp)
                row.addView(inp)
                row.addView(smallBtn("读取") {
                    val addr = inp.text.toString().trim()
                    if (addr.isEmpty()) { status.text = "❌输入地址"; return@smallBtn }
                    savedSearchInput = addr
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    searchDataType = "addr"
                    status.text = "读取中..."
                    val resultList = getResultList()
                    resultList?.let { renderSearchEmptyState(it, "读取中...") }
                    searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchAob(addr)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, buttonLp())
                parent.addView(row)
            }
            "machine" -> {
                parent.addView(fieldLabel("AOB / 通配模式"))
                val row = inputRow()
                val inp = EditText(this).apply {
                    hint = "48 89 5C 24 ?? CC"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                styleInputField(inp)
                row.addView(inp)
                row.addView(smallBtn("扫描") {
                    val pattern = inp.text.toString().trim()
                    if (pattern.isEmpty()) { status.text = "❌输入机器码"; return@smallBtn }
                    savedSearchInput = pattern
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    searchDataType = "aob"
                    status.text = "扫描中..."
                    val resultList = getResultList()
                    resultList?.let { renderSearchEmptyState(it, "扫描中...") }
                    searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchAob(pattern)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                }, buttonLp())
                parent.addView(row)
            }
        }
    }
    
    // 搜索完成通知：尝试更新当前面板UI，若面板已关闭则等重新打开时自动显示结果
    private fun notifySearchComplete(count: Int, startTime: Long) {
        val elapsed = String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0)
        try {
            handler.post {
                try {
                    // 尝试更新当前面板的UI
                    refreshCurrentPanel()
                    Toast.makeText(this, "${count}个结果 ${elapsed}s", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // 刷新当前搜索面板的结果显示
    private fun refreshCurrentPanel() {
        val content = panel as? android.view.ViewGroup ?: return
        // 找到 ScrollView 中的 rl（结果列表容器）
        fun findResultList(v: android.view.ViewGroup): LinearLayout? {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child is android.widget.ScrollView) {
                    val inner = child.getChildAt(0)
                    if (inner is LinearLayout) return inner
                }
                if (child is android.view.ViewGroup) {
                    val found = findResultList(child)
                    if (found != null) return found
                }
            }
            return null
        }
        // 找到操作栏容器
        fun findActionBarContainer(v: android.view.ViewGroup): LinearLayout? {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child is LinearLayout && child.tag == "search_action_bar_container") {
                    return child
                }
                if (child is android.view.ViewGroup) {
                    val found = findActionBarContainer(child)
                    if (found != null) return found
                }
            }
            return null
        }
        val rl = findResultList(content) ?: return
        val abc = findActionBarContainer(content)
        if (searchResults.isNotEmpty()) {
            updateSearchResults(rl, searchResults, abc)
        }
    }

    private fun updateSearchResults(rl: LinearLayout, results: List<Map<String, Any>>, actionBarContainer: LinearLayout? = null) {
        rl.removeAllViews()
        actionBarContainer?.removeAllViews()
        selectedIndices.clear()

        if (results.isEmpty()) {
            if (actionBarContainer != null) {
                actionBarContainer.visibility = android.view.View.GONE
            }
            renderSearchEmptyState(rl, "未找到结果")
            return
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = cardDrawable(overlayAccent, compact = true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val displayCount = minOf(results.size, 300)

        fun actionBtn(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setTextColor(overlayTextPrimary)
                textSize = 10f
                background = softButtonDrawable(overlayAccent, compact = true)
                setPadding(dp(4), dp(0), dp(4), dp(0))
                layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(3) }
                setOnClickListener { onClick() }
            }
        }

        actionBar.addView(actionBtn("☑全") {
            if (selectedIndices.size >= displayCount) {
                selectedIndices.clear()
            } else {
                selectedIndices.clear()
                for (i in 0 until displayCount) {
                    selectedIndices.add(i)
                }
            }
            updateSearchResults(rl, results, actionBarContainer)
        })

        actionBar.addView(actionBtn("📋址") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val addrs = selectedIndices.map { results[it]["address"] as String }.joinToString("\n")
            copyToClipboard(addrs)
            Toast.makeText(this, "已复制${selectedIndices.size}个地址", Toast.LENGTH_SHORT).show()
        })

        actionBar.addView(actionBtn("📋码") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val codes = selectedIndices.map { results[it]["machineCode"] as? String ?: "" }.joinToString("\n")
            copyToClipboard(codes)
            Toast.makeText(this, "已复制${selectedIndices.size}条机器码", Toast.LENGTH_SHORT).show()
        })

        actionBar.addView(actionBtn("📋值") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val vals = selectedIndices.map { "${results[it]["value"]}" }.joinToString("\n")
            copyToClipboard(vals)
            Toast.makeText(this, "已复制${selectedIndices.size}个值", Toast.LENGTH_SHORT).show()
        })

        actionBar.addView(actionBtn("🤖") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val selectedResults = selectedIndices.map { results[it] }
            addResultsToAIChat(selectedResults)
            Toast.makeText(this, "已添加${selectedIndices.size}条到AI", Toast.LENGTH_SHORT).show()
        })

        actionBar.addView(actionBtn("✏️") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val selectedResults = selectedIndices.map { results[it] }
            showBatchEditDialog(selectedResults)
        })

        if (actionBarContainer != null) {
            actionBarContainer.addView(actionBar)
            actionBarContainer.visibility = android.view.View.VISIBLE
        } else {
            rl.addView(actionBar)
        }

        for (index in 0 until displayCount) {
            val r = results[index]
            val addr = r["address"] as String
            val v = r["value"]
            val type = (r["type"] as? String ?: searchDataType).lowercase()
            val mc = r["machineCode"] as? String ?: ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }

            val checkBox = android.widget.CheckBox(this).apply {
                isChecked = selectedIndices.contains(index)
                buttonTintList = android.content.res.ColorStateList.valueOf(overlayAccentStrong)
                minWidth = dp(34)
                setPadding(0, 0, dp(2), 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIndices.add(index) else selectedIndices.remove(index)
                }
            }
            row.addView(checkBox)

            val mainWrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(this).apply {
                text = addr
                setTextColor(overlayAccentStrong)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, dp(6), 0)
            })
            topLine.addView(compactChip(type.uppercase(), overlayTextPrimary, alphaColor(searchTypeAccent(type), 82), alphaColor(searchTypeAccent(type), 180)))
            if (mc.isNotEmpty()) {
                topLine.addView(TextView(this).apply {
                    text = " ${mc.take(26)}${if (mc.length > 26) "..." else ""}"
                    setTextColor(overlayTextMuted)
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(6), 0, 0, 0)
                })
            }
            mainWrap.addView(topLine)

            val bottomLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            bottomLine.addView(compactChip(formatTypedValue(v, type), overlayTextPrimary, alphaColor(searchTypeAccent(type), 50), alphaColor(searchTypeAccent(type), 160), mono = type != "aob"))
            if (mc.isNotEmpty() && type != "aob") {
                bottomLine.addView(compactChip("AOB", overlayTextSecondary, alphaColor(Color.parseColor("#A48463"), 82), alphaColor(Color.parseColor("#D2B48C"), 150)).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                })
            }
            mainWrap.addView(bottomLine)
            row.addView(mainWrap)

            row.addView(TextView(this).apply {
                text = when {
                    type == "aob" -> "AoB"
                    mc.isNotEmpty() -> "Rx"
                    else -> "Jh"
                }
                setTextColor(overlayTextSecondary)
                textSize = 10f
                setPadding(dp(6), 0, dp(6), 0)
            })

            val opCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            opCol.addView(miniBtn("改") {
                val sv = rl.parent as? android.widget.ScrollView
                savedScrollY = sv?.scrollY ?: 0
                showWriteDialog(addr, v, mc)
            })
            opCol.addView(miniBtn("冻") {
                val ai = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return@miniBtn
                Thread { if (v != null) MemoryFreezer.freeze(ai, v, searchDataType) }.start()
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
            })
            row.addView(opCol)

            rl.addView(row)
        }
    }

    // 复制到剪贴板
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("search_results", text)
        clipboard.setPrimaryClip(clip)
    }

    // 将选中的搜索结果添加到AI对话
    private fun addResultsToAIChat(selectedResults: List<Map<String, Any>>) {
        val message = buildString {
            appendLine("📊 搜索结果分析请求：")
            appendLine()
            appendLine("数据类型: $searchDataType")
            appendLine("选中 ${selectedResults.size} 条结果：")
            appendLine()
            for ((i, r) in selectedResults.withIndex()) {
                val addr = r["address"] as String
                val v = r["value"]
                val mc = r["machineCode"] as? String ?: ""
                if (mc.isNotEmpty()) {
                    appendLine("${i+1}. 地址: $addr, 机器码: $mc, 值: $v")
                } else {
                    appendLine("${i+1}. 地址: $addr, 值: $v")
                }
            }
            appendLine()
            appendLine("请帮我分析这些内存地址的含义，以及可能的修改方案。")
        }

        // 添加到聊天记录
        chatMessages.add(Pair("👤 我", message))

        // 如果AI对话面板是打开的，直接刷新显示
        // 否则在下次打开时会自动显示
        Toast.makeText(this, "已添加到AI对话，请打开AI对话面板查看", Toast.LENGTH_SHORT).show()
    }

    private fun miniBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(overlayTextPrimary)
            textSize = 10f
            background = filledButtonDrawable(overlayAccent, compact = true)
            setPadding(dp(6), dp(0), dp(6), dp(0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28))
            setOnClickListener { onClick() }
        }
    }

    private fun showWriteDialog(addr: String, curVal: Any?, machineCode: String = "") {
        makeDraggablePanel("修改内存值", { content ->
            content.addView(TextView(this).apply { text = "地址: $addr"; setTextColor(overlayTextSecondary); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(2)) })
            if (machineCode.isNotEmpty()) {
                content.addView(TextView(this).apply { text = "机器码: $machineCode"; setTextColor(overlayTextMuted); textSize = 11f; setPadding(dp(12), dp(2), dp(12), dp(2)) })
            }
            content.addView(TextView(this).apply { text = "当前值: $curVal"; setTextColor(overlayTextSecondary); textSize = 12f; setPadding(dp(12), dp(2), dp(12), dp(8)) })

            val inp = EditText(this).apply {
                hint = "输入新值"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(curVal.toString())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
            }
            styleInputField(inp)
            content.addView(inp)

            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
            bar.addView(smallBtn("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("确认修改") {
                val nv = inp.text.toString()
                if (nv.isEmpty()) return@smallBtn
                val addrLong = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return@smallBtn
                Thread {
                    val numVal: Any = when (searchDataType) {
                        "float", "double" -> nv.toDoubleOrNull() ?: 0.0
                        else -> nv.toLongOrNull() ?: 0
                    }
                    val success = MemoryEngine.writeMemory(addrLong, numVal, searchDataType)
                    // 修改完成后更新列表中该地址的值
                    if (success) {
                        searchResults = searchResults.map { r ->
                            val rAddr = (r["addressInt"] as? Number)?.toLong()
                            if (rAddr == addrLong) {
                                r.toMutableMap().apply { this["value"] = numVal }
                            } else r
                        }
                    }
                    handler.post {
                        if (success) {
                            Toast.makeText(this@OverlayService, "✅ 修改成功: $addr = $numVal", Toast.LENGTH_SHORT).show()
                            showSearchPanel()
                        } else {
                            Toast.makeText(this@OverlayService, "❌ 修改失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 280, 280, onBack = { showSearchPanel() })
    }

    // 批量编辑对话框（支持单条和多条）
    private fun showBatchEditDialog(selectedResults: List<Map<String, Any>>) {
        val count = selectedResults.size
        makeDraggablePanel("编辑 $count 条数据", { content ->
            // 显示选中的地址列表（机器码+值）
            val addrList = selectedResults.joinToString("\n") { r ->
                val addr = r["address"] as String
                val v = r["value"]
                val mc = r["machineCode"] as? String ?: ""
                if (mc.isNotEmpty()) "$mc = $v" else "$addr = $v"
            }
            content.addView(TextView(this).apply {
                text = addrList; setTextColor(overlayTextSecondary); textSize = 11f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxLines = 6
            })

            // 输入新值
            content.addView(TextView(this).apply {
                text = "输入新值（将应用到所有 $count 条数据）："; setTextColor(overlayTextPrimary); textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            })

            val inp = EditText(this).apply {
                hint = "输入新值"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
            }
            styleInputField(inp)
            content.addView(inp)

            // 按钮
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
            bar.addView(smallBtn("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("确认修改") {
                val nv = inp.text.toString()
                if (nv.isEmpty()) return@smallBtn

                val numVal: Any = when (searchDataType) {
                    "float", "double" -> nv.toDoubleOrNull() ?: 0.0
                    else -> nv.toLongOrNull() ?: 0
                }

                Thread {
                    var successCount = 0
                    for (r in selectedResults) {
                        val addr = r["address"] as String
                        val addrLong = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: continue
                        val success = MemoryEngine.writeMemory(addrLong, numVal, searchDataType)
                        if (success) {
                            successCount++
                            // 更新 searchResults 中对应地址的值
                            searchResults = searchResults.map { sr ->
                                val srAddr = (sr["addressInt"] as? Number)?.toLong()
                                if (srAddr == addrLong) {
                                    sr.toMutableMap().apply { this["value"] = numVal }
                                } else sr
                            }
                        }
                    }
                    handler.post {
                        if (successCount == count) {
                            Toast.makeText(this@OverlayService, "✅ 成功修改 $successCount 条数据为 $numVal", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@OverlayService, "⚠️ 修改完成：成功 $successCount/$count 条", Toast.LENGTH_SHORT).show()
                        }
                        selectedIndices.clear()
                        showSearchPanel()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 300, 350)
    }

    // ==================== AI 对话面板 ====================

    private fun showAIChatPanel() {
        saveLastPanel("chat")
        makeDraggablePanel("AI 对话", { content ->
            content.background = panelContentDrawable()

            // 获取附加进程信息
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val attachedPid = prefs.getInt("attached_pid", -1)
            val attachedName = prefs.getString("attached_name", "")
            val attachedPackage = prefs.getString("attached_package", "")

            // 状态显示
            val status = TextView(this).apply {
                text = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "✅ 已附加: $attachedName"
                } else {
                    "⚠️ 未附加进程，请先附加游戏"
                }
                setTextColor(if (attachedPid != -1) overlaySuccess else overlayWarning)
                textSize = 11f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            // 分割线
            content.addView(overlayDivider())

            // 消息显示区域
            val messageArea = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                background = cardDrawable(parseUiColor("#6FE8FF"), emphasized = true)
            }
            val messageList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            // 如果是首次打开，添加欢迎消息
            if (chatMessages.isEmpty()) {
                val welcomeMsg = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "🤖 AI 助手已就绪！\n\n当前已附加: $attachedName\n\n请告诉我你想修改什么游戏数据？\n\n💡 提示：如果切换其他附加进程后，记得清空聊天，以免 AI 读错上下文"
                } else {
                    "🤖 AI 助手\n\n⚠️ 请先附加游戏进程\n点击返回 → 附加进程"
                }
                chatMessages.add(Pair("🤖 AI", welcomeMsg))
            }

            // 恢复所有历史消息
            for ((sender, msg) in chatMessages) {
                val isUser = sender == "👤 我"
                messageList.addView(createMessageBubble(sender, msg, isUser))
            }

            messageArea.addView(messageList)
            content.addView(messageArea)

            // 恢复滚动位置，或滚动到底部
            if (savedScrollY > 0) {
                val restoreY = savedScrollY
                savedScrollY = 0
                messageArea.post { messageArea.scrollY = restoreY }
                // WebView 异步加载后再次恢复（内容高度变化会导致位置偏移）
                messageArea.postDelayed({ messageArea.scrollY = restoreY }, 500)
                messageArea.postDelayed({ messageArea.scrollY = restoreY }, 1500)
            } else {
                messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                messageArea.postDelayed({ messageArea.fullScroll(ScrollView.FOCUS_DOWN) }, 500)
                messageArea.postDelayed({ messageArea.fullScroll(ScrollView.FOCUS_DOWN) }, 1500)
            }

            // 输入区域
            val inputArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = cardDrawable(parseUiColor("#6FE8FF"), compact = true)
            }

            val inputField = EditText(this).apply {
                hint = "输入你的需求..."
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                isFocusable = true
                isFocusableInTouchMode = true

                setOnClickListener {
                    requestFocus()
                    post {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                    }
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        post {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                        }
                    }
                }

                post {
                    requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }
            styleInputField(inputField, parseUiColor("#6FE8FF"))

            val sendBtn = TextView(this).apply {
                text = "发送"
                setTextColor(overlayTextPrimary)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                background = filledButtonDrawable(parseUiColor("#6FE8FF"))
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setOnClickListener {
                    val userInput = inputField.text.toString().trim()
                    if (userInput.isNotEmpty() && !isAiResponding) {
                        // 添加用户消息到历史
                        chatMessages.add(Pair("👤 我", userInput))
                        messageList.addView(createMessageBubble("👤 我", userInput, true))
                        inputField.text.clear()

                        // 隐藏输入法
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(inputField.windowToken, 0)

                        // 创建流式输出气泡
                        isAiResponding = true
                        val streamBubble = LinearLayout(this@OverlayService).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                        }
                        streamBubble.addView(TextView(this@OverlayService).apply {
                            text = "🤖 AI"
                            setTextColor(overlayAccentStrong)
                            textSize = 11f
                            setPadding(0, 0, 0, dp(2))
                        })
                        val streamText = TextView(this@OverlayService).apply {
                            text = "正在思考..."
                            setTextColor(overlayTextPrimary)
                            textSize = 12f
                            background = cardDrawable(parseUiColor("#6FE8FF"), emphasized = true)
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        streamBubble.addView(streamText)
                        messageList.addView(streamBubble)
                        messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

                        // 调用 LLM API（支持 function calling）
                        Thread {
                            try {
                                val result = if (readLlmConfig().streamEnabled) {
                                    callLlmApiStream(userInput, attachedName ?: "") { chunk ->
                                        handler.post {
                                            val current = streamText.tag as? String ?: ""
                                            val updated = current + chunk
                                            streamText.tag = updated
                                            streamText.text = updated.ifEmpty { "正在思考..." }
                                            messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                        }
                                    }
                                } else {
                                    callLlmApi(userInput, attachedName ?: "")
                                }
                                handler.post {
                                    chatMessages.add(Pair("🤖 AI", result))
                                    isAiResponding = false
                                    streamBubble.removeView(streamText)
                                    streamBubble.addView(createMarkdownWebView(result))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                            } catch (e: Exception) {
                                handler.post {
                                    val errorMsg = "❌ 请求失败: ${e.message}\n\n请检查设置中的 API 配置"
                                    chatMessages.add(Pair("🤖 AI", errorMsg))
                                    isAiResponding = false
                                    streamBubble.removeView(streamText)
                                    streamBubble.addView(createMarkdownWebView(errorMsg))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                            }
                        }.start()
                    }
                }
            }

            inputArea.addView(inputField)
            inputArea.addView(sendBtn)
            content.addView(inputArea)

            // 底部按钮栏：保存聊天 + 清空聊天 + 关闭窗口
            val actionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val smallBtnStyle = { text: String, onClick: () -> Unit ->
                TextView(this).apply {
                    this.text = text; setTextColor(overlayTextPrimary); textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    background = softButtonDrawable(parseUiColor("#6FE8FF"), active = true, compact = true)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { onClick() }
                }
            }
            actionBar.addView(smallBtnStyle("保存") { saveChatToStorage() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(smallBtnStyle("清空") {
                chatMessages.clear()
                messageList.removeAllViews()
                val welcomeMsg = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "🤖 AI 助手已就绪！\n\n当前已附加: $attachedName\n\n请告诉我你想修改什么游戏数据？\n\n💡 提示：如果切换其他附加进程后，记得清空聊天，以免 AI 读错上下文"
                } else {
                    "🤖 AI 助手\n\n⚠️ 请先附加游戏进程"
                }
                chatMessages.add(Pair("🤖 AI", welcomeMsg))
                messageList.addView(createMessageBubble("🤖 AI", welcomeMsg, false))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(smallBtnStyle("关闭") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(actionBar)

            // 滚动到底部
            messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

        }, 332, 560, titleIcon = R.drawable.ai, bgColor = "#6FE8FF")
    }

    // 创建消息气泡（用户消息用 TextView，AI 消息用 WebView 渲染 Markdown/LaTeX/Mermaid）
    private fun createMessageBubble(sender: String, message: String, isUser: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            // 发送者标签
            addView(TextView(this@OverlayService).apply {
                text = sender
                setTextColor(if (isUser) overlayAccentStrong else overlayTextSecondary)
                textSize = 11f
                setPadding(0, 0, 0, dp(2))
            })

            if (isUser) {
                // 用户消息用 TextView
                addView(TextView(this@OverlayService).apply {
                    text = message
                    setTextColor(overlayTextPrimary)
                    textSize = 12f
                    background = cardDrawable(parseUiColor("#57B9FF"), emphasized = true)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                })
            } else {
                // AI 消息统一用 WebView 渲染（支持 Markdown、代码块、Mermaid 图表）
                addView(createMarkdownWebView(message))
            }
        }
    }

    /**
     * LaTeX 预处理（在转义之前处理原始 Markdown 内容）
     * 参考 chatbox 的 latex.ts：标准化 LaTeX 分隔符，保护代码块不被误解析
     */
    private fun preprocessLaTeX(content: String): String {
        var result = content

        // Step 1: 保护代码块（用占位符替换，防止内部被处理）
        val codeBlocks = mutableListOf<String>()
        val codeBlockRegex = Regex("(```[\\s\\S]*?```|`[^`\\n]+`)")
        result = codeBlockRegex.replace(result) { match ->
            codeBlocks.add(match.value)
            "<<CODE_BLOCK_${codeBlocks.size - 1}>>"
        }

        // Step 2: 保护已有的 LaTeX 表达式
        val latexExpressions = mutableListOf<String>()
        val latexRegex = Regex("(\\$\\$[\\s\\S]*?\\$\\$|\\$[^$\\n]*?\\$|\\\\\\[[\\s\\S]*?\\\\]|\\\\\\(.*?\\\\\\))")
        result = latexRegex.replace(result) { match ->
            latexExpressions.add(match.value)
            "<<LATEX_${latexExpressions.size - 1}>>"
        }

        // Step 3: 转义货币符号（$后跟数字的情况）
        result = result.replace(Regex("\\$(?=\\d)"), "\\$")

        // Step 4: 恢复 LaTeX 表达式
        result = result.replace(Regex("<<LATEX_(\\d+)>>")) { match ->
            val index = match.groupValues[1].toInt()
            latexExpressions[index]
        }

        // Step 5: 恢复代码块
        result = result.replace(Regex("<<CODE_BLOCK_(\\d+)>>")) { match ->
            val index = match.groupValues[1].toInt()
            codeBlocks[index]
        }

        // Step 6: 标准化括号分隔符 \[...\] -> $$...$$，\(...\) -> $...$
        val bracketRegex = Regex("(```[\\S\\s]*?```|`.*?`)|\\\\\\[([\\S\\s]*?[^\\\\])\\\\]|\\\\\\((.*?)\\\\\\)")
        result = bracketRegex.replace(result) { match ->
            when {
                match.groupValues[1].isNotEmpty() -> match.groupValues[1] // 代码块，跳过
                match.groupValues[2].isNotEmpty() -> "$$${match.groupValues[2]}$$" // \[...\] -> $$...$$
                match.groupValues[3].isNotEmpty() -> "$${match.groupValues[3]}$" // \(...\) -> $...$
                else -> match.value
            }
        }

        return result
    }

    /**
     * 创建 WebView 渲染 Markdown/LaTeX/Mermaid
     */
    private fun createMarkdownWebView(markdownContent: String): WebView {
        // 仅转义 JS 字符串必须转义的字符：反斜杠、单引号、换行
        // $ 和 ` 不转义（占位符方式注入，不会触发 Kotlin 模板插值）
        val escapedContent = markdownContent
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        font-size: 14px;
        line-height: 1.6;
        color: #FFF3E0;
        background: #723d09;
        padding: 10px;
        word-wrap: break-word;
        overflow-wrap: break-word;
    }
    h1, h2, h3, h4, h5, h6 {
        color: #FFCC80;
        margin: 12px 0 6px 0;
        font-weight: 600;
    }
    h1 { font-size: 20px; }
    h2 { font-size: 17px; }
    h3 { font-size: 15px; }
    p { margin: 6px 0; }
    a { color: #FFB74D; text-decoration: none; }
    code {
        background: #8B4513;
        color: #FFCC80;
        padding: 2px 6px;
        border-radius: 4px;
        font-family: 'Courier New', monospace;
        font-size: 13px;
    }
    pre {
        background: #5D2F0A;
        border: 1px solid #8B4513;
        border-radius: 8px;
        padding: 12px;
        margin: 8px 0;
        overflow-x: auto;
    }
    pre code {
        background: none;
        padding: 0;
        color: #FFF3E0;
        font-size: 13px;
    }
    blockquote {
        border-left: 4px solid #FFB74D;
        padding-left: 12px;
        margin: 8px 0;
        color: #BCAAA4;
    }
    ul, ol { margin: 6px 0; padding-left: 24px; }
    li { margin: 3px 0; }
    table {
        border-collapse: collapse;
        width: 100%;
        margin: 8px 0;
    }
    th, td {
        border: 1px solid #8B4513;
        padding: 6px 10px;
        text-align: left;
    }
    th { background: #5D2F0A; color: #FFCC80; }
    hr { border: none; border-top: 1px solid #8B4513; margin: 12px 0; }
    img { max-width: 100%; border-radius: 8px; }
    strong { color: #FFF3E0; }
    em { color: #BCAAA4; }
    .mermaid {
        max-width: 100%;
        max-height: 400px;
        overflow: auto;
        -webkit-overflow-scrolling: touch;
        background: #5D2F0A;
        border-radius: 8px;
        padding: 8px;
        margin: 8px 0;
    }
    .mermaid svg {
        max-width: 100%;
        height: auto;
    }
    .table-wrapper {
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
        max-width: 100%;
    }
    /* 聊天气泡 */
    .msg { margin-bottom: 16px; }
    .msg-header { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .msg-icon { font-size: 14px; }
    .msg-sender { font-size: 12px; color: #BCAAA4; }
    .msg-time { font-size: 10px; color: #BCAAA4; }
    .msg-user .msg-header { justify-content: flex-end; }
    .bubble { padding: 10px 14px; border-radius: 12px; max-width: 85%; word-break: break-word; }
    .bubble-user { background: #8B4513; border: 1px solid #FFFFFF; border-radius: 12px; padding: 10px 14px; margin-left: auto; }
    .bubble-ai { background: #A1612D; border: 1px solid #FFFFFF; border-radius: 12px; padding: 10px 14px; }
    .msg-user .msg-sender { color: #FFCC80; }
    .msg-ai .msg-sender { color: #FFB74D; }
</style>
<!-- Prism.js 代码高亮 (本地) -->
<link rel="stylesheet" href="file:///android_asset/css/prism-tomorrow.min.css">
<script src="file:///android_asset/js/prism.min.js"></script>
<!-- KaTeX for LaTeX (本地) -->
<link rel="stylesheet" href="file:///android_asset/css/katex.min.css">
<script src="file:///android_asset/js/katex.min.js"></script>
<script src="file:///android_asset/js/auto-render.min.js"></script>
<!-- Marked for Markdown (本地) -->
<script src="file:///android_asset/js/marked.min.js"></script>
<!-- Mermaid for diagrams (本地) -->
<script src="file:///android_asset/js/mermaid.min.js"></script>
</head>
<body>
<div id="content"></div>
<script>
(function() {
    // 初始化 Mermaid
    mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        themeVariables: {
            primaryColor: '#8D6E63',
            primaryTextColor: '#E0E0E0',
            primaryBorderColor: '#E8DDD5',
            lineColor: '#8D6E63',
            secondaryColor: '#FFF9F0',
            tertiaryColor: '#FDFBF7'
        }
    });

    // 配置 marked + Prism.js 代码高亮
    var renderer = new marked.Renderer();
    renderer.code = function(code, lang) {
        var codeText = (typeof code === 'object') ? code.text : code;
        var langStr = (typeof code === 'object') ? code.lang : lang;
        var escaped = codeText.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        if (langStr && Prism.languages[langStr]) {
            try {
                var highlighted = Prism.highlight(codeText, Prism.languages[langStr], langStr);
                return '<pre class="language-' + langStr + '"><code class="language-' + langStr + '">' + highlighted + '</code></pre>';
            } catch(e) {}
        }
        return '<pre class="language-' + (langStr || 'none') + '"><code class="language-' + (langStr || 'none') + '">' + escaped + '</code></pre>';
    };
    marked.setOptions({ breaks: true, gfm: true, renderer: renderer });

    var rawContent = '___CONTENT_PLACEHOLDER___';

    // ====== LaTeX 保护：在 marked 解析前，用占位符保护 LaTeX 表达式 ======
    var latexStore = [];
    function protectLaTeX(text) {
        // 保护代码块
        var codeBlocks = [];
        text = text.replace(/(```[\s\S]*?```|`[^`\n]+`)/g, function(m, c) {
            codeBlocks.push(c);
            return '\x00CODE' + (codeBlocks.length-1) + '\x00';
        });
        // 保护 $$...$$ (display)
        text = text.replace(/\$\$([\s\S]*?)\$\$/g, function(m, inner) {
            latexStore.push('$$' + inner + '$$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 保护 $...$ (inline)
        text = text.replace(/\$([^\$\n]+?)\$/g, function(m, inner) {
            latexStore.push('$' + inner + '$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 保护 \[...\] 和 \(...\)
        text = text.replace(/\\\[([\s\S]*?)\\\]/g, function(m, inner) {
            latexStore.push('$$' + inner + '$$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        text = text.replace(/\\\((.*?)\\\)/g, function(m, inner) {
            latexStore.push('$' + inner + '$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 恢复代码块
        text = text.replace(/\x00CODE(\d+)\x00/g, function(m, i) { return codeBlocks[parseInt(i)]; });
        return text;
    }

    // ====== 第一步：保护 LaTeX ======
    var protectedContent = protectLaTeX(rawContent);

    // ====== 第二步：marked 解析 Markdown ======
    var htmlContent = marked.parse(protectedContent);

    // ====== 第三步：恢复 LaTeX 占位符 ======
    htmlContent = htmlContent.replace(/\x00LATEX(\d+)\x00/g, function(m, i) {
        return '<span class="katex-placeholder" data-latex="' +
            latexStore[parseInt(i)].replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;') +
            '"></span>';
    });

    // ====== 第四步：注入 DOM ======
    document.getElementById('content').innerHTML = htmlContent;

    // ====== 第五步：分离 mermaid 代码块，转为 <div class="mermaid"> ======
    var mermaidBlocks = document.querySelectorAll('code.language-mermaid');
    mermaidBlocks.forEach(function(block, idx) {
        var pre = block.parentElement;
        var div = document.createElement('div');
        div.className = 'mermaid';
        div.textContent = block.textContent;
        pre.parentNode.replaceChild(div, pre);
    });

    // ====== 第六步：表格滚动包裹 ======
    document.querySelectorAll('#content table').forEach(function(table) {
        var wrapper = document.createElement('div');
        wrapper.className = 'table-wrapper';
        table.parentNode.insertBefore(wrapper, table);
        wrapper.appendChild(table);
    });

    // ====== 第七步：渲染 LaTeX (KaTeX) ======
    document.querySelectorAll('.katex-placeholder').forEach(function(el) {
        var latex = el.getAttribute('data-latex');
        try {
            var isDisplay = latex.substring(0, 2) === '$$';
            var tex = isDisplay ? latex.substring(2, latex.length - 2) : latex.substring(1, latex.length - 1);
            katex.render(tex, el, { displayMode: isDisplay, throwOnError: false });
        } catch(e) {
            el.textContent = latex;
            el.style.color = '#FF5252';
        }
    });

    // ====== 第八步：渲染 Mermaid（异步）并通知高度 ======
    var notifyDone = function() {
        // 告知 WebView 内容已全部渲染完毕，可以测量高度
        window.__renderDone = true;
    };

    if (mermaidBlocks.length > 0) {
        // 兼容 mermaid v10 (run) 和 v8/v9 (init)
        if (typeof mermaid.run === 'function') {
            mermaid.run().then(function() { notifyDone(); }).catch(function(e) {
                console.error('Mermaid error:', e);
                notifyDone();
            });
        } else if (typeof mermaid.init === 'function') {
            try { mermaid.init(undefined, document.querySelectorAll('.mermaid')); } catch(e) { console.error(e); }
            notifyDone();
        } else {
            notifyDone();
        }
    } else {
        notifyDone();
    }
})();
</script>
</body>
</html>
""".trimIndent().replace("___CONTENT_PLACEHOLDER___", escapedContent)

        return WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(overlaySurfaceCard)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 等待渲染完成（含 mermaid 异步）后再测量高度
                    view?.evaluateJavascript(
                        """
                        (function() {
                            function checkAndMeasure() {
                                if (window.__renderDone) {
                                    return document.body.scrollHeight;
                                }
                                // 每 100ms 检查一次，最多 5 秒
                                var tries = 0;
                                var timer = setInterval(function() {
                                    tries++;
                                    if (window.__renderDone || tries > 50) {
                                        clearInterval(timer);
                                        var h = document.body.scrollHeight;
                                        window.__measuredHeight = h;
                                    }
                                }, 100);
                                return -1;
                            }
                            return checkAndMeasure();
                        })();
                        """.trimIndent()
                    ) { value ->
                        try {
                            val initial = value.replace("\"", "").toFloatOrNull() ?: 0f
                            if (initial > 0) {
                                // 渲染已完成，直接调整
                                val layoutParams = this@apply.layoutParams
                                layoutParams.height = (initial * resources.displayMetrics.density).toInt() + dp(20)
                                this@apply.layoutParams = layoutParams
                            } else {
                                // 等待异步渲染完成后轮询高度
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                val checkRunnable = object : Runnable {
                                    override fun run() {
                                        view?.evaluateJavascript("window.__measuredHeight || document.body.scrollHeight") { v ->
                                            try {
                                                val h = v.replace("\"", "").toFloatOrNull() ?: 0f
                                                if (h > 0) {
                                                    val lp = this@apply.layoutParams
                                                    lp.height = (h * resources.displayMetrics.density).toInt() + dp(20)
                                                    this@apply.layoutParams = lp
                                                } else {
                                                    handler.postDelayed(this, 200)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                handler.postDelayed(checkRunnable, 200)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        }
    }

    // ==================== 真实 LLM API 调用 ====================

    private fun readLlmConfig(): LlmRuntimeConfig {
        val configPrefs = getSharedPreferences("gg_llm_config", Context.MODE_PRIVATE)
        val configJson = configPrefs.getString("config", null)

        var baseUrl = ""
        var apiKey = ""
        var model = "deepseek-chat"
        var apiFormat = LlmApiFormat.OPENAI_CHAT_COMPLETIONS
        var temperature = 0.7
        var maxTokens = 2048
        var timeoutSeconds = 60
        var streamEnabled = true

        if (configJson != null) {
            try {
                val json = JSONObject(configJson)
                baseUrl = json.optString("baseUrl", "")
                apiKey = json.optString("apiKey", "")
                model = json.optString("model", "deepseek-chat")
                apiFormat = LlmApiFormat.fromValue(json.optString("apiFormat", "openai_chat_completions"))
                temperature = json.optDouble("temperature", 0.7)
                maxTokens = json.optInt("maxTokens", 2048)
                timeoutSeconds = json.optInt("timeoutSeconds", 60)
                streamEnabled = json.optBoolean("streamEnabled", true)
            } catch (_: Exception) {}
        }

        return LlmRuntimeConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            apiFormat = apiFormat,
            temperature = temperature,
            maxTokens = maxTokens,
            timeoutSeconds = timeoutSeconds,
            streamEnabled = streamEnabled,
        )
    }

    private fun buildSystemPrompt(attachedApp: String, model: String): String {
        val modelInfo = getModelInfo(model)
        return buildString {
            append("你是 GG-AI 游戏内存修改助手。你当前使用的底层大模型是：$modelInfo。\n")
            append("当用户问你是什么模型时，你必须回答「$modelInfo」，这是你真实运行的底层模型。GG-AI 只是这个应用的名称，不是你的模型名称。不要编造其他模型名称。\n\n")
            append("## 核心行为准则\n")
            append("当用户要求搜索、读取、修改内存数据时，你必须调用工具（search_memory / read_memory / write_memory）来执行真实操作，返回真实结果。\n")
            append("绝对不要模拟、编造搜索结果或内存地址。不要在回复中写 gg.searchNumber 等代码来代替真实操作。\n")
            append("搜索结果中的「机器码」是该地址处的原始字节，可用于判断地址是否正确。\n")
            append("修改值后，工具会自动回读验证。如果回读值与目标不同，说明写入可能失败。\n\n")
            append("## 脚本生成\n")
            append("只有当用户明确说「写脚本」「生成脚本」「写个lua」等要求生成脚本时，才输出 Lua 脚本。\n")
            append("Lua 脚本规范（luaj-jse-3.0.2.jar 环境）：\n")
            append("- 使用 GG API：gg.searchNumber、gg.getResults、gg.editAll、gg.clearResults\n")
            append("- type 常量：gg.TYPE_DWORD、gg.TYPE_FLOAT、gg.TYPE_DOUBLE、gg.TYPE_BYTE、gg.TYPE_WORD、gg.TYPE_QWORD\n")
            append("- UI：gg.toast、gg.alert、gg.prompt、gg.choice、gg.mainTabs、gg.viewText、gg.viewList、gg.viewPrompt、gg.viewSwitch、gg.viewWeb\n")
            append("- 用 ```lua 代码块包裹\n\n")
            if (attachedApp.isNotEmpty()) {
                append("当前已附加游戏进程: $attachedApp\n")
            }
            append("\n渲染支持：当前客户端支持 Markdown 渲染、代码块高亮、LaTeX 数学公式和 Mermaid 图表。")
            append("当用户要求画图、画表、画流程图、架构图、思维导图、时序图、甘特图等可视化内容时，你必须直接输出 Mermaid 代码块，不要解释，不要用文字描述，直接给出代码。")
            append("用 ```mermaid 代码块包裹，客户端会自动渲染成图表。\n")
            append("⚠️ Mermaid 版本为 8.14.0，必须严格使用该版本兼容语法：\n")
            append("- 流程图用 `graph TD` 或 `graph LR`（不要用 flowchart）\n")
            append("- 支持的图表类型：graph、sequenceDiagram、classDiagram、stateDiagram、gantt、pie\n")
            append("- 不支持：mindmap、timeline、quadrantChart、block-beta、sankey-beta、xychart-beta 等新类型\n")
            append("- 不要使用 `%%{init: ...}%%` 配置指令\n")
            append("- 节点文本中的特殊字符用双引号包裹，如 A[\"(特殊)文本\"]\n")
            append("示例：\n")
            append("```mermaid\ngraph TD\n    A[搜索金币值] --> B[消费金币]\n    B --> C[再次搜索]\n    C --> D[确认地址]\n    D --> E[修改值]\n```\n\n")
            append("LaTeX 公式支持：用 ${'$'}...${'$'} 包裹行内公式，${'$'}${'$'}...${'$'}${'$'} 包裹独立公式。示例：${'$'}E=mc^2${'$'}，${'$'}${'$'}\\int_0^1 x dx = \\frac{1}{2}${'$'}${'$'}\n\n")
            append("回复格式：\n")
            append("- 使用简洁友好的中文\n")
            append("- 操作步骤用编号列出\n")
            append("- 执行结果用 ✅ 或 ❌ 标记\n")
            append("- 地址和数值用代码格式显示\n")
            append("- 用户要求画图时，直接输出 mermaid 代码块，不要多余文字")
        }
    }

    private fun buildChatMessages(systemPrompt: String, userInput: String): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        for ((sender, msg) in chatMessages.takeLast(10)) {
            val role = if (sender == "👤 我") "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userInput)
        })
        return messages
    }

    private fun ensureLlmConfigured(config: LlmRuntimeConfig): String? {
        return if (config.baseUrl.isEmpty() || config.apiKey.isEmpty()) {
            "⚠️ 请先在设置中配置 LLM API\n\n打开主应用 → 设置 → LLM API 配置\n\n当前支持：DeepSeek、OpenAI、小米 MiMo、Anthropic 等"
        } else {
            null
        }
    }

    private fun buildToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "list_processes")
                    put("description", "获取当前设备上可附加的进程列表。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "attach_process")
                    put("description", "附加到指定进程。需要传入目标 pid。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("pid", JSONObject().apply {
                                put("type", "integer")
                                put("description", "要附加的进程 ID")
                            })
                        })
                        put("required", JSONArray().apply { put("pid") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "get_attached_process")
                    put("description", "查看当前已附加进程信息。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "search_memory")
                    put("description", "在游戏内存中搜索数值。支持精确搜索、范围搜索、AOB搜索。返回匹配的内存地址列表。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("mode", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索模式：exact(精确)、range(范围)、aob(AOB特征码)")
                                put("enum", JSONArray().apply { put("exact"); put("range"); put("aob") })
                            })
                            put("value", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索值。exact模式填数值如'750000'；range模式填'最小值,最大值'如'100,200'；aob模式填特征码如'48 8B 05 ?? ??'")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型：dword(整数4字节)、float(浮点)、double(双精度)、byte(1字节)、word(2字节)、qword(8字节)")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("mode"); put("value") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "filter_memory_results")
                    put("description", "在已有地址列表中继续按目标值过滤结果。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("addresses", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                                put("description", "上一步得到的地址列表，如 ['0x1234','0x5678']")
                            })
                            put("value", JSONObject().apply {
                                put("type", "string")
                                put("description", "要过滤出的目标值")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("addresses"); put("value"); put("type") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "read_memory")
                    put("description", "从指定内存地址读取值。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply {
                                put("type", "string")
                                put("description", "内存地址，十六进制如'0x12345678'")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("address"); put("type") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "write_memory")
                    put("description", "向指定内存地址写入值。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply { put("type", "string"); put("description", "内存地址，十六进制如'0x12345678'") })
                            put("value", JSONObject().apply { put("type", "string"); put("description", "要写入的值") })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("address"); put("value"); put("type") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "freeze_memory")
                    put("description", "冻结指定地址的值，持续写回。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply { put("type", "string") })
                            put("value", JSONObject().apply { put("type", "string") })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("address"); put("value"); put("type") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "unfreeze_memory")
                    put("description", "解除指定地址的冻结。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().apply { put("address") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "list_frozen_memory")
                    put("description", "查看当前所有冻结地址。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "analyze_memory_region")
                    put("description", "分析指定地址周围的内存区域。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply { put("type", "string") })
                            put("range", JSONObject().apply { put("type", "integer"); put("description", "前后分析范围，默认 256 字节") })
                        })
                        put("required", JSONArray().apply { put("address") })
                    })
                })
            })
        }
    }

    private fun callLlmApi(userInput: String, attachedApp: String): String {
        val config = readLlmConfig()
        ensureLlmConfigured(config)?.let { return it }

        val systemPrompt = buildSystemPrompt(attachedApp, config.model)
        val messages = buildChatMessages(systemPrompt, userInput)

        return when (config.apiFormat) {
            LlmApiFormat.OPENAI_CHAT_COMPLETIONS -> callOpenAiChatCompletion(config, messages)
            LlmApiFormat.OPENAI_RESPONSES -> callResponsesApi(config, systemPrompt, messages)
            LlmApiFormat.ANTHROPIC_MESSAGES -> callAnthropicApi(config, systemPrompt, messages)
        }
    }

    private fun callOpenAiChatCompletion(
        config: LlmRuntimeConfig,
        messages: JSONArray,
        round: Int = 0,
    ): String {
        if (round > 4) return "❌ 工具调用轮次过多，已停止"

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("tools", buildToolDefinitions())
            put("tool_choice", "auto")
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
        }

        val responseJson = JSONObject(
            doHttpJsonRequest("${config.baseUrl.trimEnd('/')}/chat/completions", config, requestBody)
        )
        val message = responseJson.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: return "❌ 未收到 AI 回复"

        val toolCalls = extractOpenAiToolCalls(message)
        if (toolCalls.isNotEmpty()) {
            messages.put(message)
            for (toolCall in toolCalls) {
                val result = executeToolCall(toolCall.name, toolCall.arguments)
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolCall.id)
                    put("content", result)
                })
            }
            return callOpenAiChatCompletion(config, messages, round + 1)
        }

        return extractOpenAiMessageText(message)
    }

    private fun callResponsesApi(
        config: LlmRuntimeConfig,
        instructions: String,
        messages: JSONArray,
    ): String {
        val input = buildResponsesInput(messages)
        return callResponsesApiWithInput(config, instructions, input)
    }

    private fun callResponsesApiWithInput(
        config: LlmRuntimeConfig,
        instructions: String,
        input: JSONArray,
        round: Int = 0,
    ): String {
        if (round > 4) return "❌ 工具调用轮次过多，已停止"

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("instructions", instructions)
            put("input", input)
            put("tools", buildResponsesToolDefinitions())
            put("tool_choice", "auto")
            put("temperature", config.temperature)
            put("max_output_tokens", config.maxTokens)
        }

        val responseJson = JSONObject(
            doHttpJsonRequest("${config.baseUrl.trimEnd('/')}/responses", config, requestBody)
        )
        val toolCalls = extractResponsesToolCalls(responseJson)
        if (toolCalls.isNotEmpty()) {
            val nextInput = JSONArray()
            appendJsonArray(input, nextInput)
            appendJsonArray(responseJson.optJSONArray("output"), nextInput)
            for (toolCall in toolCalls) {
                val result = executeToolCall(toolCall.name, toolCall.arguments)
                nextInput.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", toolCall.id)
                    put("output", result)
                })
            }
            return callResponsesApiWithInput(config, instructions, nextInput, round + 1)
        }

        return extractResponsesText(responseJson)
    }

    private fun callAnthropicApi(
        config: LlmRuntimeConfig,
        systemPrompt: String,
        messages: JSONArray,
    ): String {
        val anthropicMessages = buildAnthropicMessages(messages)
        return callAnthropicApiWithMessages(config, systemPrompt, anthropicMessages)
    }

    private fun callAnthropicApiWithMessages(
        config: LlmRuntimeConfig,
        systemPrompt: String,
        messages: JSONArray,
        round: Int = 0,
    ): String {
        if (round > 4) return "❌ 工具调用轮次过多，已停止"

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("system", systemPrompt)
            put("messages", messages)
            put("tools", buildAnthropicToolDefinitions())
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
        }

        val responseJson = JSONObject(
            doHttpJsonRequest("${config.baseUrl.trimEnd('/')}/messages", config, requestBody)
        )
        val content = responseJson.optJSONArray("content") ?: JSONArray()
        val toolCalls = extractAnthropicToolCalls(content)
        if (toolCalls.isNotEmpty()) {
            val nextMessages = JSONArray()
            appendJsonArray(messages, nextMessages)
            nextMessages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })

            val toolResults = JSONArray()
            for (toolCall in toolCalls) {
                val result = executeToolCall(toolCall.name, toolCall.arguments)
                toolResults.put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", toolCall.id)
                    put("content", result)
                })
            }
            nextMessages.put(JSONObject().apply {
                put("role", "user")
                put("content", toolResults)
            })
            return callAnthropicApiWithMessages(config, systemPrompt, nextMessages, round + 1)
        }

        return extractAnthropicText(content)
    }

    private fun doHttpJsonRequest(
        endpoint: String,
        config: LlmRuntimeConfig,
        body: JSONObject,
    ): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        when (config.apiFormat) {
            LlmApiFormat.ANTHROPIC_MESSAGES -> {
                conn.setRequestProperty("x-api-key", config.apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
            else -> conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        conn.doOutput = true
        conn.connectTimeout = config.timeoutSeconds * 1000
        conn.readTimeout = (config.timeoutSeconds * 2) * 1000
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body.toString())
            writer.flush()
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            conn.disconnect()
            throw Exception("HTTP $code: $err")
        }
        val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun executeToolCall(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "list_processes" -> NativeToolApi.listProcesses()
                "attach_process" -> NativeToolApi.attachProcess(args)
                "get_attached_process" -> NativeToolApi.getAttachedProcessInfo()
                "search_memory" -> NativeToolApi.searchMemory(args)
                "filter_memory_results" -> NativeToolApi.filterMemoryResults(args)
                "read_memory" -> NativeToolApi.readMemory(args)
                "write_memory" -> NativeToolApi.writeMemory(args)
                "freeze_memory" -> NativeToolApi.freezeMemory(args)
                "unfreeze_memory" -> NativeToolApi.unfreezeMemory(args)
                "list_frozen_memory" -> NativeToolApi.listFrozenMemory()
                "analyze_memory_region" -> NativeToolApi.analyzeMemoryRegion(args)
                else -> "❌ 未知工具: $name"
            }
        } catch (e: Exception) {
            "❌ 执行出错: ${e.message}"
        }
    }

    // 流式调用 LLM API
    private fun callLlmApiStream(userInput: String, attachedApp: String, onChunk: (String) -> Unit): String {
        val config = readLlmConfig()
        ensureLlmConfigured(config)?.let {
            onChunk(it)
            return it
        }

        val systemPrompt = buildSystemPrompt(attachedApp, config.model)
        val messages = buildChatMessages(systemPrompt, userInput)

        if (config.apiFormat != LlmApiFormat.OPENAI_CHAT_COMPLETIONS) {
            val result = callLlmApi(userInput, attachedApp)
            if (result.isNotEmpty()) onChunk(result)
            return result
        }

        return callOpenAiChatCompletionStream(config, messages, onChunk)
    }

    private fun callOpenAiChatCompletionStream(
        config: LlmRuntimeConfig,
        messages: JSONArray,
        onChunk: (String) -> Unit,
        round: Int = 0,
    ): String {
        if (round > 4) return "❌ 工具调用轮次过多，已停止"

        val conn = URL("${config.baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.doOutput = true
        conn.connectTimeout = config.timeoutSeconds * 1000
        conn.readTimeout = (config.timeoutSeconds * 2) * 1000

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("tools", buildToolDefinitions())
            put("tool_choice", "auto")
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", true)
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(requestBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            conn.disconnect()
            throw Exception("HTTP $responseCode: $errorStream")
        }

        val text = StringBuilder()
        val pendingToolCalls = linkedMapOf<Int, PendingToolCall>()

        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val chunk = line!!.trim()
                if (chunk.isEmpty() || !chunk.startsWith("data: ")) continue
                val data = chunk.substring(6)
                if (data == "[DONE]") break
                try {
                    val json = JSONObject(data)
                    val delta = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?: continue

                    if (delta.has("content") && !delta.isNull("content")) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty() && content != "null") {
                            text.append(content)
                            onChunk(content)
                        }
                    }

                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val item = toolCalls.optJSONObject(i) ?: continue
                            val index = item.optInt("index", i)
                            val pending = pendingToolCalls.getOrPut(index) { PendingToolCall() }
                            val id = item.optString("id", "")
                            if (id.isNotEmpty()) pending.id = id
                            val function = item.optJSONObject("function")
                            if (function != null) {
                                val name = function.optString("name", "")
                                if (name.isNotEmpty()) pending.name = name
                                val argsDelta = function.optString("arguments", "")
                                if (argsDelta.isNotEmpty()) pending.arguments.append(argsDelta)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        conn.disconnect()

        val toolCallSpecs = pendingToolCalls.entries
            .sortedBy { it.key }
            .mapNotNull { (index, pending) ->
                if (pending.name.isBlank()) null
                else ToolCallSpec(
                    id = if (pending.id.isNotBlank()) pending.id else "call_$index",
                    name = pending.name,
                    arguments = parseToolArguments(pending.arguments.toString())
                )
            }

        if (toolCallSpecs.isNotEmpty()) {
            messages.put(buildOpenAiAssistantToolMessage(text.toString(), toolCallSpecs))
            for (toolCall in toolCallSpecs) {
                val result = executeToolCall(toolCall.name, toolCall.arguments)
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolCall.id)
                    put("content", result)
                })
            }
            return callOpenAiChatCompletionStream(config, messages, onChunk, round + 1)
        }

        return if (text.isNotBlank()) text.toString() else "❌ 未收到 AI 回复"
    }

    private fun extractOpenAiToolCalls(message: JSONObject): List<ToolCallSpec> {
        val toolCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        val result = mutableListOf<ToolCallSpec>()
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name", "")
            if (name.isBlank()) continue
            result.add(
                ToolCallSpec(
                    id = item.optString("id", "call_$i"),
                    name = name,
                    arguments = parseToolArguments(function.optString("arguments", "{}"))
                )
            )
        }
        return result
    }

    private fun extractOpenAiMessageText(message: JSONObject): String {
        val content = message.optString("content", "").trim()
        if (content.isNotEmpty()) return content
        val reasoning = message.optString("reasoning_content", "").trim()
        return if (reasoning.isNotEmpty()) "⚠️ 模型仅返回推理片段，请提高 max tokens 后重试" else "❌ 未收到 AI 回复"
    }

    private fun buildResponsesToolDefinitions(): JSONArray {
        val tools = buildToolDefinitions()
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val item = tools.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            result.put(JSONObject().apply {
                put("type", "function")
                put("name", function.optString("name"))
                put("description", function.optString("description"))
                put("parameters", function.optJSONObject("parameters") ?: JSONObject())
            })
        }
        return result
    }

    private fun buildAnthropicToolDefinitions(): JSONArray {
        val tools = buildToolDefinitions()
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val item = tools.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            result.put(JSONObject().apply {
                put("name", function.optString("name"))
                put("description", function.optString("description"))
                put("input_schema", function.optJSONObject("parameters") ?: JSONObject())
            })
        }
        return result
    }

    private fun buildResponsesInput(messages: JSONArray): JSONArray {
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val role = item.optString("role", "")
            if (role == "system") continue
            input.put(JSONObject().apply {
                put("role", role)
                put("content", item.optString("content", ""))
            })
        }
        return input
    }

    private fun buildAnthropicMessages(messages: JSONArray): JSONArray {
        val anthropicMessages = JSONArray()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val role = item.optString("role", "")
            if (role == "system") continue
            anthropicMessages.put(JSONObject().apply {
                put("role", role)
                put("content", item.optString("content", ""))
            })
        }
        return anthropicMessages
    }

    private fun extractResponsesToolCalls(responseJson: JSONObject): List<ToolCallSpec> {
        val output = responseJson.optJSONArray("output") ?: return emptyList()
        val result = mutableListOf<ToolCallSpec>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "function_call") continue
            result.add(
                ToolCallSpec(
                    id = item.optString("call_id", item.optString("id", "call_$i")),
                    name = item.optString("name", ""),
                    arguments = parseToolArguments(item.optString("arguments", "{}"))
                )
            )
        }
        return result.filter { it.name.isNotBlank() }
    }

    private fun extractResponsesText(responseJson: JSONObject): String {
        val outputText = responseJson.optString("output_text", "").trim()
        if (outputText.isNotEmpty()) return outputText
        val output = responseJson.optJSONArray("output") ?: return "❌ 未收到 AI 回复"
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == "output_text" || block.optString("type") == "text") {
                    text.append(block.optString("text", ""))
                }
            }
        }
        return if (text.isNotBlank()) text.toString() else "❌ 未收到 AI 回复"
    }

    private fun extractAnthropicToolCalls(content: JSONArray): List<ToolCallSpec> {
        val result = mutableListOf<ToolCallSpec>()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") != "tool_use") continue
            result.add(
                ToolCallSpec(
                    id = item.optString("id", "tool_$i"),
                    name = item.optString("name", ""),
                    arguments = item.optJSONObject("input") ?: JSONObject()
                )
            )
        }
        return result.filter { it.name.isNotBlank() }
    }

    private fun extractAnthropicText(content: JSONArray): String {
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") == "text") {
                text.append(item.optString("text", ""))
            }
        }
        return if (text.isNotBlank()) text.toString() else "❌ 未收到 AI 回复"
    }

    private fun buildOpenAiAssistantToolMessage(content: String, toolCalls: List<ToolCallSpec>): JSONObject {
        return JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { toolCall ->
                    put(JSONObject().apply {
                        put("id", toolCall.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", toolCall.name)
                            put("arguments", toolCall.arguments.toString())
                        })
                    })
                }
            })
        }
    }

    private fun appendJsonArray(source: JSONArray?, target: JSONArray) {
        if (source == null) return
        for (i in 0 until source.length()) {
            target.put(source.get(i))
        }
    }

    private fun parseToolArguments(raw: String): JSONObject {
        return try {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // 根据模型名称返回公司+版本信息
    private fun getModelInfo(modelName: String): String {
        val m = modelName.lowercase()
        return when {
            m.contains("deepseek-reasoner") || m.contains("deepseek-r1") -> "DeepSeek-R1（深度求索公司，推理增强模型）"
            m.contains("deepseek") -> "DeepSeek-V3（深度求索公司，通用对话模型）"
            m.contains("mimo") -> "MiMo-v2.5-Pro（小米公司，大语言模型）"
            m.contains("gpt-4o") -> "GPT-4o（OpenAI 公司，多模态模型）"
            m.contains("gpt-4") -> "GPT-4（OpenAI 公司，大语言模型）"
            m.contains("gpt-3.5") -> "GPT-3.5-Turbo（OpenAI 公司，大语言模型）"
            m.contains("claude") -> "Claude（Anthropic 公司，大语言模型）"
            m.contains("qwen") || m.contains("tongyi") -> "通义千问（阿里巴巴公司，大语言模型）"
            m.contains("glm") || m.contains("chatglm") -> "ChatGLM（智谱AI公司，大语言模型）"
            else -> modelName
        }
    }

    // ==================== 保存聊天到存储 ====================

    private fun saveChatToStorage() {
        if (chatMessages.isEmpty()) {
            handler.post {
                Toast.makeText(this, "没有聊天记录可保存", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val prefs = getSharedPreferences("gg_overlay_chat", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // 保存为 JSON 数组
            val jsonArray = JSONArray()
            for ((sender, msg) in chatMessages) {
                jsonArray.put(JSONObject().apply {
                    put("sender", sender)
                    put("message", msg)
                    put("timestamp", System.currentTimeMillis())
                })
            }

            // 使用时间戳作为 key
            val sessionId = "chat_${System.currentTimeMillis()}"
            editor.putString(sessionId, jsonArray.toString())
            editor.putString("latest_session_id", sessionId)
            editor.apply()

            handler.post {
                Toast.makeText(this, "✅ 聊天已保存，可在主应用查看", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            handler.post {
                Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 脚本库面板 ====================

    private fun showScriptPanel() {
        saveLastPanel("script")
        makeDraggablePanel("脚本库", { content ->
            val status = TextView(this).apply {
                text = "正在加载脚本..."
                setTextColor(overlayTextPrimary)
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            val sv = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            sv.addView(list)
            content.addView(sv)

            // 加载脚本的函数
            fun loadScripts() {
                list.removeAllViews()
                status.text = "正在加载脚本..."
                Thread {
                    val scripts = loadScriptsFromStorage()
                    handler.post {
                        status.text = "找到 ${scripts.size} 个脚本"
                        for (script in scripts) {
                            val item = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = cardDrawable(parseUiColor("#7C9EFF"), emphasized = true)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = dp(6) }
                            }
                            val nameText = TextView(this).apply {
                                text = script["name"] ?: "未知脚本"
                                setTextColor(overlayTextPrimary)
                                textSize = 14f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            item.addView(nameText)

                            val runBtn = smallBtn("▶ 运行") {
                                val scriptContent = script["content"] ?: ""
                                if (scriptContent.isNotEmpty()) {
                                    status.text = "正在运行: ${script["name"]}..."
                                    Thread {
                                        try {
                                            LuaEngine.setContext(this@OverlayService)
                                            val output = LuaEngine.executeScript(scriptContent)
                                            // 自动保存日志
                                            saveScriptLog(script["name"] ?: "脚本", output)
                                            handler.post {
                                                status.text = "✅ ${script["name"]} 执行完成"
                                                Toast.makeText(this@OverlayService, "✅ ${script["name"]} 执行完成，日志已保存", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            handler.post {
                                                status.text = "❌ 执行失败: ${e.message}"
                                            }
                                        }
                                    }.start()
                                }
                            }
                            item.addView(runBtn)
                            list.addView(item)
                        }

                        if (scripts.isEmpty()) {
                            list.addView(TextView(this).apply {
                                text = "暂无脚本\n请在主应用脚本库中创建"
                                setTextColor(overlayTextMuted)
                                textSize = 13f
                                setPadding(dp(12), dp(20), dp(12), dp(8))
                                gravity = Gravity.CENTER
                            })
                        }
                    }
                }.start()
            }

            // 首次加载
            loadScripts()

            // 底部按钮：刷新 + 关闭窗口
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(6), dp(12), dp(10))
                background = cardDrawable(parseUiColor("#7C9EFF"), compact = true)
            }
            bar.addView(iconBtn(R.drawable.shuaxing) { loadScripts() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 308, 432, titleIcon = R.drawable.jiaoben, bgColor = "#7C9EFF")
    }

    /**
     * 保存脚本运行日志
     */
    private fun saveScriptLog(scriptName: String, output: String) {
        try {
            val now = java.text.SimpleDateFormat("MMddHHmm", java.util.Locale.getDefault()).format(java.util.Date())
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

            // 保存到 gg_script_logs SharedPreferences（与 MainActivity 的 getOverlayScriptLogs 对应）
            val prefs = getSharedPreferences("gg_script_logs", Context.MODE_PRIVATE)
            val existing = prefs.getString("logs", "[]") ?: "[]"
            val logsArray = org.json.JSONArray(existing)

            val logEntry = org.json.JSONObject().apply {
                put("name", "${now}_$scriptName")
                put("scriptName", scriptName)
                put("time", timeStr)
                put("output", output)
            }
            logsArray.put(logEntry)

            prefs.edit().putString("logs", logsArray.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 从存储加载脚本列表
     */
    private fun loadScriptsFromStorage(): List<Map<String, String>> {
        val scripts = mutableListOf<Map<String, String>>()

        try {
            // 从 SharedPreferences 读取（由主应用同步，已包含内置脚本）
            val prefs = getSharedPreferences("gg_scripts", Context.MODE_PRIVATE)
            val scriptsJson = prefs.getString("scripts", "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(scriptsJson)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                scripts.add(mapOf(
                    "id" to json.optString("id", ""),
                    "name" to json.optString("name", "未知"),
                    "content" to json.optString("content", ""),
                    "description" to json.optString("description", "")
                ))
            }
        } catch (_: Exception) {}

        // 如果没有从主应用同步到脚本，添加内置脚本作为备用
        if (scripts.none { it["id"] == "builtin_test" }) {
            // 添加内置脚本
            scripts.add(mapOf(
            "id" to "builtin_test",
            "name" to "运行测试",
            "content" to """-- 游戏修改器 Lua 测试脚本
function searchData(value, valueType)
    gg.clearResults()
    gg.searchNumber(value, valueType)
    local count = gg.getResultCount()
    gg.toast("搜索完成，找到 " .. count .. " 条结果")
    return count
end
function menu1_search()
    local choice = gg.choice({
        " 搜索整数 9999",
        " 搜索浮点 1.0",
        " 搜索双精度 3.14"
    }, nil, "【数值搜索】请选择搜索类型")
    if choice == nil then
        gg.toast("已取消")
        return
    end
    if choice == 1 then
        searchData(9999, gg.TYPE_DWORD)
    elseif choice == 2 then
        searchData("1.0", gg.TYPE_FLOAT)
    elseif choice == 3 then
        searchData("3.14", gg.TYPE_DOUBLE)
    end
end
function menu2_advanced()
    local choice = gg.choice({
        " 修改搜索结果为 88888",
        " 冻结当前结果",
        " 清除所有结果"
    }, nil, "【高级操作】请选择操作")
    if choice == nil then
        gg.toast("已取消")
        return
    end
    if choice == 1 then
        local count = gg.getResultCount()
        if count > 0 then
            local results = gg.getResults(count)
            for i, v in ipairs(results) do
                results[i].value = 88888
                results[i].flags = gg.TYPE_DWORD
            end
            gg.setValues(results)
            gg.toast("已修改 " .. count .. " 条数据为 88888")
        else
            gg.toast("没有搜索结果")
        end
    elseif choice == 2 then
        local count = gg.getResultCount()
        if count > 0 then
            local results = gg.getResults(count)
            for i, v in ipairs(results) do
                results[i].freeze = true
            end
            gg.addListItems(results)
            gg.toast("已冻结 " .. count .. " 条数据")
        else
            gg.toast("没有搜索结果")
        end
    elseif choice == 3 then
        gg.clearResults()
        gg.clearList()
        gg.toast("已清除所有结果")
    end
end
function mainMenu()
    while true do
        local main = gg.choice({
            " 数值搜索",
            " 高级操作",
            " 退出脚本"
        }, nil, "=== Lua 测试脚本 v1.0 ===")
        if main == nil or main == 3 then
            gg.toast("脚本已退出")
            break
        elseif main == 1 then
            menu1_search()
        elseif main == 2 then
            menu2_advanced()
        end
    end
end
gg.toast("Lua 测试脚本已加载")
gg.sleep(1000)
mainMenu()""",
            "description" to "Lua 菜单弹窗 + 数据搜索测试"
        ))
        }

        return scripts
    }

    /**
     * 显示脚本运行输出对话框
     */

    // ==================== UI 工具 ====================

    private fun menuBtn(label: String, iconRes: Int? = null, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardDrawable(overlayAccent, emphasized = true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
            if (iconRes != null) {
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(overlayAccentStrong)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })
            }
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(overlayTextPrimary)
                    textSize = 14f
                })
                addView(TextView(this@OverlayService).apply {
                    text = "进入 ${label} 工具"
                    setTextColor(overlayTextSecondary)
                    textSize = 10f
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(TextView(this@OverlayService).apply {
                this.text = "›"
                setTextColor(overlayAccentStrong)
                textSize = 18f
            })
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(overlayTextPrimary)
            textSize = 11f
            background = filledButtonDrawable(overlayAccent, compact = true)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { onClick() }
        }
    }

    private fun iconBtn(iconRes: Int, label: String = "", onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = softButtonDrawable(overlayAccent, compact = true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
            addView(ImageView(this@OverlayService).apply {
                setImageResource(iconRes)
                setColorFilter(overlayAccentStrong)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            })
            if (label.isNotEmpty()) {
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(overlayTextPrimary)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
