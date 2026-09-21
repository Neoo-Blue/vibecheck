package dev.vibecheck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads the visible chat off the screen, asks Jev about it, draws the card, and scores its
 * own previous advice against what happened next.
 *
 * Deliberately id-free: bubbles are found by class, geometry and long-clickability, because
 * WeChat renames its view ids every release.
 */
class ChatReaderService : AccessibilityService(), DebugServer.Host, OverlayCard.Actions {

    /** Everything the deep model needs about the turn currently on the card. */
    private class Ctx(
        val name: String,
        val note: String,
        val style: String?,
        val history: String?,
        val relation: String?,
        val transcript: List<Pair<String, String>>,
        val answers: Map<String, Jev.Answer>,
        val viaOcr: Boolean,
        val situation: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var card: OverlayCard
    private lateinit var people: PersonStore
    private var server: DebugServer? = null

    private var lastKey: String? = null
    private var forceJudge = false
    private var busy = false
    private var pending: Learner.Episode? = null
    private var pendingText: String = ""
    private var lastWindow = Chat.Box(0, 0, 0, 0)
    private var lastCounts = "尚未扫描"
    private var targetPkg = ""
    private var pendingId = ""
    private var lastTitles = listOf<Pair<String, Chat.Box>>()
    private var lastDescs = listOf<String>()
    private var lastProbe = 0L
    private var lastTitleHash = ""
    private var viaOcr = false
    private var onHomeScreen = false
    private var ctx: Ctx? = null
    private var deepBusy = false

    // 学习此人: scroll the chat toward older messages, reading each screen, then write a bio.
    private var learning = false
    private var learnRecord: PersonStore.Record? = null
    private var learnPkg = ""
    private val learnHistory = ArrayList<Pair<String, String>>()   // oldest first
    private var learnScrolls = 0
    private var learnStale = 0
    /** Bumped on every screen change in the watched app; a judgment for an older screen is dropped. */
    private var screenGen = 0
    private var currentRecord: PersonStore.Record? = null

    private val scan = Runnable { scanNow() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        card = OverlayCard(this, this)
        people = PersonStore(this)
        // No packageNames filter: with one, no event ever arrives from another app, so the card
        // never learns the chat is gone and stays pinned over whatever you open next.
        serviceInfo = serviceInfo.apply { packageNames = null }
        Diag.connected = true
        Diag.log("connected, watching ${prefs.packages}")
        if (prefs.remoteDiag) server = DebugServer(prefs.diagToken, this).also { it.start() }
    }

    /** Picks up the settings toggle without making the user restart the accessibility service. */
    private fun syncDebugRoutes() {
        if (prefs.remoteDiag && server == null) server = DebugServer(prefs.diagToken, this).also { it.start() }
        else if (!prefs.remoteDiag && server != null) { server?.stop(); server = null; Diag.log("debug routes off") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        syncDebugRoutes()
        val pkg = event?.packageName?.toString() ?: return
        // Showing the card is itself a window change: reacting to our own events hid the card
        // a moment after drawing it, which looked like a flash.
        if (pkg == packageName) return
        Diag.events++
        Diag.lastPackage = pkg
        if (pkg !in prefs.packageList) {
            // Only a real foreground switch takes the card down. Status-bar ticks and background
            // chatter from other apps must not: those arrive constantly.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                screenGen++
                main.post { abortLearning("离开了聊天"); card.hide() }
            }
            return
        }
        targetPkg = pkg
        // Navigating inside the app (chat -> list -> another chat) must drop the old judgment
        // immediately; the next scan decides what belongs on this screen. A history read on the
        // chat list would store names and previews as messages, so it ends here too.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            screenGen++
            main.post { abortLearning("切换了页面"); card.hide() }
        }
        main.removeCallbacks(scan)
        main.postDelayed(scan, DEBOUNCE_MS)   // the list settles after a new message arrives
    }

    override fun onInterrupt() { abortLearning("服务被打断") }

    override fun onDestroy() {
        Diag.connected = false
        learning = false
        card.hide()
        server?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    /** The keyboard's own window, so its keys can be cut out of the screenshot. */
    private fun imeBox(): Chat.Box? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            Chat.Box(r.left, r.top, r.right, r.bottom)
        }
    }.getOrNull()

    /**
     * A coarse grayscale fingerprint of the title bar, used as an identity when the contact's
     * name cannot be read: an emoji-only name like "🍵" is invisible to OCR, but its pixels are
     * stable across visits, so the chat still gets its own memory.
     */
    private fun fingerprint(bmp: Bitmap, win: Chat.Box, bubbles: List<Chat.Bubble>): IntArray {
        val w = bmp.width
        val h = bmp.height
        // Their avatar, taken from the row of the first message they sent: a photo, so it has
        // real variation. The title bar is nearly blank and hashed every chat to the same value.
        val firstIncoming = bubbles.firstOrNull { it.incoming }
        val region = if (firstIncoming != null)
            Chat.Box(
                (w * 0.015f).toInt(), firstIncoming.box.top,
                (w * 0.09f).toInt(), (firstIncoming.box.top + h * 0.032f).toInt(),
            )
        else
            Chat.Box((w * 0.3f).toInt(), (h * 0.035f).toInt(), (w * 0.7f).toInt(), (h * 0.075f).toInt())

        // 4x4 grid of BLOCK MEANS, not single pixels. One pixel moves with every anti-aliasing
        // wobble and scroll offset, which is why the same avatar hashed differently on every scan
        // and the phone accumulated hundreds of "people". A block average barely moves.
        val cols = 4; val rows = 4
        val out = IntArray(cols * rows)
        val cw = ((region.right - region.left) / cols).coerceAtLeast(1)
        val ch = ((region.bottom - region.top) / rows).coerceAtLeast(1)
        var i = 0
        for (row in 0 until rows) for (col in 0 until cols) {
            var sum = 0L; var n = 0
            val x0 = region.left + col * cw
            val y0 = region.top + row * ch
            var y = y0
            while (y < y0 + ch) {
                var x = x0
                while (x < x0 + cw) {
                    if (x in 0 until w && y in 0 until h) {
                        val p = bmp.getPixel(x, y)
                        sum += ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                        n++
                    }
                    x += 2
                }
                y += 2
            }
            out[i++] = if (n == 0) 0 else (sum / n).toInt()
        }
        return out
    }

    /**
     * rootInActiveWindow is whatever window holds input focus, which is not necessarily the chat:
     * it can be the IME, a system dialog, or our own overlay. Prefer the window that actually
     * belongs to the app we are watching.
     */
    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg && r.childCount > 0) return r
            }
        }
        return rootInActiveWindow
    }

    private fun scanNow() {
        if (learning) return
        val root = rootFor(targetPkg)
        val bubbles = if (root == null) emptyList() else try { collect(root) } catch (e: Exception) {
            Diag.lastError = "collect: ${e.message}"; Diag.log(Diag.lastError); emptyList()
        }
        // WeChat 8.0.52+ hands third-party services an empty tree. When that happens, fall back
        // to reading the pixels that are already on screen.
        if (bubbles.isEmpty() && prefs.ocr) { ocrScan(); return }
        viaOcr = false
        onHomeScreen = false
        handle(bubbles)
    }

    /** Capture the screen, recognize the text on it, and carry on with the normal pipeline. */
    private fun ocrScan() {
        val now = System.currentTimeMillis()
        if (busy || now - lastProbe < PROBE_GAP_MS) return
        lastProbe = now
        captureBitmap { bmp, note ->
            if (bmp == null) {
                Diag.shotProbe = "$targetPkg → $note"
                main.post { handle(emptyList()) }
                return@captureBitmap
            }
            val win = Chat.Box(0, 0, bmp.width, bmp.height)
            Ocr.read(bmp) { items ->
                // Everything on screen that is not the conversation: our own card, and the
                // keyboard, whose keys were otherwise read as a stream of one-letter messages.
                val exclude = listOfNotNull(card.bounds(), imeBox())
                val (found, titles) = Chat.fromOcr(items, win, exclude)
                onHomeScreen = Chat.looksLikeHomeScreen(items, win)
                lastTitleHash = Person.hashOf(fingerprint(bmp, win, found))
                bmp.recycle()
                lastWindow = win
                lastTitles = titles
                lastDescs = emptyList()
                lastCounts = "OCR：识别 ${items.size} 块，过滤后 ${found.size}"
                viaOcr = true
                main.post { handle(found) }
            }
        }
    }

    private fun handle(bubbles: List<Chat.Bubble>) {
        Diag.lastScan = "$lastCounts；最后一条：" +
            (bubbles.lastOrNull()?.let { (if (it.incoming) "对方 " else "我 ") + it.text.take(30) } ?: "无")

        if (prefs.debug) {
            // Always draw in debug mode, even with nothing left after filtering: a blank screen
            // cannot tell you whether the service is dead, blocked, or just over-filtering.
            val lines = if (bubbles.isEmpty()) listOf(lastCounts, "窗口 ${lastWindow.left},${lastWindow.top}-${lastWindow.right},${lastWindow.bottom}")
            else bubbles.takeLast(4).map { (if (it.incoming) "对方: " else "我: ") + it.text.take(24) }
            val who = Person.peerName(lastTitles, lastDescs, lastWindow) ?: "认不出是谁"
            val where = if (Chat.inConversation(bubbles)) "聊天页" else "不是聊天页"
            card.show(
                listOf(Jev.Block("调试：$who，$where，抓到 ${bubbles.size} 条", lines)),
                null, bubbles.lastOrNull()?.box?.toRect()
            )
            return
        }
        // The chat list is also full of names and text; judging it would be nonsense.
        if (onHomeScreen || !Chat.inConversation(bubbles)) { card.hide(); return }

        // Who am I talking to? Everything below is scoped to that person.
        // Emoji-only names are invisible to OCR, so fall back to the title-bar fingerprint
        // rather than lumping every unreadable chat together under one "unknown" record.
        val name = Person.peerName(lastTitles, lastDescs, lastWindow)
            ?: lastTitleHash.takeIf { it.isNotBlank() }?.let { people.resolveFingerprint(targetPkg, it) }
            ?: "未知"
        val record = people.load(targetPkg, name)
        currentRecord = record

        // Passive learning. This runs whether or not a card is drawn and whether or not we are
        // willing to spend an API call: the messages are already on screen, reading them is free.
        observePassively(record, bubbles)
        Diag.person = "$name（看过 ${record.stats.theirMsgs + record.stats.myMsgs} 条，" +
            "学习 ${record.model.updates} 次）"

        // Judging costs money and there is nowhere to show it, so stop here when the card is
        // dismissed or judging is switched off. The learning above already happened.
        if (!prefs.enabled) { card.hide(); return }
        // Resting state in a watched chat: a bubble, not a card.
        card.idle(bubbles.lastOrNull()?.box?.toRect())

        // Normally judge only when the other person spoke last. But if the user opened the card
        // themselves, judge the current screen whoever spoke last, or it hangs on "思考中".
        val key = Chat.triggerKey(bubbles)
            ?: (if (forceJudge) Chat.anyKey(bubbles) else null)
            ?: return
        forceJudge = false
        if (key == lastKey) return
        if (busy) { main.removeCallbacks(scan); main.postDelayed(scan, 1500); return }   // judge it after the current call
        if (prefs.apiKey.isBlank()) { card.showText("还没填 TypeSafe API Key", bubbles.last().box.toRect()); return }
        lastKey = key
        busy = true

        val anchor = bubbles.last().box.toRect()
        val lastIncoming = (bubbles.lastOrNull { it.incoming } ?: bubbles.last()).text
        card.showText("思考中…", anchor)

        val transcript = Chat.transcript(bubbles)
        val background = people.background(record, prefs.context)
        val state = Jev.stateJson(
            context = background,
            transcript = transcript,
            peer = name,
            style = Person.styleSummary(record.style),
            history = Person.historySummary(record.history),
            relation = Relation.summary(record.stats),
            source = if (viaOcr) OCR_CAVEAT else null,
        )
        val apiKey = prefs.apiKey
        val askedOn = screenGen
        Diag.lastRequest = Jev.triageBody(state)
        Diag.log("ask: ${transcript.size} 条上下文")
        val started = System.currentTimeMillis()

        // Two stages. Triage decides whether this turn deserves anything; only then is the
        // situation-specific question set asked, so the card's headers change with the situation
        // instead of every chat getting the same five blocks.
        io.execute {
            val triage = runCatching { TypeSafe.ask(apiKey, Jev.triageBody(state)) }
            val routine = triage.map { Jev.isRoutine(it) }.getOrDefault(false)
            // No situation answer at all still gets the default set, not a two-block card.
            val situation = triage.getOrNull()?.let { (it["situation"] as? Jev.Answer.Dist)?.top } ?: ""
            val merged = triage.mapCatching { t ->
                if (routine) t else t + TypeSafe.ask(apiKey, Jev.detailBody(state, situation))
            }
            main.post {
                busy = false
                if (askedOn != screenGen) {
                    // The user moved to another chat while this was in flight: not their answer.
                    Diag.log("丢弃：屏幕已切换")
                    lastKey = null
                    return@post
                }
                merged.onSuccess { raw ->
                    Diag.log("ok in ${System.currentTimeMillis() - started}ms" +
                        (if (routine) "（轻松，一步）" else "（细看，两步，场合=$situation）"))
                    val learned = applyLearning(record, raw, bubbles, lastIncoming)
                    ctx = Ctx(
                        name, background,
                        Person.styleSummary(record.style), Person.historySummary(record.history),
                        Relation.summary(record.stats), transcript, learned.answers, viaOcr, situation,
                    )
                    card.clearExtra()   // the deep answer belonged to the previous turn
                    val danger = learned.answers["danger"] as? Jev.Answer.Scored
                    val levels = (danger?.levels ?: 1).coerceAtLeast(2)
                    val riskNorm = ((danger?.score ?: 0.0) / (levels - 1)).toFloat()
                    val blocks = if (routine) Jev.routineCard(learned.answers)
                        else Jev.card(learned.answers, situation = situation)
                    if (blocks.isEmpty()) card.hide()
                    else card.show(
                        blocks, if (routine) null else footerWith(learned), anchor,
                        if (routine) emptyList() else Jev.more(learned.answers),
                        badge = if (routine) "·" else danger?.let { "${Math.round(it.score).toInt() + 1}" },
                        risk = riskNorm,
                    )
                    if (!routine && prefs.autoDeep && prefs.orKey.isNotBlank()) {
                        deepCall("深度分析", OpenRouter.DEEP_SYSTEM, copyable = false, openCard = false)
                    }
                }.onFailure { e ->
                    Diag.lastError = "ask: ${e.message}"
                    Diag.log(Diag.lastError)
                    card.showText("Jev 调用失败：${e.message?.take(60)}", anchor)
                    lastKey = null   // let the same message retry on the next screen change
                }
            }
        }
    }

    private class Learned(val answers: Map<String, Jev.Answer>, val adjusted: Boolean, val reward: Double?)

    /**
     * Score the previous card against this turn, then bend this turn's numbers by what we
     * have learned about this particular relationship.
     */
    private fun applyLearning(
        record: PersonStore.Record,
        raw: Map<String, Jev.Answer>,
        bubbles: List<Chat.Bubble>,
        lastIncoming: String,
    ): Learned {
        val score = raw["danger"] as? Jev.Answer.Scored
        val levels = (score?.levels ?: 1).coerceAtLeast(2)
        val rawNorm = (score?.score ?: 0.0) / (levels - 1)
        if (!prefs.learning) return Learned(raw, false, null)

        var reward: Double? = null
        pending?.let { ep ->
            // Only settle an episode against the same person it was opened for.
            if (pendingId == record.id && Chat.repliedSince(bubbles, pendingText)) {
                reward = Learner.observe(record.model, ep, rawNorm)
                Diag.log("learn ${record.name}: ${ep.intent}|${ep.action} reward=${"%+.2f".format(reward)}")
            }
            pending = null
        }

        val out = LinkedHashMap(raw)
        val calibrated = Learner.danger(record.model, rawNorm)
        out["danger"] = Jev.Answer.Scored(calibrated * (levels - 1), levels)

        val intent = (raw["intent"] as? Jev.Answer.Dist)?.top ?: "unknown"
        val situation = (raw["situation"] as? Jev.Answer.Dist)?.top ?: "*"
        Relation.judged(record.stats, calibrated)
        var adjusted = false
        (raw["action"] as? Jev.Answer.Dist)?.let { dist ->
            val reranked = Learner.rerank(record.model, situation, intent, dist.probs)
            adjusted = Learner.changedTop(dist.probs, reranked)
            val top = reranked.maxByOrNull { it.value }?.key ?: dist.top
            out["action"] = Jev.Answer.Dist(top, reranked)
            pending = Learner.Episode(situation, intent, top, calibrated)
            pendingId = record.id
            pendingText = lastIncoming
            record.history = Person.push(
                record.history,
                Person.Turn(intent, Math.round(calibrated * (levels - 1)).toInt() + 1, top)
            )
        }
        people.save(record)
        return Learned(out, adjusted, reward)
    }

    // ---- OverlayCard.Actions: the buttons on the card ----

    override fun onDeepThink() = deepCall(
        title = "深度分析",
        system = OpenRouter.DEEP_SYSTEM,
        copyable = false,
    )

    override fun onSuggestReplies() = deepCall(
        title = "可以这样回",
        system = OpenRouter.REPLY_SYSTEM,
        copyable = true,
    )

    override fun onOpened() {
        lastKey = null              // opened before anything was judged: judge this screen now
        forceJudge = true           // judge even if the newest message is my own
        main.removeCallbacks(scan)
        main.post(scan)
    }

    override fun onLearn() {
        val rec = currentRecord
        if (rec == null) { card.setExtra("学习此人", listOf("先在一个聊天页里打开卡片"), false); return }
        if (learning) return
        learning = true
        learnRecord = rec
        learnPkg = targetPkg
        learnHistory.clear()
        learnScrolls = 0
        learnStale = 0
        Diag.log("learn: 开始读 ${rec.name} 的历史")
        card.collapse("读", frozen = true)
        learnStep()
    }

    /** A capture that never calls back (screenshot or OCR hung) must not leave learn mode on forever. */
    private val learnWatchdog = Runnable { abortLearning("卡住了") }

    /** Is a window of this app on screen? rootInActiveWindow is the keyboard whenever it is up. */
    private fun onScreen(pkg: String): Boolean =
        runCatching { windows.any { it.root?.packageName?.toString() == pkg } }.getOrDefault(false)

    /**
     * One page of the history read: capture what is on screen, keep what is new, then scroll
     * toward older messages and go again. Stops when four pages in a row add nothing (the top
     * of the history, or a stretch of nothing but pictures) or at a hard cap.
     */
    private fun learnStep() {
        if (!learning) return
        if (!onScreen(learnPkg)) { abortLearning("离开了聊天"); return }
        main.removeCallbacks(learnWatchdog)
        main.postDelayed(learnWatchdog, 10_000)
        captureBubbles { bubbles ->
            if (!learning) return@captureBubbles
            // Within a page bubbles run oldest→newest; a page reached by scrolling up is older
            // than everything already kept, so what is not in the overlap goes to the front.
            val page = bubbles.map { (if (it.incoming) "对方" else "我") to it.text }
            val fresh = Chat.freshLines(learnHistory, page)
            learnHistory.addAll(0, fresh)
            if (fresh.isEmpty()) learnStale++ else learnStale = 0
            learnScrolls++
            card.collapse("${learnHistory.size}", frozen = true)
            if (learnStale >= 4 || learnScrolls >= MAX_LEARN_PAGES) { finishLearning(); return@captureBubbles }
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            // Drag downward: the list follows the finger, revealing older messages at the top.
            // The next step is on a timer, not the gesture callback: a callback that never comes
            // must not leave learn mode stuck on and every judgment switched off.
            swipe(w / 2, h * 0.32f, w / 2, h * 0.78f)
            main.postDelayed({ learnStep() }, 1800)
        }
    }

    private fun abortLearning(why: String) {
        if (!learning) return
        learning = false
        main.removeCallbacks(learnWatchdog)
        learnRecord = null
        Diag.log("learn: 中止，$why，已读 ${learnHistory.size} 条")
        card.collapse(null)
    }

    private fun finishLearning() {
        learning = false
        main.removeCallbacks(learnWatchdog)
        val rec = learnRecord ?: return
        learnRecord = null
        val history = ArrayList(learnHistory)
        Diag.log("learn: 读完 ${history.size} 条")
        if (history.size < 4) {
            card.setExtra("学习此人", listOf("只读到 ${history.size} 条，不够写档案"), false)
            return
        }
        // A full read is the better baseline than what was seen live, so it replaces the counts
        // and my style rather than adding on top. A read that stopped short (sticker stretch,
        // page cap) and covers less than the live count is kept for the bio only.
        val stats = rec.stats
        if (history.size >= stats.theirMsgs + stats.myMsgs) {
            stats.theirMsgs = 0; stats.myMsgs = 0; stats.theirChars = 0; stats.myChars = 0
            Relation.observeBulk(stats, history)
            val style = Person.Style()
            history.filter { it.first == "我" }.forEach { Person.observe(style, it.second) }
            rec.style = style
        }
        rec.learned = history.size
        people.save(rec)

        if (prefs.orKey.isBlank()) {
            card.setExtra("学习此人", listOf("读了 ${history.size} 条，统计已更新", "填了 OpenRouter Key 才能写档案"), false)
            return
        }
        card.setExtra("学习此人", listOf("读了 ${history.size} 条，正在写档案…"), false)
        val prompt = OpenRouter.bioPrompt(rec.name, history)
        io.execute {
            val r = runCatching { OpenRouter.chat(prefs.orKey, prefs.deepModel, OpenRouter.BIO_SYSTEM, prompt, null) }
            main.post {
                r.onSuccess { bio ->
                    // Saved onto a fresh copy: a scan during the call may have moved the counts on.
                    val fresh = people.loadId(rec.id)
                    fresh.bio = bio.trim()
                    people.save(fresh)
                    Diag.log("learn: ${rec.name} 档案已写")
                    card.setExtra("已学会 ${rec.name}（${history.size} 条）",
                        bio.lines().map { it.trim() }.filter { it.isNotEmpty() }, false)
                }.onFailure {
                    Diag.log("learn: 写档案失败 ${it.message}")
                    card.setExtra("学习此人", listOf("读了 ${history.size} 条，档案没写成：${it.message?.take(60)}"), false)
                }
            }
        }
    }

    /** What the chat currently shows, by node tree where it works and by OCR where it does not. */
    private fun captureBubbles(cb: (List<Chat.Bubble>) -> Unit) {
        val root = rootFor(targetPkg)
        val tree = root?.let { runCatching { collect(it) }.getOrNull() } ?: emptyList()
        if (tree.isNotEmpty() || !prefs.ocr) { cb(tree); return }
        captureBitmap { bmp, _ ->
            if (bmp == null) { main.post { cb(emptyList()) }; return@captureBitmap }
            val win = Chat.Box(0, 0, bmp.width, bmp.height)
            Ocr.read(bmp) { items ->
                bmp.recycle()
                val (found, _) = Chat.fromOcr(items, win, listOfNotNull(card.bounds(), imeBox()))
                main.post { cb(found) }
            }
        }
    }

    /**
     * A scroll gesture on the chat list: a slow drag, then the finger holds still before lifting,
     * so the list stops where the finger stops instead of flinging past pages nobody captured.
     */
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val drag = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, 600, true)
        val hold = drag.continueStroke(Path().apply { moveTo(x2, y2); lineTo(x2, y2 + 1) }, 0, 250, false)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(drag).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    dispatchGesture(GestureDescription.Builder().addStroke(hold).build(), null, null)
                }
                override fun onCancelled(d: GestureDescription?) { Diag.log("learn: 翻页手势被取消") }
            },
            null,
        )
        if (!ok) Diag.log("learn: 翻页手势发送失败")
    }

    override fun onCopy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("jev", text))
        card.setExtra("已复制，去输入框长按粘贴", listOf(text), false)
    }

    /**
     * On-demand call to the big model. Grabs a fresh screenshot first when the transcript came
     * from OCR, so the emoji and stickers that OCR cannot read are still part of the picture.
     */
    private fun deepCall(title: String, system: String, copyable: Boolean, openCard: Boolean = true) {
        val c = ctx ?: run { if (openCard) card.setExtra(title, listOf("还没有可分析的对话"), false); return }
        if (deepBusy) return
        if (prefs.orKey.isBlank()) {
            if (openCard) card.setExtra(title, listOf("还没填 OpenRouter Key，去设置里填"), false)
            return
        }
        deepBusy = true
        if (openCard) card.setExtra(title, listOf("思考中…"), false)

        // Bind the answer to the turn it was asked about: an automatic pass that lands after a
        // new message must not be pinned onto the wrong card.
        val askedFor = ctx
        val finish = { lines: List<String> ->
            main.post {
                deepBusy = false
                if (ctx === askedFor) card.setExtra(title, lines, copyable && lines.size > 1, openCard)
            }
        }

        val send = { image: String? ->
            val model = if (image != null) prefs.visionModel else prefs.deepModel
            val prompt = OpenRouter.deepPrompt(
                c.name, c.note, c.style, c.history, c.relation,
                c.transcript, c.answers, c.viaOcr, image != null, c.situation,
            )
            io.execute {
                val started = System.currentTimeMillis()
                val r = runCatching { OpenRouter.chat(prefs.orKey, model, system, prompt, image) }
                r.onSuccess { Diag.log("deep($model) ok in ${System.currentTimeMillis() - started}ms") }
                    .onFailure { Diag.lastError = "deep: ${it.message}"; Diag.log(Diag.lastError) }
                finish(
                    r.map { text -> text.lines().map { it.trim() }.filter { it.isNotEmpty() } }
                        .getOrElse { listOf("失败：${it.message?.take(80)}") }
                )
            }
        }

        if (c.viaOcr) captureBitmap { bmp, note ->
            send(bmp?.let { val s = toJpegBase64(it); it.recycle(); s }.also { if (it == null) Diag.log("deep: no image, $note") })
        } else send(null)
    }

    /** Downscaled so a 1440x3120 screen is a sane number of vision tokens. */
    private fun toJpegBase64(bmp: Bitmap): String {
        val target = 900
        val scaled = if (bmp.width > target)
            Bitmap.createScaledBitmap(bmp, target, bmp.height * target / bmp.width, true)
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Fold everything new on screen into this person's memory: how I write to them, and how the
     * relationship actually behaves (who opens, who writes more, how fast I answer).
     * Each message is counted exactly once, tracked by the newest one already seen.
     */
    private fun observePassively(record: PersonStore.Record, bubbles: List<Chat.Bubble>) {
        if (bubbles.isEmpty()) return
        val seen = bubbles.indexOfLast { it.text == record.lastSeenText }
        val fresh = if (seen >= 0) bubbles.drop(seen + 1) else bubbles
        if (fresh.isEmpty()) return

        fresh.filter { !it.incoming }.forEach { Person.observe(record.style, it.text) }
        Relation.observe(record.stats, fresh, System.currentTimeMillis())
        record.lastSeenText = bubbles.last().text
        people.save(record)
        Diag.log("passive: +${fresh.size} 条 → ${record.name}")
    }

    private fun footerWith(l: Learned): String? {
        val base = Jev.footer(l.answers)
        val note = if (l.adjusted) "（已按你们过去的走向调整）" else null
        return listOfNotNull(base, note).joinToString("\n").ifBlank { null }
    }

    /** Depth-first walk of the window, keeping only nodes that look like chat bubbles. */
    private fun collect(root: AccessibilityNodeInfo): List<Chat.Bubble> {
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val win = if (bounds.width() > 0 && bounds.height() > 0)
            Chat.Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
        else
            Chat.Box(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        val found = ArrayList<Chat.Bubble>()
        val longClickable = HashSet<String>()
        val titles = ArrayList<Pair<String, Chat.Box>>()
        val descs = ArrayList<String>()
        var texts = 0
        val statusBar = win.top + (win.bottom - win.top) * 0.035
        val titleBand = win.top + (win.bottom - win.top) * 0.07

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            n.contentDescription?.toString()?.let { if (it.endsWith("头像")) descs.add(it) }
            val text = n.text?.toString()?.trim().orEmpty()
            val cls = n.className?.toString().orEmpty()
            if (text.isNotEmpty() && cls.endsWith("TextView")) {
                texts++
                val r = Rect().also { n.getBoundsInScreen(it) }
                val box = Chat.Box(r.left, r.top, r.right, r.bottom)
                if (box.top >= statusBar && box.top <= titleBand) titles.add(text to box)  // name band
                if (!Chat.isChrome(box, text, win)) {
                    found.add(Chat.Bubble(text, Chat.isIncoming(box, win), box))
                    if (n.isLongClickable) longClickable.add(text)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        lastWindow = win
        lastTitles = titles
        lastDescs = descs
        lastCounts = "${root.packageName ?: "?"}：节点 $visited，带文字的 TextView $texts，过滤后 ${found.size}"
        return Chat.order(Chat.refine(found, longClickable))
    }

    private fun Chat.Box.toRect() = Rect(left, top, right, bottom)

    // ---- DebugServer.Host: served only to a caller holding the generated token ----

    override fun status(): String = onMain {
        """
        service      : ${if (Diag.connected) "connected" else "down"}
        enabled      : ${prefs.enabled}  debug=${prefs.debug}  learning=${prefs.learning}
        packages     : ${prefs.packages}
        api key      : ${if (prefs.apiKey.isBlank()) "MISSING" else "set (${prefs.apiKey.length} chars)"}
        foreground   : ${rootInActiveWindow?.packageName ?: "?"}
        last scan    : ${Diag.lastScan}
        last error   : ${Diag.lastError.ifBlank { "none" }}
        person       : ${Diag.person.ifBlank { "unknown" }}
        pending ep   : ${pending?.let { "${it.intent}|${it.action} danger=${"%.2f".format(it.danger)}" } ?: "none"}
        people known : ${people.index().size}
        routes       : /status /log /tree /windows /shot /last /learn /learn/reset /rescan
        """.trimIndent()
    }

    override fun tree(): String = onMain {
        val root = rootInActiveWindow ?: return@onMain "no active window"
        val sb = StringBuilder("window=${root.packageName} class=${root.className}\n")
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.'))
                .append(" id=").append(n.viewIdResourceName ?: "-")
                .append(" text=").append(n.text?.toString()?.take(40) ?: "-")
                .append(" desc=").append(n.contentDescription?.toString()?.take(24) ?: "-")
                .append(" lc=").append(if (n.isLongClickable) "1" else "0")
                .append(" [").append(r.left).append(',').append(r.top).append('-')
                .append(r.right).append(',').append(r.bottom).append("]\n")
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        sb.toString()
    }

    /**
     * Can we screenshot the foreground app at all? WeChat hides its node tree from third-party
     * services, so the only honest fallback is reading the pixels already on screen. FLAG_SECURE
     * would block this, and a blocked capture comes back as a single flat colour.
     */
    override fun screenshotProbe(): String {
        val done = CountDownLatch(1)
        var report = "timed out"
        capture { report = it; done.countDown() }
        done.await(8, TimeUnit.SECONDS)
        return "现在（前台 ${Diag.lastPackage}）：$report\n" +
            "自动探测：${Diag.shotProbe.ifBlank { "还没触发过，去微信聊天页停几秒" }}"
    }

    /**
     * WeChat is in the foreground exactly when we cannot also be reading a URL, so probe it
     * automatically whenever the tree comes back empty. Throttled: capture is expensive and
     * the platform rejects calls that come too close together.
     */
    private fun maybeProbe(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastProbe < PROBE_GAP_MS) return
        lastProbe = now
        capture { Diag.shotProbe = "$pkg → $it" }
    }

    private fun capture(onDone: (String) -> Unit) = captureBitmap { bmp, note ->
        if (bmp == null) onDone(note) else {
            val colors = HashSet<Int>()
            for (x in 0 until 20) for (y in 0 until 40) {
                colors.add(bmp.getPixel(x * bmp.width / 20, y * bmp.height / 40))
            }
            val size = "${bmp.width}x${bmp.height}"
            bmp.recycle()
            onDone(
                "ok $size，采样 800 点得到 ${colors.size} 种颜色 " +
                    if (colors.size <= 2) "→ 画面空白，被挡住了" else "→ 真实画面，OCR 可行"
            )
        }
    }

    private fun captureBitmap(onDone: (Bitmap?, String) -> Unit) = captureBitmap(onDone, tries = 2)

    /**
     * Samsung throws errorCode 3 (invalid display) and 5 (too soon) intermittently, so retry a
     * couple of times with a short backoff before giving up.
     */
    private fun captureBitmap(onDone: (Bitmap?, String) -> Unit, tries: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onDone(null, "needs Android 11+"); return }
        // The callback must not land on the main thread: a debug route blocks waiting for it.
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var bmp: Bitmap? = null
                var note = "ok"
                try {
                    bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bmp == null) note = "captured but unreadable"
                } catch (e: Exception) {
                    note = "capture error: ${e.message}"
                } finally {
                    runCatching { result.hardwareBuffer.close() }
                }
                onDone(bmp, note)
            }

            override fun onFailure(errorCode: Int) {
                if ((errorCode == 3 || errorCode == 5) && tries > 1) {
                    main.postDelayed({ captureBitmap(onDone, tries - 1) }, 350)
                } else {
                    onDone(null, "failed errorCode=$errorCode (5=间隔太短, 2=内部错误, 3=显示无效)")
                }
            }
        })
    }

    /** Every window the service can see, with how much of a tree each one actually exposes. */
    override fun windowList(): String = onMain {
        val sb = StringBuilder()
        runCatching {
            for (w in windows) {
                val r = w.root
                val rect = Rect().also { w.getBoundsInScreen(it) }
                var nodes = 0
                var texts = 0
                if (r != null) {
                    val stack = ArrayDeque<AccessibilityNodeInfo>()
                    stack.addLast(r)
                    while (stack.isNotEmpty() && nodes < 2000) {
                        val n = stack.removeLast()
                        nodes++
                        if (!n.text.isNullOrBlank()) texts++
                        for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
                    }
                }
                sb.append("id=").append(w.id)
                    .append(" type=").append(w.type)
                    .append(" active=").append(w.isActive)
                    .append(" focused=").append(w.isFocused)
                    .append(" pkg=").append(r?.packageName ?: "null-root")
                    .append(" nodes=").append(nodes)
                    .append(" withText=").append(texts)
                    .append(" [").append(rect.left).append(',').append(rect.top).append('-')
                    .append(rect.right).append(',').append(rect.bottom).append("]\n")
            }
        }.onFailure { sb.append("windows failed: ").append(it.message) }
        if (sb.isEmpty()) "no windows visible" else sb.toString()
    }

    override fun lastExchange(): String =
        "REQUEST\n${Diag.lastRequest}\n\nRESPONSE\n${Diag.lastResponse.ifBlank { "(not captured)" }}"

    override fun learner(): String = onMain {
        people.summary()
    }

    override fun resetLearner(): String = onMain {
        var n = 0
        for (id in people.index()) {
            val r = people.load(id.substringBefore('|'), id.substringAfter('|'))
            r.model = Learner.Model()
            r.history = emptyList()
            people.save(r)
            n++
        }
        pending = null
        Diag.log("learning reset for $n people via debug route")
        "reset learning for $n people (notes and style kept)"
    }

    override fun rescan(): String = onMain {
        lastKey = null
        busy = false
        main.removeCallbacks(scan)
        main.post(scan)
        "rescan queued"
    }

    /** Accessibility state is main-thread state; the debug routes run on their own pool. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block).getOrNull(); done.countDown() }
        done.await(3, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return result ?: ("timed out" as T)
    }

    companion object {
        private const val DEBOUNCE_MS = 450L
        private const val MAX_NODES = 3000
        private const val MAX_LEARN_PAGES = 60
        private const val PROBE_GAP_MS = 5000L

        private const val OCR_CAVEAT =
            "这段对话是从手机屏幕识别出来的文字，表情符号和表情包图片读不到，" +
            "所以看起来平淡的一句话，实际可能带着表情。缺的内容不要当成对方没有情绪。"

        /**
         * The Wi-Fi IPv4, for showing the troubleshooting URL in the app. Picking the first
         * non-loopback address is wrong on a phone: mobile data's 464XLAT interface answers
         * first with a 192.0.0.x address that no laptop can reach.
         */
        fun lanAddress(): String = runCatching {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList()
                    .filter { it.address.size == 4 && !it.isLoopbackAddress }
                    .map { i.name to (it.hostAddress ?: "") } }
            candidates.firstOrNull { it.first.startsWith("wlan") }?.second
                ?: candidates.firstOrNull { isPrivate(it.second) }?.second
                ?: candidates.firstOrNull()?.second
                ?: "?"
        }.getOrDefault("?")

        private fun isPrivate(ip: String): Boolean =
            ip.startsWith("192.168.") || ip.startsWith("10.") ||
                (ip.startsWith("172.") && (ip.split('.').getOrNull(1)?.toIntOrNull() ?: 0) in 16..31)
    }
}
