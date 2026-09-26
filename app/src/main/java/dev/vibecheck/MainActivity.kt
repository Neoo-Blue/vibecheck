package dev.vibecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The dashboard. "Setup" holds only what it takes to get going; "Tools" holds everything else
 * as tiles. Any tile can be hidden with one tap and brought back from the bottom of the Tools
 * tab, so the screen shows only what you actually use. Everything saves by itself.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var people: PersonStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Setup fields, written back when leaving the screen or switching tabs.
    private lateinit var keyField: EditText
    private lateinit var orKeyField: EditText
    private lateinit var contextField: EditText
    private lateinit var extraPkgs: EditText
    private var appBoxes = listOf<Pair<String, CheckBox>>()
    private var deepModelField: EditText? = null
    private var visionModelField: EditText? = null
    private var loaded = false

    private lateinit var readiness: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var setupPage: ScrollView
    private lateinit var toolsPage: ScrollView
    private lateinit var tabSetup: TextView
    private lateinit var tabTools: TextView

    // Tool tiles that show live numbers; null while the tile is hidden.
    private var pauseText: TextView? = null
    private var peopleText: TextView? = null
    private var usageText: TextView? = null
    private var batteryText: TextView? = null
    private var diagnostics: TextView? = null
    private var diagUrl: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.lang   // applies the UI language to L before anything is built
        people = PersonStore(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header())
        readiness = text("", 13f, sub()).also {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(20); rightMargin = dp(20) })
        }
        root.addView(tabs())
        setupPage = page(buildSetup())
        toolsPage = page(buildTools())
        val pages = FrameLayout(this)
        pages.addView(setupPage)
        pages.addView(toolsPage)
        root.addView(pages, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applyInsets(root)
        setContentView(root)
        loaded = true
        selectTab(prefs.tab)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshTools()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /**
     * Android 15 draws apps targeting it edge to edge: without this the title sat under the status
     * bar and the last buttons under the navigation bar. The keyboard is an inset there too, so
     * fields stay visible. Older versions still fit the content below the bars themselves.
     */
    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT < 35) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    // ---- frame: title, language, tabs ----

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(14), dp(2))
        addView(text(L.t("Vibecheck 读空气", "Vibecheck"), 22f, fg(), bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("中文", !L.en) { switchLang("zh") })
        addView(pill("EN", L.en) { switchLang("en") })
    }

    private fun tabs(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(10), dp(16), dp(6))
        tabSetup = tab(L.t("设置", "Setup")) { selectTab(0) }
        tabTools = tab(L.t("工具", "Tools")) { selectTab(1) }
        addView(tabSetup, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(tabTools, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    }

    private fun selectTab(i: Int) {
        saveFields()
        prefs.tab = i
        setupPage.visibility = if (i == 1) View.GONE else View.VISIBLE
        toolsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        styleTab(tabSetup, i != 1)
        styleTab(tabTools, i == 1)
        if (i == 1) refreshTools() else refreshSetup()
    }

    private fun switchLang(want: String) {
        if ((want == "en") == L.en) return
        saveFields()
        prefs.lang = want
        recreate()
    }

    // ---- Setup: only what it takes to get going ----

    private fun buildSetup(): View = column().apply {
        addView(tile(null, L.t("开始使用", "Get started")) {
            checklist = column()
            addView(checklist)
        })

        addView(tile(null, L.t("判断引擎", "Judging engine"), L.t(
            "Jev 判断每条消息；OpenRouter 用于深思、回复草稿和学习此人。只有 OpenRouter Key 就选第二项，一个 Key 全搞定。",
            "Jev judges each message; OpenRouter powers Think, reply drafts and Learn. Only have an OpenRouter key? Pick the second option: one key covers everything.")) {
            val group = RadioGroup(this@MainActivity)
            // RadioGroup tracks selection by child id; NO_ID never clears the previous button.
            val ts = RadioButton(this@MainActivity).apply { id = View.generateViewId(); text = L.t("Jev，走 TypeSafe", "Jev via TypeSafe"); setTextColor(fg()) }
            val or = RadioButton(this@MainActivity).apply { id = View.generateViewId(); text = L.t("Jev，走 OpenRouter", "Jev via OpenRouter"); setTextColor(fg()) }
            group.addView(ts)
            group.addView(or)
            group.check(if (prefs.judge == Judge.OPENROUTER) or.id else ts.id)
            group.setOnCheckedChangeListener { _, id ->
                prefs.judge = if (id == or.id) Judge.OPENROUTER else Judge.TYPESAFE
                refreshSetup()
            }
            addView(group)
            keyField = secret(L.t("TypeSafe API Key", "TypeSafe API key"), prefs.apiKey, "sk-...")
            orKeyField = secret(L.t("OpenRouter Key", "OpenRouter key"), prefs.orKey, "sk-or-v1-...")
            addView(CheckBox(this@MainActivity).apply {
                text = L.t("显示 Key", "Show keys")
                setTextColor(sub())
                setOnCheckedChangeListener { _, show ->
                    for (f in listOf(keyField, orKeyField)) {
                        f.inputType = InputType.TYPE_CLASS_TEXT or
                            (if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                        f.setSelection(f.text.length)
                    }
                }
            })
            addView(buttons(L.t("测试", "Test") to { testEngine() }))
        })

        addView(tile(null, L.t("在这些应用里解读", "Watch these apps"), L.t(
            "Discord 和 Slack 把所有消息都放在左边，分不出谁说的，所以不在列表里。",
            "Discord and Slack put every message on the left, so it can't tell who said what; they're left out.")) {
            val watched = prefs.packageList.toSet()
            val known = Apps.KNOWN.sortedBy { if (installed(it.first)) 0 else 1 }
            appBoxes = known.map { (pkg, name) ->
                val here = installed(pkg)
                pkg to CheckBox(this@MainActivity).apply {
                    text = if (here) name else name + L.t("（未安装）", " (not installed)")
                    setTextColor(fg())
                    alpha = if (here) 1f else 0.55f
                    isChecked = pkg in watched
                    setOnCheckedChangeListener { _, _ -> savePackages() }
                }.also { addView(it) }
            }
            extraPkgs = field(L.t("其他应用的包名，逗号分隔", "Other package names, comma separated"),
                watched.filter { w -> Apps.KNOWN.none { it.first == w } }.joinToString(","))
        })

        addView(tile(null, L.t("行为", "Behaviour")) {
            addView(switch(L.t("启用解读", "Judging on"), prefs.enabled) { prefs.enabled = it; refreshSetup() })
            addView(switch(L.t("值得细看的消息自动深思（只在非闲聊时）", "Auto deep read on turns that matter (not on small talk)"), prefs.autoDeep) { prefs.autoDeep = it })
            addView(switch(L.t("读不到界面时用截图识字（微信、Telegram 必须开）", "Read the screen with OCR when an app hides its text (WeChat, Telegram)"), prefs.ocr) { prefs.ocr = it })
            addView(switch(L.t("从后续走向学习（校准风险、重排动作）", "Learn from what happens next (calibrate risk, re-rank moves)"), prefs.learning) { prefs.learning = it })
            addView(switch(L.t("在聊天里顺便记住每个人（只在手机上）", "Remember people while you chat (on this phone only)"), prefs.passive) { prefs.passive = it })
        })

        addView(tile(null, L.t("关系背景", "Relationship context"), L.t(
            "会随对话一起发给模型。给某个人单独写背景：工具 → 人物记忆。",
            "Sent to the model with the chat. For one person only: Tools → People.")) {
            contextField = field(L.t("例：交往两年的女友，最近因为我总忘事在生气", "e.g. girlfriend of two years, annoyed lately because I keep forgetting things"), prefs.context, lines = 2)
        })

        addView(hint(L.t("所有改动自动保存。", "Everything saves automatically.")).apply { gravity = Gravity.CENTER })
    }

    private fun refreshSetup() {
        if (!::checklist.isInitialized) return
        checklist.removeAllViews()
        val on = serviceEnabled()
        val connected = Diag.connected
        checklist.addView(check(on && connected, when {
            on && connected -> L.t("无障碍服务已开启", "Accessibility service is on")
            on -> L.t("开关开着但服务没连上：关掉再打开一次", "Switched on but not connected: turn it off and on again")
            else -> L.t("无障碍服务未开启", "Accessibility service is off")
        }))
        if (!on || !connected) {
            checklist.addView(buttons(L.t("打开无障碍设置", "Open accessibility settings") to { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
            if (Build.VERSION.SDK_INT >= 33) {
                checklist.addView(hint(L.t(
                    "开关是灰的？应用信息 → 右上角 ⋮ → 允许受限设置，确认后再回来开启。",
                    "Toggle greyed out? App info → ⋮ menu → Allow restricted settings, confirm, then come back.")))
                checklist.addView(buttons(L.t("应用信息", "App info") to { openAppInfo() }))
            }
        }
        val missing = Judge.missingKey(prefs)
        checklist.addView(check(missing == null, missing ?: L.t("判断引擎的 Key 已填", "Judging key is set")))
        val apps = prefs.packageList.size
        checklist.addView(check(apps > 0, L.t("在 $apps 个应用里解读", "Watching $apps apps")))
        if (!prefs.enabled) checklist.addView(check(false, L.t("解读已关闭（行为 → 启用解读）", "Judging is off (Behaviour → Judging on)")))
        if (prefs.snoozed) checklist.addView(check(false, L.t("已暂停，到 ${hhmm(prefs.snoozeUntil)} 恢复", "Paused until ${hhmm(prefs.snoozeUntil)}")))

        val ready = on && connected && missing == null && apps > 0 && prefs.enabled && !prefs.snoozed
        readiness.text = if (ready) L.t("✓ 一切就绪：打开聊天，边上会出现小气泡。", "✓ All set: open a chat and the bubble appears at the edge.")
            else L.t("还差几步，见下面。", "A few steps left; see below.")
    }

    // ---- Tools: every extra, each one hideable ----

    private fun buildTools(): View = column().apply {
        pauseText = null; peopleText = null; usageText = null; batteryText = null; diagnostics = null; diagUrl = null
        deepModelField = null; visionModelField = null
        val hidden = prefs.hiddenTools
        addView(hint(L.t("用不到的工具点「隐藏」，想要时在最下面找回。", "Hide what you don't use; bring it back from the bottom of this tab.")))
        for (t in TOOLS) if (t !in hidden) addView(tool(t))
        if (hidden.isNotEmpty()) {
            addView(text(L.t("已隐藏（点一下恢复）", "Hidden (tap to bring back)"), 13f, sub()).apply { setPadding(dp(4), dp(12), 0, dp(6)) })
            val chips = TOOLS.filter { it in hidden }.map { t -> pill("＋ " + toolName(t), false) { showTool(t) } } +
                pill(L.t("全部恢复", "Show all"), true) { showTool(null) }
            addView(wrapRow(chips))
        }
    }

    private fun toolName(t: String): String = when (t) {
        "pause" -> L.t("暂停", "Pause")
        "people" -> L.t("人物记忆", "People")
        "card" -> L.t("卡片", "Card")
        "usage" -> L.t("用量", "Usage")
        "backup" -> L.t("备份", "Backup")
        "models" -> L.t("模型", "Models")
        "battery" -> L.t("保持运行", "Keep it running")
        "diagnostics" -> L.t("诊断", "Diagnostics")
        else -> L.t("使用说明", "How it works")
    }

    private fun tool(t: String): View = when (t) {
        "pause" -> tile(t, toolName(t), L.t("临时不解读、不花钱；到时间自动恢复。", "Stop judging for a while without touching settings; it comes back by itself.")) {
            pauseText = text("", 14f, fg()).also { addView(it) }
            addView(buttons(
                L.t("暂停 1 小时", "1 hour") to { snooze(60 * 60_000L) },
                L.t("到明早 8 点", "Until 8 am") to { snooze(untilMorning() - System.currentTimeMillis()) },
                L.t("恢复", "Resume") to { prefs.snoozeUntil = 0L; refreshTools() },
            ))
        }
        "people" -> tile(t, toolName(t), L.t(
            "每个人单独学习，全部存在手机上。在「管理」里查看学到了什么、写专属背景、暂停、合并或忘记。",
            "Each person is learned separately, all on this phone. Manage to see what was learned, add a note, pause, merge or forget.")) {
            peopleText = text("", 14f, fg()).also { addView(it) }
            addView(buttons(
                L.t("管理", "Manage") to { managePeople() },
                L.t("清理空记录", "Clean up") to { cleanUp() },
                L.t("全部忘记", "Forget all") to {
                    confirm(L.t("忘记所有人？学到的一切都会删除，不能撤销。", "Forget everyone? Everything learned is deleted and can't be undone."), L.t("全部忘记", "Forget all")) {
                        people.forgetAll(); refreshTools(); toast(L.t("已清空", "Cleared"))
                    }
                },
            ))
        }
        "card" -> tile(t, toolName(t), L.t("拖动气泡可以换位置，长按气泡打开菜单。", "Drag the bubble to move it; long-press it for the menu.")) {
            addView(text(L.t("字号", "Text size"), 13f, sub()))
            val sizes = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.HORIZONTAL }
            val labels = listOf(L.t("小", "Small"), L.t("中", "Normal"), L.t("大", "Large"))
            val ids = labels.map { View.generateViewId() }
            labels.forEachIndexed { i, l -> sizes.addView(RadioButton(this@MainActivity).apply { id = ids[i]; text = l; setTextColor(fg()) }) }
            sizes.check(ids[prefs.cardSize])
            sizes.setOnCheckedChangeListener { _, id -> prefs.cardSize = ids.indexOf(id).coerceAtLeast(0) }
            addView(sizes)
            addView(text(L.t("卡片上的按钮", "Buttons on the card"), 13f, sub()).apply { setPadding(0, dp(8), 0, 0) })
            for ((id, label) in listOf(
                "think" to L.t("深思", "Think"), "reply" to L.t("回复", "Reply"),
                "learn" to L.t("学习", "Learn"), "menu" to L.t("⋯ 菜单", "⋯ menu"),
            )) addView(CheckBox(this@MainActivity).apply {
                text = label
                setTextColor(fg())
                isChecked = id !in prefs.hiddenButtons
                setOnCheckedChangeListener { _, on -> prefs.hiddenButtons = if (on) prefs.hiddenButtons - id else prefs.hiddenButtons + id }
            })
            addView(switch(L.t("高风险时振动一下", "Buzz on high-risk messages"), prefs.buzz) { prefs.buzz = it })
            addView(buttons(L.t("气泡回到默认位置", "Reset bubble position") to {
                prefs.bubbleY = -1f; prefs.bubbleLeft = false; toast(L.t("下次出现时回到右边", "It goes back to the right edge next time"))
            }))
        }
        "usage" -> tile(t, toolName(t), L.t("付费接口调用了多少次。", "How many paid calls were made.")) {
            usageText = text("", 14f, fg()).also { addView(it) }
        }
        "backup" -> tile(t, toolName(t), L.t(
            "把人物记忆导出成文件，换手机时导入。API Key 不在里面。",
            "Save people memory to a file and bring it back on a new phone. API keys are not included.")) {
            addView(buttons(L.t("导出", "Export") to { exportMemory() }, L.t("导入", "Import") to { importMemory() }))
        }
        "models" -> tile(t, toolName(t), L.t(
            "深思、回复和学习此人用的 OpenRouter 模型。留空用默认。",
            "OpenRouter models for Think, Reply and Learn. Leave empty for the default.")) {
            deepModelField = field(L.t("深思模型", "Deep model"), prefs.deepModel, lines = 1, label = true)
            visionModelField = field(L.t("看图模型（截图识字的聊天用）", "Vision model (chats read by OCR)"), prefs.visionModel, lines = 1, label = true)
            addView(buttons(L.t("测试深思模型", "Test deep model") to { testDeep() }))
        }
        "battery" -> tile(t, toolName(t), L.t(
            "有些手机（小米、OPPO、华为等）会杀掉后台的无障碍服务。把 Vibecheck 设为不受电池优化限制。",
            "Some phones (Xiaomi, OPPO, Huawei…) kill background accessibility services. Exempt Vibecheck from battery optimisation.")) {
            batteryText = text("", 14f, fg()).also { addView(it) }
            addView(buttons(
                L.t("电池设置", "Battery settings") to { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                L.t("应用信息", "App info") to { openAppInfo() },
            ))
        }
        "diagnostics" -> tile(t, toolName(t), L.t("没有气泡时看这里。", "Look here when no bubble shows up.")) {
            diagnostics = text("", 12f, sub()).also { it.typeface = Typeface.MONOSPACE; addView(it) }
            addView(buttons(L.t("刷新", "Refresh") to { refreshDiagnostics() }))
            addView(switch(L.t("调试模式（只显示抓到的文本，不调用模型）", "Debug mode (show what is read, no model calls)"), prefs.debug) { prefs.debug = it })
            addView(switch(L.t("局域网排查接口（会暴露聊天内容，用完关掉）", "LAN debug endpoint (exposes chat content; switch off when done)"), prefs.remoteDiag) {
                prefs.remoteDiag = it; refreshDiagUrl()
            })
            diagUrl = text("", 12f, sub()).also { it.setTextIsSelectable(true); addView(it) }
            addView(buttons(
                L.t("复制地址", "Copy URL") to {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("vibecheck", diagUrl?.text ?: ""))
                    toast(L.t("已复制", "Copied"))
                },
                L.t("换口令", "New token") to { prefs.newToken(); refreshDiagUrl(); toast(L.t("口令已更换，旧地址立即失效", "Token changed; the old URL stops working now")) },
            ))
        }
        else -> tile(t, toolName(t)) { addView(text(guide(), 13f, fg())) }
    }

    private fun hideTool(t: String) {
        prefs.hiddenTools = prefs.hiddenTools + t
        rebuildTools()
        toast(L.t("已隐藏「${toolName(t)}」，可在工具页最下面恢复", "${toolName(t)} hidden; bring it back from the bottom of Tools"))
    }

    private fun showTool(t: String?) {
        prefs.hiddenTools = if (t == null) emptySet() else prefs.hiddenTools - t
        rebuildTools()
    }

    private fun rebuildTools() {
        saveFields()
        val y = toolsPage.scrollY
        toolsPage.removeAllViews()
        toolsPage.addView(buildTools())
        refreshTools()
        toolsPage.post { toolsPage.scrollTo(0, y) }
    }

    private fun refreshTools() {
        pauseText?.text = if (prefs.snoozed) L.t("已暂停，到 ${hhmm(prefs.snoozeUntil)} 恢复", "Paused until ${hhmm(prefs.snoozeUntil)}")
            else L.t("正在解读", "Judging is on")
        peopleText?.text = L.t("记住了 ${people.index().size} 个人", "${people.index().size} people remembered")
        usageText?.text = usage()
        batteryText?.text = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            L.t("✓ 已不受电池优化限制", "✓ Not restricted by battery optimisation")
            else L.t("✗ 受电池优化限制，服务可能被杀掉", "✗ Battery optimisation may stop the service")
        refreshDiagnostics()
        refreshDiagUrl()
    }

    private fun snooze(ms: Long) {
        prefs.snoozeUntil = System.currentTimeMillis() + ms
        refreshTools()
        toast(L.t("暂停到 ${hhmm(prefs.snoozeUntil)}", "Paused until ${hhmm(prefs.snoozeUntil)}"))
    }

    private fun untilMorning(): Long = Calendar.getInstance().apply {
        if (get(Calendar.HOUR_OF_DAY) >= 8) add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun usage(): String {
        fun line(kind: String, zh: String, en: String) =
            L.t("$zh：今天 ${prefs.usedToday(kind)}，累计 ${prefs.usedTotal(kind)}", "$en: ${prefs.usedToday(kind)} today, ${prefs.usedTotal(kind)} total")
        return listOf(
            line(Prefs.USE_JUDGE, "判断", "Judgments"),
            line(Prefs.USE_DEEP, "深思", "Deep reads"),
            line(Prefs.USE_REPLY, "回复草稿", "Reply drafts"),
            line(Prefs.USE_BIO, "档案", "Profiles"),
        ).joinToString("\n")
    }

    private fun refreshDiagnostics() {
        val d = diagnostics ?: return
        val enabled = serviceEnabled()
        val lines = mutableListOf(
            L.t("系统开关：", "System toggle: ") + if (enabled) L.t("已开启", "on") else L.t("未开启", "off"),
            when {
                Diag.connected -> L.t("服务已连接：是", "Service connected: yes")
                enabled -> L.t("服务已连接：否（关掉再打开一次）", "Service connected: no (switch it off and on)")
                else -> L.t("服务已连接：否", "Service connected: no")
            },
            L.t("收到事件：${Diag.events} 次，最近来自 ${Diag.lastPackage.ifBlank { "无" }}", "Events: ${Diag.events}, latest from ${Diag.lastPackage.ifBlank { "none" }}"),
            L.t("最近扫描：${Diag.lastScan.ifBlank { "还没扫过" }}", "Last scan: ${Diag.lastScan.ifBlank { "none yet" }}"),
            L.t("最近错误：${Diag.lastError.ifBlank { "无" }}", "Last error: ${Diag.lastError.ifBlank { "none" }}"),
        )
        if (Diag.events == 0 && enabled) lines.add(L.t("事件为 0：多半是包名没对上，或服务需要关掉再打开一次。", "Zero events: usually the app list, or the service needs an off/on."))
        val log = Diag.dump().lines().takeLast(8).filter { it.isNotBlank() }
        if (log.isNotEmpty()) { lines.add(""); lines.addAll(log) }
        d.text = lines.joinToString("\n")
    }

    private fun refreshDiagUrl() {
        diagUrl?.text = if (prefs.remoteDiag)
            "http://${ChatReaderService.lanAddress()}:${DebugServer.PORT}/status?t=${prefs.diagToken}"
        else L.t("排查接口已关闭", "Debug endpoint is off")
    }

    // ---- people ----

    private fun managePeople() {
        val all = people.entries()
        if (all.isEmpty()) { toast(L.t("还没认识任何人", "Nobody remembered yet")); return }
        val box = column().apply { setPadding(dp(20), dp(4), dp(20), 0) }
        val search = EditText(this).apply { hint = L.t("搜索名字或应用", "Search name or app"); isSingleLine = true }
        box.addView(search)
        val list = ListView(this)
        box.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        var shown = all
        fun label(e: PersonStore.Entry) = buildString {
            append(e.name).append("  ·  ").append(e.apps.joinToString("+") { Apps.label(it) })
            append("  ·  ").append(L.t("${e.messages} 条", "${e.messages} msgs"))
            if (e.muted) append(L.t("  ·  已暂停", "  ·  paused"))
        }
        fun fill() { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { label(it) }) }
        fill()
        val dialog = AlertDialog.Builder(this)
            .setTitle(L.t("人物记忆（${all.size}）", "People (${all.size})"))
            .setView(box)
            .setNegativeButton(L.t("关闭", "Close"), null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                shown = if (q.isEmpty()) all else all.filter { e ->
                    e.name.contains(q, ignoreCase = true) || e.apps.any { Apps.label(it).contains(q, ignoreCase = true) }
                }
                fill()
            }
        })
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { dialog.dismiss(); personDetail(it.id) }
        }
        dialog.show()
    }

    private fun personDetail(id: String) {
        val r = people.loadId(id)
        val box = column().apply { setPadding(dp(20), dp(4), dp(20), 0) }
        if (r.muted) box.addView(text(L.t("已暂停：不解读，也不记录。", "Paused: not judged, nothing recorded."), 13f, warn()))
        box.addView(text(people.describe(id), 13f, sub()).apply { setTextIsSelectable(true) })
        box.addView(text(L.t("专属背景（发给模型，优先于通用背景）", "Context for this person (sent to the model instead of the general one)"), 13f, fg()).apply { setPadding(0, dp(12), 0, 0) })
        val note = EditText(this).apply { setText(r.note); minLines = 2; hint = L.t("例：大学室友，说话很直", "e.g. college roommate, very blunt") }
        box.addView(note)
        AlertDialog.Builder(this)
            .setTitle("${r.name}（${r.apps.joinToString(" · ") { Apps.label(it) }}）")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("保存", "Save")) { _, _ ->
                val fresh = people.loadId(id)
                fresh.note = note.text.toString()
                people.save(fresh)
                toast(L.t("已保存 ${fresh.name} 的背景", "Saved context for ${fresh.name}"))
            }
            .setNeutralButton(L.t("更多…", "More…")) { _, _ -> personActions(id) }
            .setNegativeButton(L.t("关闭", "Close"), null)
            .show()
    }

    private fun personActions(id: String) {
        val name = people.nameOf(id)
        val muted = people.isMuted(id)
        val items = arrayOf(
            if (muted) L.t("恢复解读这个人", "Resume judging this person") else L.t("不再解读这个人", "Pause judging this person"),
            L.t("和另一个人合并（跨应用同一人）", "Merge with another person (same person, other app)"),
            L.t("忘记这个人", "Forget this person"),
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        people.setMuted(id, !muted)
                        toast(if (muted) L.t("已恢复", "Resumed") else L.t("已暂停，不解读也不记录", "Paused: not judged, nothing recorded"))
                    }
                    1 -> merge(id)
                    2 -> confirm(L.t("忘记「$name」？学到的一切都会删除。", "Forget $name? Everything learned about them is deleted."), L.t("忘记", "Forget")) {
                        people.forget(id); refreshTools(); toast(L.t("已忘记", "Forgotten"))
                    }
                }
            }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    /** One person on two apps: fold this record into another and keep the link. */
    private fun merge(from: String) {
        val others = people.entries().filter { it.id != from }
        if (others.isEmpty()) { toast(L.t("只认识这一个人", "Only one person known")); return }
        val labels = others.map { "${it.name}（${it.apps.joinToString(" · ") { a -> Apps.label(a) }}）" }
        AlertDialog.Builder(this)
            .setTitle(L.t("把「${people.nameOf(from)}」合并进谁？", "Merge ${people.nameOf(from)} into whom?"))
            .setItems(labels.toTypedArray()) { _, i ->
                people.link(from, others[i].id)
                refreshTools()
                toast(L.t("已合并：以后两边都用「${others[i].name}」的记忆", "Merged: both apps now use ${others[i].name}'s memory"))
            }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    private fun cleanUp() {
        val n = people.cleanup()
        refreshTools()
        toast(if (n == 0) L.t("没有可清理的", "Nothing to clean up") else L.t("清掉了 $n 条几乎没内容的记录", "Removed $n near-empty records"))
    }

    // ---- backup ----

    private fun exportMemory() {
        val name = "vibecheck-memory-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name)
        runCatching { startActivityForResult(i, REQ_EXPORT) }.onFailure { toast(L.t("没有可用的文件应用", "No file app available")) }
    }

    private fun importMemory() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        runCatching { startActivityForResult(i, REQ_IMPORT) }.onFailure { toast(L.t("没有可用的文件应用", "No file app available")) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> {
                val json = people.exportJson()
                io.execute {
                    val r = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                    main.post { toast(if (r.isSuccess) L.t("已导出", "Exported") else L.t("导出失败：", "Export failed: ") + r.exceptionOrNull()?.message) }
                }
            }
            REQ_IMPORT -> io.execute {
                val r = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                main.post {
                    r.onSuccess { text ->
                        confirm(
                            L.t("用这个文件替换现在的人物记忆（${people.index().size} 人）？", "Replace the current people memory (${people.index().size} people) with this file?"),
                            L.t("替换", "Replace"),
                        ) {
                            runCatching { people.importJson(text) }
                                .onSuccess { n -> refreshTools(); toast(L.t("已导入 $n 人", "Imported $n people")) }
                                .onFailure { toast(L.t("不是 Vibecheck 的备份文件", "Not a Vibecheck backup file")) }
                        }
                    }.onFailure { toast(L.t("读不了这个文件：", "Couldn't read that file: ") + it.message) }
                }
            }
        }
    }

    // ---- tests ----

    private fun testEngine() {
        saveFields()
        Judge.missingKey(prefs)?.let { toast(it); return }
        toast(L.t("测试中…", "Testing…"))
        io.execute {
            val body = Jev.requestBody("测试", listOf("对方" to "你今天是不是又忘了我跟你说过什么？"))
            val r = runCatching { Judge.ask(prefs, body) }
            main.post {
                r.onSuccess { prefs.kick(); toast(L.t("成功，返回 ${it.size} 个判断", "Works: ${it.size} judgments back")) }
                    .onFailure { toast(L.t("失败：", "Failed: ") + Judge.describe(it)) }
            }
        }
    }

    private fun testDeep() {
        saveFields()
        if (prefs.orKey.isBlank()) { toast(L.t("先在设置页填 OpenRouter Key", "Add the OpenRouter key on the Setup tab first")); return }
        val model = prefs.deepModel
        toast(L.t("测试中…", "Testing…"))
        io.execute {
            val r = runCatching { OpenRouter.chat(prefs.orKey, model, "Reply with the single word OK.", "ping", null) }
            main.post {
                r.onSuccess { toast(L.t("$model 可用", "$model works")) }
                    .onFailure { toast(L.t("失败：", "Failed: ") + Judge.describe(it)) }
            }
        }
    }

    // ---- saving ----

    private fun saveFields() {
        if (!loaded) return
        prefs.apiKey = keyField.text.toString()
        prefs.orKey = orKeyField.text.toString()
        prefs.context = contextField.text.toString()
        deepModelField?.let { prefs.deepModel = it.text.toString() }
        visionModelField?.let { prefs.visionModel = it.text.toString() }
        savePackages()
    }

    private fun savePackages() {
        if (!loaded) return
        prefs.packages = (appBoxes.filter { it.second.isChecked }.map { it.first } +
            extraPkgs.text.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() })
            .distinct().joinToString(",")
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, ChatReaderService::class.java)
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        // Stored as flattened component names, long or short form depending on the ROM.
        return on.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    private fun installed(pkg: String): Boolean = packageManager.getLaunchIntentForPackage(pkg) != null

    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure { toast(L.t("这台手机打不开这个设置页", "This phone can't open that settings page")) }
    }

    private fun openAppInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    // ---- small views ----

    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun fg() = if (night) 0xFFEDEDED.toInt() else 0xFF1F1F1F.toInt()
    private fun sub() = if (night) 0xFFA9A9A9.toInt() else 0xFF666666.toInt()
    private fun accent() = if (night) 0xFF7FC4B4.toInt() else 0xFF2E6F63.toInt()
    private fun warn() = if (night) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    private fun tileBg() = if (night) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()
    private fun chipBg() = if (night) 0xFF2F2F2F.toInt() else 0xFFE6EEEC.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(16))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    /** A card with a title; with [hideId] it also gets a Hide button that tucks it away. */
    private fun tile(hideId: String?, title: String, desc: String? = null, body: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = rounded(tileBg(), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            val head = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(title, 16f, fg(), bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (hideId != null) addView(text(L.t("隐藏", "Hide"), 13f, accent()).apply {
                    setPadding(dp(10), dp(6), dp(4), dp(6))
                    contentDescription = L.t("隐藏「$title」", "Hide $title")
                    setOnClickListener { hideTool(hideId) }
                })
            }
            addView(head)
            if (desc != null) addView(text(desc, 13f, sub()).apply { setPadding(0, dp(2), 0, dp(6)) })
            body()
        }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun hint(t: String) = text(t, 12f, sub()).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) }

    private fun check(ok: Boolean, t: String) = text((if (ok) "✓  " else "✗  ") + t, 14f, if (ok) fg() else warn()).apply {
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun LinearLayout.field(hint: String, value: String, lines: Int = 1, label: Boolean = false): EditText {
        if (label) addView(text(hint, 13f, sub()).apply { setPadding(0, dp(6), 0, 0) })
        return EditText(this@MainActivity).apply {
            setText(value)
            this.hint = hint
            if (lines > 1) minLines = lines else isSingleLine = true
        }.also { addView(it) }
    }

    private fun LinearLayout.secret(label: String, value: String, hint: String): EditText {
        addView(text(label, 13f, sub()).apply { setPadding(0, dp(8), 0, 0) })
        return EditText(this@MainActivity).apply {
            setText(value)
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { addView(it) }
    }

    private fun switch(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        textSize = 14f
        setTextColor(fg())
        isChecked = on
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun buttons(vararg items: Pair<String, () -> Unit>): View =
        wrapRow(items.map { (label, action) -> pill(label, true) { action() } }).apply { setPadding(0, dp(6), 0, 0) }

    /** A rounded chip; [strong] is the filled accent style for actions. */
    private fun pill(t: String, strong: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(if (strong) (if (night) 0xFF10201C.toInt() else 0xFFFFFFFF.toInt()) else fg())
        background = rounded(if (strong) accent() else chipBg(), dp(20))
        setOnClickListener { onClick() }
        layoutParams = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8); bottomMargin = dp(8) }
    }

    private fun tab(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = dp(42)
        setOnClickListener { onClick() }
    }

    private fun styleTab(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) (if (night) 0xFF10201C.toInt() else 0xFFFFFFFF.toInt()) else fg())
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        v.background = rounded(if (selected) accent() else chipBg(), dp(21))
    }

    /** Chips left to right, wrapping to a new row when the screen is narrow. */
    private fun wrapRow(children: List<View>): ViewGroup = object : ViewGroup(this) {
        init { children.forEach { addView(it) } }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(avail.coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val m = c.layoutParams as? MarginLayoutParams
                val w = c.measuredWidth + (m?.rightMargin ?: 0)
                if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += rowH; rowH = 0 }
                x += w
                rowH = maxOf(rowH, c.measuredHeight + (m?.bottomMargin ?: 0))
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(y + rowH + paddingTop + paddingBottom, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val avail = r - l - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val m = c.layoutParams as? MarginLayoutParams
                if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += rowH; rowH = 0 }
                c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + (m?.rightMargin ?: 0)
                rowH = maxOf(rowH, c.measuredHeight + (m?.bottomMargin ?: 0))
            }
        }

        override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
        override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams
        override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun confirm(message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42

        /** Tools tab order. Each can be hidden and brought back. */
        val TOOLS = listOf("pause", "people", "card", "usage", "backup", "models", "battery", "diagnostics", "guide")

        fun guide(): String = L.t(
            "用法\n" +
                "1. 在「设置」页填 Key，打开无障碍服务。\n" +
                "2. 进聊天页，边上出现小气泡；对方发来消息后气泡变色，点开看卡片。\n" +
                "3. 拖动气泡换位置，长按气泡打开菜单：重新判断、暂停 1 小时、这个聊天不再解读、设置。\n" +
                "4. 「回复」给出三条草稿，点一条会填进空的输入框（不会发送），长按任意一行复制。\n" +
                "5. 「学习」会自己往上翻聊天记录，写一份这个人的档案；再点一下气泡就停。\n" +
                "6. 没有气泡就去「诊断」打开调试模式，看服务到底读到了什么。\n\n" +
                "学习\n" +
                "卡片给出建议后，下一轮对方的语气变化就是这次建议的回报：变缓和为正，升级为负。" +
                "应用据此校准这段关系的风险偏置，并重排「最佳动作」。卡片底部出现「已按你们过去的走向调整」时，说明经验改变了排在最前面的动作。" +
                "它只能看到「显示建议之后发生了什么」，看不到你是否照做，所以这是相关性不是因果。\n\n" +
                "为什么有的应用要截图识字\n" +
                "微信 8.0.52 起不再把界面内容交给第三方无障碍服务，Telegram 的气泡画在 canvas 上：节点树里没有文字。" +
                "这两个走截图加手机本地识字，图片不出手机。其他应用走节点树，更省电。\n\n" +
                "隐私\n" +
                "聊天内容会发送到 api.typesafe.ai（或 openrouter.ai）进行判断；人物记忆只存在这台手机上。",
            "How to use\n" +
                "1. On Setup, add a key and turn on the accessibility service.\n" +
                "2. Open a chat: a small bubble appears at the edge and takes on a colour when a message arrives. Tap it for the card.\n" +
                "3. Drag the bubble to move it. Long-press it for the menu: re-check, pause 1 hour, pause this chat, settings.\n" +
                "4. Reply gives three drafts; tap one to put it in the empty reply box (it is never sent), long-press any line to copy it.\n" +
                "5. Learn scrolls up through the history by itself and writes a profile of this person; tap the bubble to stop early.\n" +
                "6. No bubble? Turn on debug mode under Diagnostics to see what the service actually reads.\n\n" +
                "Learning\n" +
                "After the card suggests a move, how the other side's tone changes next turn is that move's reward: calmer is positive, escalation negative. " +
                "The app calibrates this relationship's risk from it and re-ranks the best move; \"adjusted from how things went before\" means experience overrode the model's first choice. " +
                "It only sees what happened after the advice was shown, not whether you followed it: correlation, not causation.\n\n" +
                "Why some apps need OCR\n" +
                "WeChat 8.0.52+ withholds its content from third-party accessibility services and Telegram draws bubbles on a canvas, so the tree has no text. " +
                "Those two go through a screenshot and on-device OCR; the picture stays on the phone. Other apps use the node tree, which saves battery.\n\n" +
                "Privacy\n" +
                "Chat text is sent to api.typesafe.ai (or openrouter.ai) for judging; people memory stays on this phone.",
        )
    }
}
