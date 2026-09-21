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
        people = PersonStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
        }

        root.addView(label("Vibecheck 读空气", 22f))
        status = label("", 13f)
        root.addView(status)

        root.addView(label("\nTypeSafe API Key", 14f))
        val key = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "sk-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key)

        root.addView(label("\nOpenRouter Key（只给「深思」和「回复」两个按钮用）", 14f))
        val orKey = EditText(this).apply {
            setText(prefs.orKey)
            hint = "sk-or-v1-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(orKey)

        root.addView(label("深思模型 / 看图模型", 14f))
        val deepModel = EditText(this).apply { setText(prefs.deepModel) }
        root.addView(deepModel)
        val visionModel = EditText(this).apply { setText(prefs.visionModel) }
        root.addView(visionModel)

        root.addView(label("\n关系背景（会随对话一起发给模型）", 14f))
        val ctx = EditText(this).apply {
            setText(prefs.context)
            hint = "例：交往两年的女友，最近因为我总忘事在生气"
            minLines = 2
        }
        root.addView(ctx)

        root.addView(label("\n在这些应用里解读", 14f))
        val watched = prefs.packageList.toSet()
        val appBoxes = Apps.KNOWN.map { (pkg, name) ->
            CheckBox(this).apply { text = name; isChecked = pkg in watched }.also { root.addView(it) }
        }
        val extraPkgs = EditText(this).apply {
            hint = "其他应用的包名，逗号分隔"
            setText(watched.filter { w -> Apps.KNOWN.none { it.first == w } }.joinToString(","))
        }
        root.addView(extraPkgs)

        val on = Switch(this).apply { text = "  启用解读"; isChecked = prefs.enabled }
        root.addView(on)
        val learn = Switch(this).apply { text = "  从后续走向学习（校准危险等级、重排动作）"; isChecked = prefs.learning }
        root.addView(learn)
        val dbg = Switch(this).apply { text = "  调试模式（只显示抓到的文本，不调用模型）"; isChecked = prefs.debug }
        root.addView(dbg)
        val ocr = Switch(this).apply {
            text = "  读不到界面时用截图识字（微信必须开）"
            isChecked = prefs.ocr
        }
        root.addView(ocr)
        val autoDeep = Switch(this).apply {
            text = "  值得细看的消息自动深思（只在非闲聊时调 DeepSeek）"
            isChecked = prefs.autoDeep
        }
        root.addView(autoDeep)
        val diag = Switch(this).apply { text = "  局域网排查接口（默认关，会暴露聊天内容）"; isChecked = prefs.remoteDiag }
        root.addView(diag)

        diagUrl = label("", 11f)
        root.addView(diagUrl)

        root.addView(button("保存") {
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
            prefs.deepModel = deepModel.text.toString()
            prefs.visionModel = visionModel.text.toString()
            prefs.remoteDiag = diag.isChecked
            refreshDiagUrl()
            toast("已保存")
        })

        root.addView(button("复制排查地址") {
            val text = diagUrl.text.toString()
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("jev", text))
            toast("已复制")
        })

        root.addView(button("换一个排查口令") {
            prefs.newToken(); refreshDiagUrl(); toast("口令已更换，旧地址失效")
        })

        root.addView(button("测试 API Key") {
            val k = key.text.toString().trim()
            if (k.isEmpty()) { toast("先填 Key"); return@button }
            toast("测试中…")
            io.execute {
                val r = runCatching {
                    TypeSafe.ask(k, Jev.requestBody("测试", listOf("对方" to "你今天是不是又忘了我跟你说过什么？")))
                }
                main.post {
                    r.onSuccess { toast("成功，返回 ${it.size} 个判断") }
                        .onFailure { toast("失败：${it.message?.take(120)}") }
                }
            }
        })

        root.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // Same process as the service, so this reads live state with no network involved.
        root.addView(label("\n诊断（切回微信用一下，再回来点刷新）", 14f))
        diagnostics = label("", 11f)
        root.addView(diagnostics)
        root.addView(button("刷新诊断") { refreshDiagnostics() })

        // Per-person memory: each contact gets their own style profile, history and bandit.
        root.addView(label("\n人物记忆（每个人各自学习，全部存在手机上）", 14f))
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
            hint = "这个人的专属背景，留空就用上面的通用背景"
            minLines = 2
        }
        root.addView(personNote)
        root.addView(button("保存此人背景") {
            selectedPerson()?.let { id ->
                val r = people.loadId(id)
                r.note = personNote.text.toString()
                people.save(r)
                refreshPeople()
                toast("已保存 ${r.name} 的背景")
            } ?: toast("还没认识任何人")
        })
        root.addView(button("清空全部人物记忆") {
            people.forgetAll()
            personNote.setText("")
            refreshPeople()
            toast("已清空")
        })
        // One person on two apps: fold the selected record into another and keep the link.
        root.addView(button("和另一个人合并（跨应用同一人）") {
            val from = selectedPerson() ?: run { toast("还没认识任何人"); return@button }
            val others = personIds.filter { it != from }
            if (others.isEmpty()) { toast("只认识这一个人"); return@button }
            val labels = others.map { "${people.nameOf(it)}（${Apps.label(it.substringBefore('|'))}）" }
            AlertDialog.Builder(this)
                .setTitle("把「${people.nameOf(from)}」合并进谁？")
                .setItems(labels.toTypedArray()) { _, i ->
                    people.link(from, others[i])
                    refreshPeople()
                    toast("已合并：以后两边都用「${people.nameOf(others[i])}」的记忆")
                }
                .setNegativeButton("取消", null)
                .show()
        })
        root.addView(button("忘记此人") {
            selectedPerson()?.let { id ->
                people.forget(id)
                personNote.setText("")
                refreshPeople()
                toast("已忘记")
            } ?: toast("还没认识任何人")
        })

        root.addView(label(
            "\n用法\n" +
            "1. 填 Key 并保存。\n" +
            "2. 打开无障碍设置，找到「Vibecheck 读空气」并开启。\n" +
            "3. 若开关是灰的（Android 13+ 对侧载应用的限制）：设置 → 应用 → Vibecheck 读空气 → 右上角菜单 → 允许受限设置，再回来开启。\n" +
            "4. 进微信或 Soul 聊天页，对方发来消息后卡片会出现在气泡下方。\n" +
            "5. 没有卡片就先开调试模式，看服务到底读到了什么。\n\n" +
            "学习\n" +
            "卡片给出建议后，下一轮对方的语气变化就是这次建议的回报：变缓和为正，升级为负。\n" +
            "应用据此校准这段关系的危险等级偏置，并重排「最佳动作」。卡片底部出现\n" +
            "「已按你们过去的走向调整」时，说明经验改变了排在最前面的动作。\n" +
            "注意：它只能看到「显示建议之后发生了什么」，看不到你是否真的照做，所以这是相关性不是因果。\n\n" +
            "排查接口\n" +
            "打开后，同一 Wi-Fi 的电脑可以读取运行时状态：/status /log /tree /last /learn /rescan。\n" +
            "每个地址都要带口令。/tree 会打印当前界面的完整节点树，用来确认微信有没有打乱内容。\n" +
            "接口在你打开微信/Soul 后才真正启动。用完请关掉。\n\n" +
            "微信为什么需要截图识字\n" +
            "微信 8.0.52 起不再把界面内容交给第三方无障碍服务：窗口在、事件有，但节点树是空的。\n" +
            "所以微信这条路走截图 + 本地识字，模型在手机里，图片和文字都不出手机。Soul 等普通应用仍走节点树，更省电。\n\n" +
            "聊天内容会发送到 api.typesafe.ai 进行判断。", 12f))

        val scroll = ScrollView(this)
        scroll.addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scroll)
        refreshDiagUrl()
    }

    override fun onResume() {
        super.onResume()
        status.text = if (serviceEnabled()) "无障碍服务：已开启" else "无障碍服务：未开启（先去无障碍设置里打开）"
        refreshDiagnostics()
        refreshPeople()
    }

    private fun selectedPerson(): String? = personIds.getOrNull(personPicker.selectedItemPosition)

    private fun refreshPeople() {
        personIds = people.index()
        val labels = if (personIds.isEmpty()) listOf("还没认识任何人")
        else personIds.map { id -> "${people.nameOf(id)}  (${people.loadId(id).apps.joinToString("+") { Apps.label(it) }})" }
        personPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        peopleSummary.text = people.summary()
    }

    private fun refreshDiagnostics() {
        val enabled = serviceEnabled()
        val lines = mutableListOf(
            "系统开关：" + if (enabled) "已开启" else "未开启",
            when {
                Diag.connected -> "服务已连接：是"
                enabled -> "服务已连接：否（开关开着却没连上，关掉再打开一次）"
                else -> "服务已连接：否"
            },
            "收到事件：${Diag.events} 次，最近来自 ${Diag.lastPackage.ifBlank { "无" }}",
            "最近扫描：${Diag.lastScan.ifBlank { "还没扫过" }}",
            "最近错误：${Diag.lastError.ifBlank { "无" }}",
        )
        if (Diag.events == 0 && enabled) lines.add("事件为 0：多半是包名没对上，或服务需要关掉再打开一次。")
        lines.add("")
        lines.addAll(Diag.dump().lines().takeLast(8))
        diagnostics.text = lines.joinToString("\n")
    }

    private fun refreshDiagUrl() {
        diagUrl.text = if (prefs.remoteDiag)
            "http://${ChatReaderService.lanAddress()}:${DebugServer.PORT}/status?t=${prefs.diagToken}"
        else "排查接口已关闭"
    }

    private fun serviceEnabled(): Boolean {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return on.contains("$packageName/${ChatReaderService::class.java.name}")
    }

    private fun label(t: String, sp: Float) = TextView(this).apply { text = t; textSize = sp }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
