package com.yl.aigg.ai_gg666

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LuaJ GG API / AGG 兼容桥接层
 */
object LuaEngine {

    private const val AGG_VIEW_TYPE = "__aggViewType"
    private const val AGG_WINDOW_ID = "__aggWindowId"
    private const val PREFS_OVERLAY = "gg_overlay"
    private const val PREF_BALL_X = "ball_x"
    private const val PREF_BALL_Y = "ball_y"

    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchResults = mutableListOf<Map<String, Any>>()
    private var frozenList = mutableListOf<Map<String, Any>>()
    private val outputLog = StringBuilder()
    private val luaInvokeLock = Any()
    private val nextWindowId = AtomicInteger(1)
    private val aggWindows = ConcurrentHashMap<Int, AggWindowState>()

    private data class AggTab(
        val title: String,
        val viewModel: LuaValue,
        val locked: Boolean
    )

    private data class AggWindowState(
        val id: Int,
        val tabs: MutableList<AggTab> = mutableListOf(),
        var activeIndex: Int = 0,
        var visible: Boolean = true,
        var dialog: AlertDialog? = null,
        var tabBar: LinearLayout? = null,
        var contentFrame: FrameLayout? = null
    )

    fun setContext(ctx: Context?) {
        context = ctx
    }

    fun setActivity(act: android.app.Activity?) {
        context = act
    }

    fun executeScript(scriptContent: String): String {
        synchronized(luaInvokeLock) {
            clearRuntimeState()
            log("Lua 脚本开始执行")

            try {
                val globals = JsePlatform.standardGlobals()
                val gg = LuaTable()
                registerGgApi(gg)
                globals.set("gg", gg)
                val chunk = globals.load(scriptContent)
                chunk.call()
                log("Lua 脚本执行完成")
                return currentOutput()
            } catch (e: Exception) {
                log("Lua 执行错误: ${e.message}")
                return currentOutput()
            }
        }
    }

    private fun clearRuntimeState() {
        synchronized(outputLog) {
            outputLog.clear()
        }
        searchResults.clear()
        frozenList.clear()
        closeAggWindows()
        aggWindows.clear()
    }

    private fun currentOutput(): String {
        synchronized(outputLog) {
            return outputLog.toString()
        }
    }

    private fun log(message: String) {
        synchronized(outputLog) {
            outputLog.appendLine(message)
        }
    }

    private fun dp(value: Int): Int {
        val dm = context?.resources?.displayMetrics ?: return value
        return (value * dm.density).toInt()
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun showDialog(dialog: AlertDialog) {
        val ctx = context
        if (ctx != null && ctx !is android.app.Activity) {
            dialog.window?.setType(getOverlayType())
        }
        dialog.show()
    }

    private fun showChoiceDialog(title: String, items: List<String>): Int {
        val latch = CountDownLatch(1)
        val selectedIndex = AtomicInteger(-1)
        val ctx = context ?: return -1

        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        selectedIndex.set(which + 1)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setNegativeButton("取消") { _, _ ->
                        selectedIndex.set(-1)
                        latch.countDown()
                    }
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                log("对话框显示失败: ${e.message}")
                selectedIndex.set(-1)
                latch.countDown()
            }
        }

        latch.await()
        return selectedIndex.get()
    }

    private fun showInputDialog(title: String, defaultValue: String): String {
        val latch = CountDownLatch(1)
        val inputResult = AtomicReference(defaultValue)
        val ctx = context ?: return defaultValue

        mainHandler.post {
            try {
                val editText = EditText(ctx).apply {
                    setText(defaultValue)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setView(editText)
                    .setPositiveButton("确定") { _, _ ->
                        inputResult.set(editText.text.toString())
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        inputResult.set(defaultValue)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                log("输入框显示失败: ${e.message}")
                inputResult.set(defaultValue)
                latch.countDown()
            }
        }

        latch.await()
        return inputResult.get()
    }

    private fun showAlertDialog(
        message: String,
        positive: String = "确定",
        negative: String? = null,
        neutral: String? = null
    ): Int {
        val latch = CountDownLatch(1)
        val result = AtomicInteger(0)
        val ctx = context ?: return 0

        mainHandler.post {
            try {
                val builder = AlertDialog.Builder(ctx)
                    .setMessage(message)
                    .setCancelable(true)
                    .setOnCancelListener {
                        result.set(0)
                        latch.countDown()
                    }
                    .setPositiveButton(positive) { _, _ ->
                        result.set(1)
                        latch.countDown()
                    }
                if (!negative.isNullOrEmpty()) {
                    builder.setNegativeButton(negative) { _, _ ->
                        result.set(2)
                        latch.countDown()
                    }
                }
                if (!neutral.isNullOrEmpty()) {
                    builder.setNeutralButton(neutral) { _, _ ->
                        result.set(3)
                        latch.countDown()
                    }
                }
                showDialog(builder.create())
            } catch (_: Exception) {
                result.set(0)
                latch.countDown()
            }
        }

        latch.await()
        return result.get()
    }

    private fun parseAddress(text: String): Int? {
        val trimmed = text.trim()
        val hex = trimmed.removePrefix("0x").removePrefix("0X")
        return hex.toLongOrNull(16)?.toInt() ?: trimmed.toLongOrNull()?.toInt()
    }

    private fun luaTypeToDataType(type: Int): String {
        return when (type) {
            1 -> "byte"
            2 -> "word"
            4 -> "dword"
            8 -> "qword"
            16 -> "float"
            32 -> "double"
            else -> "dword"
        }
    }

    private fun dataTypeToLuaType(type: String): Int {
        return when (type) {
            "byte" -> 1
            "word" -> 2
            "dword" -> 4
            "qword" -> 8
            "float" -> 16
            "double" -> 32
            else -> 4
        }
    }

    private fun cleanAggText(text: String): String {
        return text.replace(Regex("\\{\\?([^:}]+)(?::[^}]*)?}"), "$1")
    }

    private fun createAggView(type: String): LuaTable {
        val view = LuaTable()
        view.set(AGG_VIEW_TYPE, LuaValue.valueOf(type))
        view.set("getView", object : ZeroArgFunction() {
            override fun call(): LuaValue = view
        })
        return view
    }

    private fun newAggWindowRef(windowId: Int): LuaTable {
        val ref = LuaTable()
        ref.set(AGG_WINDOW_ID, LuaValue.valueOf(windowId))
        return ref
    }

    private fun getAggWindowId(value: LuaValue): Int? {
        if (!value.istable()) return null
        val id = value.get(AGG_WINDOW_ID).optint(0)
        return if (id > 0) id else null
    }

    private fun closeAggWindows() {
        val windows = aggWindows.values.toList()
        if (windows.isEmpty()) return
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                windows.forEach { state ->
                    try {
                        state.dialog?.dismiss()
                    } catch (_: Exception) {}
                    state.dialog = null
                    state.tabBar = null
                    state.contentFrame = null
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    private fun invokeLuaCallback(callback: LuaValue, vararg args: LuaValue) {
        if (!callback.isfunction()) return
        Thread {
            synchronized(luaInvokeLock) {
                try {
                    callback.invoke(LuaValue.varargsOf(args))
                } catch (e: Exception) {
                    log("Lua 回调执行失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun anyToLua(value: Any?): LuaValue {
        return when (value) {
            null -> LuaValue.NIL
            is LuaValue -> value
            is String -> LuaValue.valueOf(value)
            is Int -> LuaValue.valueOf(value)
            is Long -> LuaValue.valueOf(value.toDouble())
            is Float -> LuaValue.valueOf(value.toDouble())
            is Double -> LuaValue.valueOf(value)
            is Boolean -> LuaValue.valueOf(value)
            is Map<*, *> -> {
                val table = LuaTable()
                value.forEach { (k, v) ->
                    if (k != null) {
                        table.set(k.toString(), anyToLua(v))
                    }
                }
                table
            }
            is List<*> -> {
                val table = LuaTable()
                value.forEachIndexed { index, item ->
                    table.set(index + 1, anyToLua(item))
                }
                table
            }
            else -> LuaValue.valueOf(value.toString())
        }
    }

    private fun asVarargs(value: LuaValue): Varargs {
        return LuaValue.varargsOf(arrayOf<LuaValue>(value))
    }

    private fun renderAggWindow(windowState: AggWindowState) {
        val ctx = context ?: return
        mainHandler.post {
            try {
                val dialog = windowState.dialog ?: buildAggWindowDialog(ctx, windowState)
                rebuildAggTabs(windowState)
                if (windowState.visible) {
                    if (!dialog.isShowing) {
                        showDialog(dialog)
                    }
                } else if (dialog.isShowing) {
                    dialog.hide()
                }
            } catch (e: Exception) {
                log("AGG 视图渲染失败: ${e.message}")
            }
        }
    }

    private fun buildAggWindowDialog(ctx: Context, windowState: AggWindowState): AlertDialog {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF7F2"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val header = TextView(ctx).apply {
            text = "AGG 兼容视图"
            setTextColor(Color.parseColor("#2F1C14"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(header)

        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tabScroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }
        root.addView(tabScroller)

        val contentFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            setBackgroundColor(Color.WHITE)
        }
        root.addView(contentFrame)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val hideButton = Button(ctx).apply {
            text = "隐藏"
            setOnClickListener {
                windowState.visible = false
                windowState.dialog?.hide()
            }
        }
        footer.addView(hideButton)

        val closeButton = Button(ctx).apply {
            text = "关闭"
            setOnClickListener {
                windowState.visible = false
                try {
                    windowState.dialog?.dismiss()
                } catch (_: Exception) {}
                windowState.dialog = null
                windowState.tabBar = null
                windowState.contentFrame = null
            }
        }
        footer.addView(closeButton)

        root.addView(footer)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setCancelable(true)
            .create()
        dialog.setOnCancelListener {
            windowState.visible = false
        }

        windowState.dialog = dialog
        windowState.tabBar = tabBar
        windowState.contentFrame = contentFrame
        return dialog
    }

    private fun rebuildAggTabs(windowState: AggWindowState) {
        val tabBar = windowState.tabBar ?: return
        val contentFrame = windowState.contentFrame ?: return
        val ctx = context ?: return

        tabBar.removeAllViews()
        if (windowState.tabs.isEmpty()) {
            contentFrame.removeAllViews()
            contentFrame.addView(TextView(ctx).apply {
                text = "暂无内容"
                gravity = Gravity.CENTER
            })
            return
        }

        if (windowState.activeIndex !in windowState.tabs.indices) {
            windowState.activeIndex = 0
        }

        windowState.tabs.forEachIndexed { index, tab ->
            val isActive = index == windowState.activeIndex
            val button = Button(ctx).apply {
                text = tab.title
                setBackgroundColor(if (isActive) Color.parseColor("#D9A066") else Color.parseColor("#F2E0D0"))
                setTextColor(Color.parseColor("#2F1C14"))
                setOnClickListener {
                    windowState.activeIndex = index
                    rebuildAggTabs(windowState)
                }
            }
            tabBar.addView(button)
        }

        contentFrame.removeAllViews()
        contentFrame.addView(buildAggTabView(windowState.tabs[windowState.activeIndex]))
    }

    private fun buildAggTabView(tab: AggTab): View {
        val type = tab.viewModel.get(AGG_VIEW_TYPE).optjstring("text")
        return when (type) {
            "text" -> buildTextView(tab.viewModel)
            "list" -> buildListView(tab.viewModel)
            "prompt" -> buildPromptView(tab.viewModel)
            "switch" -> buildSwitchView(tab.viewModel)
            "web" -> buildWebView(tab.viewModel)
            else -> buildTextView(tab.viewModel)
        }
    }

    private fun buildTextView(viewModel: LuaValue): View {
        val ctx = context ?: throw IllegalStateException("Context not set")
        val text = cleanAggText(viewModel.get("text").optjstring(""))
        return ScrollView(ctx).apply {
            addView(TextView(ctx).apply {
                setTextColor(Color.parseColor("#2F1C14"))
                textSize = 14f
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setText(text)
            })
        }
    }

    private fun buildListView(viewModel: LuaValue): View {
        val ctx = context ?: throw IllegalStateException("Context not set")
        val items = viewModel.get("items")
        val flushed = viewModel.get("flushed")

        return ScrollView(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))

                if (flushed.isfunction()) {
                    addView(Button(ctx).apply {
                        text = "刷新"
                        setOnClickListener { invokeLuaCallback(flushed) }
                    })
                }

                if (items.istable()) {
                    val table = items.checktable()
                    for (i in 1..table.length()) {
                        val item = table.get(i)
                        if (!item.istable()) continue
                        val itemTable = item.checktable()
                        val title = cleanAggText(itemTable.get("title").optjstring("菜单$i"))
                        val subTitle = cleanAggText(itemTable.get("subTitle").optjstring(""))
                        val main = itemTable.get("main")

                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundColor(Color.parseColor("#FFF5EA"))
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                            setOnClickListener {
                                log("点击菜单项: $title")
                                invokeLuaCallback(main, LuaValue.valueOf(i))
                            }

                            addView(TextView(ctx).apply {
                                text = title
                                setTextColor(Color.parseColor("#2F1C14"))
                                textSize = 15f
                            })

                            if (subTitle.isNotEmpty()) {
                                addView(TextView(ctx).apply {
                                    text = subTitle
                                    setTextColor(Color.parseColor("#7B5B47"))
                                    textSize = 12f
                                })
                            }
                        })
                    }
                }
            })
        }
    }

    private fun buildSwitchView(viewModel: LuaValue): View {
        val ctx = context ?: throw IllegalStateException("Context not set")
        val items = viewModel.get("items")
        return ScrollView(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))

                if (items.istable()) {
                    val table = items.checktable()
                    for (i in 1..table.length()) {
                        val item = table.get(i)
                        if (!item.istable()) continue
                        val itemTable = item.checktable()
                        val title = cleanAggText(itemTable.get("title").optjstring("开关$i"))
                        val openCallback = itemTable.get("open")
                        val closeCallback = itemTable.get("close")
                        val checked = itemTable.get("isCheck").toboolean()

                        addView(Switch(ctx).apply {
                            text = title
                            isChecked = checked
                            setTextColor(Color.parseColor("#2F1C14"))
                            setOnCheckedChangeListener { _, isChecked ->
                                log("${if (isChecked) "开启" else "关闭"}开关: $title")
                                if (isChecked) {
                                    invokeLuaCallback(openCallback, LuaValue.TRUE)
                                } else {
                                    invokeLuaCallback(closeCallback, LuaValue.FALSE)
                                }
                            }
                        })
                    }
                }
            })
        }
    }

    private fun defaultValueText(value: LuaValue): String {
        return when {
            value.isnil() -> ""
            value.istable() -> {
                val parts = mutableListOf<String>()
                val table = value.checktable()
                for (i in 1..table.length()) {
                    parts.add(table.get(i).tojstring())
                }
                parts.joinToString(", ")
            }
            else -> value.tojstring()
        }
    }

    private fun buildPromptView(viewModel: LuaValue): View {
        val ctx = context ?: throw IllegalStateException("Context not set")
        val prompts = viewModel.get("prompts")
        val defaults = viewModel.get("defaults")
        val types = viewModel.get("types")
        val onClick = viewModel.get("onclick")
        val inputGetters = mutableListOf<() -> LuaValue>()

        return ScrollView(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))

                if (prompts.istable()) {
                    val table = prompts.checktable()
                    for (i in 1..table.length()) {
                        val prompt = table.get(i)
                        val defaultValue = if (defaults.istable()) defaults.get(i) else LuaValue.NIL
                        val type = if (types.istable()) types.get(i).optjstring("text") else "text"

                        when {
                            type == "checkbox" -> {
                                val label = cleanAggText(prompt.tojstring())
                                val checkBox = CheckBox(ctx).apply {
                                    text = label
                                    isChecked = defaultValue.toboolean()
                                    setTextColor(Color.parseColor("#2F1C14"))
                                }
                                addView(checkBox)
                                inputGetters.add { LuaValue.valueOf(checkBox.isChecked) }
                            }

                            prompt.istable() && type != "chip" -> {
                                val options = mutableListOf<String>()
                                val promptTable = prompt.checktable()
                                for (n in 1..promptTable.length()) {
                                    options.add(cleanAggText(promptTable.get(n).tojstring()))
                                }
                                addView(TextView(ctx).apply {
                                    text = "选项$i"
                                    setTextColor(Color.parseColor("#2F1C14"))
                                })
                                val spinner = Spinner(ctx)
                                spinner.adapter = android.widget.ArrayAdapter(
                                    ctx,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    options
                                )
                                val defaultIndex = defaultValue.optint(1).coerceIn(1, maxOf(options.size, 1))
                                if (options.isNotEmpty()) {
                                    spinner.setSelection(defaultIndex - 1)
                                }
                                addView(spinner)
                                inputGetters.add { LuaValue.valueOf(spinner.selectedItemPosition + 1) }
                            }

                            else -> {
                                val label = cleanAggText(if (prompt.istable()) "输入$i" else prompt.tojstring())
                                addView(TextView(ctx).apply {
                                    text = label
                                    setTextColor(Color.parseColor("#2F1C14"))
                                })
                                val editText = EditText(ctx).apply {
                                    setText(defaultValueText(defaultValue))
                                    when (type) {
                                        "number" -> inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                                        else -> inputType = android.text.InputType.TYPE_CLASS_TEXT
                                    }
                                }
                                addView(editText)
                                inputGetters.add {
                                    val text = editText.text.toString()
                                    when (type) {
                                        "number" -> LuaValue.valueOf(text.toDoubleOrNull() ?: 0.0)
                                        else -> LuaValue.valueOf(text)
                                    }
                                }
                            }
                        }
                    }
                }

                addView(Button(ctx).apply {
                    text = "提交"
                    setOnClickListener {
                        val result = LuaTable()
                        inputGetters.forEachIndexed { index, getter ->
                            result.set(index + 1, getter())
                        }
                        log("提交输入视图")
                        invokeLuaCallback(onClick, result)
                    }
                })
            })
        }
    }

    private fun buildWebView(viewModel: LuaValue): View {
        val ctx = context ?: throw IllegalStateException("Context not set")
        val source = viewModel.get("source").optjstring("")
        val funcs = viewModel.get("funcs")

        return FrameLayout(ctx).apply {
            addView(WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (funcs.istable()) {
                            addJavascriptInterface(AggJsBridge(funcs), "AGGBridge")
                            injectJsBridge(this@apply, funcs)
                        }
                    }
                }

                if (source.startsWith("http://") || source.startsWith("https://")) {
                    loadUrl(source)
                } else {
                    loadDataWithBaseURL(null, source, "text/html", "utf-8", null)
                }
            })
        }
    }

    private fun injectJsBridge(webView: WebView, funcs: LuaValue) {
        if (!funcs.istable()) return
        val table = funcs.checktable()
        val js = buildString {
            append("(function(){")
            var key = LuaValue.NIL
            while (true) {
                val next = table.next(key)
                key = next.arg1()
                if (key.isnil()) break
                if (!next.arg(2).isfunction()) continue
                val name = key.tojstring()
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                append(
                    "window['$name']={" +
                        "onClick:function(){AGGBridge.onClick('$name');}," +
                        "number:function(v){if(typeof v==='boolean'){AGGBridge.bool('$name',v);}else{AGGBridge.number('$name',Number(v));}}," +
                        "string:function(v){AGGBridge.string('$name',String(v));}," +
                        "bool:function(v){AGGBridge.bool('$name',!!v);}," +
                        "boolean:function(v){AGGBridge.bool('$name',!!v);}" +
                        "};"
                )
            }
            append("})();")
        }
        webView.evaluateJavascript(js, null)
    }

    private class AggJsBridge(private val funcs: LuaValue) {
        @JavascriptInterface
        fun onClick(name: String) {
            invoke(name)
        }

        @JavascriptInterface
        fun number(name: String, value: Double) {
            invoke(name, LuaValue.valueOf(value))
        }

        @JavascriptInterface
        fun string(name: String, value: String) {
            invoke(name, LuaValue.valueOf(value))
        }

        @JavascriptInterface
        fun bool(name: String, value: Boolean) {
            invoke(name, LuaValue.valueOf(value))
        }

        private fun invoke(name: String, vararg args: LuaValue) {
            val function = funcs.get(name)
            if (function.isfunction()) {
                LuaEngine.invokeLuaCallback(function, *args)
            }
        }
    }

    private fun buildNotification(title: String, message: String) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "gg_ai_script_notice"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "GG-AI 脚本通知",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        builder
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }

    private fun getWindowMetrics(): Pair<Int, Int> {
        val dm = context?.resources?.displayMetrics
        return Pair(dm?.widthPixels ?: 0, dm?.heightPixels ?: 0)
    }

    private fun getBallPosition(): Pair<Int, Int> {
        val ctx = context ?: return Pair(0, dp(200))
        val prefs = ctx.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)
        return Pair(
            prefs.getInt(PREF_BALL_X, 0),
            prefs.getInt(PREF_BALL_Y, dp(200))
        )
    }

    private fun getProcessInfoList(filterSystemApps: Boolean): List<Map<String, Any>> {
        val ctx = context ?: return emptyList()
        val all = ProcessManager.getProcessList(ctx)
        return if (filterSystemApps) {
            all.filter { !(it["isSystem"] as? Boolean ?: false) }
        } else {
            all
        }
    }

    private fun attachProcessByName(processName: String): Boolean {
        val ctx = context ?: return false
        val target = ProcessManager.getProcessList(ctx).firstOrNull { item ->
            val label = item["processName"]?.toString().orEmpty()
            val pkg = item["packageName"]?.toString().orEmpty()
            label.equals(processName, true) ||
                pkg.equals(processName, true) ||
                label.contains(processName, true) ||
                pkg.contains(processName, true)
        } ?: return false

        val pid = (target["pid"] as? Number)?.toInt() ?: return false
        val success = MemoryEngine.attachProcess(pid)
        if (success) {
            ctx.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)
                .edit()
                .putInt("attached_pid", pid)
                .apply()
        }
        return success
    }

    private fun getClassMethodsTable(className: String): LuaTable {
        val result = LuaTable()
        try {
            val clazz = Class.forName(className)
            clazz.methods.forEachIndexed { index, method ->
                val item = LuaTable()
                item.set("method_name", LuaValue.valueOf(method.name))
                item.set("return_type", LuaValue.valueOf(method.returnType.simpleName))
                val parameters = LuaTable()
                method.parameters.forEachIndexed { paramIndex, parameter ->
                    val parameterTable = LuaTable()
                    parameterTable.set("parameter_name", LuaValue.valueOf(parameter.name ?: "arg$paramIndex"))
                    parameterTable.set("parameter_type", LuaValue.valueOf(parameter.type.simpleName))
                    parameters.set(paramIndex + 1, parameterTable)
                }
                item.set("parameters", parameters)
                result.set(index + 1, item)
            }
        } catch (e: Exception) {
            log("获取类方法失败: ${e.message}")
        }
        return result
    }

    private fun isVpnEnabled(): Boolean {
        val ctx = context ?: return false
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } else {
            @Suppress("DEPRECATION")
            manager.allNetworkInfo?.any {
                it.type == android.net.ConnectivityManager.TYPE_VPN && it.isConnected
            } == true
        }
    }

    private fun registerAggApi(gg: LuaTable) {
        gg.set("viewText", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return createAggView("text").apply {
                    set("text", LuaValue.valueOf(arg.tojstring()))
                }
            }
        })

        gg.set("viewList", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val view = createAggView("list")
                view.set("items", args.arg(1))
                view.set("flushed", args.arg(2))
                return asVarargs(view)
            }
        })

        gg.set("viewPrompt", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val view = createAggView("prompt")
                view.set("prompts", args.arg(1))
                view.set("defaults", args.arg(2))
                view.set("types", args.arg(3))
                view.set("onclick", args.arg(4))
                return asVarargs(view)
            }
        })

        gg.set("viewSwitch", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return createAggView("switch").apply {
                    set("items", arg)
                }
            }
        })

        gg.set("viewWeb", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val view = createAggView("web")
                view.set("source", LuaValue.valueOf(args.arg(1).tojstring()))
                view.set("funcs", args.arg(2))
                return asVarargs(view)
            }
        })

        gg.set("composePreview", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val windowId = nextWindowId.getAndIncrement()
                val state = AggWindowState(id = windowId)
                state.tabs.add(AggTab("Preview", arg, false))
                aggWindows[windowId] = state
                renderAggWindow(state)
                return arg
            }
        })

        gg.set("mainTabs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val title = args.arg(1).tojstring()
                val view = args.arg(2)
                val lock = args.arg(3).toboolean()
                val windowId = getAggWindowId(args.arg(4))
                val state = if (windowId != null && aggWindows.containsKey(windowId)) {
                    aggWindows[windowId]!!
                } else {
                    val newId = nextWindowId.getAndIncrement()
                    AggWindowState(id = newId).also { aggWindows[newId] = it }
                }

                val existingIndex = state.tabs.indexOfFirst { it.title == title }
                val tab = AggTab(title = title, viewModel = view, locked = lock)
                if (existingIndex >= 0) {
                    state.tabs[existingIndex] = tab
                    state.activeIndex = existingIndex
                } else {
                    state.tabs.add(tab)
                    state.activeIndex = state.tabs.lastIndex
                }

                log("注册 AGG Tab: $title")
                renderAggWindow(state)
                return asVarargs(newAggWindowRef(state.id))
            }
        })

        gg.set("isTabVisible", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                return LuaValue.valueOf(aggWindows.values.any { it.visible })
            }
        })

        gg.set("setTabVisible", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val visible = arg.toboolean()
                aggWindows.values.forEach {
                    it.visible = visible
                    renderAggWindow(it)
                }
                return LuaValue.NIL
            }
        })

        gg.set("getWM", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val (width, height) = getWindowMetrics()
                val table = LuaTable()
                table.set("width", LuaValue.valueOf(width))
                table.set("height", LuaValue.valueOf(height))
                return table
            }
        })

        gg.set("getHot", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val (x, y) = getBallPosition()
                val table = LuaTable()
                table.set("x", LuaValue.valueOf(x))
                table.set("y", LuaValue.valueOf(y))
                return table
            }
        })

        gg.set("getProcessInfo", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val filterSystem = if (args.narg() >= 1 && !args.arg(1).isnil()) args.arg(1).toboolean() else false
                return asVarargs(anyToLua(getProcessInfoList(filterSystem)))
            }
        })

        gg.set("setProcessInfo", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return LuaValue.valueOf(attachProcessByName(arg.tojstring()))
            }
        })

        gg.set("getClassMethods", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return getClassMethodsTable(arg.tojstring())
            }
        })

        gg.set("isVPN", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(isVpnEnabled())
        })

        gg.set("notification", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val title = args.arg(1).optjstring("GG-AI")
                val message = args.arg(2).optjstring("")
                buildNotification(title, message)
                return asVarargs(LuaValue.NIL)
            }
        })
    }

    private fun registerGgApi(gg: LuaTable) {
        // gg.toast
        gg.set("toast", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                log("提示: $msg")
                mainHandler.post {
                    try {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                return LuaValue.NIL
            }
        })

        // gg.alert
        gg.set("alert", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.arg(1).optjstring("")
                val positive = args.arg(2).optjstring("确定")
                val negative = args.arg(3).takeIf { !it.isnil() }?.tojstring()
                val neutral = args.arg(4).takeIf { !it.isnil() }?.tojstring()
                log("弹窗: $text")
                return asVarargs(LuaValue.valueOf(showAlertDialog(text, positive, negative, neutral)))
            }
        })

        // gg.prompt
        gg.set("prompt", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val msg = args.arg(1).tojstring()
                var defaultValue = ""
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    val table = args.arg(2).checktable()
                    if (table.length() > 0) {
                        defaultValue = table.get(1).tojstring()
                    }
                }
                val result = showInputDialog(msg, defaultValue)
                log("输入: $msg -> $result")
                val resultTable = LuaTable()
                resultTable.set(1, LuaValue.valueOf(result))
                return asVarargs(resultTable)
            }
        })

        // gg.choice
        gg.set("choice", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val items = mutableListOf<String>()
                var title = "选择"
                if (args.arg(1).istable()) {
                    val table = args.arg(1).checktable()
                    for (i in 1..table.length()) {
                        items.add(cleanAggText(table.get(i).tojstring()))
                    }
                }
                if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    title = args.arg(3).tojstring()
                } else if (args.narg() >= 2 && args.arg(2).isstring()) {
                    title = args.arg(2).tojstring()
                }
                if (items.isEmpty()) {
                    return LuaValue.NIL
                }

                val selected = showChoiceDialog(title, items)
                if (selected > 0) {
                    log("选择: ${items[selected - 1]}")
                } else {
                    log("选择已取消")
                }
                return if (selected > 0) {
                    asVarargs(LuaValue.valueOf(selected))
                } else {
                    asVarargs(LuaValue.NIL)
                }
            }
        })

        // gg.searchNumber
        gg.set("searchNumber", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val value = arg1.tojstring()
                val type = luaTypeToDataType(arg2.toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.toDoubleOrNull() ?: 0.0
                    else -> value.toLongOrNull() ?: 0
                }
                val results = MemoryEngine.searchExact(numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                log("搜索 $value ($type): 找到 ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.refineNumber
        gg.set("refineNumber", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val value = arg.tojstring()
                val prevAddresses = searchResults.mapNotNull { (it["addressInt"] as? Number)?.toLong() }
                val numValue: Any = value.toDoubleOrNull() ?: value.toLongOrNull() ?: 0
                val type = searchResults.firstOrNull()?.get("type") as? String ?: "dword"
                val results = MemoryEngine.filterResults(prevAddresses, numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                log("过滤后: ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.getResultsCount / gg.getResultCount
        val getResultsCountFunc = object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(searchResults.size)
        }
        gg.set("getResultsCount", getResultsCountFunc)
        gg.set("getResultCount", getResultsCountFunc)

        // gg.getResults
        gg.set("getResults", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val count = arg.toint()
                val table = LuaTable()
                val takeCount = minOf(count, searchResults.size)
                for (i in 0 until takeCount) {
                    val result = searchResults[i]
                    val item = LuaTable()
                    item.set("address", LuaValue.valueOf(result["address"] as? String ?: "0"))
                    item.set("value", LuaValue.valueOf((result["value"] as? Number)?.toDouble() ?: 0.0))
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType(result["type"] as? String ?: "dword")))
                    table.set(i + 1, item)
                }
                return table
            }
        })

        // gg.setValues
        gg.set("setValues", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf(0)
                val table = arg.checktable()
                var count = 0
                for (i in 1..table.length()) {
                    val item = table.get(i)
                    if (!item.istable()) continue
                    val itemTable = item.checktable()
                    val address = parseAddress(itemTable.get("address").tojstring()) ?: continue
                    val value = itemTable.get("value")
                    val flags = itemTable.get("flags")
                    val type = luaTypeToDataType(flags.toint())
                    val numValue: Any = when (type) {
                        "float", "double" -> value.todouble()
                        else -> value.tolong()
                    }
                    if (MemoryEngine.writeMemory(address, numValue, type)) {
                        count++
                    }
                }
                log("已修改 $count 个地址")
                return LuaValue.valueOf(count)
            }
        })

        // gg.writeMemory
        gg.set("writeMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = parseAddress(args.arg(1).tojstring()) ?: return asVarargs(LuaValue.valueOf(false))
                val value = args.arg(2)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.todouble()
                    else -> value.tolong()
                }
                val success = MemoryEngine.writeMemory(address, numValue, type)
                if (success) {
                    log("写入 0x${address.toString(16).uppercase()} = $numValue")
                }
                return asVarargs(LuaValue.valueOf(success))
            }
        })

        // gg.freeze
        gg.set("freeze", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = parseAddress(args.arg(1).tojstring()) ?: return asVarargs(LuaValue.valueOf(false))
                val value = args.arg(2)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.todouble()
                    else -> value.tolong()
                }
                val success = MemoryFreezer.freeze(address, numValue, type)
                if (success) {
                    log("冻结 0x${address.toString(16).uppercase()} = $numValue")
                }
                return asVarargs(LuaValue.valueOf(success))
            }
        })

        // gg.addListItems
        gg.set("addListItems", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf(0)
                val table = arg.checktable()
                var count = 0
                for (i in 1..table.length()) {
                    val item = table.get(i)
                    if (!item.istable()) continue
                    val itemTable = item.checktable()
                    val freeze = itemTable.get("freeze")
                    if (!freeze.toboolean()) continue
                    val address = parseAddress(itemTable.get("address").tojstring()) ?: continue
                    val value = itemTable.get("value")
                    val flags = itemTable.get("flags")
                    val type = luaTypeToDataType(flags.toint())
                    val numValue: Any = when (type) {
                        "float", "double" -> value.todouble()
                        else -> value.tolong()
                    }
                    if (MemoryFreezer.freeze(address, numValue, type)) {
                        count++
                        frozenList.add(
                            mapOf(
                                "address" to itemTable.get("address").tojstring(),
                                "value" to numValue,
                                "type" to type
                            )
                        )
                    }
                }
                log("已冻结 $count 个地址")
                return LuaValue.valueOf(count)
            }
        })

        // gg.clearResults
        gg.set("clearResults", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                searchResults.clear()
                return LuaValue.NIL
            }
        })

        // gg.clearList
        gg.set("clearList", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                frozenList.clear()
                return LuaValue.NIL
            }
        })

        // gg.sleep
        gg.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                try {
                    Thread.sleep(arg.tolong())
                } catch (_: Exception) {}
                return LuaValue.NIL
            }
        })

        // Constants
        gg.set("TYPE_BYTE", LuaValue.valueOf(1))
        gg.set("TYPE_WORD", LuaValue.valueOf(2))
        gg.set("TYPE_DWORD", LuaValue.valueOf(4))
        gg.set("TYPE_QWORD", LuaValue.valueOf(8))
        gg.set("TYPE_FLOAT", LuaValue.valueOf(16))
        gg.set("TYPE_DOUBLE", LuaValue.valueOf(32))

        registerAggApi(gg)
    }
}
