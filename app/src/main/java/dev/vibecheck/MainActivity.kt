package dev.vibecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
 * The dashboard. "Setup" holds what it takes to get going, the models included; "People" shows
 * everyone remembered and, for each, what was learned; "Tools" holds everything else as tiles.
 * Any tool can be hidden with one tap and brought back from the bottom of the Tools tab, so the
 * screen shows only what you actually use. Everything saves by itself.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var people: PersonStore
    private val me by lazy { MeStore(this) }
    private val meLearner by lazy { MeLearner(me, people, prefs) }
    private val io = Executors.newSingleThreadExecutor(Crash.threads("vibecheck-app"))
    private val main = Crash.mainHandler()

    // Setup fields, written back when leaving the screen or switching tabs.
    private lateinit var keyField: EditText
    private lateinit var orKeyField: EditText
    private lateinit var contextField: EditText
    private lateinit var extraPkgs: EditText
    private var appBoxes = listOf<Pair<String, CheckBox>>()
    private var loaded = false

    private lateinit var readiness: TextView
    /** Says the app closed unexpectedly last time, until dismissed; hidden otherwise. */
    private lateinit var crashBanner: LinearLayout
    private lateinit var checklist: LinearLayout
    private lateinit var setupPage: ScrollView
    private lateinit var toolsPage: ScrollView
    private lateinit var peoplePage: ScrollView
    private lateinit var tabSetup: TextView
    private lateinit var tabTools: TextView
    private lateinit var tabPeople: TextView

    // Tool tiles that show live numbers; null while the tile is hidden.
    private var pauseText: TextView? = null
    private var usageText: TextView? = null
    private var batteryText: TextView? = null
    private var diagnostics: TextView? = null
    private var diagUrl: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        prefs = Prefs(this)
        prefs.lang   // applies the UI language to L before anything is built
        OpenRouter.onUsage = prefs::addUsage
        people = PersonStore(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header())
        crashBanner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }.also {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(6) })
        }
        readiness = text("", 13f, sub()).also {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(20); rightMargin = dp(20) })
        }
        root.addView(tabs())
        setupPage = page(buildSetup())
        toolsPage = page(buildTools())
        peoplePage = page(buildPeople(""))
        val pages = FrameLayout(this)
        pages.addView(setupPage)
        pages.addView(toolsPage)
        pages.addView(peoplePage)
        root.addView(pages, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applyInsets(root)
        setContentView(root)
        loaded = true
        selectTab(prefs.tab)
        openPerson(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPerson(intent)
    }

    /** The card's "Rename" lands here: that person's page, with the name field ready to type in. */
    private fun openPerson(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PERSON) ?: return
        intent.removeExtra(EXTRA_PERSON)
        selectTab(TAB_PEOPLE)
        showPerson(id, focusName = true)
    }

    /** Back from a person's page goes to the list, not out of the app. */
    @Deprecated("Deprecated in Android 13; still called without the predictive back opt-in")
    override fun onBackPressed() {
        if (prefs.tab == TAB_PEOPLE && personShown != null) showPeople() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        refreshCrashBanner()
        refreshSetup()
        refreshTools()
        refreshPeople()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(progressTick)
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

    /**
     * When the app or its service last ended because something went wrong (a crash, including
     * one in native code, "not responding", or being killed for memory): what it was, with a
     * button to copy the details and send them on.
     */
    private fun refreshCrashBanner() {
        val b = crashBanner
        b.removeAllViews()
        val record = Crash.unseenExit(this)
        if (record == null) { b.visibility = View.GONE; return }
        b.visibility = View.VISIBLE
        b.setPadding(dp(14), dp(10), dp(14), dp(4))
        b.background = rounded(tileBg(), dp(12))
        b.addView(text(L.t("Vibecheck 上次意外退出了", "Vibecheck closed unexpectedly last time"), 15f, warn(), bold = true))
        b.addView(text(record.lineSequence().first().take(200), 12f, fg()).apply { setPadding(0, dp(4), 0, 0) })
        b.addView(text(L.t(
            "点「复制原因」把完整记录发过来，就能查到是哪里出的问题。「工具 → 诊断」里也能看到。",
            "Copy the details and send them on to find out what went wrong. They are under Tools → Diagnostics too."), 12f, sub()).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        b.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill(L.t("复制原因", "Copy details"), true) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("vibecheck", Crash.report(record)))
                toast(L.t("已复制", "Copied"))
            })
            addView(pill(L.t("知道了", "Dismiss"), false) { Crash.dismissExit(this@MainActivity); refreshCrashBanner() })
        })
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
        tabSetup = tab(L.t("设置", "Setup")) { selectTab(TAB_SETUP) }
        tabPeople = tab(L.t("人物", "People")) { selectTab(TAB_PEOPLE) }
        tabTools = tab(L.t("工具", "Tools")) { selectTab(TAB_TOOLS) }
        addView(tabSetup, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(tabPeople, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        addView(tabTools, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    }

    private fun selectTab(i: Int) {
        saveFields()
        val tab = if (i == TAB_TOOLS || i == TAB_PEOPLE) i else TAB_SETUP
        prefs.tab = tab
        setupPage.visibility = if (tab == TAB_SETUP) View.VISIBLE else View.GONE
        toolsPage.visibility = if (tab == TAB_TOOLS) View.VISIBLE else View.GONE
        peoplePage.visibility = if (tab == TAB_PEOPLE) View.VISIBLE else View.GONE
        styleTab(tabSetup, tab == TAB_SETUP)
        styleTab(tabPeople, tab == TAB_PEOPLE)
        styleTab(tabTools, tab == TAB_TOOLS)
        when (tab) {
            TAB_TOOLS -> refreshTools()
            TAB_PEOPLE -> refreshPeople()
            else -> refreshSetup()
        }
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

        addView(tile(null, L.t("模型", "Models"), L.t(
            "OpenRouter 上用哪个模型。回复模型写回复草稿、看截图识字的聊天截图，要会写、要快；深思模型做深度分析和学习此人。",
            "Which OpenRouter models to use. The reply model writes drafts and looks at the screenshots of chats read by OCR: it should write well and fast. The Think model does deep reads and Learn.")) {
            addView(text(L.t("回复", "Replies"), 14f, fg(), bold = true).apply { setPadding(0, dp(6), 0, dp(4)) })
            addView(modelChoices(Models.REPLY, { prefs.fastModel }) { prefs.fastModel = it })
            addView(text(L.t("深思和学习", "Think and Learn"), 14f, fg(), bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
            addView(modelChoices(Models.DEEP, { prefs.deepModel }) { prefs.deepModel = it })
            addView(buttons(
                L.t("测试回复模型", "Test reply model") to { testModel(deep = false) },
                L.t("测试深思模型", "Test Think model") to { testModel(deep = true) },
            ))
            addView(hint(L.t("两个模型互为备用：一个出错（忙、挂了、拒绝回答、半路断了），会自动换另一个再问一次，卡片上会注明是谁回答的。两个选同一个时，备用的是 DeepSeek V4.1 Flash。",
                "Each model stands in for the other: when one fails (busy, down, refusing, cut off), the other is asked the same, and the card says which answered. With both set to one model, DeepSeek V4.1 Flash stands in.")))
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
            addView(switch(L.t("值得细看的消息自动深思（每次都要调用模型，默认关）", "Auto deep read on turns that matter (a paid call each time; off by default)"), prefs.autoDeep) { prefs.autoDeep = it })
            addView(switch(L.t("回复可以带成人内容（18+）：聊到了才写，尺度跟着对方走", "Adult replies (18+): only when the chat already goes there, at their level"), prefs.adult) { prefs.adult = it })
            addView(switch(L.t("了解我：跨聊天记下我的日常，每天写个小结，回复照我的生活来写（记录只存在手机上）",
                "Learn about me: a day log across chats, a write-up of each day, replies drawn from my life (the log stays on this phone)"), prefs.aboutMe) { prefs.aboutMe = it })
            addView(switch(L.t("读不到界面时用截图识字（微信、Telegram 必须开）", "Read the screen with OCR when an app hides its text (WeChat, Telegram)"), prefs.ocr) { prefs.ocr = it })
            addView(switch(L.t("从后续走向学习（校准风险、重排动作）", "Learn from what happens next (calibrate risk, re-rank moves)"), prefs.learning) { prefs.learning = it })
            addView(switch(L.t("在聊天里顺便记住每个人（只在手机上）", "Remember people while you chat (on this phone only)"), prefs.passive) { prefs.passive = it })
        })

        addView(tile(null, L.t("通用背景", "General context"), L.t(
            "只发给还没有专属背景、也没学习过的人。别在这里写某一个人（比如对象）：那会被当成所有人的背景。" +
                "每个人是你的什么人、专属背景：「人物」页，或卡片上的 ⋯ → 关系。",
            "Sent only for people with no context of their own and no profile. Don't describe one person here " +
                "(your partner, say): it would apply to everyone. Who each person is to you, and their own context: " +
                "the People tab, or ⋯ → Relationship on the card.")) {
            contextField = field(L.t("例：我说话比较直，不太会哄人", "e.g. I'm blunt and not great at comforting people"), prefs.context, lines = 2)
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
        pauseText = null; usageText = null; batteryText = null; diagnostics = null; diagUrl = null
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
        "card" -> L.t("卡片", "Card")
        "usage" -> L.t("用量", "Usage")
        "backup" -> L.t("备份", "Backup")
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
            "把人物记忆导出成文件，换手机时导入。API Key 和聊天记录存档不在里面（存档到新手机上再学习一次就有）。",
            "Save people memory to a file and bring it back on a new phone. API keys and kept chat histories are not included (Learn again on the new phone).")) {
            addView(buttons(L.t("导出", "Export") to { exportMemory() }, L.t("导入", "Import") to { importMemory() }))
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
            diagnostics = text("", 12f, sub()).also { it.typeface = Typeface.MONOSPACE; it.setTextIsSelectable(true); addView(it) }
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

    /** Calls, tokens and cost per kind of call, today and in total, and where the tokens went. */
    private fun usage(): String {
        val kinds = listOf(
            Triple(Prefs.USE_JUDGE, "判断", "Judgments"),
            Triple(Prefs.USE_DEEP, "深思", "Deep reads"),
            Triple(Prefs.USE_REPLY, "回复草稿", "Reply drafts"),
            Triple(Prefs.USE_BIO, "档案", "Profiles"),
            Triple(Prefs.USE_ME, "关于我", "About me"),
            Triple(Prefs.USE_OTHER, "设置里的测试", "Tests in settings"),
        )
        val lines = ArrayList<String>()
        var today = Prefs.Spent(0, 0, 0, 0, 0, 0.0)
        var total = today
        for ((kind, zh, en) in kinds) {
            val d = prefs.spent(kind, today = true)
            val t = prefs.spent(kind, today = false)
            if (kind == Prefs.USE_OTHER && t.tokens == 0L) continue
            today += d
            total += t
            lines += L.t("$zh：今天 ${Prefs.describe(d)}；累计 ${Prefs.describe(t)}", "$en: today ${Prefs.describe(d)}; total ${Prefs.describe(t)}")
        }
        Prefs.breakdown(today)?.let { lines += L.t("今天", "Today") + L.t("：", ": ") + it }
        Prefs.breakdown(total)?.let { lines += L.t("累计", "In total") + L.t("：", ": ") + it }
        lines += L.t("token 和花费从 6.8.8 起记，按 OpenRouter 报的数", "Tokens and cost are counted from 6.8.8 on, as OpenRouter reports them")
        return lines.joinToString("\n")
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
        // What went wrong last, kept across restarts: long-press to copy it and send it on.
        val last = Crash.last()
        Crash.lastExit()?.takeIf { it != last }?.let {
            lines.add(""); lines.add(L.t("上次意外退出（长按可复制）：", "Last unexpected exit (long-press to copy):")); lines.addAll(it.lines().take(16))
        }
        last?.let { lines.add(""); lines.add(L.t("上次出错（长按可复制）：", "Last error (long-press to copy):")); lines.addAll(it.lines().take(16)) }
        d.text = lines.joinToString("\n")
    }

    private fun refreshDiagUrl() {
        diagUrl?.text = if (prefs.remoteDiag)
            "http://${ChatReaderService.lanAddress()}:${DebugServer.PORT}/status?t=${prefs.diagToken}"
        else L.t("排查接口已关闭", "Debug endpoint is off")
    }

    // ---- People: everyone remembered, and what was learned about each ----

    /** The person whose page is open on the People tab, or null for the list. */
    private var personShown: String? = null

    /** The progress of a profile being written, on the open person's page; null when there is none. */
    private var progressView: TextView? = null

    /** Redraws [progressView] while the write runs, and the whole page once it is done. */
    private val progressTick = object : Runnable {
        override fun run() {
            val v = progressView ?: return
            val id = personShown ?: return
            val p = Learning.writing[id]
            if (p == null) { progressView = null; keepScreenOn(false); showPerson(id); return }
            v.text = Learning.lines(p, System.currentTimeMillis()).joinToString("\n")
            main.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    /** The list again, unless a person's page is open (it may have a half-typed note on it). */
    private fun refreshPeople() {
        if (::peoplePage.isInitialized && personShown == null) showPeople()
    }

    private fun showPeople(query: String = "") {
        personShown = null
        progressView = null
        keepScreenOn(false)
        peoplePage.removeAllViews()
        peoplePage.addView(buildPeople(query))
    }

    private fun showPerson(id: String, focusName: Boolean = false) {
        saveFields()
        personShown = id
        peoplePage.removeAllViews()
        peoplePage.addView(buildPerson(id, focusName))
        peoplePage.post { peoplePage.scrollTo(0, 0) }
    }

    // ---- about me: learned across every chat ----

    private fun showMe() {
        saveFields()
        personShown = ME_PAGE
        progressView = null
        keepScreenOn(false)
        peoplePage.removeAllViews()
        peoplePage.addView(buildMe())
        peoplePage.post { peoplePage.scrollTo(0, 0) }
    }

    /** "我" at the top of the People list. */
    private fun meCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(chipBg(), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(text(L.t("我", "Me"), 16f, fg(), bold = true))
        val days = me.days()
        val facts = listOfNotNull(
            if (me.profile.isNotBlank()) L.t("关于我的档案已整理", "profile written") else L.t("档案还没整理", "no profile yet"),
            days.size.takeIf { it > 0 }?.let { L.t("记了 $it 天", "$it days logged") },
        )
        addView(text(facts.joinToString("  ·  "), 13f, sub()).apply { setPadding(0, dp(2), 0, 0) })
        val line = Me.oneLine(me.profile)
            ?: me.summaries(1).firstOrNull()?.second?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("•")?.trim()
            ?: L.t("学习联系人时会顺便学你，每天的聊天也会记下来。点这里看。", "Learning someone teaches it about you too, and each day's chats are logged. Tap to see.")
        addView(text(line, 14f, fg()).apply { maxLines = 2; setPadding(0, dp(4), 0, 0) })
        isClickable = true
        setOnClickListener { showMe() }
    }

    /**
     * Everything learned about me: the profile of me, section by section; each day written up;
     * how I write across chats; and the buttons to write it again or forget it.
     */
    private fun buildMe(): View = column().apply {
        addView(LinearLayout(this@MainActivity).apply {
            setPadding(0, dp(6), 0, 0)
            addView(pill(L.t("← 所有人", "← Everyone"), false) { showPeople() })
        })

        val learned = me.learnedAt.takeIf { it > 0 }?.let {
            L.t("整理于 ", "Written ") + SimpleDateFormat(L.t("M月d日 HH:mm", "MMM d, HH:mm"), Locale.getDefault()).format(Date(it))
        }
        addView(tile(null, L.t("关于我", "About me"), learned) {
            val secs = Profile.sections(me.profile)
            if (secs.isEmpty()) addView(text(L.t(
                "还没整理：学习一个人时会顺便从你们的聊天里学你，每天的聊天也会记下来，第二天写成小结。也可以点下面的「整理关于我的档案」。",
                "Not written yet: learning someone also learns about you from your chats with them, and each day's chats are logged and written up the next day. Or tap \"Write my profile\" below."), 14f, fg()))
            for (sec in secs) {
                if (sec.heading.isNotEmpty()) addView(text(sec.heading, 14f, accent(), bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                for (l in sec.lines) addView(text(l, 14f, fg()).apply { setTextIsSelectable(true); setPadding(0, dp(1), 0, dp(1)) })
            }
        })

        val today = Me.day(System.currentTimeMillis())
        addView(tile(null, L.t("每天", "Day by day"), L.t(
            "在各个聊天里说的话按天记下，第二天由模型写成几行小结。回复草稿会参考最近几天和今天在别的聊天里说过的话。",
            "What is said in each chat is logged by day and written up the next day. Reply drafts draw on the last few days and on what you said in other chats today.")) {
            val n = me.lines(today).size
            addView(text(L.t("今天：记了 $n 条", "Today: $n messages logged"), 14f, fg(), bold = true))
            me.summary(today)?.let { addView(text(it, 14f, fg()).apply { setTextIsSelectable(true); setPadding(0, dp(2), 0, 0) }) }
            if (n > 0) addView(buttons(L.t("总结今天", "Write up today") to { summarizeToday() }))
            for (d in me.days().filter { it != today }.take(30)) {
                addView(text(Me.dayLabel(d), 14f, accent(), bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                // Read once: both languages' texts are built, and the log is read for the count.
                val summary = me.summary(d) ?: me.lines(d).size.let { c -> L.t("还没写小结（$c 条）", "Not written up yet ($c messages)") }
                addView(text(summary, 14f, fg()).apply { setTextIsSelectable(true) })
            }
        })

        val records = people.index().map { people.loadId(it) }
        val style = Person.Style().also { st -> records.forEach { Person.mergeStyle(st, it.style) } }
        Person.styleSummary(style)?.let { summary ->
            addView(tile(null, L.t("你在各个聊天里", "You across chats")) {
                addView(text(summary, 14f, fg()))
                addView(text(L.t("和 ${records.count { it.style.msgs > 0 }} 个人聊过", "Talked with ${records.count { it.style.msgs > 0 }} people"), 13f, sub()).apply { setPadding(0, dp(4), 0, 0) })
            })
        }

        addView(tile(null, L.t("更多", "More")) {
            addView(wrapRow(listOf(
                pill(L.t("整理关于我的档案", "Write my profile"), false) { rebuildMe() },
                pill(L.t("清空关于我的记忆", "Forget about me"), false) {
                    confirm(L.t("清空每天的记录、小结和关于你的档案？不能撤销。", "Delete the day log, the write-ups and your profile? This can't be undone."), L.t("清空", "Delete")) {
                        me.clear(); toast(L.t("已清空", "Deleted")); showMe()
                    }
                },
            )))
            addView(hint(L.t("不想记：设置 → 行为 → 了解我。", "To stop: Setup → Behaviour → Learn about me.")))
        })
    }

    private fun summarizeToday() {
        if (prefs.orKey.isBlank()) { toast(L.t("先在设置页填 OpenRouter Key", "Add the OpenRouter key on the Setup tab first")); return }
        toast(L.t("正在总结今天…", "Writing up today…"))
        io.execute {
            val r = runCatching { meLearner.summarizeToday(System.currentTimeMillis()) }
            main.post {
                r.onSuccess { toast(if (it == null) L.t("今天还没记下什么", "Nothing logged today yet") else L.t("今天的小结写好了", "Today is written up")) }
                    .onFailure { toast(L.t("失败：", "Failed: ") + Judge.describe(it)) }
                if (personShown == ME_PAGE) showMe()
            }
        }
    }

    private fun rebuildMe() {
        if (prefs.orKey.isBlank()) { toast(L.t("先在设置页填 OpenRouter Key", "Add the OpenRouter key on the Setup tab first")); return }
        toast(L.t("正在整理关于你的档案，可能要一两分钟…", "Writing your profile; this can take a minute or two…"))
        io.execute {
            val r = runCatching { meLearner.rebuild(byHand = true) }
            main.post {
                r.onSuccess { toast(L.t("关于你的档案整理好了", "Your profile is written")) }
                    .onFailure { toast(L.t("没整理成：", "Not written: ") + Judge.describe(it)) }
                if (personShown == ME_PAGE) showMe()
            }
        }
    }

    private fun buildPeople(query: String): View = column().apply {
        val all = people.entries()
        addView(tile(null, L.t("人物记忆", "People"), L.t(
            "每个人单独学习，全部只存在这台手机上。点一个人，看学到了什么，改关系、名字和专属背景。",
            "Each person is learned separately, all on this phone only. Tap someone to see what was learned, and to change their relationship, name or context.")) {
            addView(text(L.t("记住了 ${all.size} 个人，学习过 ${all.count { it.learned > 0 }} 个",
                "${all.size} people remembered, ${all.count { it.learned > 0 }} learned"), 14f, fg()))
            addView(buttons(
                L.t("清理空记录", "Clean up") to { cleanUp() },
                L.t("全部忘记", "Forget all") to {
                    confirm(L.t("忘记所有人？学到的一切都会删除，不能撤销。", "Forget everyone? Everything learned is deleted and can't be undone."), L.t("全部忘记", "Forget all")) {
                        people.forgetAll(); showPeople(); toast(L.t("已清空", "Cleared"))
                    }
                },
            ))
        })
        if (prefs.aboutMe || me.profile.isNotBlank()) addView(meCard())
        if (all.isEmpty()) {
            addView(hint(L.t("还没认识任何人：打开一个聊天，边上出现小气泡后就开始记了。", "Nobody yet: open a chat, and once the bubble shows, people are remembered.")))
            return@apply
        }
        val list = column().apply { setPadding(0, dp(6), 0, 0) }
        if (all.size > 6) addView(EditText(this@MainActivity).apply {
            hint = L.t("搜索名字、应用或关系", "Search name, app or relationship")
            isSingleLine = true
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { fillPeople(list, all, s?.toString().orEmpty()) }
            })
        })
        addView(list)
        fillPeople(list, all, query)
    }

    private fun fillPeople(list: LinearLayout, all: List<PersonStore.Entry>, query: String) {
        list.removeAllViews()
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { e ->
            e.name.contains(q, ignoreCase = true) || e.kind?.contains(q, ignoreCase = true) == true ||
                e.apps.any { Apps.label(it).contains(q, ignoreCase = true) }
        }
        for (e in shown) list.addView(personCard(e))
        if (shown.isEmpty()) list.addView(hint(L.t("没有找到", "No one matches")))
    }

    /** One person in the list: who, where, what they are to you, how much was learned, one line about them. */
    private fun personCard(e: PersonStore.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(tileBg(), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(text("", 16f, fg(), bold = true).apply { text = nameLabel(e.id, 16f) })
        val facts = listOfNotNull(
            e.apps.joinToString("+") { Apps.label(it) },
            e.kind,
            if (e.learned > 0) L.t("学习了 ${e.learned} 条", "${e.learned} learned") else L.t("还没学习", "not learned"),
            L.t("正在写档案", "writing the profile").takeIf { Learning.writing.containsKey(e.id) },
            L.t("已暂停", "paused").takeIf { e.muted },
        )
        addView(text(facts.joinToString("  ·  "), 13f, if (e.muted) warn() else sub()).apply { setPadding(0, dp(2), 0, 0) })
        e.line?.let { addView(text(it, 14f, fg()).apply { maxLines = 2; setPadding(0, dp(4), 0, 0) }) }
        isClickable = true
        setOnClickListener { showPerson(e.id) }
    }

    /**
     * Everything known about one person, on a page of its own: who they are to you, what the
     * history read learned (section by section), what judging them has taught, and the settings
     * that are theirs alone.
     */
    private fun buildPerson(id: String, focusName: Boolean): View = column().apply {
        val r = people.loadId(id)
        progressView = null
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(pill(L.t("← 所有人", "← Everyone"), false) { showPeople() })
        })

        // Who, where, and what they are to you.
        // A name that could only be seen (emoji OCR cannot read) is shown as its picture, not as
        // "Unnamed contact": it is the name, just not in letters.
        val picture = people.namePicture(id)
        addView(tile(null, people.shownName(id), r.apps.joinToString(" · ") { Apps.label(it) }, label = picture?.let { nameLabel(id, 22f) }) {
            if (picture != null) addView(text(L.t("名字是表情，读不成文字，所以用聊天标题的截图。想要文字名字，在下面「名字和专属背景」里起一个。",
                "The name is emoji, which can't be read as text, so the chat title's picture stands for it. For a name in words, give one under Name and context below."), 13f, sub()))
            if (r.muted) addView(text(L.t("已暂停：不解读，也不记录。", "Paused: not judged, nothing recorded."), 13f, warn()).apply { setPadding(0, dp(4), 0, 0) })
            lateinit var relPill: TextView
            lateinit var closePill: TextView
            val showRel = { relPill.text = L.t("关系：", "Relationship: ") + relationChoice(people.relationOf(id)) + "  ▾" }
            val showClose = { closePill.text = L.t("亲近：", "Closeness: ") + closenessChoice(people.closenessOf(id)) + "  ▾" }
            relPill = pill("", false) { pickRelation(id, showRel) }
            closePill = pill("", false) { pickCloseness(id, showClose) }
            showRel()
            showClose()
            addView(wrapRow(listOf(relPill, closePill)).apply { setPadding(0, dp(8), 0, 0) })
            addView(hint(L.t("关系和亲近程度是两回事：关系好不等于是恋人。选定后每条消息都按这个判断，学习也不会改掉；「自动判断」由学习从档案里读出来。",
                "What they are to you and how close you are are separate: close is not a couple. Once chosen, every message is judged that way and learning won't change it; \"Work it out\" takes it from the profile.")))
        })

        // What the history read learned.
        addView(tile(null, L.t("学到了什么", "What was learned"), learnedLine(r)) {
            // Being written right now: how far it has got, kept up to date while the page is open.
            Learning.writing[id]?.let { p ->
                progressView = text(Learning.lines(p, System.currentTimeMillis()).joinToString("\n"), 14f, accent()).also {
                    it.setPadding(0, dp(2), 0, dp(8))
                    addView(it)
                }
                main.removeCallbacks(progressTick)
                main.postDelayed(progressTick, PROGRESS_TICK_MS)
            }
            keepScreenOn(Learning.writing.containsKey(id))
            val kept = people.archiveCount(id)
            if (kept > 0 && !Learning.writing.containsKey(id)) addView(buttons(
                L.t("用存的 $kept 条记录重新分析", "Rewrite from the $kept kept messages") to { rewrite(id, kept) }))
            val secs = Profile.sections(r.bio)
            if (secs.isEmpty()) addView(text(L.t("还没学习：在和 Ta 的聊天里打开卡片，点「学习」。应用会自己翻完你们的聊天记录，写一份档案。",
                "Not learned yet: open the card in your chat with them and tap Learn. It reads your whole history and writes a profile."), 14f, fg()))
            for (sec in secs) {
                if (sec.heading.isNotEmpty()) addView(text(sec.heading, 14f, accent(), bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                for (l in sec.lines) addView(text(l, 14f, fg()).apply { setTextIsSelectable(true); setPadding(0, dp(1), 0, dp(1)) })
            }
        })

        // What watching and judging them has taught.
        val watched = listOfNotNull(
            Relation.summary(r.stats)?.let { L.t("聊天：", "Chats: ") + it },
            Person.theirStyleSummary(r.theirStyle)?.let { L.t("Ta 的说话方式：", "How they write: ") + it },
            Person.normSummary(r.norm)?.let { L.t("Ta 平时：", "Usually: ") + it },
            Person.styleSummary(r.style)?.let { L.t("你对 Ta：", "You to them: ") + it },
            people.archiveCount(id).takeIf { it > 0 }?.let { L.t("手机上存着 $it 条聊天记录", "$it messages kept on this phone") },
        )
        val taught = Learner.plain(r.model)
        val recent = Person.historySummary(r.history)
        if (watched.isNotEmpty() || taught.isNotEmpty() || recent != null) addView(tile(null, L.t("边聊边学到的", "Learned while you chat"), L.t(
            "不用点学习，聊天时自动记下的。Ta 的说话方式和平时的状态每一轮都会告诉判断模型，聊得越久，判断越贴近 Ta。判断的学习看的是给出建议后，下一轮气氛是缓和了还是更僵了。",
            "Recorded as you chat, without Learn. How they write and how they usually come across go to the judge with every turn, so the longer you talk, the more its reading fits them. Judging learns from whether things calmed down or got worse after the card's advice.")) {
            for (l in watched) addView(text(l, 14f, fg()).apply { setPadding(0, dp(3), 0, dp(3)) })
            for (l in taught) addView(text("• $l", 14f, fg()).apply { setPadding(0, dp(3), 0, dp(3)) })
            recent?.let { addView(text(L.t("最近几轮：", "Last few turns: ") + it, 13f, sub()).apply { setPadding(0, dp(3), 0, 0) }) }
        })

        // Their name and context, edited here.
        lateinit var alias: EditText
        lateinit var note: EditText
        addView(tile(null, L.t("名字和专属背景", "Name and context")) {
            alias = field(if (Person.isFingerprint(r.name)) L.t("输入或粘贴 Ta 的名字，emoji 也行", "Type or paste their name, emoji and all")
                else L.t("聊天标题上的名字：", "Name in the chat title: ") + r.name, r.alias)
            if (Person.isFingerprint(r.name) && r.alias.isBlank())
                addView(hint(L.t("Ta 给你发消息、通知里带着名字时，会自动认出这个名字。", "Their name is picked up by itself from their next message notification.")))
            addView(text(L.t("专属背景（发给模型，优先于通用背景）", "Context for this person (sent to the model instead of the general one)"), 13f, sub()).apply { setPadding(0, dp(8), 0, 0) })
            note = field(L.t("例：大学室友，说话很直", "e.g. college roommate, very blunt"), r.note, lines = 2)
            addView(buttons(L.t("保存", "Save") to {
                val fresh = people.loadId(id)
                fresh.note = note.text.toString()
                fresh.alias = alias.text.toString().trim().take(24)
                people.save(fresh)
                toast(L.t("已保存", "Saved"))
                showPerson(id)
            }))
        })

        // Everything else that is theirs alone.
        val kept = people.archiveCount(id)
        addView(tile(null, L.t("更多", "More")) {
            val muted = people.isMuted(id)
            val actions = mutableListOf<Pair<String, () -> Unit>>(
                (if (muted) L.t("恢复解读", "Resume judging") else L.t("暂停这个人", "Pause this person")) to {
                    people.setMuted(id, !muted)
                    toast(if (muted) L.t("已恢复", "Resumed") else L.t("已暂停，不解读也不记录", "Paused: not judged, nothing recorded"))
                    showPerson(id)
                },
                L.t("和别的应用上的 Ta 合并", "Merge with them on another app") to { merge(id) },
            )
            if (people.namePicture(id) != null) actions += L.t("名字图片不对，重新截", "Wrong name picture: take it again") to {
                people.deleteNamePicture(id)
                toast(L.t("下次打开和 Ta 的聊天时会重新截", "It is taken again next time you open the chat"))
                showPerson(id)
            }
            if (kept > 0) actions += L.t("删除聊天记录存档（$kept 条）", "Delete kept history ($kept)") to {
                val name = people.shownName(id)
                confirm(L.t("删除和「$name」的 $kept 条聊天记录存档？档案留着；下次学习会从头读。",
                    "Delete the $kept kept messages with $name? The profile stays; the next Learn reads from the start."), L.t("删除", "Delete")) {
                    people.deleteArchive(id); toast(L.t("已删除存档", "Kept history deleted")); showPerson(id)
                }
            }
            actions += L.t("忘记这个人", "Forget this person") to {
                val name = people.shownName(id)
                confirm(L.t("忘记「$name」？学到的一切都会删除。", "Forget $name? Everything learned about them is deleted."), L.t("忘记", "Forget")) {
                    people.forget(id); toast(L.t("已忘记", "Forgotten")); showPeople()
                }
            }
            addView(wrapRow(actions.map { (label, go) -> pill(label, false) { go() } }))
        })

        if (focusName) post {
            alias.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(alias, 0)
        }
    }

    /** "学习了 3617 条 · 9月27日 · 朋友 · 很铁（学习得出）", or what to do when nothing was learned. */
    private fun learnedLine(r: PersonStore.Record): String? {
        val bits = listOfNotNull(
            r.learned.takeIf { it > 0 }?.let { L.t("读了 $it 条聊天记录", "$it messages read") },
            r.learnedAt.takeIf { it > 0 }?.let { SimpleDateFormat(L.t("M月d日 HH:mm", "MMM d, HH:mm"), Locale.getDefault()).format(Date(it)) },
            people.relationLine(r),
        )
        return bits.joinToString("  ·  ").ifBlank { null }
    }

    private fun closenessChoice(key: String): String =
        if (key.isEmpty()) L.t("不确定", "Not sure") else L.label(key)

    /** How close you are, separately from what they are to you. */
    private fun pickCloseness(id: String, done: () -> Unit) {
        val keys = listOf("") + Relationship.CLOSENESS
        val now = keys.indexOf(people.closenessOf(id)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(L.t("和 ${people.shownName(id)} 有多亲近", "How close are you to ${people.shownName(id)}"))
            .setSingleChoiceItems(keys.map { closenessChoice(it) }.toTypedArray(), now) { d, which ->
                people.setCloseness(id, keys[which])
                d.dismiss()
                done()
            }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    /**
     * A person's name for a label: in words, or, for a name the app could only see (emoji OCR
     * cannot read), the picture of it from the chat's title bar. Never an internal code.
     */
    private fun nameLabel(id: String, sp: Float): CharSequence {
        val picture = people.namePicture(id) ?: return people.shownName(id)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * 1.3f, resources.displayMetrics).toInt()
        val (w, h) = Person.pictureSize(picture.width, picture.height, px)
        val d = BitmapDrawable(resources, picture).apply { setBounds(0, 0, w, h) }
        return SpannableStringBuilder("\uFFFC").apply { setSpan(ImageSpan(d, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun relationChoice(key: String): String =
        if (key.isEmpty()) L.t("自动判断", "Work it out") else L.label(key)

    /** Who a person is to you, chosen from the same options Jev judges with. */
    private fun pickRelation(id: String, done: () -> Unit) {
        val keys = listOf("") + Relationship.KEYS
        val now = keys.indexOf(people.relationOf(id)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(L.t("${people.shownName(id)} 是你的…", "${people.shownName(id)} is your…"))
            .setSingleChoiceItems(keys.map { relationChoice(it) }.toTypedArray(), now) { d, which ->
                people.setRelation(id, keys[which])
                d.dismiss()
                done()
                toast(if (which == 0) L.t("关系：自动判断", "Relationship: worked out automatically")
                    else L.t("关系已设为：", "Relationship set: ") + relationChoice(keys[which]))
            }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    /** One person on two apps: fold this record into another and keep the link. */
    private fun merge(from: String) {
        val others = people.entries().filter { it.id != from }
        if (others.isEmpty()) { toast(L.t("只认识这一个人", "Only one person known")); return }
        val labels: List<CharSequence> = others.map { SpannableStringBuilder(nameLabel(it.id, 16f)).append("（${it.apps.joinToString(" · ") { a -> Apps.label(a) }}）") }
        AlertDialog.Builder(this)
            .setTitle(L.t("把「${people.nameOf(from)}」合并进谁？", "Merge ${people.nameOf(from)} into whom?"))
            .setItems(labels.toTypedArray()) { _, i ->
                people.link(from, others[i].id)
                showPeople()
                toast(L.t("已合并：以后两边都用「${others[i].name}」的记忆", "Merged: both apps now use ${others[i].name}'s memory"))
            }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    private fun cleanUp() {
        val n = people.cleanup()
        showPeople()
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
            val r = runCatching { Judge.ask(prefs, body, Prefs.USE_OTHER) }
            main.post {
                r.onSuccess { prefs.kick(); toast(L.t("成功，返回 ${it.size} 个判断", "Works: ${it.size} judgments back")) }
                    .onFailure { toast(L.t("失败：", "Failed: ") + Judge.describe(it)) }
            }
        }
    }

    /** One short call with the model as it is used, and how long it took to answer. */
    private fun testModel(deep: Boolean) {
        saveFields()
        if (prefs.orKey.isBlank()) { toast(L.t("先在设置页填 OpenRouter Key", "Add the OpenRouter key on the Setup tab first")); return }
        // Read after saving: the field may have just changed.
        val model = if (deep) prefs.deepModel else prefs.fastModel
        val think = if (deep) OpenRouter.Think.LOW else OpenRouter.Think.OFF
        toast(L.t("测试中…", "Testing…"))
        io.execute {
            val started = System.currentTimeMillis()
            val r = runCatching { OpenRouter.chat(prefs.orKey, model, "Reply with the single word OK.", "ping", null, think = think, kind = Prefs.USE_OTHER) }
            val secs = "%.1f".format((System.currentTimeMillis() - started) / 1000.0)
            main.post {
                r.onSuccess { toast(L.t("$model 可用，$secs 秒", "$model works, ${secs}s")) }
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
    /** A titled card. [label] is shown in place of [title] when given (a name that is a picture). */
    private fun tile(hideId: String?, title: String, desc: String? = null, label: CharSequence? = null, body: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = rounded(tileBg(), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            val head = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(title, 16f, fg(), bold = true).apply { if (label != null) text = label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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

    /**
     * The models to choose from, each with what it is good at, the chosen one marked; and "Other…"
     * for any OpenRouter id typed in by hand. A choice saves at once.
     */
    private fun modelChoices(options: List<Models.Option>, now: () -> String, choose: (String) -> Unit): View {
        val box = column().apply { setPadding(0, 0, 0, 0) }
        fun fill() {
            box.removeAllViews()
            val current = now()
            for (o in options) box.addView(choiceRow(o.name, o.about, o.id == current) {
                choose(o.id)
                fill()
                toast(L.t("已选 ", "Chosen: ") + o.name)
            })
            val custom = current.takeIf { Models.option(options, it) == null }
            box.addView(choiceRow(L.t("其他模型…", "Other…"), custom ?: L.t("填任何 OpenRouter 模型名", "Type any OpenRouter model id"), custom != null) {
                val field = EditText(this).apply { setText(custom.orEmpty()); hint = "vendor/model"; isSingleLine = true }
                AlertDialog.Builder(this)
                    .setTitle(L.t("OpenRouter 模型名", "OpenRouter model id"))
                    .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
                    .setPositiveButton(L.t("用这个", "Use it")) { _, _ ->
                        val id = field.text.toString().trim()
                        if (id.isNotEmpty()) { choose(id); fill() }
                    }
                    .setNegativeButton(L.t("取消", "Cancel"), null)
                    .show()
            })
        }
        fill()
        return box
    }

    /** One option: its name, what it is good at, and a mark when it is the one in use. */
    private fun choiceRow(title: String, about: String, chosen: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(if (chosen) chipBg() else tileBg(), dp(10)).apply { setStroke(dp(1), if (chosen) accent() else chipBg()) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
        addView(text((if (chosen) "✓  " else "") + title, 15f, if (chosen) accent() else fg(), bold = true))
        addView(text(about, 12f, sub()))
        isClickable = true
        setOnClickListener { onClick() }
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

    /**
     * Writes [id]'s profile again from the history kept on the phone, through the running service,
     * without opening the chat: only the stretches whose text changed, or everything.
     */
    private fun rewrite(id: String, kept: Int) {
        if (ChatReaderService.running == null) {
            toast(L.t("Vibecheck 的无障碍服务没开：先在「设置」里打开它", "Vibecheck's accessibility service is off: switch it on under Setup first"))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("重新分析", "Rewrite the profile"))
            .setMessage(L.t(
                "用手机上存的 $kept 条聊天记录重新写 Ta 的档案，不用打开聊天。写的时候这一页显示进度，屏幕保持常亮。\n\n" +
                    "只更新变了的：内容没变的段落沿用以前的笔记，省钱。\n全部重做：每一段都重新整理，适合换了模型以后，花费多一些。",
                "Write their profile again from the $kept messages kept on this phone, without opening the chat. The progress shows on this page, and the screen stays on while it runs.\n\n" +
                    "Only what changed: stretches whose text is the same keep their notes, which costs less.\nEverything: every stretch is noted again, for after a model change; it costs more."))
            .setPositiveButton(L.t("只更新变了的", "Only what changed")) { _, _ -> startRewrite(id, fresh = false) }
            .setNeutralButton(L.t("全部重做", "Everything")) { _, _ -> startRewrite(id, fresh = true) }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    private fun startRewrite(id: String, fresh: Boolean) {
        // Looked up again: the service may have stopped (or started anew) while the dialog was open.
        val svc = ChatReaderService.running
        if (svc == null) {
            toast(L.t("Vibecheck 的无障碍服务没开：先在「设置」里打开它", "Vibecheck's accessibility service is off: switch it on under Setup first"))
            return
        }
        val why = runCatching { svc.rewriteFromKept(id, fresh) }.getOrElse { L.t("没能开始：${it.message}", "Could not start: ${it.message}") }
        if (why != null) { toast(why); return }
        toast(L.t("开始重新分析，进度在这一页", "Rewriting; the progress is on this page"))
        showPerson(id)
    }

    /**
     * While a profile is written from this page the screen stays on: once it goes off Android
     * takes the network away, and the write would stop halfway.
     */
    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun confirm(message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton(L.t("取消", "Cancel"), null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        /** Opens that person's page (the card's "Rename"). */
        const val EXTRA_PERSON = "dev.vibecheck.PERSON"
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42

        /** Tools tab order. Each can be hidden and brought back. */
        val TOOLS = listOf("pause", "card", "usage", "backup", "battery", "diagnostics", "guide")

        private const val TAB_SETUP = 0
        private const val TAB_TOOLS = 1
        private const val TAB_PEOPLE = 2
        /** How often a person's page redraws the progress of a profile being written. */
        private const val PROGRESS_TICK_MS = 5_000L
        /** [personShown] while the page about me is open. */
        private const val ME_PAGE = "\u0000me"

        fun guide(): String = L.t(
            "用法\n" +
                "1. 在「设置」页填 Key，打开无障碍服务。\n" +
                "2. 进聊天页，边上出现小气泡；对方发来消息后气泡变色，点开看卡片。\n" +
                "3. 拖动气泡换位置，长按气泡打开菜单：重新判断、暂停 1 小时、这个聊天不再解读、设置。\n" +
                "4. 「回复」给出三条草稿，点一条会填进空的输入框（不会发送），长按任意一行复制。\n" +
                "5. 「学习」会自己往上翻完你们的全部聊天记录（随时点气泡停），存在手机上，再分批交给模型写一份详细档案：Ta 是你的什么人、有多亲近、怎么说话、喜好、梗、雷区……回复草稿会照着你以前对 Ta 的真实回复来写。以后再点「学习」只补新的部分。\n" +
                "6. 卡片上 ⋯ → 关系：Ta 是你的恋人、朋友、同事……以及有多亲近（关系好不等于是恋人），之后每条都按这个判断。名字是 emoji 认不出来时，⋯ → 改名。\n" +
                "7. 没有气泡就去「诊断」打开调试模式，看服务到底读到了什么。\n\n" +
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
                "5. Learn scrolls through your whole history with them by itself (tap the bubble to stop), keeps it on the phone, and has the model write a detailed profile in batches: what they are to you, how close, how they talk, likes, running jokes, sore spots… Reply drafts then follow how you really answered them. Learning again only adds what is new.\n" +
                "6. On the card, ⋯ → Relationship: what they are to you (partner, friend, colleague…) and how close you are, which is not the same thing. Every message is then judged that way. If their name is emoji the app can't read, ⋯ → Rename.\n" +
                "7. No bubble? Turn on debug mode under Diagnostics to see what the service actually reads.\n\n" +
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
