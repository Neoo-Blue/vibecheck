package dev.vibecheck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
        /** Who they are to me when that is known (learned or set), not guessed this turn. */
        val relationship: String?,
        val closeness: String?,
        /** The full learned profile; the judge only got its brief. */
        val profile: String,
        /** The chat on screen, whose kept history lines up with [transcript]. */
        val own: String,
        /** How they write: what is normal for them (Person.theirStyleSummary). */
        val theirStyle: String? = null,
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
        /** What was known about the person when this was judged; see [basisOf]. */
        val basis: String,
        /** This turn went into the person's history (Person.Turn). */
        val recorded: Boolean,
        val sections: LinkedHashMap<String, OverlayCard.Section> = LinkedHashMap(),
    )

    /** Everything posted here that throws is logged, not the end of the service (see [Crash]). */
    private val main = Crash.mainHandler()
    // Worker threads log what a task throws and carry on, where it used to end the process.
    /** Jev judgments, one at a time. */
    private val judgeIo = Executors.newSingleThreadExecutor(Crash.threads("vibecheck-judge"))
    /**
     * OpenRouter: deep reads and reply drafts, never queued in front of a judgment, nor behind each
     * other: drafts asked for while the automatic deep read and an older draft were still waiting
     * on a slow model used to wait for a free thread before they even started.
     */
    private val deepIo = Executors.newCachedThreadPool(Crash.threads("vibecheck-deep"))
    /**
     * Writing a profile: one coordinator per write, side by side (a write started again after it
     * got stuck must not queue behind the stuck one), and the notes on stretches three at a time.
     */
    private val profileIo = Executors.newCachedThreadPool(Crash.threads("vibecheck-profile"))
    private val notesIo = Executors.newFixedThreadPool(3, Crash.threads("vibecheck-notes"))
    /** Learning about me: day write-ups and the profile of me, one at a time. */
    private val meIo = Executors.newSingleThreadExecutor(Crash.threads("vibecheck-me"))
    /** Screenshot callbacks: off the main thread (a debug route blocks on one) and never behind a network call. */
    private val shotIo = Executors.newSingleThreadExecutor(Crash.threads("vibecheck-shot"))

    private lateinit var prefs: Prefs
    private lateinit var card: OverlayCard
    private lateinit var people: PersonStore
    private val me by lazy { MeStore(this) }
    private val meLearner by lazy { MeLearner(me, people, prefs) }
    private var server: DebugServer? = null
    /** The watched packages, re-read when the setting changes rather than split apart on every event. */
    private var watched: Set<String> = emptySet()
    /** SharedPreferences keeps listeners weakly, so this field is what keeps it alive. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> Crash.guard("prefs") { onPrefChanged(key) } }

    private var targetPkg = ""
    private var lastWindow = Chat.Box(0, 0, 0, 0)
    private var lastCounts = ""
    private var lastTitles = listOf<Pair<String, Chat.Box>>()
    private var lastDescs = listOf<String>()
    /** Fingerprints from the last OCR pass: their avatar (when one of their messages shows) and the title bar. */
    private var lastAvatarHash = ""
    /** The inside of their avatar ([Person.insideHash]): the same in light and in dark mode. */
    private var lastAvatarInside = ""
    private var lastTitleHash = ""
    /** The name the last conversation was read under, for the moments a typing indicator hides it. */
    private var lastPeerName: String? = null
    /** The title bar's name cut out as a picture, from the last OCR pass that could not read it. */
    private var lastNamePicture: Bitmap? = null
    private var viaOcr = false
    private var onHomeScreen = false
    private var hasInput = false
    private var lastProbe = 0L
    /** When the last screenshot was asked for (uptime): see [captureChat]. */
    private var lastShotAt = -SHOT_GAP_MS
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
    /** When [inFlight] was asked: a judgment that never comes back must not hold up every later one. */
    private var inFlightAt = 0L
    /** The judgment in flight is a re-judge of the card on screen, which stays up until it lands. */
    private var quietFor: String? = null
    /** Whose chat a history read left scrolled far up, and what it read there; see handle(). */
    private var heldFor: String? = null
    private var heldLines: List<Pair<String, String>> = emptyList()
    /** The verdict Re-check took off the card, until its turn has been judged again. */
    private var recheckOf: Verdict? = null
    private var forceJudge = false
    /** A burst of their messages is judged once it stops (see Judge.Settle). */
    private val settled = Judge.Settle()
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
    /** The chat's own id: its history file, which a linked person's other chats do not share. */
    private var learnOwn = ""
    private var learnPkg = ""
    /** Their name as the title bar shows it: quotes of their messages start with it. */
    private var learnName = ""
    private val learnHistory = ArrayList<Pair<String, String>>()   // oldest first
    private var learnScrolls = 0
    private var learnStale = 0
    /** The newest part of what an earlier read kept, when that read reached the first message: reaching it ends this read. */
    private var learnKept: List<Pair<String, String>> = emptyList()
    /** People whose profile is being written right now. */
    private val writing = HashSet<String>()

    private val scan = Runnable { scanNow() }

    override fun onServiceConnected() {
        Crash.install(this)
        Learning.writing.clear()
        running = this
        prefs = Prefs(this)
        prefs.lang   // applies the UI language to L
        // What each call used, added up per kind of call: shown under Tools.
        OpenRouter.onUsage = prefs::addUsage
        people = PersonStore(this)
        card = OverlayCard(this, this, prefs) { imeBox()?.top }
        watched = prefs.packageList.toSet()
        lastCounts = L.t("尚未扫描", "not scanned yet")
        prefs.listen(prefListener)
        // No packageNames filter: with one, no event ever arrives from another app, so the card
        // never learns the chat is gone and stays pinned over whatever you open next.
        // Notifications too: they spell out the names OCR cannot read (emoji). Set here as well as
        // in the XML, so an update does not wait for the service to be switched off and on.
        serviceInfo = serviceInfo.apply {
            packageNames = null
            eventTypes = eventTypes or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Crash.guard("event") { onEvent(event) }

    private fun onEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (pkg in watched) hear(pkg, event)
            return
        }
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
            emptyReads = 0
            main.removeCallbacks(foreignCheck)
            main.post { abortLearning("page changed"); card.suspend() }
        }
        main.removeCallbacks(scan)
        main.postDelayed(scan, DEBOUNCE_MS)   // the list settles after a new message arrives
    }

    private val foreignCheck = Runnable { if (!chatOnScreen()) leaveChat("left the chat") }

    /** Recent message notifications from watched apps: package, sender as the app writes it, message. In memory only. */
    private val heard = ArrayDeque<Triple<String, String, String>>()

    private fun hear(pkg: String, event: AccessibilityEvent) {
        val extras = (event.parcelableData as? Notification)?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val (name, msg) = Person.fromNotification(title, text) ?: return
        heard.addLast(Triple(pkg, name, msg))
        while (heard.size > 40) heard.removeFirst()
    }

    /**
     * A name OCR could not read in full gets its real one, emoji and all, from their
     * notifications: "欧欧" becomes "欧欧🌸", an unnamed emoji-only chat becomes "🐟". Kept as the
     * name you would give them, so it shows everywhere; never over one you gave yourself.
     */
    private fun nameFromNotifications(record: PersonStore.Record, bubbles: List<Chat.Bubble>) {
        if (!viaOcr || record.alias.isNotBlank() || record.name == UNKNOWN || heard.isEmpty()) return
        val read = record.name.takeUnless { Person.isFingerprint(it) }
        val full = Person.nameFromNotifications(
            read,
            bubbles.filter { it.incoming }.takeLast(8).map { it.text },
            heard.filter { it.first == targetPkg }.map { it.second to it.third },
        ) ?: return
        people.setAlias(record.id, full)
        record.alias = full
        Diag.log("named ${record.name} \"$full\" from a notification")
    }

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
        Learning.writing.clear()
        if (running === this) running = null
        learning = false
        main.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        if (::card.isInitialized) card.hide()
        server?.stop()
        judgeIo.shutdownNow()
        deepIo.shutdownNow()
        profileIo.shutdownNow()
        meIo.shutdownNow()
        notesIo.shutdownNow()
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
        // Fingerprints belong to the OCR pass that made them: an old one would name this chat
        // after whichever chat was captured last.
        lastAvatarHash = ""
        lastAvatarInside = ""
        lastTitleHash = ""
        lastNamePicture = null
        handle(bubbles)
    }

    private val ocrWatchdog = Runnable { ocrBusy = false }

    /** People whose way of writing has been read from their kept history in this run; see [backfillTheirStyle]. */
    private val styleBackfilled = HashSet<String>()

    /**
     * Someone learned before their way of writing was kept: it is read once from the history on
     * the phone, in the background, rather than starting from nothing.
     */
    private fun backfillTheirStyle(record: PersonStore.Record) {
        val id = record.id
        if (record.theirStyle.msgs > 0 || id in styleBackfilled || people.archiveCount(id) == 0) return
        styleBackfilled += id
        profileIo.execute {
            val theirs = Person.Style()
            runCatching { people.archiveAll(id) }.getOrDefault(emptyList())
                .filter { it.first != "我" }.forEach { Person.observe(theirs, it.second) }
            if (theirs.msgs == 0) return@execute
            main.post {
                val r = people.loadId(id)
                if (theirs.msgs > r.theirStyle.msgs) { r.theirStyle = theirs; people.save(r) }
            }
        }
    }

    /** Reads in a row that saw nothing; see [inconclusive]. */
    private var emptyReads = 0

    /**
     * A read that saw nothing (a screenshot Android refused, a frame caught mid-animation) says
     * nothing about where we are. It used to put the card away until the next screen change,
     * and in a chat where nothing moved that meant until you scrolled: the card seemed to vanish.
     * Now the card stays as it is and the screen is read again in a moment, a few times; only
     * then does the card go, until the screen changes. Not leaving the chat either way, or the
     * next screen would count as freshly opened and its history as new messages.
     */
    private fun inconclusive(why: String) {
        emptyReads++
        Diag.log("scan: $why ($emptyReads)")
        if (emptyReads <= EMPTY_RETRIES) { rescanSoon(SHOT_GAP_MS + 200); return }
        card.suspend()
    }

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
                    if (gen == screenGen) inconclusive("capture $note")
                }
                return@captureChat
            }
            val win = shot.box
            // Looked at as the picture is taken: a notification sliding over the title bar, or our
            // own card sitting there, was once cut out and kept as "their name". A capture of the
            // chat's own window has neither in it.
            val over = if (shot.windowOnly) emptyList() else overlays()
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
                val ime = imeBox()
                val exclude = if (shot.windowOnly) emptyList() else listOfNotNull(card.bounds(), ime)
                val (found, titles) = Chat.fromOcr(
                    placed, win, exclude, Chat.replyRowAbove(ime, win),
                    resources.displayMetrics.density, pixelSide(shot.bmp, win, over + exclude),
                )
                val covered = Person.titleCovered(over + exclude, win)
                val avatar = avatarPrint(shot.bmp, win, found, over + exclude)?.let { Person.hashOf(it) }.orEmpty()
                val inside = avatarPrint(shot.bmp, win, found, over + exclude, inside = true)?.let { Person.insideHash(it) }.orEmpty()
                val title = if (covered) "" else Person.hashOf(titlePrint(shot.bmp))
                // No name to read in the title bar: keep a picture of it (the emoji the name is made of).
                val picture = if (!covered && titles.none { Person.looksLikeName(it.first) } && !Person.isTyping(titles)) namePicture(shot.bmp) else null
                shot.bmp.recycle()
                if (gen != screenGen) { rescanSoon(); return@read }   // the screen changed under the capture
                onHomeScreen = Chat.looksLikeHomeScreen(placed, win)
                hasInput = false
                lastAvatarHash = avatar
                lastAvatarInside = inside
                lastTitleHash = title
                lastNamePicture = picture
                lastWindow = win
                lastTitles = titles
                lastDescs = emptyList()
                lastCounts = L.t("OCR：识别 ${items.size} 块，过滤后 ${found.size}", "OCR: ${items.size} blocks, ${found.size} after filtering")
                viaOcr = true
                handle(found)
            }
        }
    }

    /** The app's view tree took longer than [WALK_BUDGET_MS] to walk. */
    private class SlowTree : Exception("the app's view tree answered too slowly")

    /** Depth-first walk of the window, keeping only nodes that look like chat bubbles. */
    private fun collect(root: AccessibilityNodeInfo): List<Chat.Bubble> {
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val win = if (bounds.width() > 0 && bounds.height() > 0)
            Chat.Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
        else
            Chat.Box(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        val titles = ArrayList<Pair<String, Chat.Box>>()
        val descs = ArrayList<String>()
        val texts = ArrayList<Pair<String, Chat.Box>>()
        val longClick = ArrayList<Boolean>()
        // Avatars along the edges: beside a message whose margins leave its side open, they settle it.
        val avatars = ArrayList<Chat.Box>()
        val dp = resources.displayMetrics.density
        var input: Chat.Box? = null
        val h = win.bottom - win.top
        val statusBar = win.top + h * 0.035
        val titleBand = win.top + h * 0.07

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        // Each node not yet cached is a call into the other app, which waits while that app is
        // busy: past the budget, this read is dropped (and the screen read by OCR instead) rather
        // than holding the main thread until Android calls us not responding.
        val until = SystemClock.uptimeMillis() + WALK_BUDGET_MS
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            if (visited % 50 == 0 && SystemClock.uptimeMillis() > until) throw SlowTree()
            val n = stack.removeLast()
            visited++
            // Hidden views (and whatever is inside them) are not on screen: a hidden label in the
            // title bar is no one's name, and a message scrolled out of the list is not on show.
            if (!n.isVisibleToUser) continue
            n.contentDescription?.toString()?.let { if (it.endsWith("头像")) descs.add(it) }
            if (n.isEditable) {
                // The reply box: the lowest text field below the top third, which is where the
                // keyboard pushes it. Chat lists keep their search at the top.
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0 && r.top > win.top + h * 0.35 && r.top > (input?.top ?: Int.MIN_VALUE))
                    input = Chat.Box(r.left, r.top, r.right, r.bottom)
            }
            val text = n.text?.toString()?.trim().orEmpty()
            val cls = n.className?.toString().orEmpty()
            if (text.isNotEmpty() && cls.endsWith("TextView")) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val box = Chat.Box(r.left, r.top, r.right, r.bottom)
                texts.add(text to box)
                longClick.add(n.isLongClickable)
                if (box.top >= statusBar && box.top <= titleBand) titles.add(text to box)  // name band
            } else if (text.isEmpty() && ("Image" in cls || "Avatar" in cls || n.contentDescription?.endsWith("头像") == true)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val box = Chat.Box(r.left, r.top, r.right, r.bottom)
                if (Sides.isAvatar(box, win, dp)) avatars.add(box)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        // Messages only: not the title bar, not a strip of quick replies, nothing in the compose area.
        val keep = Chat.messageIndices(texts, win, input).sortedBy { texts[it].second.top }
        val theirs = Chat.sides(keep.map { texts[it].second }, win, dp) { Sides.byAvatars(it, avatars) }
        val found = keep.mapIndexed { j, i -> Chat.Bubble(texts[i].first, theirs[j], texts[i].second) }
        val longClickable = keep.filter { longClick[it] }.mapTo(HashSet()) { texts[it].first }
        lastWindow = win
        lastTitles = titles
        lastDescs = descs
        // A text field along the bottom is a reply box, which a chat has and a chat list does not.
        hasInput = input?.let { it.top > win.top + h * 0.6 } ?: false
        onHomeScreen = Chat.looksLikeHomeScreen(texts, win)
        lastCounts = L.t(
            "${root.packageName ?: "?"}：节点 $visited，带文字的 TextView ${texts.size}，过滤后 ${found.size}",
            "${root.packageName ?: "?"}: $visited nodes, ${texts.size} text views, ${found.size} after filtering",
        )
        return Chat.order(Chat.refine(found, longClickable))
    }

    // ---- deciding what the card says ----

    private fun handle(read: List<Chat.Bubble>) {
        unsettled = false
        Diag.lastScan = lastCounts + L.t("；最后一条：", "; newest: ") +
            (read.lastOrNull()?.let { (if (it.incoming) L.t("对方 ", "them ") else L.t("我 ", "me ")) + it.text.take(30) } ?: L.t("无", "none"))

        if (prefs.debug) { debugCard(read); return }
        if (read.isEmpty()) { inconclusive("nothing read"); return }
        emptyReads = 0
        // The chat list is also full of names and text; judging it would be nonsense.
        if (onHomeScreen || !Chat.inConversation(read, hasInput)) {
            activeId = null
            card.hide()
            return
        }

        // Who am I talking to? Everything below is scoped to that person. While they type, WeChat
        // shows "对方正在输入..." where the name was: still the same chat. Emoji-only names are
        // invisible to OCR, so those chats are known by a fingerprint instead of all being lumped
        // together under one "unknown" record.
        // A name hidden by typing is not an unreadable one: no fingerprint for it, or a contact
        // with a perfectly good name would be filed under a stray fingerprint record.
        val name = Person.peerName(lastTitles, lastDescs, lastWindow, symbols = !viaOcr)
            ?: (if (Person.isTyping(lastTitles)) lastPeerName?.takeIf { activeId != null } else unreadableName())
            ?: UNKNOWN
        lastPeerName = name
        // A quote is someone's earlier words, not a new message: theirs under my reply (by their
        // name), or anyone's whose words are further up the screen.
        val bubbles = Chat.withoutEchoes(if (name == UNKNOWN || Person.isFingerprint(name)) read else Chat.withoutQuotes(read, name))
        if (bubbles.isEmpty()) { inconclusive("only quotes"); return }
        val record = people.load(targetPkg, name)
        val id = record.id
        // One person split in two by a change between light and dark mode: put back together
        // before anything is counted into the wrong half, and the screen read again.
        if (Person.isFingerprint(record.name) && lastNamePicture?.let { mergeLookalike(record, it) } == true) { rescanSoon(); return }
        // Someone known only by a fingerprint: the picture of their name is how they are shown.
        if (Person.isFingerprint(record.name) && record.alias.isBlank()) lastNamePicture?.let { considerNamePicture(id, it) }
        nameFromNotifications(record, bubbles)
        val entering = activeId != id
        activeId = id
        currentRecord = record
        backfillTheirStyle(record)
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
        resumeProfile(id)
        checkDay()
        Diag.person = "${displayName(record)} (${record.stats.theirMsgs + record.stats.myMsgs} seen, ${record.model.updates} updates)"

        // Judging costs money and there is nowhere to show it, so stop here when judging is off.
        if (!prefs.enabled) { card.hide(); return }
        if (prefs.snoozed) {
            val left = ((prefs.snoozeUntil - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
            card.paused(anchor, L.t("已暂停，$left 分钟后恢复", "Paused, back in $left min"))
            rescanSoon(prefs.snoozeUntil - System.currentTimeMillis() + 500)
            return
        }
        card.unpause()

        // A history read leaves the chat scrolled far up, on old messages: nothing is judged on
        // its own until the newest ones are back on screen, something new is, or the chat is
        // opened again. Judging those old screens used to replace the card with a verdict about
        // messages from months ago.
        if (heldFor != null && (heldFor != id || entering ||
                Chat.pastHistory(record.tail, heldLines, bubbles.map { (if (it.incoming) "对方" else "我") to it.text }))) {
            heldFor = null
            heldLines = emptyList()
        }

        // Normally judge only when the other person spoke last. But if the user opened the card
        // themselves, judge the current screen whoever spoke last, or it hangs on "Thinking".
        // A judgment whose answer never came back (its thread gave out) would hold up every later
        // one, and the card would say "Thinking…" for good: after a few minutes it is let go.
        if (inFlight != null && System.currentTimeMillis() - inFlightAt > STUCK_MS) {
            Diag.log("judge: gave up waiting on $inFlight")
            inFlight = null
            quietFor = null
        }
        val read = if (heldFor == id && !forceJudge) null
            else Chat.triggerKey(bubbles) ?: (if (forceJudge) Chat.anyKey(bubbles) else null)
        val known = verdicts[id]
        // Read by OCR, the same messages can come out a character apart from one frame to the
        // next: still the turn already judged, not a new one to pay for.
        val key = if (viaOcr && read != null && known != null && read != known.key &&
            Chat.sameTurn(known.ctx.transcript, Chat.transcript(bubbles))) known.key else read
        val title = cardTitle(record, Relationship.pinned(record.rel))
        when {
            key != null && inFlight == "$id|$key" -> if (inFlight != quietFor) status(L.t("思考中…", "Thinking…"), anchor, "…", title)
            known != null && known !== cardShows && (key == null || known.key == key || inFlight != null) -> present(known, anchor)
        }
        card.idle(anchor)
        // Something new is known about the person since the card was judged (a history read, a
        // relationship chosen by hand, a note): the same messages are judged again, and the card
        // stays up until the new reading lands.
        if (known != null && known === cardShows && (key == null || key == known.key) && known.basis != basisOf(record)) {
            if (inFlight == null) rejudge(record, known, anchor) else rescanSoon(1500)
            return
        }
        if (key == null || inFlight == "$id|$key") return
        val forced = forceJudge
        forceJudge = false
        if (known?.key == key) return
        // While they are still sending, wait: the burst is judged once, as a whole. Not on the
        // way into a chat (what is there has stopped coming), nor when the card was asked for.
        val settle = settled.wait("$id|$key", System.currentTimeMillis())
        if (settle > 0 && !entering && !forced) { rescanSoon(settle + 50); return }
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

    /**
     * The card's own messages judged again, quietly: the verdict on it stays up until the new one
     * lands. From the messages it was judged on rather than the screen, which after a history
     * read is still far up in the past.
     */
    private fun rejudge(record: PersonStore.Record, v: Verdict, anchor: Rect) {
        val now = System.currentTimeMillis()
        if (now < retryAt) { rescanSoon(retryAt - now + 50); return }
        if (Judge.missingKey(prefs) != null) return
        val bubbles = v.ctx.transcript.map { (who, text) -> Chat.Bubble(text, who == "对方", Chat.Box(0, 0, 0, 0)) }
        if (bubbles.isEmpty()) return
        judge(record, bubbles, v.key, anchor, quiet = true, ocr = v.ctx.viaOcr)
    }

    /** Everything a judgment took from the person rather than the screen: who they are, and what we know. */
    private fun basisOf(r: PersonStore.Record): String = listOf(
        Relationship.pinned(r.rel).orEmpty(), Relationship.closeness(r.close).orEmpty(), r.alias,
        people.background(r, prefs.context),
    ).joinToString("\u0000")

    private fun present(v: Verdict, anchor: Rect?, keepScroll: Boolean = false) {
        if (cardShows === v) return
        cardShows = v
        card.show(v.title, v.blocks, v.footer, anchor, v.more, v.badge, v.risk, v.sections, keepScroll)
    }

    /** One line in place of a judgment. Drawn only when it changes, not on every scan. */
    private fun status(text: String, anchor: Rect?, badge: String?, title: String? = null) {
        val tag = "status:$badge:$text"
        if (cardShows == tag) return
        cardShows = tag
        if (title == null) card.showText(text, anchor, badge) else card.showText(text, anchor, badge, title)
    }

    private fun judge(
        record: PersonStore.Record,
        bubbles: List<Chat.Bubble>,
        key: String,
        anchor: Rect,
        quiet: Boolean = false,
        ocr: Boolean = viaOcr,
    ) {
        val id = record.id
        inFlight = "$id|$key"
        inFlightAt = System.currentTimeMillis()
        // Known (learned or set by hand): Jev is told, not asked, so one turn cannot contradict it.
        val rel = Relationship.pinned(record.rel)
        val close = Relationship.closeness(record.close)
        if (quiet) {
            quietFor = inFlight
            card.flash(L.t("按更新后的关系和档案重新判断…", "Re-checking with the updated relationship and profile…"))
        } else status(L.t("思考中…", "Thinking…"), anchor, "…", cardTitle(record, rel))

        val lastIncoming = (bubbles.lastOrNull { it.incoming } ?: bubbles.last()).text
        val transcript = Chat.transcript(bubbles)
        val background = people.background(record, prefs.context)
        val basis = basisOf(record)
        val style = Person.styleSummary(record.style)
        val history = Person.historySummary(record.history)
        val relation = Relation.summary(record.stats)
        val state = Jev.stateJson(
            context = background,
            transcript = transcript,
            peer = record.alias.ifBlank { record.name }.takeUnless { it == UNKNOWN || Person.isFingerprint(it) },
            style = style,
            history = history,
            relation = relation,
            source = if (ocr) OCR_CAVEAT else null,
            relationship = rel,
            closeness = close,
            // What is normal for this person, learned message by message and turn by turn: the
            // reading of each turn gets more theirs the longer you talk.
            theirStyle = Person.theirStyleSummary(record.theirStyle),
            usual = Person.normSummary(record.norm),
        )
        val triageBody = Jev.triageBody(state, askSituation = rel == null)
        // This record copy is not touched again (the answer is applied to a fresh load), so the
        // judge thread may read its model.
        val model = if (prefs.learning) record.model else Learner.Model()
        Diag.lastRequest = triageBody
        Diag.log("ask: ${transcript.size} lines of context")
        val started = System.currentTimeMillis()

        // Two stages. Triage decides whether this turn deserves anything; only then is the
        // situation-specific question set asked, so the card's headers change with the situation
        // instead of every chat getting the same five blocks.
        judgeIo.execute {
            prefs.countUse(Prefs.USE_JUDGE)
            val triage = runCatching { Judge.ask(prefs, triageBody) }
            // Routed on the danger this person's history calibrates to, not the raw reading.
            val routine = triage.mapCatching { Jev.isRoutine(calibrated(it, model)) }.getOrDefault(false)
            // No situation answer at all still gets the default set, not a two-block card.
            val situation = rel ?: triage.getOrNull()?.let { (it["situation"] as? Jev.Answer.Dist)?.top } ?: ""
            val merged = triage.mapCatching { t ->
                if (routine) t else {
                    prefs.countUse(Prefs.USE_JUDGE)
                    t + Judge.ask(prefs, Jev.detailBody(state, situation))
                }
            }
            main.post {
                judged(id, record.own, key, merged, routine, situation, rel, basis, bubbles, lastIncoming, transcript, background, ocr, started)
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
        own: String,
        key: String,
        result: Result<Map<String, Jev.Answer>>,
        routine: Boolean,
        situation: String,
        rel: String?,
        basis: String,
        bubbles: List<Chat.Bubble>,
        lastIncoming: String,
        transcript: List<Pair<String, String>>,
        background: String,
        ocr: Boolean,
        started: Long,
    ) {
        inFlight = null
        val quiet = quietFor == "$id|$key"
        quietFor = null
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
            // The same turn judged again (Re-check, a new profile, a relationship set by hand)
            // replaces the earlier reading of it rather than counting as one more turn.
            val before = (verdicts[id] ?: recheckOf?.takeIf { it.personId == id })?.takeIf { it.key == key }
            recheckOf = null
            val learned = applyLearning(record, raw, bubbles, lastIncoming, situation, before)
            val ctx = Ctx(
                displayName(record), record.note.ifBlank { if (record.bio.isBlank()) prefs.context else "" },
                Person.styleSummary(record.style), Person.historySummary(record.history),
                Relation.summary(record.stats), transcript, learned.answers, ocr, situation, rel,
                Relationship.closeness(record.close), record.bio, own, Person.theirStyleSummary(record.theirStyle),
            )
            val danger = learned.answers["danger"] as? Jev.Answer.Scored
            val risk = (Jev.risk(learned.answers) ?: 0.0).toFloat()
            val blocks = (if (routine) Jev.routineCard(learned.answers) else Jev.card(learned.answers, situation = situation))
                .ifEmpty { listOf(Jev.Block("", listOf(L.t("没什么要提醒的", "Nothing to flag here")))) }
            val v = Verdict(
                id, key, cardTitle(record, situation), blocks,
                if (routine) emptyList() else Jev.more(learned.answers),
                if (routine) null else footerWith(learned, Profile.hint(record.bio, learned.answers)),
                if (routine) "·" else danger?.let { "${Jev.shownLevel(it)}" },
                risk, ctx, basis, learned.recorded,
            )
            // Judged again because something new is known: what the history read found stays
            // under the new card. Deep reads and drafts go: they were written on the old footing.
            verdicts[id]?.takeIf { it.basis != basis }?.sections?.get(LEARN)?.let { v.sections[LEARN] = it }
            verdicts[id] = v
            if (visible) {
                present(v, null, keepScroll = quiet)
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
            // A failed re-judge leaves the card as it was, with a note, rather than an error in its place.
            if (visible) {
                if (quiet) card.flash(L.t("重新判断失败：", "Re-check failed: ") + lastFailure)
                else status(L.t("Jev 调用失败：", "Jev call failed: ") + lastFailure, null, "!")
            }
            rescanSoon(retryAt - now + 50)
        }
    }

    /**
     * "欧欧 · 朋友 · 很铁": who, what kind of chat, how close. A misread contact shows at a glance.
     * Someone whose name is only a picture (emoji OCR cannot read) gets the picture there.
     */
    private fun cardTitle(r: PersonStore.Record, situation: String?): String = listOfNotNull(
        if (people.namePicture(r.id) != null) OverlayCard.NAME_PICTURE.toString() else displayName(r),
        situation?.takeIf { it.isNotBlank() }?.let { L.label(it) },
        Relationship.closeness(r.close)?.let { L.label(it) },
    ).joinToString(" · ")

    private fun displayName(r: PersonStore.Record): String = when {
        r.alias.isNotBlank() -> r.alias
        r.name == UNKNOWN -> L.t("认不出是谁", "Unknown contact")
        Person.isFingerprint(r.name) -> L.t("未命名联系人", "Unnamed contact")
        else -> r.name
    }

    private fun debugCard(bubbles: List<Chat.Bubble>) {
        // Always draw in debug mode, even with nothing left after filtering: a blank screen
        // cannot tell you whether the service is dead, blocked, or just over-filtering.
        val read = if (bubbles.isEmpty()) listOf(lastCounts, L.t("窗口 ", "window ") +
            "${lastWindow.left},${lastWindow.top}-${lastWindow.right},${lastWindow.bottom}")
        else bubbles.takeLast(4).map { (if (it.incoming) L.t("对方: ", "them: ") else L.t("我: ", "me: ")) + it.text.take(24) }
        // Where the name comes from, so a misread one can be told apart from a missing one.
        val lines = read + listOfNotNull(
            L.t("标题栏：", "Title bar: ") + lastTitles.joinToString(" | ") { it.first.take(16) }.ifEmpty { "—" },
            lastDescs.takeIf { it.isNotEmpty() }?.let { d -> L.t("头像描述：", "Avatar labels: ") + d.distinct().take(3).joinToString(" | ") { it.take(16) } },
        )
        val who = Person.peerName(lastTitles, lastDescs, lastWindow, symbols = !viaOcr) ?: L.t("认不出是谁", "unknown contact")
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

    private class Learned(val answers: Map<String, Jev.Answer>, val adjusted: Boolean, val reward: Double?, val recorded: Boolean = false)

    /**
     * Score the previous card against this turn, then bend this turn's numbers by what we
     * have learned about this particular relationship.
     */
    private fun applyLearning(
        record: PersonStore.Record,
        raw: Map<String, Jev.Answer>,
        bubbles: List<Chat.Bubble>,
        lastIncoming: String,
        situation: String,
        before: Verdict?,
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
            // Only settle an episode against the same person it was opened for, and never against
            // a second reading of its own turn: that would score the advice against itself.
            if (before == null && pendingId == record.id && Chat.repliedSince(bubbles, pendingText)) {
                reward = Learner.observe(record.model, ep, rawNorm)
                Diag.log("learn ${record.name}: ${ep.intent}|${ep.action} reward=${"%+.2f".format(reward)}")
            }
            pending = null
        }

        val out = LinkedHashMap(raw)
        val calibrated = Learner.danger(record.model, rawNorm)
        out["danger"] = Jev.Answer.Scored(calibrated * (levels - 1), levels)

        val intent = (raw["intent"] as? Jev.Answer.Dist)?.top ?: "unknown"
        // The relationship the turn was judged as: known, or this turn's answer.
        val sit = situation.ifBlank { "*" }
        if (before == null) Relation.judged(record.stats, calibrated)
        // What is usual for them moves with every new turn (not with a second reading of one),
        // on Jev's own reading, so the next turn is read against how they usually come across.
        if (before == null) Person.observeNorm(record.norm, intent, rawNorm)
        var adjusted = false
        var recorded = false
        (raw["action"] as? Jev.Answer.Dist)?.let { dist ->
            val reranked = Learner.rerank(record.model, sit, intent, dist.probs)
            adjusted = Learner.changedTop(dist.probs, reranked)
            val top = reranked.maxByOrNull { it.value }?.key ?: dist.top
            out["action"] = Jev.Answer.Dist(top, reranked)
            pending = Learner.Episode(sit, intent, top, calibrated, rawNorm)
            pendingId = record.id
            pendingText = lastIncoming
            val turn = Person.Turn(intent, Math.round(calibrated * (levels - 1)).toInt() + 1, top)
            record.history = if (before?.recorded == true && record.history.isNotEmpty()) record.history.dropLast(1) + turn
                else Person.push(record.history, turn)
            recorded = true
        }
        people.save(record)
        return Learned(out, adjusted, reward, recorded)
    }

    /** The takeaway, what this person's profile says about a moment like this, and whether experience moved things. */
    private fun footerWith(l: Learned, hint: String?): String? {
        val base = Jev.footer(l.answers)
        val note = if (l.adjusted) L.t("（已按你们过去的走向调整）", "(adjusted from how things went before)") else null
        return listOfNotNull(base, hint, note).joinToString("\n").ifBlank { null }
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
            // A person whose history is kept keeps it current, so the next read only needs what
            // it missed; nobody else's messages are stored.
            if (sync.aligned) people.appendArchive(record.own, sync.fresh)
            val fresh = bubbles.takeLast(sync.fresh.size)
            fresh.filter { !it.incoming }.forEach { Person.observe(record.style, it.text) }
            fresh.filter { it.incoming }.forEach { Person.observe(record.theirStyle, it.text) }
            val now = System.currentTimeMillis()
            Relation.observe(record.stats, fresh, now, live = sync.aligned, tzOffsetMs = TimeZone.getDefault().getOffset(now).toLong())
            // Across every chat, what is said as it happens goes into the day log: what I did today,
            // and with whom. Not lines that only look new because the chat was opened after a while.
            if (prefs.aboutMe && sync.aligned) me.log(now, displayName(record), sync.fresh)
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
        recheckOf = activeId?.let { verdicts.remove(it) }
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

    override fun relationNow(): String? =
        currentRecord?.takeIf { it.id == activeId && it.name != UNKNOWN }?.let { people.relationOf(it.id) }

    /** Who they are to me, chosen on the card ("" = work it out). The card is judged again at once. */
    override fun onSetRelation(key: String) {
        val r = currentRecord?.takeIf { it.id == activeId && it.name != UNKNOWN } ?: return
        people.setRelation(r.id, key)
        Diag.log("relationship for ${r.name}: ${key.ifEmpty { "auto" }}")
        card.flash(if (key.isEmpty()) L.t("关系：自动判断", "Relationship: worked out automatically") else L.t("关系已设为：", "Relationship set: ") + L.label(key))
        rescanSoon()
    }

    override fun namePicture(): Bitmap? = currentRecord?.takeIf { it.id == activeId }?.let { people.namePicture(it.id) }

    override fun closenessNow(): String? =
        currentRecord?.takeIf { it.id == activeId && it.name != UNKNOWN }?.let { people.closenessOf(it.id) }

    override fun onSetCloseness(key: String) {
        val r = currentRecord?.takeIf { it.id == activeId && it.name != UNKNOWN } ?: return
        people.setCloseness(r.id, key)
        Diag.log("closeness for ${r.name}: ${key.ifEmpty { "unknown" }}")
        card.flash(if (key.isEmpty()) L.t("亲近程度：交给学习判断", "Closeness: left to learning") else L.t("亲近程度：", "Closeness: ") + L.label(key))
        rescanSoon()
    }

    /** Naming someone happens in the app, where there is a keyboard to type with. */
    override fun onRename() {
        val r = currentRecord?.takeIf { it.id == activeId && it.name != UNKNOWN } ?: return
        leaveChat("naming a contact")
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PERSON, r.id).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        val until = SystemClock.uptimeMillis() + WALK_BUDGET_MS   // see collect()
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            if (visited % 50 == 0 && SystemClock.uptimeMillis() > until) break
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
        deepSince[tag] = System.currentTimeMillis()
        waitingText[tag] = L.t("思考中…", "Thinking…")
        if (openCard) {
            deepWanted += tag
            v.sections[kind] = waiting
            card.setSection(kind, waiting, true)
        }
        waitTick(kind, tag, v, title)
        val use = if (kind == REPLY) Prefs.USE_REPLY else Prefs.USE_DEEP
        prefs.countUse(use)
        val c = v.ctx
        val system = if (kind == REPLY) OpenRouter.replySystem(prefs.adult) else OpenRouter.deepSystem(prefs.adult)
        // Neither thinks first. Drafts are wanted now; a deep read is three short lines, and
        // thinking before them took a minute or more and cost several times the answer. A model
        // that cannot skip it is asked again with a little (see OpenRouter.chat).
        val think = OpenRouter.Think.OFF
        val send = { image: String? ->
            val model = if (kind == REPLY || image != null) prefs.fastModel else prefs.deepModel
            // The other model set in Setup stands in when this one fails (see Models.backup).
            val backup = Models.backup(model, prefs.fastModel, prefs.deepModel)
            // A read and three drafts, or three lines: this is room to spare, and a cap on a model
            // that runs on.
            val tokens = if (kind == REPLY) 1200 else 1000
            // A model stuck in a provider's queue, or gone quiet mid-answer, is given up on and the
            // backup asked; some providers send nothing while a model that must think does.
            val deadline = if (kind == REPLY) OpenRouter.Deadline(quietMs = 40_000, answerMs = 75_000, totalMs = 150_000)
            else OpenRouter.Deadline(quietMs = 60_000, answerMs = 120_000, totalMs = 200_000)
            deepIo.execute {
                // Whatever goes wrong, building the prompt included, ends up on the card as a
                // failure: the panel must never be left saying "Thinking…".
                val built = runCatching { deepPromptFor(kind, v, image != null) }
                val started = System.currentTimeMillis()
                // One model. Each finished line goes on the card while it writes the next, and a
                // model that reads no images (one typed in, or Qwen) gets the text alone.
                val ask = { m: String, prompt: String, pic: String? ->
                    val once = { p: String?, text: String ->
                        OpenRouter.chat(prefs.orKey, m, system, text, p, maxTokens = tokens, timeoutMs = deadline.quietMs.toInt(), think = think,
                            onLine = { line -> main.post { deepPartial(kind, tag, v, title, line) } }, deadline = deadline, kind = use)
                    }
                    runCatching { once(pic, prompt) }.recoverCatching { e ->
                        if (pic != null && OpenRouter.noImages(e)) once(null, prompt.replace(OpenRouter.SCREENSHOT_NOTE, "")) else throw e
                    }.getOrThrow()
                }
                // The model set in Setup first; when it fails in a way another model might not
                // (busy, down, refusing, cut off, stuck), the backup is asked the same, without
                // the screenshot: it is the quick way out, and a picture is what some models choke on.
                var backupNote: String? = null
                val r = built.mapCatching { prompt ->
                    try {
                        ask(model, prompt, image)
                    } catch (e: Exception) {
                        if (backup == null || !OpenRouter.worthAnotherModel(e)) throw e
                        Diag.log("deep: $model failed (${Judge.describe(e)}); asking $backup")
                        main.post {
                            if (tag in deepInFlight) {
                                waitingText[tag] = L.t("${Models.nameOf(model)} 没成（${Judge.describe(e)}），换 ${Models.nameOf(backup)}…",
                                    "${Models.nameOf(model)} failed (${Judge.describe(e)}); asking ${Models.nameOf(backup)}…")
                                showWaiting(kind, tag, v, title)
                            }
                        }
                        val answer = try {
                            ask(backup, if (image != null) prompt.replace(OpenRouter.SCREENSHOT_NOTE, "") else prompt, null)
                        } catch (e2: Exception) {
                            throw OpenRouter.Failure(0, L.t("${Models.nameOf(model)}：${Judge.describe(e)}；${Models.nameOf(backup)} 也出错：${Judge.describe(e2)}",
                                "${Models.nameOf(model)}: ${Judge.describe(e)}; ${Models.nameOf(backup)} failed too: ${Judge.describe(e2)}"))
                        }
                        backupNote = L.t("${Models.nameOf(model)} 出错（${Judge.describe(e)}），这次由 ${Models.nameOf(backup)} 回答",
                            "${Models.nameOf(model)} failed (${Judge.describe(e)}); ${Models.nameOf(backup)} answered instead")
                        answer
                    }
                }
                r.onSuccess { Diag.log("deep($model) ok in ${System.currentTimeMillis() - started}ms") }
                    .onFailure { Diag.lastError = "deep: ${it.message}"; Diag.log(Diag.lastError) }
                val note = backupNote
                main.post { deepDone(kind, tag, v, title, r, note) }
            }
        }
        // A picture of a different chat, or of this one scrolled back into its history after a read,
        // would contradict the transcript, so only while this chat's messages are on screen.
        // Only a deep read, asked for, takes a screenshot along: for drafts the text is enough, and
        // the picture cost more than the rest of the question put together and hid the card while
        // it was taken.
        if (kind == DEEP && c.viaOcr && activeId == v.personId && heldFor != v.personId) captureChat(hideOverlay = true) { shot, note ->
            // A picture that cannot be made is left out, rather than the question never being sent.
            val jpeg = shot?.let { runCatching { toJpegBase64(it.bmp) }.also { _ -> it.bmp.recycle() }.getOrNull() }
            if (jpeg == null) Diag.log("deep: no image, $note")
            send(jpeg)
        } else send(null)
    }

    /**
     * The prompt for a deep read or drafts on [v]: the kept history before the screen, what I am
     * like and have been doing, and for drafts how I really answer this person. Blocking.
     */
    private fun deepPromptFor(kind: String, v: Verdict, hasImage: Boolean): String {
        val c = v.ctx
        val drafts = kind == REPLY
        // More than the dozen lines a screen holds, when this chat's kept history lines up with
        // them. Drafts answer the last few, and get less of everything: they are asked for often.
        val lines = Archive.before(people.archive(c.own), c.transcript, if (drafts) REPLY_LINES else CONTEXT_LINES)
        // What I am like and have been up to, which changes by the day; what I said elsewhere
        // today, which changes by the minute and so goes further down (see deepPrompt).
        val about = if (!prefs.aboutMe) null else Me.brief(me.profile, me.summaries(3), max = if (drafts) 500 else 900)
        val elsewhere = if (!prefs.aboutMe) null
            else Me.elsewhereToday(me.lines(Me.day(System.currentTimeMillis())), c.name, max = if (drafts) 4 else 8)
        var prompt = OpenRouter.deepPrompt(
            c.name, c.note, c.style, c.history, c.relation,
            lines, c.answers, c.viaOcr, hasImage, c.situation, c.relationship, c.closeness,
            if (drafts) Profile.forDrafts(c.profile) else c.profile, about, c.theirStyle, elsewhere,
        )
        // Drafts in my voice: how I actually answered this person before, from the kept history.
        if (drafts) {
            val history = people.archiveAll(v.personId)
            if (history.isNotEmpty()) {
                val said = c.transcript.takeLast(4).filter { it.first == "对方" }.takeLast(2).joinToString(" ") { it.second }
                prompt += OpenRouter.voice(
                    Archive.examples(history, said, k = 5, exclude = lines.map { it.second }.toSet()),
                    Archive.phrases(history, "我"),
                )
            }
        }
        return prompt
    }

    /**
     * A deep read or drafts still being written: the lines done so far replace "Thinking…" on an
     * open card. A closed card is left alone until the answer is complete.
     */
    private fun deepPartial(kind: String, tag: String, v: Verdict, title: String, text: String) {
        if (tag !in deepInFlight) return
        waitingText.remove(tag)   // words are coming: no more "waiting N s"
        val section = sectionFrom(kind, title, text, done = false) ?: return
        v.sections[kind] = section
        if (cardShows === v && card.isOpen()) card.setSection(kind, section, openCard = false)
    }

    /** The panel for an answer; null while nothing of it can be shown yet. */
    private fun sectionFrom(kind: String, title: String, text: String, done: Boolean): OverlayCard.Section? {
        if (kind != REPLY) {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return if (lines.isEmpty() && !done) null else OverlayCard.Section(title, lines.ifEmpty { listOf(text.trim()) })
        }
        val r = OpenRouter.replies(text)
        return when {
            r.drafts.isNotEmpty() -> OverlayCard.Section(title, r.drafts, pickable = true, read = r.read)
            !done -> r.read?.let { OverlayCard.Section(title, listOf(L.t("正在写回复…", "Writing the drafts…")), read = it) }
            else -> OverlayCard.Section(title, listOf(text.trim()), pickable = true, read = r.read)
        }
    }

    private fun deepDone(kind: String, tag: String, v: Verdict, title: String, r: Result<String>, note: String? = null) {
        deepInFlight -= tag
        waitingText.remove(tag)
        deepSince.remove(tag)
        val wanted = deepWanted.remove(tag)
        val section = r.fold(
            { text -> sectionFrom(kind, title, text, done = true)!!.withNote(note) },
            // Both models' reasons in full when the backup failed too (describe() would cut them short).
            { e -> OverlayCard.Section(title, listOf(L.t("失败：", "Failed: ") + (if (e is OpenRouter.Failure && e.status == 0) e.message.orEmpty() else Judge.describe(e)))) },
        )
        v.sections[kind] = section
        // Bound to the turn it was asked about: an answer that lands after a new message must not
        // be pinned onto the wrong card. And it only pops open while that chat is on screen; after
        // leaving it waits on that chat's card for the way back.
        if (cardShows === v) card.setSection(kind, section, openCard = wanted && activeId == v.personId && !unsettled)
    }

    /** When each deep read or draft set in flight was asked, and what its panel says while nothing is written yet. */
    private val deepSince = HashMap<String, Long>()
    private val waitingText = HashMap<String, String>()

    /** The waiting line with the seconds gone by, so a slow model reads as slow rather than stuck. */
    private fun showWaiting(kind: String, tag: String, v: Verdict, title: String) {
        val text = waitingText[tag] ?: return
        val secs = (System.currentTimeMillis() - (deepSince[tag] ?: return)) / 1000
        val section = OverlayCard.Section(title, listOf(if (secs >= 3) text + L.t(" $secs 秒", " ${secs}s") else text))
        v.sections[kind] = section
        if (cardShows === v && card.isOpen()) card.setSection(kind, section, openCard = false)
    }

    private fun waitTick(kind: String, tag: String, v: Verdict, title: String) {
        main.postDelayed({
            if (tag !in deepInFlight) return@postDelayed
            showWaiting(kind, tag, v, title)
            waitTick(kind, tag, v, title)
        }, 5_000)
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

    /** Why a history read ended, which decides whether it covers the whole history. */
    private enum class LearnEnd { TOP, KNOWN, STOPPED, CAP }

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
        if (rec.id in writing) {
            val p = Learning.writing[rec.id]
            val now = System.currentTimeMillis()
            // Stuck (no sign of life for minutes): given up on, and written again. Stretches
            // already noted are kept, so only what is missing is paid for again.
            if (p != null && Learning.stalled(p, now)) {
                Diag.log("learn: ${rec.name}'s write is stuck; starting it again")
                writing -= rec.id
                writeProfile(rec.id, null, byUser = true)
                return
            }
            card.setSection(LEARN, OverlayCard.Section(title, p?.let { Learning.lines(it, now) }
                ?: listOf(L.t("还在整理上次读到的记录，好了会显示在这里", "Still writing up the last read; it will show here"))), true)
            return
        }
        learning = true
        awake()
        learnId = rec.id
        learnOwn = rec.own
        learnPkg = targetPkg
        learnName = rec.name
        learnHistory.clear()
        learnScrolls = 0
        learnStale = 0
        // A history kept from a read that went all the way back only needs what came after it.
        // Reading a long one takes a while: off the main thread, and in use from the page it lands on.
        learnKept = emptyList()
        val since = people.archiveComplete(rec.own)
        if (since) {
            val own = rec.own
            profileIo.execute {
                val kept = people.archive(own).takeLast(Archive.MERGE_WINDOW)
                main.post { if (learning && learnOwn == own) learnKept = kept }
            }
        }
        Diag.log("learn: reading ${rec.name}'s history" + if (since) " (since the last read)" else "")
        card.learning(0)
        learnStep()
    }

    /** Tapping the bubble during a read stops it and writes the profile from what was read so far. */
    override fun onStopLearning() {
        if (!learning) return
        Diag.log("learn: stopped by the user")
        finishLearning(LearnEnd.STOPPED)
    }

    /** A capture that never calls back (screenshot or OCR hung) must not leave learn mode on forever. */
    private val learnWatchdog = Runnable { abortLearning("stuck") }
    private val learnNext = Runnable { learnStep() }

    /** Is a window of this app on screen? rootInActiveWindow is the keyboard whenever it is up. */
    private fun onScreen(pkg: String): Boolean =
        runCatching { windows.any { it.root?.packageName?.toString() == pkg } }.getOrDefault(false)

    /**
     * One page of the history read: capture what is on screen, keep what is new, then scroll
     * toward older messages and go again. Ends at the first message (a few pages in a row that
     * add nothing), on reaching what an earlier read kept, when the bubble is tapped, or at a
     * very high cap.
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
            // Their message quoted under my reply is theirs: left out, not kept as mine.
            val shown = Chat.withoutEchoes(if (Person.isFingerprint(learnName)) bubbles else Chat.withoutQuotes(bubbles, learnName))
            val page = shown.map { (if (it.incoming) "对方" else "我") to it.text }
            val fresh = Chat.freshLines(learnHistory, page)
            learnHistory.addAll(0, fresh)
            if (fresh.isEmpty()) learnStale++ else learnStale = 0
            learnScrolls++
            card.learning(learnHistory.size)
            val end = when {
                Archive.reached(learnKept, page) -> LearnEnd.KNOWN
                learnStale >= STALE_PAGES -> LearnEnd.TOP
                learnScrolls >= MAX_LEARN_PAGES -> LearnEnd.CAP
                else -> null
            }
            if (end != null) { finishLearning(end); return@captureBubbles }
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            // Drag downward: the list follows the finger, revealing older messages at the top.
            // Just over half a screen, so pages still overlap by a few messages to line up on.
            // The next step is on a timer, not the gesture callback: a callback that never comes
            // must not leave learn mode stuck on and every judgment switched off.
            swipe(w / 2, h * 0.28f, w / 2, h * 0.82f)
            main.removeCallbacks(learnNext)
            main.postDelayed(learnNext, LEARN_STEP_MS)
        }
    }

    private fun abortLearning(why: String) {
        if (!learning) return
        learning = false
        heldFor = learnId
        heldLines = ArrayList(learnHistory)
        main.removeCallbacks(learnWatchdog)
        main.removeCallbacks(learnNext)
        Diag.log("learn: aborted ($why), ${learnHistory.size} read")
        card.learning(null)
        awake()
    }

    private fun finishLearning(end: LearnEnd) {
        learning = false
        heldFor = learnId
        heldLines = ArrayList(learnHistory)
        main.removeCallbacks(learnWatchdog)
        main.removeCallbacks(learnNext)
        card.learning(null)
        val id = learnId
        val own = learnOwn
        val title = L.t("学习此人", "Learn this person")
        val read = ArrayList(learnHistory)
        Diag.log("learn: read ${read.size} ($end)")
        // Joining the read onto a long kept history reads, checks and writes all of it: seconds,
        // which on the main thread (where a tap that stopped the read waits) made Android close
        // the app as not responding. Marked as writing meanwhile, so Learn is not started twice.
        writing += id
        awake()
        profileIo.execute {
            val r = runCatching { keep(id, own, read, end) }
            main.post {
                writing -= id
                awake()
                r.onFailure { e ->
                    Crash.caught("learn", e)
                    learnResult(id, title, listOf(L.t("没存上：${Judge.describe(e)}", "Could not keep it: ${Judge.describe(e)}")))
                }.onSuccess { k -> afterKeep(id, title, read.size, k) }
            }
        }
    }

    /** A read joined onto what was kept, and what all of it says; see [keep]. */
    private class Kept(
        val size: Int,
        val history: List<Pair<String, String>>,
        val all: List<Pair<String, String>>,
        val counts: Relation.Stats,
        val style: Person.Style,
        val theirStyle: Person.Style = Person.Style(),
    )

    /**
     * Blocking. The read joined onto what was kept, and saved. A read that reached the kept
     * history adds what came since; a read that reached the first message is the whole history;
     * anything else extends what was kept where the two line up, and is otherwise kept after
     * it, marked incomplete so the next read goes all the way back. Too little is not saved.
     */
    private fun keep(id: String, own: String, read: List<Pair<String, String>>, end: LearnEnd): Kept {
        val kept = people.archive(own)
        val (history, complete) = when {
            kept.isEmpty() -> read to (end == LearnEnd.TOP)
            end == LearnEnd.TOP && read.size >= kept.size -> read to true
            else -> Archive.merge(kept, read)?.let { it to (people.archiveComplete(own) || end == LearnEnd.TOP) }
                ?: ((kept + read) to false)
        }
        if (history.size < 4) return Kept(history.size, history, emptyList(), Relation.Stats(), Person.Style())
        people.saveArchive(own, history, complete)
        val all = people.archiveAll(id)
        val counts = Relation.Stats().also { Relation.observeBulk(it, all) }
        val style = Person.Style()
        val theirs = Person.Style()
        for ((who, text) in all) Person.observe(if (who == "我") style else theirs, text)
        return Kept(history.size, history, all, counts, style, theirs)
    }

    /** On the main thread, once [keep] is done: the record takes in what was kept, then the profile is written. */
    private fun afterKeep(id: String, title: String, readSize: Int, k: Kept) {
        if (k.size < 4) {
            learnResult(id, title, listOf(L.t("只读到 ${k.size} 条，不够写档案", "Only ${k.size} messages read, not enough for a profile")))
            return
        }
        val history = k.history
        val all = k.all
        // Reloaded: the record may have moved on since the read began.
        val rec = people.loadId(id)
        // A full read is the better baseline than what was seen live, so it replaces the counts
        // and my style rather than adding on top; less than the live count, and it does not.
        val stats = rec.stats
        if (all.size >= stats.theirMsgs + stats.myMsgs) {
            stats.theirMsgs = k.counts.theirMsgs; stats.myMsgs = k.counts.myMsgs
            stats.theirChars = k.counts.theirChars; stats.myChars = k.counts.myChars
            rec.style = k.style
            rec.theirStyle = k.theirStyle
        }
        // The read began at the newest screen: live counting carries on from there, not from the
        // top of the history the read ended on.
        if (rec.tail.isEmpty()) rec.tail = history.takeLast(Chat.TAIL)
        rec.learned = all.size
        people.save(rec)

        if (prefs.orKey.isBlank()) {
            learnResult(id, title, listOf(
                L.t("读了 $readSize 条，存了 ${all.size} 条，统计已更新", "Read $readSize, kept ${all.size} messages, statistics updated"),
                L.t("填了 OpenRouter Key 才能写档案", "Add an OpenRouter key to get a profile")))
            return
        }
        writeProfile(id, readSize, byUser = true)
    }

    /**
     * The profile from everything kept for this person. A long history goes in stretches: notes
     * on each (three at a time, and never again for a stretch already noted), then one profile
     * from all the notes. The card shows how far it has got ([Learning]), refreshed while it runs.
     * [readNow] is how many messages the read that led here got, or null when a write is picked
     * up again. [byUser]: the user asked (a read, or Learn on a stuck write), so what it says may
     * pop the card open; a write picked up by itself only updates it. [fresh] notes every stretch
     * again instead of keeping the notes on those whose text has not changed.
     */
    private fun writeProfile(id: String, readNow: Int?, byUser: Boolean, fresh: Boolean = false) {
        val rec = people.loadId(id)
        val name = displayName(rec)
        val title = L.t("学习此人", "Learn this person")
        val key = prefs.orKey
        val model = prefs.deepModel
        val backup = Models.backup(model, prefs.fastModel, prefs.deepModel)
        // A write started again after it got stuck leaves the old one running: whatever that one
        // still does is dropped, by its number.
        val gen = (writeGen[id] ?: 0) + 1
        writeGen[id] = gen
        val progress = Learning.Progress(System.currentTimeMillis(), readNow)
        Learning.writing[id] = progress
        writing += id
        awake()
        learnResult(id, title, listOf(
            if (readNow == null) L.t("接着写档案…", "Picking the profile up again…")
            else L.t("读了 $readNow 条，开始写档案…", "Read $readNow messages, starting the profile…"),
            L.t("在后台写，不用重来。", "It runs in the background; no need to start again.")), open = byUser)
        tickProgress()
        profileIo.execute {
            // Everything, reading the kept history included, inside: a failure anywhere must end
            // the write, or the person stays "still writing" and the screen stays on for good.
            val r = runCatching {
                val history = people.archiveAll(id)
                progress.kept = history.size
                progress.chars = history.sumOf { it.second.length }
                val chunks = Archive.chunks(history, CHUNK_CHARS)
                profileFrom(id, name, history, chunks, key, model, backup, fresh) { stage, done, of ->
                    progress.update(stage, done, of)
                    main.post { if (writeGen[id] == gen) showProgress(id) }
                }
            }
            main.post {
                if (writeGen[id] != gen) { Diag.log("learn: a write given up on has ended"); return@post }
                writing -= id
                Learning.writing.remove(id)
                awake()
                val size = if (progress.kept > 0) L.t("共存 ${progress.kept} 条（${Learning.amount(progress.chars)}）",
                    "${progress.kept} messages kept (${Learning.amount(progress.chars)})") else null
                r.onSuccess { (text, skipped) -> resumes.remove(id); profileDone(id, progress.kept, text, skipped, open = byUser) }
                    .onFailure {
                        Diag.log("learn: profile failed ${it.message}")
                        // A lost connection mends itself: picked up again once back in this chat,
                        // redoing only the stretches that were not written yet.
                        val again = Judge.isTransient(it) && (resumes[id] ?: 0) < PROFILE_RESUMES
                        if (again) {
                            resumes.putIfAbsent(id, 0)
                            resumeAt = System.currentTimeMillis() + PROFILE_RESUME_MS
                            if (activeId == id) rescanSoon(PROFILE_RESUME_MS + 100)
                        } else resumes.remove(id)
                        learnResult(id, title, listOfNotNull(size, L.t("档案没写成：", "The profile failed: ") + Judge.describe(it),
                            if (again) L.t("读到的和写好的部分都存下了，回到这个聊天会自动接着写", "Everything read and written so far is kept; it carries on by itself when you are back in this chat")
                            else L.t("读到的都存下了，再点「学习」只会补写没写完的部分", "Everything read is kept; Learn again only redoes what is missing")),
                            open = byUser)
                    }
            }
        }
    }

    /**
     * From the person's page in the app: [id]'s profile written again from the history kept on the
     * phone, without opening the chat. [fresh] notes every stretch again (after a model change),
     * rather than only those whose text changed. Null once it has started, else why not. Main thread.
     */
    fun rewriteFromKept(id: String, fresh: Boolean): String? {
        if (prefs.orKey.isBlank()) return L.t("先在设置页填 OpenRouter Key", "Add the OpenRouter key on the Setup tab first")
        if (people.archiveCount(id) == 0) return L.t("手机上还没有和 Ta 的聊天记录：先在聊天里点一次「学习」", "No history with them on this phone yet: tap Learn in their chat once first")
        if (learning && learnId == id) return L.t("正在读 Ta 的聊天记录，读完会自己写档案", "Their history is being read; the profile follows by itself")
        val p = Learning.writing[id]
        if (id in writing && p == null) return L.t("正在整理刚读到的记录，稍等一下", "Still putting the last read together; a moment")
        if (id in writing && p != null && !Learning.stalled(p, System.currentTimeMillis()))
            return L.t("已经在写了，进度就在这一页", "Already being written; the progress is on this page")
        // Not writing, or stuck: (again) from the top, the stuck one's late result dropped.
        writing -= id
        writeProfile(id, null, byUser = false, fresh = fresh)
        return null
    }

    /** Which write is the live one, per person: see [writeProfile]. Read from worker threads too. */
    private val writeGen = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** How far [id]'s write has got, on the card (never popping it open). */
    private fun showProgress(id: String) {
        val p = Learning.writing[id] ?: return
        learnResult(id, L.t("学习此人", "Learn this person"), Learning.lines(p, System.currentTimeMillis()), open = false)
    }

    /**
     * While a profile is being written its panel is redrawn every little while, so the time taken,
     * the estimate, and a stall show without waiting for the next stretch to be done.
     */
    private val progressTick = object : Runnable {
        override fun run() {
            if (writing.isEmpty()) return
            for (id in writing) showProgress(id)
            main.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun tickProgress() {
        main.removeCallbacks(progressTick)
        main.postDelayed(progressTick, PROGRESS_TICK_MS)
    }

    /** Profile writes cut off by a lost connection, by person: how many times each was picked up again. */
    private val resumes = HashMap<String, Int>()
    private var resumeAt = 0L

    /** In [id]'s chat again: a profile write that lost its connection carries on. */
    private fun resumeProfile(id: String) {
        val n = resumes[id] ?: return
        if (id in writing || learning || System.currentTimeMillis() < resumeAt || prefs.orKey.isBlank()) return
        resumes[id] = n + 1
        Diag.log("learn: picking up ${people.nameOf(id)}'s profile again (${n + 1})")
        writeProfile(id, null, byUser = false)
    }

    /**
     * Our window keeps the screen on while a history read or a profile write is under way. Once
     * the screen goes off Android takes the network from a background app, and a write of a few
     * thousand messages failed halfway with "Software caused connection abort".
     */
    private fun awake() = card.keepAwake(learning || writing.isNotEmpty())

    /** Blocking. The profile text, and how many stretches could not be noted. */
    private fun profileFrom(
        id: String,
        name: String,
        history: List<Pair<String, String>>,
        chunks: List<IntRange>,
        key: String,
        model: String,
        backup: String?,
        fresh: Boolean,
        progress: (Learning.Stage, Int, Int) -> Unit,
    ): Pair<String, Int> {
        // A long run of calls meets the odd dropped connection or busy moment: each call is tried
        // again after a pause, and only a failure that does not go away ends the write.
        fun once(m: String, system: String, prompt: String, tokens: Int, think: OpenRouter.Think): String {
            var wait = 5_000L
            var tries = 0
            while (true) {
                prefs.countUse(Prefs.USE_BIO)
                try {
                    return OpenRouter.chat(key, m, system, prompt, null, maxTokens = tokens, timeoutMs = 180_000, temperature = 0.4,
                        think = think, kind = Prefs.USE_BIO)
                } catch (e: Exception) {
                    if (++tries >= ASK_TRIES || !Judge.isTransient(e)) throw e
                    Diag.log("learn: ${Judge.describe(e)}; again in ${wait / 1000}s")
                    Thread.sleep(wait)
                    wait *= 3
                }
            }
        }
        // And when the model set for Think still fails, the other one is asked the same. Taking
        // notes and merging them is writing down what is there: that is done without thinking
        // first, which was most of what those calls cost. The profile itself, which weighs it all
        // up, still thinks a little.
        fun ask(system: String, prompt: String, tokens: Int, think: OpenRouter.Think = OpenRouter.Think.OFF): String = try {
            once(model, system, prompt, tokens, think)
        } catch (e: Exception) {
            if (backup == null || !OpenRouter.worthAnotherModel(e)) throw e
            Diag.log("learn: $model failed (${Judge.describe(e)}); asking $backup")
            once(backup, system, prompt, tokens, think)
        }
        if (chunks.size <= 1) {
            progress(Learning.Stage.FINAL, 0, 0)
            return ask(Profile.profileSystem(false), Profile.profilePrompt(name, history, Archive.text(history), false), 5000, OpenRouter.Think.LOW) to 0
        }
        val texts = chunks.map { Archive.text(history, it) }
        val hashes = texts.map { Archive.hash(it) }
        // Done again from scratch when asked (after a model change): no stretch's old notes are kept.
        val cached = if (fresh) emptyMap() else people.notes(id)
        val notes = arrayOfNulls<String>(chunks.size)
        for (i in chunks.indices) notes[i] = cached[hashes[i]]
        // Counted as each stretch comes back, in whatever order: one slow stretch no longer holds
        // the count still while the others are done.
        val finished = java.util.concurrent.atomic.AtomicInteger(notes.count { it != null })
        progress(Learning.Stage.NOTES, finished.get(), chunks.size)
        val jobs = chunks.indices.filter { notes[it] == null }.map { i ->
            i to notesIo.submit<String> {
                try {
                    ask(Profile.NOTES_SYSTEM, Profile.notesPrompt(name, i + 1, chunks.size, texts[i]), 2500)
                } finally {
                    progress(Learning.Stage.NOTES, finished.incrementAndGet(), chunks.size)
                }
            }
        }
        var failure: Throwable? = null
        for ((i, job) in jobs) {
            notes[i] = runCatching { job.get() }.onFailure { failure = it.cause ?: it }.getOrNull()
            // Kept as each one lands, even if the profile below fails: the next try pays only for
            // what is missing.
            if (notes[i] != null) people.saveNotes(id, chunks.indices.filter { notes[it] != null }.associate { hashes[it] to notes[it]!! })
        }
        // Notes for stretches no longer in the history go.
        val noted = chunks.indices.filter { notes[it] != null }.associate { hashes[it] to notes[it]!! }
        people.saveNotes(id, noted)
        var layer = notes.filterNotNull()
        if (layer.isEmpty()) throw failure ?: IllegalStateException("no notes")
        // More notes than one pass can take: merged a group at a time first. Only the newest
        // stretches change from one read to the next, so most groups are the same notes as last
        // time: those merges are kept with the notes and not paid for again.
        val merged = LinkedHashMap<String, String>()
        while (layer.size > 1 && layer.sumOf { it.length } > REDUCE_CHARS) {
            val groups = Profile.groups(layer, REDUCE_CHARS / 2)
            if (groups.size >= layer.size) break
            progress(Learning.Stage.MERGE, 0, 0)
            layer = groups.map { g ->
                if (g.size == 1) g[0] else {
                    val k = Profile.mergeKey(g)
                    (cached[k] ?: ask(Profile.MERGE_SYSTEM, Profile.mergePrompt(name, g), 3000)).also {
                        merged[k] = it
                        people.saveNotes(id, noted + merged)
                    }
                }
            }
        }
        val body = layer.withIndex().joinToString("\n\n") { (i, n) -> "--- ${i + 1} ---\n$n" }
        progress(Learning.Stage.FINAL, 0, 0)
        return ask(Profile.profileSystem(true), Profile.profilePrompt(name, history, body, true), 5000, OpenRouter.Think.LOW) to notes.count { it == null }
    }

    private fun profileDone(id: String, total: Int, text: String, skipped: Int, open: Boolean = true) {
        // Saved onto a fresh copy: a scan during the calls may have moved the counts on.
        val rec = people.loadId(id)
        val p = Relationship.parse(text.trim())
        rec.bio = p.profile
        // What was chosen by hand stays; the read only fills in what is unknown.
        val overruled = p.rel?.takeIf { rec.relByHand && it != Relationship.pinned(rec.rel) }
        if (!rec.relByHand && p.rel != null) rec.rel = p.rel
        if (!rec.closeByHand && p.close != null) rec.close = p.close
        rec.learnedAt = System.currentTimeMillis()
        people.save(rec)
        Diag.log("learn: profile written for ${rec.name} (${p.rel}, ${p.close})")
        learnResult(id, L.t("已学会 ${displayName(rec)}（$total 条）", "Learned ${displayName(rec)} ($total messages)"),
            listOfNotNull(
                people.relationLine(rec),
                overruled?.let { L.t("（档案看像「${L.label(it)}」，没改你设定的）", "(the profile reads as ${L.label(it)}; your choice was kept)") },
                skipped.takeIf { it > 0 }?.let { L.t("（有 $it 段没整理成，再点「学习」补上）", "($it stretches failed; Learn again to fill them in)") },
            ) + p.profile.lines().map { it.trim() }.filter { it.isNotEmpty() }, open)
        // The card on screen was judged without this profile: the next scan sees that and judges again.
        if (activeId == id) rescanSoon()
        // A history read about someone says things about me too.
        refreshMe()
    }

    // ---- learning about me ----

    private var dayChecked = ""
    private var dayPruned = ""
    private var dayBusy = false
    private var dayRetryAt = 0L
    private var meBusy = false

    /** Once a day: the days before today that have a log are written up, and old logs go. */
    private fun checkDay() {
        val now = System.currentTimeMillis()
        val today = Me.day(now)
        // Logs older than 90 days go whether or not they can be written up (no key) or are still
        // being made (the switch off): 90 days is what the app says it keeps them.
        if (today != dayPruned) {
            dayPruned = today
            meIo.execute { runCatching { me.prune() } }
        }
        if (!prefs.aboutMe || today == dayChecked || dayBusy || now < dayRetryAt || prefs.orKey.isBlank()) return
        dayBusy = true
        meIo.execute {
            val r = runCatching { meLearner.summarizeDays(today) }
            r.onSuccess { if (it > 0) Diag.log("me: $it day(s) written up") }.onFailure { Diag.log("me: day write-up failed ${it.message}") }
            main.post {
                dayBusy = false
                if (r.isSuccess) dayChecked = today else dayRetryAt = System.currentTimeMillis() + 10 * 60_000L
                // New days written up: the profile of me follows them every few days, and is first
                // written from days alone for someone who has not learned anyone yet.
                if ((r.getOrNull() ?: 0) > 0 && (me.profile.isBlank() || System.currentTimeMillis() - me.learnedAt > 3 * 86_400_000L)) refreshMe()
            }
        }
    }

    /** The profile of me, written again from everything learned so far. */
    private fun refreshMe() {
        if (!prefs.aboutMe || prefs.orKey.isBlank() || meBusy) return
        meBusy = true
        meIo.execute {
            runCatching { meLearner.rebuild() }
                .onSuccess { Diag.log("me: profile written") }
                .onFailure { Diag.log("me: profile failed ${it.message}") }
            main.post { meBusy = false }
        }
    }

    /** What a history read produced goes on that person's card, and only there. */
    private fun learnResult(id: String, title: String, lines: List<String>, open: Boolean = true) {
        val section = OverlayCard.Section(title, lines)
        verdicts[id]?.sections?.put(LEARN, section)
        if (cardFor == id) card.setSection(LEARN, section, openCard = open && activeId == id && !unsettled)
    }

    /** What the chat currently shows, by node tree where it works and by OCR where it does not. */
    private fun captureBubbles(cb: (List<Chat.Bubble>) -> Unit) {
        val root = rootFor(targetPkg)
        val tree = root?.let { runCatching { collect(it) }.getOrNull() } ?: emptyList()
        if (tree.isNotEmpty() || !prefs.ocr) { cb(tree); return }
        captureChat(hideOverlay = false) { shot, _ ->
            if (shot == null) { main.post { cb(emptyList()) }; return@captureChat }
            val win = shot.box
            val over = if (shot.windowOnly) emptyList() else overlays()
            Ocr.read(shot.bmp) { items ->
                val placed = items.map { (text, b) -> text to Chat.Box(b.left + win.left, b.top + win.top, b.right + win.left, b.bottom + win.top) }
                val ime = imeBox()
                val exclude = if (shot.windowOnly) emptyList() else listOfNotNull(card.bounds(), ime)
                // The pixels are read while sides are decided, so the picture goes only after.
                val found = Chat.fromOcr(
                    placed, win, exclude, Chat.replyRowAbove(ime, win),
                    resources.displayMetrics.density, pixelSide(shot.bmp, win, over + exclude),
                ).first
                shot.bmp.recycle()
                cb(found)
            }
        }
    }

    /**
     * Whose a text on a capture is, from the pixels beside it (see [Sides]): rows through its first
     * and last lines, where an avatar sits. A row that anything else is drawn across (our card, the
     * keyboard, a notification) is left out. Only asked about texts whose margins leave it open.
     */
    private fun pixelSide(bmp: Bitmap, win: Chat.Box, covered: List<Chat.Box>): (Chat.Box) -> Int {
        val dp = resources.displayMetrics.density
        return { box ->
            runCatching {
                val rows = Sides.rowsFor(box.top - win.top, box.bottom - win.top, bmp.height, dp)
                    .filter { y -> covered.none { y + win.top >= it.top && y + win.top < it.bottom } }
                    .map { y -> IntArray(bmp.width).also { bmp.getPixels(it, 0, bmp.width, 0, y, bmp.width, 1) } }
                Sides.blockSide(rows, box.left - win.left, box.right - win.left, dp)
            }.getOrDefault(Sides.UNKNOWN)
        }
    }

    /**
     * A scroll gesture on the chat list: a slow drag, then the finger holds still before lifting,
     * so the list stops where the finger stops instead of flinging past pages nobody captured.
     * Needs canPerformGestures in the service config: without it the system drops the gesture
     * silently, and every history read ended after its first screen.
     */
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val drag = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, 450, true)
        val hold = drag.continueStroke(Path().apply { moveTo(x2, y2); lineTo(x2, y2 + 1) }, 0, 150, false)
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
        // Android takes one screenshot a second for a service and refuses the next: a screen read
        // right after a deep read's picture failed, and the card went with it. Waited for instead,
        // before the card is hidden, so hiding it does not last longer.
        val wait = lastShotAt + SHOT_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) { main.postDelayed({ captureChat(hideOverlay, onDone) }, wait); return }
        if (Build.VERSION.SDK_INT >= 34) {
            val w = chatWindow(targetPkg)?.first
            if (w != null) { captureWindow(w, hideOverlay, onDone); return }
        }
        captureDisplay(hideOverlay, onDone)
    }

    @TargetApi(34)
    private fun captureWindow(w: AccessibilityWindowInfo, hideOverlay: Boolean, onDone: (Shot?, String) -> Unit) {
        val r = Rect().also { w.getBoundsInScreen(it) }
        lastShotAt = SystemClock.uptimeMillis()
        takeScreenshotOfWindow(w.id, shotIo, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = toBitmap(result)
                if (bmp == null) { main.post { captureDisplay(hideOverlay, onDone) }; return }
                onDone(Shot(bmp, Chat.Box(r.left, r.top, r.left + bmp.width, r.top + bmp.height), true), "ok (window)")
            }

            override fun onFailure(errorCode: Int) {
                Diag.log("window capture failed (${shotError(errorCode)}), using the display")
                val wait = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) SHOT_GAP_MS else 0L
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
    } catch (e: Throwable) {
        // Out of memory for a full-screen copy included: this capture is skipped, not the service.
        Diag.log("capture unreadable: ${e.javaClass.simpleName}: ${e.message}")
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
        lastShotAt = SystemClock.uptimeMillis()
        // The callback must not land on the main thread: a debug route blocks waiting for it.
        takeScreenshot(Display.DEFAULT_DISPLAY, shotIo, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = toBitmap(result)
                onDone(bmp, if (bmp == null) "captured but unreadable" else "ok")
            }

            override fun onFailure(errorCode: Int) {
                if (errorCode in RETRYABLE_SHOT && tries > 1) {
                    // Too soon means a second: three tries 350 ms apart never got past it.
                    main.postDelayed({ captureBitmap(onDone, tries - 1) }, if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) SHOT_GAP_MS else 350)
                } else {
                    onDone(null, "failed: ${shotError(errorCode)}")
                }
            }
        })
    }

    /**
     * A coarse grayscale fingerprint used as an identity when the contact's name cannot be read:
     * an emoji-only name like "🌙" is invisible to OCR, but its pixels are stable across visits,
     * so the chat still gets its own memory. [win] is where the bitmap sits on screen.
     */
    /**
     * A chat whose name cannot be read (OCR cannot see emoji) is known by the avatar beside their
     * messages. The title bar, which shows the emoji name, is fingerprinted too and remembered
     * against that avatar, so a screen with only my own messages on it still finds the same
     * person instead of minting another; with no such memory it stays "unknown" for now.
     */
    private fun unreadableName(): String? {
        val pkg = targetPkg
        val inside = lastAvatarInside.takeIf { it.isNotBlank() }
        val older = lastAvatarHash.takeIf { it.isNotBlank() }
        val title = lastTitleHash.takeIf { it.isNotBlank() }
        // The inside of the avatar first: it is the same in light and dark mode. Then the older
        // fingerprint, which names everyone seen before this one existed.
        val byInside = inside?.let { people.printOwner(pkg, it) }
        val byOlder = older?.let { people.existingFingerprint(pkg, it) }
        val who = when {
            byInside != null && byOlder != null && byInside.first != byOlder -> onePerson(pkg, byInside.first, byOlder, byInside.second)
            else -> byInside?.first ?: byOlder ?: inside ?: older
        }
        if (who != null) {
            inside?.let { people.rememberPrint(pkg, it, who) }
            title?.let { people.rememberTitle(pkg, it, who) }
            return who
        }
        // Only my messages on screen, no avatar: the title bar, as seen with them before.
        return title?.let { people.titleOwner(pkg, it) }
    }

    /**
     * Two records for one chat: its avatar's inside belongs to one, its older fingerprint to the
     * other. That is one person seen in light and in dark mode, whose background the older
     * fingerprint took in. The one with more known goes on; the other, if it is only a few
     * turns seen and nothing learned, is merged into it. Two with a history read each are left
     * as they are, the next chat simply going to the better one.
     */
    private fun onePerson(pkg: String, a: String, b: String, dist: Int): String {
        val keep = Person.better(people.known(pkg, a), people.known(pkg, b))
        val other = people.known(pkg, if (keep.name == a) b else a)
        if (other.learned == 0 && dist <= 1) {
            people.link("$pkg|${other.name}", "$pkg|${keep.name}")
            Diag.log("merged ${other.name} into ${keep.name}: one chat in light and dark mode")
        }
        return keep.name
    }

    /**
     * The name in the middle of the title bar, cut out as a small picture: for a name OCR cannot
     * read (emoji), this is how the app shows who it is. Null when nothing stands out there.
     */
    private fun namePicture(bmp: Bitmap): Bitmap? = runCatching {
        val x0 = (bmp.width * 0.18f).toInt()
        val x1 = (bmp.width * 0.82f).toInt()
        val y0 = (bmp.height * 0.045f).toInt()
        val y1 = (bmp.height * 0.105f).toInt()
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val box = Person.nameBox(px, w, h) ?: return@runCatching null
        val pad = 4
        val left = (x0 + box[0] - pad).coerceAtLeast(0)
        val top = (y0 + box[1] - pad).coerceAtLeast(0)
        val right = (x0 + box[2] + pad).coerceAtMost(bmp.width - 1)
        val bottom = (y0 + box[3] + pad).coerceAtMost(bmp.height - 1)
        // Not the shape of a name (a long thin strip): no picture is better than a wrong one.
        if (!Person.plausiblePicture(right - left + 1, bottom - top + 1)) return@runCatching null
        val cut = Bitmap.createBitmap(bmp, left, top, right - left + 1, bottom - top + 1)
        if (cut.height <= 64) cut
        else Bitmap.createScaledBitmap(cut, cut.width * 64 / cut.height, 64, true).also { if (it !== cut) cut.recycle() }
    }.getOrNull()

    /**
     * Their avatar, from the row of the first message they sent that nothing is drawn over (a
     * notification, our card, the keyboard): a photo, so it has real variation.
     */
    private fun avatarPrint(bmp: Bitmap, win: Chat.Box, bubbles: List<Chat.Bubble>, over: List<Chat.Box>, inside: Boolean = false): IntArray? {
        val w = bmp.width
        val dp = resources.displayMetrics.density
        val region = bubbles.filter { it.incoming }.map { b ->
            // [inside]: a square in the middle of the avatar, well inside its edges and rounded
            // corners (WeChat draws it about 40dp across, some 12dp from the edge, its top level
            // with the bubble's). The older strip began at the screen's edge, in the background.
            if (inside) Chat.Box(win.left + (16 * dp).toInt(), b.box.top + (2 * dp).toInt(), win.left + (40 * dp).toInt(), b.box.top + (26 * dp).toInt())
            else Chat.Box(win.left + (w * 0.015f).toInt(), b.box.top, win.left + (w * 0.09f).toInt(), (b.box.top + bmp.height * 0.032f).toInt())
        }.firstOrNull { r -> over.none { Chat.overlaps(it, r) } } ?: return null
        // Screen to bitmap coordinates.
        return blockMeans(bmp, Chat.Box(region.left - win.left, region.top - win.top, region.right - win.left, region.bottom - win.top))
    }

    /**
     * What other windows draw over a capture of the whole display: system windows short of the
     * full screen (the status bar, which grows over the title bar while a notification slides
     * down; the navigation bar). A pulled-down shade is not a chat anyway.
     */
    private fun overlays(): List<Chat.Box> = runCatching {
        val screen = resources.displayMetrics.heightPixels
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }.map { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            Chat.Box(r.left, r.top, r.right, r.bottom)
        }.filter { it.bottom - it.top < screen * 0.9f }
    }.getOrDefault(emptyList())

    /** People already compared with the others in their app this run; see [mergeLookalike]. */
    private val lookalikeChecked = HashSet<String>()

    /**
     * Someone known by a fingerprint whose name looks the same, by its colours, as another's in
     * the same app: one person, split in two by a change between light and dark mode before the
     * fingerprint left the background out. The one with nothing learned is merged into the other
     * (two with a history read each are not merged on a picture alone). Once per person a run.
     * True when [record] itself was merged away and the screen is to be read again.
     */
    private fun mergeLookalike(record: PersonStore.Record, pic: Bitmap): Boolean {
        if (!lookalikeChecked.add(record.id)) return false
        val pkg = record.id.substringBefore('|')
        val mine = colourSignature(pic) ?: return false
        val other = people.fingerprintPeople(pkg).filter { it != record.id }.firstOrNull { id ->
            people.namePicture(id)?.let { colourSignature(it) }?.let { Person.sameColours(mine, it) } == true
        } ?: return false
        val a = people.known(pkg, record.name)
        val b = people.known(pkg, other.substringAfter('|'))
        val keep = Person.better(a, b)
        val gone = if (keep.name == a.name) b else a
        if (gone.learned > 0) return false
        people.link("$pkg|${gone.name}", "$pkg|${keep.name}")
        Diag.log("merged ${gone.name} into ${keep.name}: the same name in light and dark mode")
        if (cardFor == "$pkg|${gone.name}") cardFor = null
        return gone.name == record.name
    }

    private fun colourSignature(bmp: Bitmap): IntArray? {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Person.colourSignature(px, bmp.width, bmp.height)
    }

    /** Name pictures seen lately that differ from the kept one, per person. */
    private val pictureVotes = HashMap<String, Person.PictureVotes>()

    /** A picture of an unreadable name is kept, or replaces the kept one, only once seen alike more than once. */
    private fun considerNamePicture(id: String, pic: Bitmap) {
        val seen = pictureSignature(pic)
        val kept = people.namePicture(id)?.let { pictureSignature(it) }
        if (!Person.keepPicture(kept, seen, pictureVotes.getOrPut(id) { Person.PictureVotes() })) return
        people.saveNamePicture(id, pic)
        Diag.log(if (kept == null) "name picture kept" else "name picture replaced")
        if (cardFor == id) cardShows = null   // the title is drawn again with it
    }

    private fun pictureSignature(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Person.signature(px, bmp.width, bmp.height)
    }

    /** The middle of the title bar, where the name (or the emoji that is the name) is drawn. */
    private fun titlePrint(bmp: Bitmap): IntArray =
        blockMeans(bmp, Chat.Box((bmp.width * 0.3f).toInt(), (bmp.height * 0.035f).toInt(), (bmp.width * 0.7f).toInt(), (bmp.height * 0.075f).toInt()))

    private fun blockMeans(bmp: Bitmap, region: Chat.Box): IntArray {
        val w = bmp.width
        val h = bmp.height

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
        var nodes = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            // Capped like a scan: each node is a call into the other app, on the main thread.
            if (depth > 25 || ++nodes > MAX_NODES) return
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
        /** The service while it runs, for the app's buttons that act through it (same process). */
        @Volatile var running: ChatReaderService? = null
            private set

        private const val DEBOUNCE_MS = 450L
        private const val MAX_NODES = 3000
        /** The longest a walk of the watched app's view tree may hold the main thread. */
        private const val WALK_BUDGET_MS = 2_000L
        /** Android's least time between two screenshots by one service, and a little more. */
        private const val SHOT_GAP_MS = 1_050L
        /** Empty reads in a row that are read again before the card is put away. */
        private const val EMPTY_RETRIES = 3
        /** A safety net, not a limit anyone should meet: the read ends at the first message. */
        private const val MAX_LEARN_PAGES = 5000
        /** Pages in a row with nothing new that mean the top of the history (or a long run of pictures). */
        private const val STALE_PAGES = 5
        /** From one scroll to the next capture: the gesture, then a moment for the list to settle. */
        private const val LEARN_STEP_MS = 1000L
        /** One stretch of history per notes call, and how much the profile call takes at once. */
        private const val CHUNK_CHARS = 12_000
        private const val REDUCE_CHARS = 40_000
        /** Lines of conversation a deep read or reply drafts see, from the kept history when the screen holds fewer. */
        private const val CONTEXT_LINES = 30
        /** Context for reply drafts, asked for far more often than a deep read. */
        private const val REPLY_LINES = 20
        /** A profile write that lost its connection starts again by itself this many times. */
        private const val PROFILE_RESUMES = 3
        /** How long after a lost connection a profile write is picked up again. */
        private const val PROFILE_RESUME_MS = 20_000L
        /** Tries per profile call before a failure that keeps coming back ends the write. */
        private const val ASK_TRIES = 3
        /** Screenshots are rate-limited by the platform and cost battery: at most one OCR pass per gap. */
        private const val PROBE_GAP_MS = 3000L
        private const val MAX_VERDICTS = 24
        private const val SNOOZE_MS = 60 * 60 * 1000L
        private const val FATAL_BACKOFF_MS = 10 * 60 * 1000L
        /** The record every chat whose name cannot be read at all shares. Stored as is, shown translated. */
        private const val UNKNOWN = "未知"
        /** Longer than a judgment's two calls with their retries ever take. */
        private const val STUCK_MS = 180_000L
        /** How often a profile write's progress is redrawn while nothing else changes. */
        private const val PROGRESS_TICK_MS = 15_000L

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
