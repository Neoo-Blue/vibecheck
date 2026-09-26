package dev.vibecheck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.TimeZone
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

    /** Everything the deep model needs about the turn on the card. */
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

    /**
     * The latest judgment for one person: what the card shows when you come back to that chat,
     * without asking again. Deep reads and drafts asked about it are kept with it.
     */
    private class Verdict(
        val personId: String,
        val key: String,
        val title: String,
        val blocks: List<Jev.Block>,
        val more: List<Jev.Block>,
        val footer: String?,
        val badge: String?,
        val risk: Float,
        val ctx: Ctx,
        val sections: LinkedHashMap<String, OverlayCard.Section> = LinkedHashMap(),
    )

    private val main = Handler(Looper.getMainLooper())
    /** Jev judgments, one at a time. */
    private val judgeIo = Executors.newSingleThreadExecutor { Thread(it, "vibecheck-judge") }
    /** OpenRouter: deep reads, reply drafts, profiles. Seconds each, so never queued in front of a judgment. */
    private val deepIo = Executors.newFixedThreadPool(2) { Thread(it, "vibecheck-deep") }
    /** Screenshot callbacks: off the main thread (a debug route blocks on one) and never behind a network call. */
    private val shotIo = Executors.newSingleThreadExecutor { Thread(it, "vibecheck-shot") }

    private lateinit var prefs: Prefs
    private lateinit var card: OverlayCard
    private lateinit var people: PersonStore
    private var server: DebugServer? = null
    /** The watched packages, re-read when the setting changes rather than split apart on every event. */
    private var watched: Set<String> = emptySet()
    /** SharedPreferences keeps listeners weakly, so this field is what keeps it alive. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPrefChanged(key) }

    private var targetPkg = ""
    private var lastWindow = Chat.Box(0, 0, 0, 0)
    private var lastCounts = ""
    private var lastTitles = listOf<Pair<String, Chat.Box>>()
    private var lastDescs = listOf<String>()
    private var lastTitleHash = ""
    private var viaOcr = false
    private var onHomeScreen = false
    private var hasInput = false
    private var lastProbe = 0L
    private var ocrBusy = false
    /** Bumped on every screen change in the watched app; a capture of an older screen is dropped. */
    private var screenGen = 0

    /** Whose chat is on screen as of the last scan; null on anything that is not a conversation. */
    private var activeId: String? = null
    private var currentRecord: PersonStore.Record? = null
    /** A screen change happened and no scan has looked at the new screen yet. */
    private var unsettled = false
    /** Whose chat the card belongs to, and what it holds: a Verdict, or a status line. */
    private var cardFor: String? = null
    private var cardShows: Any? = null
    private val verdicts = object : LinkedHashMap<String, Verdict>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict>?) = size > MAX_VERDICTS
    }
    /** "personId|key" of the judgment in flight. */
    private var inFlight: String? = null
    private var forceJudge = false
    private var failStreak = 0
    private var retryAt = 0L
    private var lastFailure = ""
    private var lastBuzz: String? = null

    private var pending: Learner.Episode? = null
    private var pendingText = ""
    private var pendingId = ""

    /** "personId|key|kind" of OpenRouter calls in flight, and the ones the user is waiting to see. */
    private val deepInFlight = HashSet<String>()
    private val deepWanted = HashSet<String>()

    // 学习此人: scroll the chat toward older messages, reading each screen, then write a bio.
    private var learning = false
    private var learnId = ""
    private var learnPkg = ""
    private val learnHistory = ArrayList<Pair<String, String>>()   // oldest first
    private var learnScrolls = 0
    private var learnStale = 0

    private val scan = Runnable { scanNow() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        prefs.lang   // applies the UI language to L
        people = PersonStore(this)
        card = OverlayCard(this, this, prefs) { imeBox()?.top }
        watched = prefs.packageList.toSet()
        lastCounts = L.t("尚未扫描", "not scanned yet")
        prefs.listen(prefListener)
        // No packageNames filter: with one, no event ever arrives from another app, so the card
        // never learns the chat is gone and stays pinned over whatever you open next.
        serviceInfo = serviceInfo.apply { packageNames = null }
        Diag.connected = true
        Diag.log("connected, watching ${prefs.packages}")
        syncDebugRoutes()
    }

    /** Settings apply at once, without restarting the accessibility service. */
    private fun onPrefChanged(key: String?) {
        when (key) {
            "pkgs", null -> watched = prefs.packageList.toSet()
            "diag" -> syncDebugRoutes()
            // A new key, or a successful Test in settings: whatever failed may work now.
            "key", "orkey", "judge", "kick" -> { failStreak = 0; retryAt = 0L; lastFailure = ""; rescanSoon() }
            "on", "snooze", "debug", "ocr", "passive" -> rescanSoon()
            "lang" -> { prefs.lang; card.refresh() }
            "cardsize", "hiddenbuttons" -> card.refresh()
        }
    }

    private fun syncDebugRoutes() {
        if (prefs.remoteDiag && server == null) {
            // The token is read on every request, so rotating it in settings locks the old URL out at once.
            val s = DebugServer({ prefs.diagToken }, this)
            server = s
            deepIo.execute { if (!s.start()) main.post { if (server === s) server = null } }
        } else if (!prefs.remoteDiag && server != null) {
            server?.stop()
            server = null
            Diag.log("debug routes off")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // Showing the card is itself a window change: reacting to our own events hid the card
        // a moment after drawing it, which looked like a flash. Our settings screen coming up
        // is a real switch, though.
        if (pkg == packageName) {
            if (stateChange && event.className?.toString() == MainActivity::class.java.name) {
                main.removeCallbacks(foreignCheck)
                main.postDelayed(foreignCheck, 150)
            }
            return
        }
        Diag.events++
        Diag.lastPackage = pkg
        if (pkg !in watched) {
            // Only a real foreground switch takes the card down, and a window change from another
            // package is not always one: the keyboard, a heads-up notification and the volume panel
            // report them too, and hiding the card for those closed it mid-read. Look first.
            if (stateChange) {
                main.removeCallbacks(foreignCheck)
                main.postDelayed(foreignCheck, 150)
            }
            return
        }
        targetPkg = pkg
        if (stateChange) {
            // Navigating inside the app (chat -> list -> another chat) must not leave the old card
            // over the new screen; the next scan decides what belongs here, and brings the card back
            // as it was when it is the same chat after all. A history read on the chat list would
            // store names and previews as messages, so it ends here too.
            screenGen++
            unsettled = true
            forceJudge = false
            main.removeCallbacks(foreignCheck)
            main.post { abortLearning("page changed"); card.suspend() }
        }
        main.removeCallbacks(scan)
        main.postDelayed(scan, DEBOUNCE_MS)   // the list settles after a new message arrives
    }

    private val foreignCheck = Runnable { if (!chatOnScreen()) leaveChat("left the chat") }

    /**
     * Is a watched app still what the user is looking at? The top window by layer decides, not
     * counting the keyboard, our own overlay, or small panels (heads-up notifications, the volume
     * slider, a picture-in-picture video) that float over the chat without replacing it.
     */
    private fun chatOnScreen(): Boolean = runCatching {
        val dm = resources.displayMetrics
        val screen = dm.widthPixels.toLong() * dm.heightPixels
        val top = windows.sortedByDescending { it.layer }.firstOrNull { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                w.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                w.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
            ) return@firstOrNull false
            val r = Rect().also { w.getBoundsInScreen(it) }
            r.width().toLong() * r.height() > screen * 0.4 || w.root?.packageName?.toString() in watched
        } ?: return@runCatching false
        top.type == AccessibilityWindowInfo.TYPE_APPLICATION && top.root?.packageName?.toString() in watched
    }.getOrDefault(false)

    /** Out of any conversation: nothing on screen, and the next chat opened starts fresh. */
    private fun leaveChat(why: String) {
        screenGen++
        activeId = null
        forceJudge = false
        abortLearning(why)
        card.hide()
    }

    override fun onInterrupt() { abortLearning("service interrupted") }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::card.isInitialized) card.refresh()   // rotated, or dark mode switched
    }

    override fun onDestroy() {
        Diag.connected = false
        learning = false
        main.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        if (::card.isInitialized) card.hide()
        server?.stop()
        judgeIo.shutdownNow()
        deepIo.shutdownNow()
        shotIo.shutdownNow()
        super.onDestroy()
    }

    // ---- windows ----

    /** The keyboard's own window: cut out of full-screen captures, and the card is kept above it. */
    private fun imeBox(): Chat.Box? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            Chat.Box(r.left, r.top, r.right, r.bottom)
        }
    }.getOrNull()

    /**
     * The chat's own window and its root: the largest window of that app, so a popup menu or a
     * dialog of the same app is not mistaken for the conversation. rootInActiveWindow is whatever
     * holds input focus, which can be the keyboard or our own overlay.
     */
    private fun chatWindow(pkg: String): Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>? = runCatching {
        var best: Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>? = null
        var bestArea = -1L
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() != pkg || r.childCount == 0) continue
            val b = Rect().also { w.getBoundsInScreen(it) }
            val area = b.width().toLong() * b.height()
            if (area > bestArea) { best = w to r; bestArea = area }
        }
        best
    }.getOrNull()

    private fun rootFor(pkg: String): AccessibilityNodeInfo? =
        chatWindow(pkg)?.second ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    // ---- reading the screen ----

    private fun scanNow() {
        if (learning) return
        hasInput = false
        onHomeScreen = false
        val root = rootFor(targetPkg)
        val bubbles = if (root == null) emptyList() else try { collect(root) } catch (e: Exception) {
            Diag.lastError = "collect: ${e.message}"; Diag.log(Diag.lastError); emptyList()
        }
        // WeChat 8.0.52+ hands third-party services an empty tree. When that happens, fall back
        // to reading the pixels that are already on screen.
        if (bubbles.isEmpty() && prefs.ocr) { ocrScan(); return }
        viaOcr = false
        handle(bubbles)
    }

    private val ocrWatchdog = Runnable { ocrBusy = false }

    private fun ocrDone() {
        ocrBusy = false
        main.removeCallbacks(ocrWatchdog)
    }

    /** Capture the chat, recognize the text in it, and carry on with the normal pipeline. */
    private fun ocrScan() {
        val now = System.currentTimeMillis()
        val wait = PROBE_GAP_MS - (now - lastProbe)
        if (ocrBusy || wait > 0) {
            // Throttled, not dropped: a message that lands just after a capture must still be read
            // once the gap has passed, even when nothing else on screen changes after it.
            rescanSoon(if (ocrBusy) 700 else wait + 50)
            return
        }
        lastProbe = now
        ocrBusy = true
        main.postDelayed(ocrWatchdog, 15_000)   // a capture or OCR that never calls back
        val gen = screenGen
        captureChat(hideOverlay = false) { shot, note ->
            if (shot == null) {
                main.post {
                    ocrDone()
                    Diag.shotProbe = "$targetPkg → $note"
                    if (gen == screenGen) handle(emptyList())
                }
                return@captureChat
            }
            val win = shot.box
            Ocr.read(shot.bmp) { items ->   // main thread
                ocrDone()
                // Recognized in bitmap pixels; everything else works in screen coordinates.
                val placed = items.map { (text, b) ->
                    text to Chat.Box(b.left + win.left, b.top + win.top, b.right + win.left, b.bottom + win.top)
                }
                // Everything on screen that is not the conversation: our own card, and the keyboard,
                // whose keys were otherwise read as a stream of one-letter messages. A capture of the
                // chat's own window has neither in it, and cutting the card's area out of it would
                // throw away real messages that the card merely covers.
                val exclude = if (shot.windowOnly) emptyList() else listOfNotNull(card.bounds(), imeBox())
                val (found, titles) = Chat.fromOcr(placed, win, exclude)
                val hash = Person.hashOf(fingerprint(shot.bmp, win, found))
                shot.bmp.recycle()
                if (gen != screenGen) { rescanSoon(); return@read }   // the screen changed under the capture
                onHomeScreen = Chat.looksLikeHomeScreen(placed, win)
                hasInput = false
                lastTitleHash = hash
                lastWindow = win
                lastTitles = titles
                lastDescs = emptyList()
                lastCounts = L.t("OCR：识别 ${items.size} 块，过滤后 ${found.size}", "OCR: ${items.size} blocks, ${found.size} after filtering")
                viaOcr = true
                handle(found)
            }
        }
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
        val texts = ArrayList<Pair<String, Chat.Box>>()
        var input = false
        val h = win.bottom - win.top
        val statusBar = win.top + h * 0.035
        val titleBand = win.top + h * 0.07

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            n.contentDescription?.toString()?.let { if (it.endsWith("头像")) descs.add(it) }
            if (!input && n.isEditable) {
                // A text field along the bottom is a reply box; chat lists keep their search at the top.
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.top > win.top + h * 0.6) input = true
            }
            val text = n.text?.toString()?.trim().orEmpty()
            val cls = n.className?.toString().orEmpty()
            if (text.isNotEmpty() && cls.endsWith("TextView")) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val box = Chat.Box(r.left, r.top, r.right, r.bottom)
                texts.add(text to box)
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
        hasInput = input
        onHomeScreen = Chat.looksLikeHomeScreen(texts, win)
        lastCounts = L.t(
            "${root.packageName ?: "?"}：节点 $visited，带文字的 TextView ${texts.size}，过滤后 ${found.size}",
            "${root.packageName ?: "?"}: $visited nodes, ${texts.size} text views, ${found.size} after filtering",
        )
        return Chat.order(Chat.refine(found, longClickable))
    }

    // ---- deciding what the card says ----

    private fun handle(bubbles: List<Chat.Bubble>) {
        unsettled = false
        Diag.lastScan = lastCounts + L.t("；最后一条：", "; newest: ") +
            (bubbles.lastOrNull()?.let { (if (it.incoming) L.t("对方 ", "them ") else L.t("我 ", "me ")) + it.text.take(30) } ?: L.t("无", "none"))

        if (prefs.debug) { debugCard(bubbles); return }
        if (bubbles.isEmpty()) {
            // Nothing read at all (a failed capture, a frame caught mid-animation) says nothing
            // about where we are: put the card away for now, but this is not leaving the chat,
            // or the next screen would count as freshly opened and its history as new messages.
            card.suspend()
            return
        }
        // The chat list is also full of names and text; judging it would be nonsense.
        if (onHomeScreen || !Chat.inConversation(bubbles, hasInput)) {
            activeId = null
            card.hide()
            return
        }

        // Who am I talking to? Everything below is scoped to that person.
        // Emoji-only names are invisible to OCR, so fall back to the title-bar fingerprint
        // rather than lumping every unreadable chat together under one "unknown" record.
        val name = Person.peerName(lastTitles, lastDescs, lastWindow)
            ?: lastTitleHash.takeIf { it.isNotBlank() }?.let { people.resolveFingerprint(targetPkg, it) }
            ?: UNKNOWN
        val record = people.load(targetPkg, name)
        val id = record.id
        val entering = activeId != id
        activeId = id
        currentRecord = record
        val anchor = bubbles.last().box.toRect()

        if (cardFor != id) {
            // Another chat is on screen: nothing from the previous one may stay on the card, and
            // Think or Reply must not answer about the wrong person.
            card.reset()
            cardFor = id
            cardShows = null
        }

        // Paused for this person: nothing is sent and nothing is counted.
        if (record.muted) {
            if (prefs.enabled) card.paused(anchor, L.t("这个聊天已暂停解读", "Paused for this chat")) else card.hide()
            return
        }

        // Passive learning. This runs whether or not a card is drawn and whether or not we are
        // willing to spend an API call: the messages are already on screen, reading them is free.
        // An unreadable name shares one record between people, so nothing is learned into it.
        if (prefs.passive && name != UNKNOWN) observePassively(record, bubbles, entering)
        Diag.person = "${record.name} (${record.stats.theirMsgs + record.stats.myMsgs} seen, ${record.model.updates} updates)"

        // Judging costs money and there is nowhere to show it, so stop here when judging is off.
        if (!prefs.enabled) { card.hide(); return }
        if (prefs.snoozed) {
            val left = ((prefs.snoozeUntil - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
            card.paused(anchor, L.t("已暂停，$left 分钟后恢复", "Paused, back in $left min"))
            rescanSoon(prefs.snoozeUntil - System.currentTimeMillis() + 500)
            return
        }
        card.unpause()

        // Normally judge only when the other person spoke last. But if the user opened the card
        // themselves, judge the current screen whoever spoke last, or it hangs on "Thinking".
        val key = Chat.triggerKey(bubbles) ?: (if (forceJudge) Chat.anyKey(bubbles) else null)
        val known = verdicts[id]
        val title = cardTitle(record, null)
        when {
            key != null && inFlight == "$id|$key" -> status(L.t("思考中…", "Thinking…"), anchor, "…", title)
            known != null && known !== cardShows && (key == null || known.key == key || inFlight != null) -> present(known, anchor)
        }
        card.idle(anchor)
        if (key == null || inFlight == "$id|$key") return
        forceJudge = false
        if (known?.key == key) return
        if (inFlight != null) { rescanSoon(1500); return }   // judge it after the current call
        val now = System.currentTimeMillis()
        if (now < retryAt) {
            status(L.t("Jev 调用失败：", "Jev call failed: ") + lastFailure, anchor, "!", title)
            rescanSoon(retryAt - now + 50)
            return
        }
        Judge.missingKey(prefs)?.let { status(it, anchor, "!", title); return }
        judge(record, bubbles, key, anchor)
    }

    private fun present(v: Verdict, anchor: Rect?) {
        if (cardShows === v) return
        cardShows = v
        card.show(v.title, v.blocks, v.footer, anchor, v.more, v.badge, v.risk, v.sections)
    }

    /** One line in place of a judgment. Drawn only when it changes, not on every scan. */
    private fun status(text: String, anchor: Rect?, badge: String?, title: String? = null) {
        val tag = "status:$badge:$text"
        if (cardShows == tag) return
        cardShows = tag
        if (title == null) card.showText(text, anchor, badge) else card.showText(text, anchor, badge, title)
    }

    private fun judge(record: PersonStore.Record, bubbles: List<Chat.Bubble>, key: String, anchor: Rect) {
        val id = record.id
        inFlight = "$id|$key"
        status(L.t("思考中…", "Thinking…"), anchor, "…", cardTitle(record, null))

        val lastIncoming = (bubbles.lastOrNull { it.incoming } ?: bubbles.last()).text
        val transcript = Chat.transcript(bubbles)
        val background = people.background(record, prefs.context)
        val style = Person.styleSummary(record.style)
        val history = Person.historySummary(record.history)
        val relation = Relation.summary(record.stats)
        val ocr = viaOcr
        val state = Jev.stateJson(
            context = background,
            transcript = transcript,
            peer = record.name.takeUnless { it == UNKNOWN || Person.isFingerprint(it) },
            style = style,
            history = history,
            relation = relation,
            source = if (ocr) OCR_CAVEAT else null,
        )
        // This record copy is not touched again (the answer is applied to a fresh load), so the
        // judge thread may read its model.
        val model = if (prefs.learning) record.model else Learner.Model()
        Diag.lastRequest = Jev.triageBody(state)
        Diag.log("ask: ${transcript.size} lines of context")
        val started = System.currentTimeMillis()

        // Two stages. Triage decides whether this turn deserves anything; only then is the
        // situation-specific question set asked, so the card's headers change with the situation
        // instead of every chat getting the same five blocks.
        judgeIo.execute {
            prefs.countUse(Prefs.USE_JUDGE)
            val triage = runCatching { Judge.ask(prefs, Jev.triageBody(state)) }
            // Routed on the danger this person's history calibrates to, not the raw reading.
            val routine = triage.map { Jev.isRoutine(calibrated(it, model)) }.getOrDefault(false)
            // No situation answer at all still gets the default set, not a two-block card.
            val situation = triage.getOrNull()?.let { (it["situation"] as? Jev.Answer.Dist)?.top } ?: ""
            val merged = triage.mapCatching { t ->
                if (routine) t else {
                    prefs.countUse(Prefs.USE_JUDGE)
                    t + Judge.ask(prefs, Jev.detailBody(state, situation))
                }
            }
            main.post {
                judged(id, key, merged, routine, situation, bubbles, lastIncoming, transcript, background, ocr, started)
            }
        }
    }

    /** The same calibration the card will show (Learner.danger), applied before routing. */
    private fun calibrated(a: Map<String, Jev.Answer>, model: Learner.Model): Map<String, Jev.Answer> {
        val d = a["danger"] as? Jev.Answer.Scored ?: return a
        val span = (d.levels - 1).coerceAtLeast(1)
        return a + ("danger" to Jev.Answer.Scored(Learner.danger(model, d.score / span) * span, d.levels))
    }

    private fun judged(
        id: String,
        key: String,
        result: Result<Map<String, Jev.Answer>>,
        routine: Boolean,
        situation: String,
        bubbles: List<Chat.Bubble>,
        lastIncoming: String,
        transcript: List<Pair<String, String>>,
        background: String,
        ocr: Boolean,
        started: Long,
    ) {
        inFlight = null
        // Shown only if the user is still in that chat and has not paused it meanwhile; kept either
        // way, so coming back shows it instead of paying for the same question twice.
        val visible = id == activeId && !unsettled && !prefs.snoozed && !people.isMuted(id)
        result.onSuccess { raw ->
            failStreak = 0
            retryAt = 0L
            lastFailure = ""
            Diag.log("ok in ${System.currentTimeMillis() - started}ms" +
                (if (routine) " (routine, one step)" else " (two steps, situation=$situation)"))
            // Reloaded rather than reused: passive counting may have saved newer counts for this
            // person while the call was out, and saving the copy from before would undo them.
            val record = people.loadId(id)
            val learned = applyLearning(record, raw, bubbles, lastIncoming)
            val ctx = Ctx(
                record.name, background,
                Person.styleSummary(record.style), Person.historySummary(record.history),
                Relation.summary(record.stats), transcript, learned.answers, ocr, situation,
            )
            val danger = learned.answers["danger"] as? Jev.Answer.Scored
            val risk = (Jev.risk(learned.answers) ?: 0.0).toFloat()
            val blocks = (if (routine) Jev.routineCard(learned.answers) else Jev.card(learned.answers, situation = situation))
                .ifEmpty { listOf(Jev.Block("", listOf(L.t("没什么要提醒的", "Nothing to flag here")))) }
            val v = Verdict(
                id, key, cardTitle(record, situation), blocks,
                if (routine) emptyList() else Jev.more(learned.answers),
                if (routine) null else footerWith(learned),
                if (routine) "·" else danger?.let { "${Jev.shownLevel(it)}" },
                risk, ctx,
            )
            verdicts[id] = v
            if (visible) {
                present(v, null)
                if (prefs.buzz && risk >= 0.75f && lastBuzz != "$id|$key") { lastBuzz = "$id|$key"; buzz() }
                // Only for a chat still on screen: nobody reads a deep pass about a chat they left.
                if (!routine && prefs.autoDeep && prefs.orKey.isNotBlank()) deepCall(DEEP, v, openCard = false)
            }
        }.onFailure { e ->
            failStreak++
            lastFailure = Judge.describe(e)
            val now = System.currentTimeMillis()
            // A rejected key or an empty balance does not fix itself within seconds: wait long,
            // not forever. A new key, a successful Test in settings or "Try again" retries at once.
            retryAt = now + if (Judge.isFatal(e)) FATAL_BACKOFF_MS else Judge.backoffMs(failStreak)
            Diag.lastError = "ask: ${e.message}"
            Diag.log(Diag.lastError)
            if (visible) status(L.t("Jev 调用失败：", "Jev call failed: ") + lastFailure, null, "!")
            rescanSoon(retryAt - now + 50)
        }
    }

    private fun cardTitle(r: PersonStore.Record, situation: String?): String =
        listOfNotNull(displayName(r.name), situation?.takeIf { it.isNotBlank() }?.let { L.label(it) }).joinToString(" · ")

    private fun displayName(name: String): String = when {
        name == UNKNOWN -> L.t("认不出是谁", "Unknown contact")
        Person.isFingerprint(name) -> L.t("未命名联系人", "Unnamed contact")
        else -> name
    }

    private fun debugCard(bubbles: List<Chat.Bubble>) {
        // Always draw in debug mode, even with nothing left after filtering: a blank screen
        // cannot tell you whether the service is dead, blocked, or just over-filtering.
        val lines = if (bubbles.isEmpty()) listOf(lastCounts, L.t("窗口 ", "window ") +
            "${lastWindow.left},${lastWindow.top}-${lastWindow.right},${lastWindow.bottom}")
        else bubbles.takeLast(4).map { (if (it.incoming) L.t("对方: ", "them: ") else L.t("我: ", "me: ")) + it.text.take(24) }
        val who = Person.peerName(lastTitles, lastDescs, lastWindow) ?: L.t("认不出是谁", "unknown contact")
        val where = if (Chat.inConversation(bubbles, hasInput)) L.t("聊天页", "a chat") else L.t("不是聊天页", "not a chat")
        cardFor = null
        cardShows = null
        card.show(
            L.t("调试", "Debug"),
            listOf(Jev.Block(L.t("$who，$where，抓到 ${bubbles.size} 条", "$who, $where, ${bubbles.size} captured"), lines)),
            null, bubbles.lastOrNull()?.box?.toRect(),
        )
    }

    private fun buzz() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun rescanSoon(delay: Long = 0) {
        main.removeCallbacks(scan)
        main.postDelayed(scan, delay.coerceIn(0, 60 * 60_000L))
    }

    // ---- learning ----

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
        // Off, or a shared "unknown" record: an outstanding episode would only be settled later
        // against something it has nothing to do with.
        if (!prefs.learning || record.name == UNKNOWN) {
            pending = null
            return Learned(raw, false, null)
        }

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
            pending = Learner.Episode(situation, intent, top, calibrated, rawNorm)
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

    private fun footerWith(l: Learned): String? {
        val base = Jev.footer(l.answers)
        val note = if (l.adjusted) L.t("（已按你们过去的走向调整）", "(adjusted from how things went before)") else null
        return listOfNotNull(base, note).joinToString("\n").ifBlank { null }
    }

    /**
     * Fold everything new on screen into this person's memory: how I write to them, and how the
     * relationship actually behaves (who opens, who writes more, how fast I answer).
     * Each message is counted once: the screen is lined up against the newest lines already
     * counted (Chat.sync), so scrolling through history does not count it again.
     */
    private fun observePassively(record: PersonStore.Record, bubbles: List<Chat.Bubble>, entering: Boolean) {
        if (bubbles.isEmpty()) return
        val page = bubbles.map { (if (it.incoming) "对方" else "我") to it.text }
        val sync = Chat.sync(record.tail, page, reentry = entering, legacyLast = record.legacySeen)
        val changed = sync.tail != record.tail || record.legacySeen.isNotEmpty()
        record.tail = sync.tail
        record.legacySeen = ""
        if (sync.fresh.isNotEmpty()) {
            val fresh = bubbles.takeLast(sync.fresh.size)
            fresh.filter { !it.incoming }.forEach { Person.observe(record.style, it.text) }
            val now = System.currentTimeMillis()
            Relation.observe(record.stats, fresh, now, live = sync.aligned, tzOffsetMs = TimeZone.getDefault().getOffset(now).toLong())
            Diag.log("passive: +${fresh.size} → ${record.name}")
        }
        if (sync.fresh.isNotEmpty() || changed) people.save(record)
    }

    // ---- OverlayCard.Actions: the buttons on the card ----

    override fun onDeepThink() = deepCall(DEEP, cardShows as? Verdict, openCard = true)

    override fun onSuggestReplies() = deepCall(REPLY, cardShows as? Verdict, openCard = true)

    override fun onOpened() {
        forceJudge = true           // judge even if the newest message is my own
        rescanSoon()
    }

    override fun onRescan() {
        activeId?.let { verdicts.remove(it) }
        cardShows = null
        forceJudge = true
        failStreak = 0
        retryAt = 0L
        rescanSoon()
    }

    override fun onSnooze() {
        prefs.snoozeUntil = System.currentTimeMillis() + SNOOZE_MS   // the settings listener rescans
        Diag.log("paused for an hour")
    }

    override fun onPauseChat() {
        val id = activeId ?: return
        people.setMuted(id, true)
        Diag.log("paused for ${people.nameOf(id)}")
        rescanSoon()
    }

    override fun onUnpause() {
        if (prefs.snoozed) prefs.snoozeUntil = 0L
        activeId?.let { if (people.isMuted(it)) people.setMuted(it, false) }
        rescanSoon()
    }

    override fun onSettings() {
        leaveChat("opened settings")
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onPick(text: String) {
        copy(text)
        card.flash(
            if (insertIntoInput(text)) L.t("已填进输入框，也复制了一份", "Put in the reply box (also copied)")
            else L.t("已复制，长按输入框粘贴", "Copied; long-press the reply box to paste")
        )
    }

    override fun onCopy(text: String) {
        copy(text)
        card.flash(L.t("已复制", "Copied"))
    }

    private fun copy(text: String) {
        runCatching { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("vibecheck", text)) }
    }

    /**
     * Puts a draft into the chat's reply box, as pasting would. Only into an empty box, so nothing
     * already typed is replaced, and it is never sent: that stays the user's call.
     */
    private fun insertIntoInput(text: String): Boolean {
        val input = rootFor(targetPkg)?.let { findInput(it) } ?: return false
        val typed = if (input.isShowingHintText) "" else input.text?.toString().orEmpty()
        if (typed.isNotBlank()) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return runCatching { input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    /** The reply box: the focused text field, or else the lowest one on screen. */
    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (n.isEditable) {
                if (n.isFocused) return n
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.bottom > bestBottom) { best = n; bestBottom = r.bottom }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /**
     * On-demand call to the big model about the verdict on the card. Deep read and reply drafts
     * are separate panels, so an automatic deep read landing later no longer wipes the drafts
     * you were choosing from. When the transcript came from OCR a fresh screenshot goes along,
     * so the emoji and stickers that OCR cannot read are still part of the picture.
     */
    private fun deepCall(kind: String, v: Verdict?, openCard: Boolean) {
        val title = if (kind == REPLY) L.t("可以这样回", "You could say") else L.t("深度分析", "Deep analysis")
        if (v == null) {
            if (openCard) card.setSection(kind, OverlayCard.Section(title, listOf(L.t("还没有可分析的对话", "Nothing judged yet"))), true)
            return
        }
        if (prefs.orKey.isBlank()) {
            if (openCard) card.setSection(kind, OverlayCard.Section(title, listOf(L.t("还没填 OpenRouter Key，去设置里填", "No OpenRouter key yet; add one in settings"))), true)
            return
        }
        val tag = "${v.personId}|${v.key}|$kind"
        val waiting = OverlayCard.Section(title, listOf(L.t("思考中…", "Thinking…")))
        if (tag in deepInFlight) {
            // Already on its way (the automatic pass): open it the moment it lands.
            if (openCard) { deepWanted += tag; card.setSection(kind, waiting, true) }
            return
        }
        deepInFlight += tag
        if (openCard) {
            deepWanted += tag
            v.sections[kind] = waiting
            card.setSection(kind, waiting, true)
        }
        prefs.countUse(if (kind == REPLY) Prefs.USE_REPLY else Prefs.USE_DEEP)
        val c = v.ctx
        val system = if (kind == REPLY) OpenRouter.REPLY_SYSTEM else OpenRouter.DEEP_SYSTEM
        val send = { image: String? ->
            val model = if (image != null) prefs.visionModel else prefs.deepModel
            val prompt = OpenRouter.deepPrompt(
                displayName(c.name), c.note, c.style, c.history, c.relation,
                c.transcript, c.answers, c.viaOcr, image != null, c.situation,
            )
            deepIo.execute {
                val started = System.currentTimeMillis()
                val r = runCatching { OpenRouter.chat(prefs.orKey, model, system, prompt, image) }
                r.onSuccess { Diag.log("deep($model) ok in ${System.currentTimeMillis() - started}ms") }
                    .onFailure { Diag.lastError = "deep: ${it.message}"; Diag.log(Diag.lastError) }
                main.post { deepDone(kind, tag, v, title, r) }
            }
        }
        // A picture of a different chat would contradict the transcript, so only while still in this one.
        if (c.viaOcr && activeId == v.personId) captureChat(hideOverlay = true) { shot, note ->
            send(shot?.let { val s = toJpegBase64(it.bmp); it.bmp.recycle(); s }.also { if (it == null) Diag.log("deep: no image, $note") })
        } else send(null)
    }

    private fun deepDone(kind: String, tag: String, v: Verdict, title: String, r: Result<String>) {
        deepInFlight -= tag
        val wanted = deepWanted.remove(tag)
        val section = r.fold(
            { text ->
                if (kind == REPLY) OverlayCard.Section(title, OpenRouter.drafts(text).ifEmpty { listOf(text.trim()) }, pickable = true)
                else OverlayCard.Section(title, text.lines().map { it.trim() }.filter { it.isNotEmpty() })
            },
            { e -> OverlayCard.Section(title, listOf(L.t("失败：", "Failed: ") + Judge.describe(e))) },
        )
        v.sections[kind] = section
        // Bound to the turn it was asked about: an answer that lands after a new message must not
        // be pinned onto the wrong card. And it only pops open while that chat is on screen; after
        // leaving it waits on that chat's card for the way back.
        if (cardShows === v) card.setSection(kind, section, openCard = wanted && activeId == v.personId && !unsettled)
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

    // ---- 学习此人: the history read ----

    override fun onLearn() {
        val title = L.t("学习此人", "Learn this person")
        val rec = currentRecord?.takeIf { it.id == activeId }
        if (rec == null) {
            card.setSection(LEARN, OverlayCard.Section(title, listOf(L.t("先在一个聊天页里打开卡片", "Open the card inside a chat first"))), true)
            return
        }
        if (rec.name == UNKNOWN) {
            card.setSection(LEARN, OverlayCard.Section(title, listOf(L.t("认不出这是谁，没地方存档案", "Can't tell who this is, so there is nowhere to keep a profile"))), true)
            return
        }
        if (learning) return
        learning = true
        learnId = rec.id
        learnPkg = targetPkg
        learnHistory.clear()
        learnScrolls = 0
        learnStale = 0
        Diag.log("learn: reading ${rec.name}'s history")
        card.learning(0)
        learnStep()
    }

    /** Tapping the bubble during a read stops it and writes the profile from what was read so far. */
    override fun onStopLearning() {
        if (!learning) return
        Diag.log("learn: stopped by the user")
        finishLearning()
    }

    /** A capture that never calls back (screenshot or OCR hung) must not leave learn mode on forever. */
    private val learnWatchdog = Runnable { abortLearning("stuck") }
    private val learnNext = Runnable { learnStep() }

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
        if (!onScreen(learnPkg)) { abortLearning("left the chat"); return }
        main.removeCallbacks(learnWatchdog)
        main.postDelayed(learnWatchdog, 12_000)
        captureBubbles { bubbles ->
            if (!learning) return@captureBubbles
            // Within a page bubbles run oldest→newest; a page reached by scrolling up is older
            // than everything already kept, so what is not in the overlap goes to the front.
            val page = bubbles.map { (if (it.incoming) "对方" else "我") to it.text }
            val fresh = Chat.freshLines(learnHistory, page)
            learnHistory.addAll(0, fresh)
            if (fresh.isEmpty()) learnStale++ else learnStale = 0
            learnScrolls++
            card.learning(learnHistory.size)
            if (learnStale >= 4 || learnScrolls >= MAX_LEARN_PAGES) { finishLearning(); return@captureBubbles }
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            // Drag downward: the list follows the finger, revealing older messages at the top.
            // The next step is on a timer, not the gesture callback: a callback that never comes
            // must not leave learn mode stuck on and every judgment switched off.
            swipe(w / 2, h * 0.32f, w / 2, h * 0.78f)
            main.removeCallbacks(learnNext)
            main.postDelayed(learnNext, 1800)
        }
    }

    private fun abortLearning(why: String) {
        if (!learning) return
        learning = false
        main.removeCallbacks(learnWatchdog)
        main.removeCallbacks(learnNext)
        Diag.log("learn: aborted ($why), ${learnHistory.size} read")
        card.learning(null)
    }

    private fun finishLearning() {
        learning = false
        main.removeCallbacks(learnWatchdog)
        main.removeCallbacks(learnNext)
        card.learning(null)
        val title = L.t("学习此人", "Learn this person")
        val history = ArrayList(learnHistory)
        Diag.log("learn: read ${history.size}")
        if (history.size < 4) {
            learnResult(title, listOf(L.t("只读到 ${history.size} 条，不够写档案", "Only ${history.size} messages read, not enough for a profile")))
            return
        }
        // Reloaded: the record may have moved on since the read began.
        val rec = people.loadId(learnId)
        // A full read is the better baseline than what was seen live, so it replaces the counts
        // and my style rather than adding on top. A read that stopped short (sticker stretch,
        // page cap, stopped by hand) and covers less than the live count is kept for the bio only.
        val stats = rec.stats
        if (history.size >= stats.theirMsgs + stats.myMsgs) {
            stats.theirMsgs = 0; stats.myMsgs = 0; stats.theirChars = 0; stats.myChars = 0
            Relation.observeBulk(stats, history)
            val style = Person.Style()
            history.filter { it.first == "我" }.forEach { Person.observe(style, it.second) }
            rec.style = style
        }
        // The read began at the newest screen: live counting carries on from there, not from the
        // top of the history the read ended on.
        if (rec.tail.isEmpty()) rec.tail = history.takeLast(Chat.TAIL)
        rec.learned = history.size
        people.save(rec)

        if (prefs.orKey.isBlank()) {
            learnResult(title, listOf(
                L.t("读了 ${history.size} 条，统计已更新", "Read ${history.size} messages, statistics updated"),
                L.t("填了 OpenRouter Key 才能写档案", "Add an OpenRouter key to get a profile")))
            return
        }
        learnResult(title, listOf(L.t("读了 ${history.size} 条，正在写档案…", "Read ${history.size} messages, writing the profile…")))
        val prompt = OpenRouter.bioPrompt(rec.name, history)
        prefs.countUse(Prefs.USE_BIO)
        val id = rec.id
        deepIo.execute {
            val r = runCatching { OpenRouter.chat(prefs.orKey, prefs.deepModel, OpenRouter.BIO_SYSTEM, prompt, null) }
            main.post {
                r.onSuccess { bio ->
                    // Saved onto a fresh copy: a scan during the call may have moved the counts on.
                    val fresh = people.loadId(id)
                    fresh.bio = bio.trim()
                    people.save(fresh)
                    Diag.log("learn: profile written for ${fresh.name}")
                    learnResult(L.t("已学会 ${fresh.name}（${history.size} 条）", "Learned ${fresh.name} (${history.size} messages)"),
                        bio.lines().map { it.trim() }.filter { it.isNotEmpty() })
                }.onFailure {
                    Diag.log("learn: profile failed ${it.message}")
                    learnResult(title, listOf(L.t("读了 ${history.size} 条，档案没写成：", "Read ${history.size} messages, profile failed: ") + Judge.describe(it)))
                }
            }
        }
    }

    /** What a history read produced goes on that person's card, and only there. */
    private fun learnResult(title: String, lines: List<String>) {
        val section = OverlayCard.Section(title, lines)
        (cardShows as? Verdict)?.takeIf { it.personId == learnId }?.sections?.put(LEARN, section)
        if (cardFor == learnId) card.setSection(LEARN, section, openCard = activeId == learnId && !unsettled)
    }

    /** What the chat currently shows, by node tree where it works and by OCR where it does not. */
    private fun captureBubbles(cb: (List<Chat.Bubble>) -> Unit) {
        val root = rootFor(targetPkg)
        val tree = root?.let { runCatching { collect(it) }.getOrNull() } ?: emptyList()
        if (tree.isNotEmpty() || !prefs.ocr) { cb(tree); return }
        captureChat(hideOverlay = false) { shot, _ ->
            if (shot == null) { main.post { cb(emptyList()) }; return@captureChat }
            val win = shot.box
            Ocr.read(shot.bmp) { items ->
                shot.bmp.recycle()
                val placed = items.map { (text, b) -> text to Chat.Box(b.left + win.left, b.top + win.top, b.right + win.left, b.bottom + win.top) }
                val exclude = if (shot.windowOnly) emptyList() else listOfNotNull(card.bounds(), imeBox())
                cb(Chat.fromOcr(placed, win, exclude).first)
            }
        }
    }

    /**
     * A scroll gesture on the chat list: a slow drag, then the finger holds still before lifting,
     * so the list stops where the finger stops instead of flinging past pages nobody captured.
     * Needs canPerformGestures in the service config: without it the system drops the gesture
     * silently, and every history read ended after its first screen.
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
                override fun onCancelled(d: GestureDescription?) { Diag.log("learn: scroll gesture cancelled") }
            },
            null,
        )
        if (!ok) Diag.log("learn: scroll gesture not dispatched")
    }

    // ---- screenshots ----

    /** A screenshot of the chat, and where on screen its pixels sit. */
    private class Shot(val bmp: Bitmap, val box: Chat.Box, val windowOnly: Boolean)

    /**
     * On Android 14+ this is the chat's own window: neither our card nor the keyboard is in it, so
     * an open card no longer hides the newest messages from OCR. Elsewhere, and whenever that
     * fails, it is the whole display; [hideOverlay] makes our card invisible for that capture (for
     * a picture the vision model will read) instead of leaving it painted over the chat.
     */
    private fun captureChat(hideOverlay: Boolean, onDone: (Shot?, String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 34) {
            val w = chatWindow(targetPkg)?.first
            if (w != null) { captureWindow(w, hideOverlay, onDone); return }
        }
        captureDisplay(hideOverlay, onDone)
    }

    @TargetApi(34)
    private fun captureWindow(w: AccessibilityWindowInfo, hideOverlay: Boolean, onDone: (Shot?, String) -> Unit) {
        val r = Rect().also { w.getBoundsInScreen(it) }
        takeScreenshotOfWindow(w.id, shotIo, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = toBitmap(result)
                if (bmp == null) { main.post { captureDisplay(hideOverlay, onDone) }; return }
                onDone(Shot(bmp, Chat.Box(r.left, r.top, r.left + bmp.width, r.top + bmp.height), true), "ok (window)")
            }

            override fun onFailure(errorCode: Int) {
                Diag.log("window capture failed (${shotError(errorCode)}), using the display")
                val wait = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 350L else 0L
                main.postDelayed({ captureDisplay(hideOverlay, onDone) }, wait)
            }
        })
    }

    private fun captureDisplay(hideOverlay: Boolean, onDone: (Shot?, String) -> Unit) {
        val wrap = { bmp: Bitmap?, note: String -> onDone(bmp?.let { Shot(it, Chat.Box(0, 0, it.width, it.height), false) }, note) }
        if (!hideOverlay) { captureBitmap(wrap); return }
        card.setHiddenForCapture(true)
        // A frame or two for the change to reach the screen before it is captured.
        main.postDelayed({
            captureBitmap { bmp, note ->
                main.post { card.setHiddenForCapture(false) }
                wrap(bmp, note)
            }
        }, 120)
    }

    @TargetApi(30)
    private fun toBitmap(result: ScreenshotResult): Bitmap? = try {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
        hw?.recycle()
        copy
    } catch (e: Exception) {
        Diag.log("capture unreadable: ${e.message}")
        null
    } finally {
        runCatching { result.hardwareBuffer.close() }
    }

    private fun captureBitmap(onDone: (Bitmap?, String) -> Unit) = captureBitmap(onDone, tries = 3)

    /**
     * Some phones (Samsung among them) intermittently report "too soon", "invalid display" or an
     * internal error, so retry a couple of times with a short backoff before giving up.
     */
    private fun captureBitmap(onDone: (Bitmap?, String) -> Unit, tries: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onDone(null, "needs Android 11+"); return }
        // The callback must not land on the main thread: a debug route blocks waiting for it.
        takeScreenshot(Display.DEFAULT_DISPLAY, shotIo, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = toBitmap(result)
                onDone(bmp, if (bmp == null) "captured but unreadable" else "ok")
            }

            override fun onFailure(errorCode: Int) {
                if (errorCode in RETRYABLE_SHOT && tries > 1) {
                    main.postDelayed({ captureBitmap(onDone, tries - 1) }, 350)
                } else {
                    onDone(null, "failed: ${shotError(errorCode)}")
                }
            }
        })
    }

    /**
     * A coarse grayscale fingerprint used as an identity when the contact's name cannot be read:
     * an emoji-only name like "🍵" is invisible to OCR, but its pixels are stable across visits,
     * so the chat still gets its own memory. [win] is where the bitmap sits on screen.
     */
    private fun fingerprint(bmp: Bitmap, win: Chat.Box, bubbles: List<Chat.Bubble>): IntArray {
        val w = bmp.width
        val h = bmp.height
        // Their avatar, taken from the row of the first message they sent: a photo, so it has
        // real variation. The title bar is nearly blank and hashed every chat to the same value.
        val firstIncoming = bubbles.firstOrNull { it.incoming }
        val region = if (firstIncoming != null) {
            val top = firstIncoming.box.top - win.top   // screen to bitmap coordinates
            Chat.Box((w * 0.015f).toInt(), top, (w * 0.09f).toInt(), (top + h * 0.032f).toInt())
        } else
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

    private fun Chat.Box.toRect() = Rect(left, top, right, bottom)

    // ---- DebugServer.Host: served only to a caller holding the generated token ----

    override fun status(): String = onMain {
        """
        service      : ${if (Diag.connected) "connected" else "down"}
        enabled      : ${prefs.enabled}  debug=${prefs.debug}  learning=${prefs.learning}  passive=${prefs.passive}
        paused       : ${if (prefs.snoozed) "until ${java.util.Date(prefs.snoozeUntil)}" else "no"}
        packages     : ${prefs.packages}
        judge        : ${prefs.judge}
        api key      : ${if (prefs.apiKey.isBlank()) "MISSING" else "set (${prefs.apiKey.length} chars)"}
        openrouter   : ${if (prefs.orKey.isBlank()) "MISSING" else "set (${prefs.orKey.length} chars)"}
        foreground   : ${rootInActiveWindow?.packageName ?: "?"}
        last scan    : ${Diag.lastScan}
        last error   : ${Diag.lastError.ifBlank { "none" }}
        backoff      : ${if (retryAt == 0L) "none" else "$failStreak failures, $lastFailure"}
        person       : ${Diag.person.ifBlank { "unknown" }}
        in flight    : ${inFlight ?: "none"}  deep=${deepInFlight.size}
        cached cards : ${verdicts.size}
        pending ep   : ${pending?.let { "${it.intent}|${it.action} danger=${"%.2f".format(it.danger)}" } ?: "none"}
        people known : ${people.index().size}
        routes       : /status /log /tree /windows /shot /last /learn /learn/reset /rescan
        """.trimIndent()
    }

    override fun tree(): String = onMain {
        val root = rootFor(targetPkg) ?: rootInActiveWindow ?: return@onMain "no active window"
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
                .append(" ed=").append(if (n.isEditable) "1" else "0")
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
        return "now (foreground ${Diag.lastPackage}): $report\n" +
            "last automatic capture: ${Diag.shotProbe.ifBlank { "none yet; open a WeChat chat and wait a few seconds" }}"
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
                "ok $size, 800 samples gave ${colors.size} colours " +
                    if (colors.size <= 2) "→ blank, the capture is blocked" else "→ real picture, OCR can work"
            )
        }
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
                    .append(" layer=").append(w.layer)
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
            val r = people.loadId(id)
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
        verdicts.clear()
        cardShows = null
        forceJudge = true
        rescanSoon()
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
        /** Screenshots are rate-limited by the platform and cost battery: at most one OCR pass per gap. */
        private const val PROBE_GAP_MS = 3000L
        private const val MAX_VERDICTS = 24
        private const val SNOOZE_MS = 60 * 60 * 1000L
        private const val FATAL_BACKOFF_MS = 10 * 60 * 1000L
        /** The record every chat whose name cannot be read at all shares. Stored as is, shown translated. */
        private const val UNKNOWN = "未知"

        private const val DEEP = "deep"
        private const val REPLY = "reply"
        private const val LEARN = "learn"

        private val RETRYABLE_SHOT = setOf(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
        )

        private fun shotError(code: Int): String = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no access"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "too soon"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid window"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window"
            else -> "error $code"
        }

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
