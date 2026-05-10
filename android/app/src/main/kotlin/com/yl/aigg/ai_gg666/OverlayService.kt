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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

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

    // AI 对话历史（持久化在内存中，防止切换后消失）
    private val chatMessages = mutableListOf<Pair<String, String>>() // (sender, message)
    private var isAiResponding = false

    // 记住上次打开的面板
    private var lastPanel = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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

    // ==================== 悬浮球 ====================

    private fun createBall() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ballView = TextView(this).apply {
            text = "🎮"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6C63FF")) }
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
            x = 0
            y = dp(200)
        }

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        ballView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = ballParams?.x ?: 0; iy = ballParams?.y ?: 0; tx = e.rawX; ty = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - tx) > 10 || kotlin.math.abs(e.rawY - ty) > 10) dragging = true
                    ballParams?.x = ix + (e.rawX - tx).toInt(); ballParams?.y = iy + (e.rawY - ty).toInt()
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

    private fun makeDraggablePanel(title: String, contentBuilder: (LinearLayout) -> Unit, w: Int = 280, h: Int = 400) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#1E1E1E")); setStroke(1, Color.parseColor("#6C63FF"))
            }
        }

        // 可拖动标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
        val titleText = TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        // 返回按钮（如果不是主菜单）
        if (title != "🎮 GG-AI Modifier") {
            titleBar.addView(Button(this).apply {
                text = "返回"; setTextColor(Color.WHITE); textSize = 11f
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(Color.parseColor("#555555")) }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener { showMainMenu() }
            })
        }

        root.addView(titleBar)

        // 分割线
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        // 内容区域
        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentBuilder(contentArea)
        root.addView(contentArea)

        // 标题栏拖动支持 - 修复横屏模式下的拖动问题
        var pix = 0; var piy = 0; var ptx = 0f; var pty = 0f; var isDragging = false
        val dm = resources.displayMetrics
        
        titleBar.setOnTouchListener { _, e ->
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
                    
                    // 计算新位置，确保不超出屏幕边界
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val panelW = panelParams?.width ?: 0
                    val panelH = panelParams?.height ?: 0
                    
                    var newX = pix + dx
                    var newY = piy + dy
                    
                    // 限制在屏幕范围内
                    newX = newX.coerceIn(-panelW / 2, screenW - panelW / 2)
                    newY = newY.coerceIn(0, screenH - panelH)
                    
                    panelParams?.x = newX
                    panelParams?.y = newY
                    try { wm?.updateViewLayout(panel, panelParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }

        // 根据面板类型选择显示方式
        if (title == "🤖 AI 对话") {
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
        makeDraggablePanel("🎮 GG-AI Modifier", { content ->
            val sv = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }

            list.addView(menuBtn("📱 附加进程") { showProcessPanel() })
            list.addView(menuBtn("🔍 内存搜索") { showSearchPanel() })
            list.addView(menuBtn("🤖 AI 对话") { showAIChatPanel() })
            list.addView(menuBtn("📜 脚本库") { showScriptPanel() })

            sv.addView(list); content.addView(sv)

            // 底部按钮
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            bar.addView(smallBtn("关闭悬浮窗") { stopSelf() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("关闭菜单") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 250, 380)
    }

    // ==================== 进程面板 ====================

    private fun showProcessPanel() {
        saveLastPanel("process")
        makeDraggablePanel("📱 选择游戏进程", { content ->
            val status = TextView(this).apply { text = "正在扫描..."; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(4)) }
            content.addView(status)

            val sv = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
            sv.addView(list); content.addView(sv)

            // 底部按钮
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
            bar.addView(smallBtn("刷新") { loadProcs(list, status) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("关闭窗口") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)

            loadProcs(list, status)
        }, 300, 450)
    }

    private fun loadProcs(list: LinearLayout, status: TextView) {
        status.text = "正在扫描..."; list.removeAllViews()
        Thread {
            val procs = ProcessManager.getProcessList(this@OverlayService).filter {
                val p = it["packageName"] as String
                p.isNotEmpty() && p.contains(".") && !p.startsWith("com.android.") && !p.startsWith("android.") &&
                        p != "system" && p != "zygote" && p != "zygote64"
            }
            handler.post {
                status.text = "找到 ${procs.size} 个应用"
                for (proc in procs) {
                    val name = proc["processName"] as String; val pkg = proc["packageName"] as String; val pid = proc["pid"] as Int
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    item.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 14f })
                    item.addView(TextView(this).apply { text = "$pkg | PID: $pid"; setTextColor(Color.parseColor("#888888")); textSize = 11f })
                    item.setOnClickListener {
                        Thread {
                            val ok = MemoryEngine.attachProcess(pid)
                            handler.post { 
                                status.text = if (ok) "✅ 已附加: $name" else "❌ 附加失败"
                                // 附加成功后，通知主应用更新状态
                                if (ok) {
                                    saveAttachedProcess(pid, pkg, name)
                                }
                            }
                        }.start()
                    }
                    list.addView(item)
                }
            }
        }.start()
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

    private fun showSearchPanel() {
        saveLastPanel("search")
        makeDraggablePanel("🔍 内存搜索", { content ->
            val pid = MemoryEngine.getAttachedPid()
            val status = TextView(this).apply {
                text = if (pid != null) "已附加 PID: $pid" else "⚠️ 请先附加进程"
                setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            // 数据类型选择
            val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
            typeRow.addView(TextView(this).apply { text = "类型:"; setTextColor(Color.WHITE); textSize = 12f; setPadding(0, dp(6), dp(8), 0) })
            val types = arrayOf("dword", "float", "double", "byte", "word", "qword")
            val typeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, types)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            typeRow.addView(typeSpinner)
            content.addView(typeRow)

            // 搜索输入
            val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
            val input = EditText(this).apply {
                hint = "输入数值"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            inputRow.addView(input)
            
            // 结果列表（在搜索按钮之前创建，以便实时更新）
            val rsv = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            val rl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); tag = "rl" }
            rsv.addView(rl)
            
            inputRow.addView(smallBtn("搜索") {
                val v = input.text.toString()
                if (v.isEmpty()) { status.text = "❌ 请输入数值"; return@smallBtn }
                if (MemoryEngine.getAttachedPid() == null) { status.text = "❌ 请先附加进程"; return@smallBtn }
                val dtype = typeSpinner.selectedItem.toString()
                searchDataType = dtype
                status.text = "搜索中... 0 个结果"
                rl.removeAllViews()
                searchResults = emptyList()
                
                Thread {
                    val numValue: Any = when (dtype) {
                        "float", "double" -> v.toDoubleOrNull() ?: 0.0
                        else -> v.toLongOrNull() ?: 0
                    }
                    
                    // 实时搜索 - 边搜边显示
                    val results = mutableListOf<Map<String, Any>>()
                    val mapsResult = RootManager.executeRootCommand("cat /proc/${MemoryEngine.getAttachedPid()}/maps 2>/dev/null") ?: ""
                    val hexValue = when (dtype) {
                        "dword" -> {
                            val iv = (numValue as? Number)?.toInt() ?: 0
                            String.format("%02x%02x%02x%02x", iv and 0xFF, (iv shr 8) and 0xFF, (iv shr 16) and 0xFF, (iv shr 24) and 0xFF)
                        }
                        "float" -> {
                            val fv = (numValue as? Number)?.toFloat() ?: 0f
                            val bits = java.lang.Float.floatToIntBits(fv)
                            String.format("%02x%02x%02x%02x", bits and 0xFF, (bits shr 8) and 0xFF, (bits shr 16) and 0xFF, (bits shr 24) and 0xFF)
                        }
                        else -> ""
                    }
                    
                    if (hexValue.isNotEmpty()) {
                        var regionCount = 0
                        for (line in mapsResult.lines()) {
                            if (!line.contains("rw-p")) continue
                            val parts = line.split(" ")
                            if (parts.isEmpty()) continue
                            val addrRange = parts[0].split("-")
                            if (addrRange.size != 2) continue
                            val startAddr = addrRange[0].toLongOrNull(16) ?: continue
                            val endAddr = addrRange[1].toLongOrNull(16) ?: continue
                            val regionSize = endAddr - startAddr
                            if (regionSize > 50 * 1024 * 1024 || regionSize <= 0) continue
                            
                            regionCount++
                            handler.post { status.text = "搜索中... 区域 $regionCount | ${results.size} 个结果" }
                            
                            val searchResult = RootManager.executeRootCommand(
                                "xxd -s $startAddr -l $regionSize -p /proc/${MemoryEngine.getAttachedPid()}/mem 2>/dev/null | grep -bo '$hexValue' | head -50"
                            ) ?: continue
                            
                            for (resultLine in searchResult.lines()) {
                                if (resultLine.isBlank()) continue
                                val offset = resultLine.split(":").firstOrNull()?.toLongOrNull() ?: continue
                                val address = startAddr + offset
                                results.add(mapOf(
                                    "address" to "0x${address.toString(16).uppercase()}",
                                    "addressInt" to address.toInt(),
                                    "value" to numValue,
                                    "type" to dtype
                                ))
                                
                                // 每找到 10 个结果就更新一次界面
                                if (results.size % 10 == 0) {
                                    val currentResults = results.toList()
                                    handler.post {
                                        status.text = "搜索中... ${currentResults.size} 个结果"
                                        updateSearchResults(rl, currentResults)
                                    }
                                }
                                
                                if (results.size >= 500) break
                            }
                            if (results.size >= 500) break
                        }
                    }
                    
                    searchResults = results
                    handler.post {
                        status.text = "找到 ${results.size} 个结果"
                        updateSearchResults(rl, results)
                    }
                }.start()
            })
            content.addView(inputRow)

            // 缩小范围输入
            val refineRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
            val refineInput = EditText(this).apply {
                hint = "新值(缩小范围)"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            refineRow.addView(refineInput)
            refineRow.addView(smallBtn("过滤") {
                val v = refineInput.text.toString()
                if (v.isEmpty() || searchResults.isEmpty()) { status.text = "❌ 请先搜索"; return@smallBtn }
                status.text = "过滤中..."
                Thread {
                    val numValue: Any = when (searchDataType) {
                        "float", "double" -> v.toDoubleOrNull() ?: 0.0
                        else -> v.toLongOrNull() ?: 0
                    }
                    val prevAddrs = searchResults.map { it["addressInt"] as Int }
                    val results = MemoryEngine.filterResults(prevAddrs, numValue, searchDataType)
                    searchResults = results
                    handler.post { status.text = "缩小到 ${results.size} 个结果"; updateSearchResults(rl, results) }
                }.start()
            })
            content.addView(refineRow)

            // 结果列表
            content.addView(rsv)

            // 重置按钮
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
            bar.addView(smallBtn("重置") {
                searchResults = emptyList(); rl.removeAllViews(); status.text = "已重置"
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("关闭窗口") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 300, 500)
    }
    
    private fun updateSearchResults(rl: LinearLayout, results: List<Map<String, Any>>) {
        rl.removeAllViews()
        for (r in results.take(100)) {
            val addr = r["address"] as String
            val v = r["value"]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
            }
            row.addView(TextView(this).apply {
                text = "$addr = $v"; setTextColor(Color.WHITE); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(smallBtn("改") { showWriteDialog(addr, v) })
            row.addView(smallBtn("冻") {
                val ai = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toInt() ?: return@smallBtn
                Thread { if (v != null) MemoryFreezer.freeze(ai, v, searchDataType) }.start()
            })
            rl.addView(row)
        }
        if (results.isEmpty()) {
            rl.addView(TextView(this).apply { text = "未找到结果"; setTextColor(Color.parseColor("#888888")); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(8)) })
        }
    }

    private fun showWriteDialog(addr: String, curVal: Any?) {
        makeDraggablePanel("✏️ 修改内存值", { content ->
            content.addView(TextView(this).apply { text = "地址: $addr"; setTextColor(Color.parseColor("#888888")); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(4)) })
            content.addView(TextView(this).apply { text = "当前值: $curVal"; setTextColor(Color.parseColor("#888888")); textSize = 12f; setPadding(dp(12), dp(4), dp(12), dp(8)) })

            val inp = EditText(this).apply {
                hint = "输入新值"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(curVal.toString())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
            }
            content.addView(inp)

            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
            bar.addView(smallBtn("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("确认修改") {
                val nv = inp.text.toString()
                if (nv.isEmpty()) return@smallBtn
                val ai = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toInt() ?: return@smallBtn
                Thread {
                    val numVal: Any = when (searchDataType) {
                        "float", "double" -> nv.toDoubleOrNull() ?: 0.0
                        else -> nv.toLongOrNull() ?: 0
                    }
                    MemoryEngine.writeMemory(ai, numVal, searchDataType)
                    handler.post { showSearchPanel() }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 280, 280)
    }

    // ==================== 跳转到主应用 ====================

    private fun jumpToPage(page: String) {
        closePanel()
        try {
            // 保存到 SharedPreferences 作为备用
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            prefs.edit().putString("pending_page", page).apply()
            
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("page", page)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== AI 对话面板 ====================

    private fun showAIChatPanel() {
        saveLastPanel("chat")
        makeDraggablePanel("🤖 AI 对话", { content ->
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
                setTextColor(if (attachedPid != -1) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
                textSize = 11f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            // 分割线
            content.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#3A3A3A"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })

            // 消息显示区域
            val messageArea = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#2A2A2A"))
                }
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

            // 输入区域
            val inputArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }

            val inputField = EditText(this).apply {
                hint = "输入你的需求..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#3A3A3A"))
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
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

            val sendBtn = Button(this).apply {
                text = "发送"
                setTextColor(Color.WHITE)
                textSize = 12f
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#6C63FF"))
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
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

                        // 显示"正在思考..."
                        isAiResponding = true
                        val thinkingBubble = createMessageBubble("🤖 AI", "正在思考...", false)
                        messageList.addView(thinkingBubble)
                        messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

                        // 调用真实 LLM API
                        Thread {
                            try {
                                val response = callLlmApi(userInput, attachedName ?: "")
                                handler.post {
                                    // 移除"正在思考..."气泡
                                    messageList.removeView(thinkingBubble)
                                    // 添加真实回复
                                    chatMessages.add(Pair("🤖 AI", response))
                                    messageList.addView(createMessageBubble("🤖 AI", response, false))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                    isAiResponding = false
                                }
                            } catch (e: Exception) {
                                handler.post {
                                    messageList.removeView(thinkingBubble)
                                    val errorMsg = "❌ 请求失败: ${e.message}\n\n请检查设置中的 API 配置"
                                    chatMessages.add(Pair("🤖 AI", errorMsg))
                                    messageList.addView(createMessageBubble("🤖 AI", errorMsg, false))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                    isAiResponding = false
                                }
                            }
                        }.start()

                        messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }

            inputArea.addView(inputField)
            inputArea.addView(sendBtn)
            content.addView(inputArea)

            // 底部按钮栏：保存聊天 + 清空聊天 + 关闭窗口
            val actionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            val smallBtnStyle = { text: String, onClick: () -> Unit ->
                Button(this).apply {
                    this.text = text; setTextColor(Color.WHITE); textSize = 10f
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#6C63FF")) }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnClickListener { onClick() }
                }
            }
            actionBar.addView(smallBtnStyle("💾保存") { saveChatToStorage() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(smallBtnStyle("🗑️清空") {
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
            actionBar.addView(smallBtnStyle("❌关闭") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(actionBar)

            // 滚动到底部
            messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

        }, 320, 550)
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
                setTextColor(if (isUser) Color.parseColor("#03DAC6") else Color.parseColor("#6C63FF"))
                textSize = 11f
                setPadding(0, 0, 0, dp(2))
            })

            if (isUser) {
                // 用户消息用 TextView
                addView(TextView(this@OverlayService).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#3A3A3A"))
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                })
            } else {
                // AI 消息用 WebView 渲染 Markdown/LaTeX/Mermaid
                addView(createMarkdownWebView(message))
            }
        }
    }

    /**
     * 创建 WebView 渲染 Markdown/LaTeX/Mermaid
     */
    private fun createMarkdownWebView(markdownContent: String): WebView {
        // 转义 HTML 特殊字符
        val escapedContent = markdownContent
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
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
        color: #E0E0E0;
        background: #1E1E1E;
        padding: 10px;
        word-wrap: break-word;
        overflow-wrap: break-word;
    }
    h1, h2, h3, h4, h5, h6 {
        color: #BB86FC;
        margin: 12px 0 6px 0;
        font-weight: 600;
    }
    h1 { font-size: 20px; }
    h2 { font-size: 17px; }
    h3 { font-size: 15px; }
    p { margin: 6px 0; }
    a { color: #6C63FF; text-decoration: none; }
    code {
        background: #2D2D2D;
        color: #03DAC6;
        padding: 2px 6px;
        border-radius: 4px;
        font-family: 'Courier New', monospace;
        font-size: 13px;
    }
    pre {
        background: #121212;
        border: 1px solid #3A3A3A;
        border-radius: 8px;
        padding: 12px;
        margin: 8px 0;
        overflow-x: auto;
    }
    pre code {
        background: none;
        padding: 0;
        color: #03DAC6;
    }
    blockquote {
        border-left: 4px solid #6C63FF;
        padding-left: 12px;
        margin: 8px 0;
        color: #AAAAAA;
    }
    ul, ol { margin: 6px 0; padding-left: 24px; }
    li { margin: 3px 0; }
    table {
        border-collapse: collapse;
        width: 100%;
        margin: 8px 0;
    }
    th, td {
        border: 1px solid #3A3A3A;
        padding: 6px 10px;
        text-align: left;
    }
    th { background: #2A2A2A; color: #BB86FC; }
    hr { border: none; border-top: 1px solid #3A3A3A; margin: 12px 0; }
    img { max-width: 100%; border-radius: 8px; }
    strong { color: #FFFFFF; }
    em { color: #CCCCCC; }
</style>
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
    // 初始化 Mermaid
    mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        themeVariables: {
            primaryColor: '#6C63FF',
            primaryTextColor: '#E0E0E0',
            primaryBorderColor: '#3A3A3A',
            lineColor: '#6C63FF',
            secondaryColor: '#2A2A2A',
            tertiaryColor: '#1E1E1E'
        }
    });

    // 配置 marked
    marked.setOptions({
        breaks: true,
        gfm: true
    });

    var rawContent = '$escapedContent';

    try {
        // 渲染 Markdown
        var htmlContent = marked.parse(rawContent);

        // 分离 mermaid 代码块
        var tempDiv = document.createElement('div');
        tempDiv.innerHTML = htmlContent;

        var mermaidBlocks = tempDiv.querySelectorAll('code.language-mermaid');
        var mermaidIndex = 0;

        mermaidBlocks.forEach(function(block) {
            var pre = block.parentElement;
            var placeholder = document.createElement('div');
            placeholder.id = 'mermaid-' + mermaidIndex;
            placeholder.className = 'mermaid';
            placeholder.textContent = block.textContent;
            pre.parentNode.replaceChild(placeholder, pre);
            mermaidIndex++;
        });

        document.getElementById('content').innerHTML = tempDiv.innerHTML;

        // 渲染 Mermaid 图表
        if (mermaidIndex > 0) {
            mermaid.run();
        }

        // 渲染 LaTeX
        renderMathInElement(document.getElementById('content'), {
            delimiters: [
                {left: '$$', right: '$$', display: true},
                {left: '$', right: '$', display: false},
                {left: '\\\\(', right: '\\\\)', display: false},
                {left: '\\\\[', right: '\\\\]', display: true}
            ],
            throwOnError: false
        });
    } catch(e) {
        document.getElementById('content').innerHTML = '<pre>' + rawContent + '</pre>';
    }

    // 通知高度变化
    document.body.onload = function() {
        // 自动调整高度
    };
</script>
</body>
</html>
""".trimIndent()

        return WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
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
                    // 注入 JS 获取内容高度并调整 WebView 大小
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var height = document.body.scrollHeight;
                            if (height > 0) {
                                window.flutterHeight = height;
                            }
                            return height;
                        })();
                        """.trimIndent()
                    ) { value ->
                        try {
                            val height = value.replace("\"", "").toFloatOrNull()
                            if (height != null && height > 0) {
                                val layoutParams = this@apply.layoutParams
                                layoutParams.height = (height * resources.displayMetrics.density).toInt() + dp(20)
                                this@apply.layoutParams = layoutParams
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        }
    }

    // ==================== 真实 LLM API 调用 ====================

    private fun callLlmApi(userInput: String, attachedApp: String): String {
        // 从 SharedPreferences 读取 LLM 配置（由主应用保存）
        val configPrefs = getSharedPreferences("gg_llm_config", Context.MODE_PRIVATE)
        val configJson = configPrefs.getString("config", null)

        var baseUrl = ""
        var apiKey = ""
        var model = "deepseek-chat"

        if (configJson != null) {
            try {
                val json = JSONObject(configJson)
                baseUrl = json.optString("baseUrl", "")
                apiKey = json.optString("apiKey", "")
                model = json.optString("model", "deepseek-chat")
            } catch (e: Exception) {
                // 解析失败，使用默认值
            }
        }

        // 如果没有配置 API，返回提示
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            return "⚠️ 请先在设置中配置 LLM API\n\n打开主应用 → 设置 → LLM API 配置\n\n当前支持：DeepSeek、OpenAI、小米 MiMo 等"
        }

        // 构建系统提示
        val systemPrompt = buildString {
            append("你是一个游戏内存修改助手，名为 GG-AI。你的能力：\n")
            append("1. 数值修改: 用户描述想要修改的游戏数据，你引导他们通过搜索定位内存地址\n")
            append("2. 内存分析: 分析搜索结果，帮助用户识别哪个地址对应目标数据\n")
            append("3. 脚本生成: 根据用户需求自动生成 Lua 修改脚本\n\n")
            if (attachedApp.isNotEmpty()) {
                append("当前已附加游戏进程: $attachedApp\n")
            }
            append("\n使用简洁友好的中文回复，操作步骤用编号列出。")
        }

        // 构建消息历史（最近 10 条）
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        val recentMessages = chatMessages.takeLast(10)
        for ((sender, msg) in recentMessages) {
            val role = if (sender == "👤 我") "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg)
            })
        }

        // 添加当前用户消息
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userInput)
        })

        // 发送 HTTP 请求
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }

        val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
        writer.write(requestBody.toString())
        writer.flush()
        writer.close()

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            throw Exception("HTTP $responseCode: $errorStream")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val responseText = reader.readText()
        reader.close()
        conn.disconnect()

        // 解析响应
        val responseJson = JSONObject(responseText)
        val choices = responseJson.getJSONArray("choices")
        if (choices.length() > 0) {
            val message = choices.getJSONObject(0).getJSONObject("message")
            return message.getString("content")
        }

        return "❌ 未收到 AI 回复"
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
        makeDraggablePanel("📜 脚本库", { content ->
            val status = TextView(this).apply {
                text = "正在加载脚本..."
                setTextColor(Color.WHITE)
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
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(8).toFloat()
                                    setColor(Color.parseColor("#2A2A2A"))
                                }
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = dp(6) }
                            }
                            val nameText = TextView(this).apply {
                                text = script["name"] ?: "未知脚本"
                                setTextColor(Color.WHITE)
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
                                setTextColor(Color.parseColor("#888888"))
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
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
            bar.addView(smallBtn("🔄 刷新") { loadScripts() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("关闭窗口") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 300, 420)
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

    private fun menuBtn(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#2A2A2A")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 11f
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#6C63FF")) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
