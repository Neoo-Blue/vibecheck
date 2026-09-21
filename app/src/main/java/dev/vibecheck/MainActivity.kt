package dev.vibecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var diagUrl: TextView
    private lateinit var diagnostics: TextView
    private lateinit var people: PersonStore
    private lateinit var peopleSummary: TextView
    private lateinit var personPicker: Spinner
    private lateinit var personNote: EditText
    private var personIds = listOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.lang   // applies the UI language to L before anything is built
        people = PersonStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
        }

        root.addView(label("Vibecheck 读空气", 22f))
        status = label("", 13f)
        root.addView(status)

        // Language: rebuild the screen on change so every label follows.
        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val zhBtn = RadioButton(this).apply { text = "中文"; isChecked = !L.en }
        val enBtn = RadioButton(this).apply { text = "English"; isChecked = L.en }
        langGroup.addView(zhBtn); langGroup.addView(enBtn)
        langGroup.setOnCheckedChangeListener { _, id ->
            val want = if (id == enBtn.id) "en" else "zh"
            if (want != prefs.lang) { prefs.lang = want; recreate() }
        }
        root.addView(langGroup)

        root.addView(label("\nTypeSafe API Key", 14f))
        val key = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "sk-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key)

        root.addView(label(L.t("\nOpenRouter Key（深思 / 回复 / 学习此人；选了下面的引擎时也用来判断）", "\nOpenRouter key (deep analysis / replies / learn this person; also judging if chosen below)"), 14f))
        val orKey = EditText(this).apply {
            setText(prefs.orKey)
            hint = "sk-or-v1-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(orKey)

        root.addView(label(L.t("\n判断引擎", "\nJudging engine"), 14f))
        val judgeGroup = RadioGroup(this)
        // RadioGroup tracks selection by child id; NO_ID never clears the previous button.
        val judgeTs = RadioButton(this).apply { id = android.view.View.generateViewId(); text = L.t("Jev，走 TypeSafe（用上面的 TypeSafe Key）", "Jev via TypeSafe (uses the TypeSafe key above)"); isChecked = prefs.judge == Judge.TYPESAFE }
        val judgeOr = RadioButton(this).apply { id = android.view.View.generateViewId(); text = L.t("Jev，走 OpenRouter（用上面的 OpenRouter Key，一个 Key 全搞定）", "Jev via OpenRouter (uses the OpenRouter key above; one key for everything)"); isChecked = prefs.judge == Judge.OPENROUTER }
        judgeGroup.addView(judgeTs); judgeGroup.addView(judgeOr)
        root.addView(judgeGroup)

        root.addView(label(L.t("深思模型 / 看图模型", "Deep model / vision model"), 14f))
        val deepModel = EditText(this).apply { setText(prefs.deepModel) }
        root.addView(deepModel)
        val visionModel = EditText(this).apply { setText(prefs.visionModel) }
        root.addView(visionModel)

        root.addView(label(L.t("\n关系背景（会随对话一起发给模型）", "\nRelationship context (sent to the model with the chat)"), 14f))
        val ctx = EditText(this).apply {
            setText(prefs.context)
            hint = L.t("例：交往两年的女友，最近因为我总忘事在生气", "e.g. girlfriend of two years, annoyed lately because I keep forgetting things")
            minLines = 2
        }
        root.addView(ctx)

        root.addView(label(L.t("\n在这些应用里解读", "\nWatch these apps"), 14f))
        val watched = prefs.packageList.toSet()
        val appBoxes = Apps.KNOWN.map { (pkg, name) ->
            CheckBox(this).apply { text = name; isChecked = pkg in watched }.also { root.addView(it) }
        }
        val extraPkgs = EditText(this).apply {
            hint = L.t("其他应用的包名，逗号分隔", "Other package names, comma separated")
            setText(watched.filter { w -> Apps.KNOWN.none { it.first == w } }.joinToString(","))
        }
        root.addView(extraPkgs)

        val on = Switch(this).apply { text = L.t("  启用解读", "  Judging on"); isChecked = prefs.enabled }
        root.addView(on)
        val learn = Switch(this).apply { text = L.t("  从后续走向学习（校准危险等级、重排动作）", "  Learn from what happens next (calibrate risk, re-rank moves)"); isChecked = prefs.learning }
        root.addView(learn)
        val dbg = Switch(this).apply { text = L.t("  调试模式（只显示抓到的文本，不调用模型）", "  Debug mode (show captured text, no model calls)"); isChecked = prefs.debug }
        root.addView(dbg)
        val ocr = Switch(this).apply {
            text = L.t("  读不到界面时用截图识字（微信必须开）", "  OCR the screen when an app hides its tree (WeChat, Telegram)")
            isChecked = prefs.ocr
        }
        root.addView(ocr)
        val autoDeep = Switch(this).apply {
            text = L.t("  值得细看的消息自动深思（只在非闲聊时调 DeepSeek）", "  Auto deep analysis on turns worth it (DeepSeek, non-routine only)")
            isChecked = prefs.autoDeep
        }
        root.addView(autoDeep)
        val diag = Switch(this).apply { text = L.t("  局域网排查接口（默认关，会暴露聊天内容）", "  LAN debug endpoint (off by default; exposes chat content)"); isChecked = prefs.remoteDiag }
        root.addView(diag)

        diagUrl = label("", 11f)
        root.addView(diagUrl)

        root.addView(button(L.t("保存", "Save")) {
            prefs.apiKey = key.text.toString()
            prefs.context = ctx.text.toString()
            prefs.packages = (Apps.KNOWN.filterIndexed { i, _ -> appBoxes[i].isChecked }.map { it.first } +
                extraPkgs.text.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }).joinToString(",")
            prefs.enabled = on.isChecked
            prefs.learning = learn.isChecked
            prefs.debug = dbg.isChecked
            prefs.ocr = ocr.isChecked
            prefs.autoDeep = autoDeep.isChecked
            prefs.orKey = orKey.text.toString()
            prefs.judge = if (judgeOr.isChecked) Judge.OPENROUTER else Judge.TYPESAFE
            prefs.deepModel = deepModel.text.toString()
            prefs.visionModel = visionModel.text.toString()
            prefs.remoteDiag = diag.isChecked
            refreshDiagUrl()
            toast(L.t("已保存", "Saved"))
        })

        root.addView(button(L.t("复制排查地址", "Copy debug URL")) {
            val text = diagUrl.text.toString()
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("jev", text))
            toast(L.t("已复制", "Copied"))
        })

        root.addView(button(L.t("换一个排查口令", "Rotate debug token")) {
            prefs.newToken(); refreshDiagUrl(); toast(L.t("口令已更换，旧地址失效", "Token rotated; the old URL is dead"))
        })

        root.addView(button(L.t("测试判断引擎", "Test judging engine")) {
            val useOr = judgeOr.isChecked
            val k = (if (useOr) orKey else key).text.toString().trim()
            if (k.isEmpty()) { toast(if (useOr) L.t("先填 OpenRouter Key", "Add the OpenRouter key first") else L.t("先填 TypeSafe Key", "Add the TypeSafe key first")); return@button }
            toast(L.t("测试中…", "Testing…"))
            io.execute {
                val body = Jev.requestBody("测试", listOf("对方" to "你今天是不是又忘了我跟你说过什么？"))
                val r = runCatching {
                    if (useOr) TypeSafe.ask(k, Judge.viaOpenRouter(body), Judge.OPENROUTER_ENDPOINT) else TypeSafe.ask(k, body)
                }
                main.post {
                    r.onSuccess { toast(L.t("成功，返回 ${it.size} 个判断", "OK, ${it.size} judgments back")) }
                        .onFailure { toast(L.t("失败：${it.message?.take(120)}", "Failed: ${it.message?.take(120)}")) }
                }
            }
        })

        root.addView(button(L.t("打开无障碍设置", "Open accessibility settings")) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // Same process as the service, so this reads live state with no network involved.
        root.addView(label(L.t("\n诊断（切回微信用一下，再回来点刷新）", "\nDiagnostics (use a chat app, then come back and refresh)"), 14f))
        diagnostics = label("", 11f)
        root.addView(diagnostics)
        root.addView(button(L.t("刷新诊断", "Refresh diagnostics")) { refreshDiagnostics() })

        // Per-person memory: each contact gets their own style profile, history and bandit.
        root.addView(label(L.t("\n人物记忆（每个人各自学习，全部存在手机上）", "\nPeople memory (learned per person, all on this phone)"), 14f))
        peopleSummary = label("", 11f)
        root.addView(peopleSummary)
        personPicker = Spinner(this).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    personIds.getOrNull(pos)?.let { personNote.setText(people.loadId(it).note) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(personPicker)
        personNote = EditText(this).apply {
            hint = L.t("这个人的专属背景，留空就用上面的通用背景", "Context for this person; empty = use the general context above")
            minLines = 2
        }
        root.addView(personNote)
        root.addView(button(L.t("保存此人背景", "Save this person's context")) {
            selectedPerson()?.let { id ->
                val r = people.loadId(id)
                r.note = personNote.text.toString()
                people.save(r)
                refreshPeople()
                toast(L.t("已保存 ${r.name} 的背景", "Saved context for ${r.name}"))
            } ?: toast(L.t("还没认识任何人", "Nobody remembered yet"))
        })
        root.addView(button(L.t("清空全部人物记忆", "Forget everyone")) {
            people.forgetAll()
            personNote.setText("")
            refreshPeople()
            toast(L.t("已清空", "Cleared"))
        })
        // One person on two apps: fold the selected record into another and keep the link.
        root.addView(button(L.t("和另一个人合并（跨应用同一人）", "Merge with another person (same person, other app)")) {
            val from = selectedPerson() ?: run { toast(L.t("还没认识任何人", "Nobody remembered yet")); return@button }
            val others = personIds.filter { it != from }
            if (others.isEmpty()) { toast(L.t("只认识这一个人", "Only one person known")); return@button }
            val labels = others.map { "${people.nameOf(it)}（${Apps.label(it.substringBefore('|'))}）" }
            AlertDialog.Builder(this)
                .setTitle(L.t("把「${people.nameOf(from)}」合并进谁？", "Merge ${people.nameOf(from)} into whom?"))
                .setItems(labels.toTypedArray()) { _, i ->
                    people.link(from, others[i])
                    refreshPeople()
                    toast(L.t("已合并：以后两边都用「${people.nameOf(others[i])}」的记忆", "Merged: both apps now use ${people.nameOf(others[i])}'s memory"))
                }
                .setNegativeButton(L.t("取消", "Cancel"), null)
                .show()
        })
        root.addView(button(L.t("忘记此人", "Forget this person")) {
            selectedPerson()?.let { id ->
                people.forget(id)
                personNote.setText("")
                refreshPeople()
                toast(L.t("已忘记", "Forgotten"))
            } ?: toast(L.t("还没认识任何人", "Nobody remembered yet"))
        })

        root.addView(label(L.t(
            "\n用法\n" +
            "1. 填 Key 并保存。\n" +
            "2. 打开无障碍设置，找到「Vibecheck 读空气」并开启。\n" +
            "3. 若开关是灰的（Android 13+ 对侧载应用的限制）：设置 → 应用 → Vibecheck 读空气 → 右上角菜单 → 允许受限设置，再回来开启。\n" +
            "4. 进聊天页，右边缘出现小气泡；对方发来消息后气泡变色，点气泡看卡片。\n" +
            "5. 没有气泡就先开调试模式，看服务到底读到了什么。\n\n" +
            "学习\n" +
            "卡片给出建议后，下一轮对方的语气变化就是这次建议的回报：变缓和为正，升级为负。\n" +
            "应用据此校准这段关系的危险等级偏置，并重排「最佳动作」。卡片底部出现\n" +
            "「已按你们过去的走向调整」时，说明经验改变了排在最前面的动作。\n" +
            "注意：它只能看到「显示建议之后发生了什么」，看不到你是否真的照做，所以这是相关性不是因果。\n\n" +
            "排查接口\n" +
            "打开后，同一 Wi-Fi 的电脑可以读取运行时状态：/status /log /tree /last /learn /rescan。\n" +
            "每个地址都要带口令。/tree 会打印当前界面的完整节点树，用来确认应用有没有打乱内容。\n" +
            "接口在你打开被监听的应用后才真正启动。用完请关掉。\n\n" +
            "为什么有的应用要截图识字\n" +
            "微信 8.0.52 起不再把界面内容交给第三方无障碍服务，Telegram 的气泡画在 canvas 上：窗口在、事件有，但节点树里没有文字。\n" +
            "这两条路走截图 + 本地识字，模型在手机里，图片和文字都不出手机。其他应用走节点树，更省电。\n\n" +
            "聊天内容会发送到 api.typesafe.ai（或 openrouter.ai）进行判断。",
            "\nHow to use\n" +
            "1. Enter a key and save.\n" +
            "2. Open accessibility settings and enable \"Vibecheck 读空气\".\n" +
            "3. Toggle greyed out (Android 13+ restricts sideloaded apps)? Settings → Apps → Vibecheck → menu → Allow restricted settings, then enable it.\n" +
            "4. Open a chat: a small bubble appears at the right edge; it takes on a colour after a message arrives. Tap it for the card.\n" +
            "5. No bubble? Turn on debug mode to see what the service actually reads.\n\n" +
            "Learning\n" +
            "After the card gives a move, how the other side's tone changes next turn is that move's reward: calmer is positive, escalation negative.\n" +
            "The app calibrates this relationship's danger bias from it and re-ranks the best move. When the card says\n" +
            "\"adjusted from how things went before\", experience overrode the model's first choice.\n" +
            "It only sees what happened after the advice was shown, not whether you followed it: correlation, not causation.\n\n" +
            "Debug endpoint\n" +
            "When on, a computer on the same Wi-Fi can read live state: /status /log /tree /last /learn /rescan.\n" +
            "Every URL needs the token. /tree prints the full node tree of the current screen.\n" +
            "The endpoint only starts once a watched app is opened. Switch it off when done.\n\n" +
            "Why some apps need OCR\n" +
            "WeChat 8.0.52+ withholds its content from third-party accessibility services and Telegram draws bubbles on a canvas: the window and events are there, the tree has no text.\n" +
            "Those two go through screenshot + on-device OCR; nothing leaves the phone for that. Other apps use the node tree.\n\n" +
            "Chat text is sent to api.typesafe.ai (or openrouter.ai) for judging."), 12f))

        val scroll = ScrollView(this)
        scroll.addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scroll)
        refreshDiagUrl()
    }

    override fun onResume() {
        super.onResume()
        status.text = if (serviceEnabled()) L.t("无障碍服务：已开启", "Accessibility service: on") else L.t("无障碍服务：未开启（先去无障碍设置里打开）", "Accessibility service: off (enable it in accessibility settings first)")
        refreshDiagnostics()
        refreshPeople()
    }

    private fun selectedPerson(): String? = personIds.getOrNull(personPicker.selectedItemPosition)

    private fun refreshPeople() {
        personIds = people.index()
        val labels = if (personIds.isEmpty()) listOf(L.t("还没认识任何人", "Nobody remembered yet"))
        else personIds.map { id -> "${people.nameOf(id)}  (${people.loadId(id).apps.joinToString("+") { Apps.label(it) }})" }
        personPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        peopleSummary.text = people.summary()
    }

    private fun refreshDiagnostics() {
        val enabled = serviceEnabled()
        val lines = mutableListOf(
            L.t("系统开关：", "System toggle: ") + if (enabled) L.t("已开启", "on") else L.t("未开启", "off"),
            when {
                Diag.connected -> L.t("服务已连接：是", "Service connected: yes")
                enabled -> L.t("服务已连接：否（开关开着却没连上，关掉再打开一次）", "Service connected: no (toggle is on but not connected; switch it off and on)")
                else -> L.t("服务已连接：否", "Service connected: no")
            },
            L.t("收到事件：${Diag.events} 次，最近来自 ${Diag.lastPackage.ifBlank { "无" }}", "Events: ${Diag.events}, latest from ${Diag.lastPackage.ifBlank { "none" }}"),
            L.t("最近扫描：${Diag.lastScan.ifBlank { "还没扫过" }}", "Last scan: ${Diag.lastScan.ifBlank { "none yet" }}"),
            L.t("最近错误：${Diag.lastError.ifBlank { "无" }}", "Last error: ${Diag.lastError.ifBlank { "none" }}"),
        )
        if (Diag.events == 0 && enabled) lines.add(L.t("事件为 0：多半是包名没对上，或服务需要关掉再打开一次。", "Zero events: usually the package list, or the service needs an off/on."))
        lines.add("")
        lines.addAll(Diag.dump().lines().takeLast(8))
        diagnostics.text = lines.joinToString("\n")
    }

    private fun refreshDiagUrl() {
        diagUrl.text = if (prefs.remoteDiag)
            "http://${ChatReaderService.lanAddress()}:${DebugServer.PORT}/status?t=${prefs.diagToken}"
        else L.t("排查接口已关闭", "Debug endpoint is off")
    }

    private fun serviceEnabled(): Boolean {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return on.contains("$packageName/${ChatReaderService::class.java.name}")
    }

    private fun label(t: String, sp: Float) = TextView(this).apply { text = t; textSize = sp }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
