package dev.vibecheck

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/**
 * A small bubble that sits at the edge of the screen whenever you are in a watched chat, and the
 * judgment card that opens when you tap it.
 *
 * The bubble is the resting state. Judgments keep arriving and tint the bubble by how badly the
 * turn could go, but nothing covers the conversation until you ask for it. Drag the bubble to
 * move it (it snaps to the nearest edge and remembers where); long-press it for the menu.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY: an accessibility service may add it without the "display over
 * other apps" permission, it is a trusted window (so taps on it are not dropped as untrusted
 * touches on Android 12+), and an app calling setHideOverlayWindows() cannot hide it.
 * NOT_TOUCH_MODAL means taps anywhere outside it still reach the chat underneath.
 */
class OverlayCard(
    private val svc: AccessibilityService,
    private val actions: Actions,
    private val prefs: Prefs,
    /** Top of the keyboard in screen pixels while it is up, so the card is never placed under it. */
    private val keyboardTop: () -> Int?,
) {

    interface Actions {
        fun onOpened()
        fun onDeepThink()
        fun onSuggestReplies()
        fun onLearn()
        fun onStopLearning()
        /** A reply draft was tapped: put it in the reply box. */
        fun onPick(text: String)
        /** Any line was long-pressed. */
        fun onCopy(text: String)
        fun onRescan()
        fun onSnooze()
        fun onPauseChat()
        fun onUnpause()
        fun onSettings()
    }

    /** A titled panel under the judgment: the deep read, reply drafts, a learned profile. */
    class Section(val title: String, val lines: List<String>, val pickable: Boolean = false)

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    private var cardRoot: ScrollView? = null
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var shown: Chat.Box? = null

    // What the card says.
    private var title: String? = null
    private var blocks: List<Jev.Block> = emptyList()
    private var moreBlocks: List<Jev.Block> = emptyList()
    private var footer: String? = null
    private var anchor: Rect? = null
    private var badge: String? = null
    private var risk = 0f
    private val sections = LinkedHashMap<String, Section>()
    private var note: String? = null
    private var pausedText: String? = null

    // How it is shown.
    private var open = false
    private var expanded = false
    private var menu = false
    /** Non-null while a history read drives the chat: the bubble counts, and a tap stops the read. */
    private var learnCount: Int? = null
    /** Taken down for a screen change inside the app, with everything kept for coming back. */
    private var suspended = false
    /** The section to scroll to on the next render: the one the user just asked for. */
    private var focusKind: String? = null

    private val clearNote = Runnable { note = null; if (open && cardRoot != null) renderCard(keepScroll = true) }

    /** What occludes the chat, to be cut out of a full-screen capture. */
    fun bounds(): Chat.Box? = shown

    /** In a watched conversation: make sure the bubble, or the card if it was open, is up. */
    fun idle(anchor: Rect?) {
        if (anchor != null) this.anchor = anchor
        if (suspended || (cardRoot == null && bubbleView == null)) {
            suspended = false
            render()
        }
    }

    fun show(
        title: String?,
        blocks: List<Jev.Block>,
        footer: String?,
        anchor: Rect?,
        more: List<Jev.Block> = emptyList(),
        badge: String? = null,
        risk: Float = 0f,
        sections: Map<String, Section> = emptyMap(),
    ) {
        this.title = title
        this.blocks = blocks
        this.moreBlocks = more
        this.footer = footer
        if (anchor != null) this.anchor = anchor
        this.badge = badge
        this.risk = risk
        this.sections.clear()
        this.sections.putAll(sections)
        pausedText = null
        if (blocks.isEmpty()) { hide(); return }
        suspended = false
        render()
    }

    /** One line of status in place of a judgment: "Thinking…", a missing key, an error. */
    fun showText(text: String, anchor: Rect?, badge: String? = null, title: String? = this.title) =
        show(title, listOf(Jev.Block("", listOf(text))), null, anchor, badge = badge)

    /**
     * Adds, replaces or (with null) removes a panel.
     * @param openCard true when the user asked for it (Think / Reply): pop the card open at it.
     *   False for the automatic deep pass: keep whatever state the user has, and just make the
     *   bubble say there is something to read.
     */
    fun setSection(kind: String, section: Section?, openCard: Boolean) {
        if (section == null) sections.remove(kind) else sections[kind] = section
        when {
            openCard -> {
                open = true
                expanded = true
                menu = false
                suspended = false
                pausedText = null
                focusKind = kind
                renderCard(keepScroll = true)
            }
            open && cardRoot != null -> renderCard(keepScroll = true)
            section != null && pausedText == null && learnCount == null -> {
                badge = "✦"
                if (bubbleView != null) renderBubble()
            }
        }
    }

    /** A short-lived line at the bottom of the card: "Copied", "Put in the reply box". */
    fun flash(text: String) {
        note = text
        if (open && cardRoot != null) renderCard(keepScroll = true)
        main.removeCallbacks(clearNote)
        main.postDelayed(clearNote, 3000)
    }

    /** Judging is paused here; the bubble says so, and the card offers to resume. */
    fun paused(anchor: Rect?, text: String) {
        if (anchor != null) this.anchor = anchor
        pausedText = text
        menu = false
        suspended = false
        render()
    }

    /** Judging is back on: show again what was there before the pause. */
    fun unpause() {
        if (pausedText == null) return
        pausedText = null
        if (cardRoot != null || bubbleView != null) render()
    }

    /** A history read in progress (count so far), or null when it is over. */
    fun learning(count: Int?) {
        learnCount = count
        if (count != null) { open = false; expanded = false; menu = false }
        renderBubble()
    }

    /** A different chat is on screen: nothing from the previous one may stay on the card. */
    fun reset() {
        title = null
        blocks = emptyList(); moreBlocks = emptyList(); footer = null
        badge = null; risk = 0f
        sections.clear(); note = null; pausedText = null
        open = false; expanded = false; menu = false
        if (cardRoot != null || bubbleView != null) renderBubble()
    }

    fun hide() {
        removeCard()
        removeBubble()
        open = false
        expanded = false
        menu = false
        suspended = false
    }

    /** Off the screen for now, state kept: [idle] brings it back exactly as it was. */
    fun suspend() {
        if (cardRoot == null && bubbleView == null) return
        removeCard()
        removeBubble()
        suspended = true
    }

    /** Settings, theme or screen size changed: draw again from scratch. */
    fun refresh() {
        if (cardRoot == null && bubbleView == null) return
        removeCard()
        removeBubble()
        render()
    }

    /** True while our windows are invisible for a screenshot; every render respects it. */
    private var capturing = false
    private val endCapture = Runnable { setHiddenForCapture(false) }

    /** Invisible for a screenshot, so the vision model sees the chat rather than our card. */
    fun setHiddenForCapture(hidden: Boolean) {
        capturing = hidden
        main.removeCallbacks(endCapture)
        // A capture that never calls back must not leave the card invisible.
        if (hidden) main.postDelayed(endCapture, 4000)
        cardRoot?.alpha = cardAlpha()
        bubbleView?.alpha = bubbleAlpha()
    }

    private fun cardAlpha() = if (capturing) 0f else 1f
    private fun bubbleAlpha() = if (capturing) 0f else if (pausedText != null) 0.7f else 1f

    private fun render(keepScroll: Boolean = false) {
        if (open && learnCount == null) renderCard(keepScroll) else renderBubble()
    }

    // ---- bubble ----

    private fun renderBubble() {
        removeCard()
        val size = dp(44)
        bubbleView?.let { styleBubble(it); return }   // already up: restyle, so it does not blink

        val v = TextView(svc).apply {
            gravity = Gravity.CENTER
            elevation = dp(3).toFloat()
            // Click and long-click go through the listeners, so TalkBack's double-tap works too.
            setOnClickListener { onBubbleTap() }
            setOnLongClickListener { onBubbleLongPress(); true }
        }
        styleBubble(v)
        v.setOnTouchListener(BubbleTouch())
        val (x, y) = bubblePosition(size)
        val lp = params(size, x, y, size)
        try {
            wm.addView(v, lp)
            bubbleView = v
            bubbleParams = lp
            shown = Chat.Box(x, y, x + size, y + size)
        } catch (e: Exception) {
            bubbleView = null
            bubbleParams = null
            shown = null
        }
    }

    private fun styleBubble(v: TextView) {
        val p = palette()
        val label = when {
            learnCount != null -> if (learnCount == 0) "…" else "$learnCount"
            pausedText != null -> "⏸"
            else -> badge ?: "Jev"
        }
        v.text = label
        v.textSize = if (label == "Jev") 10f else 15f
        v.setTextColor(p.bubbleText)
        v.alpha = bubbleAlpha()
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    learnCount != null || pausedText != null || badge == null || badge == "…" -> p.idle
                    risk >= 0.7f -> p.alarm
                    risk >= 0.45f -> p.warn
                    else -> p.calm
                }
            )
            setStroke(dp(1), p.ring)
        }
        v.contentDescription = when {
            learnCount != null -> L.t("正在读聊天记录，已读 $learnCount 条，点一下停止", "Reading the history, $learnCount messages so far; tap to stop")
            pausedText != null -> "Vibecheck: $pausedText"
            badge == null -> L.t("Vibecheck，点开查看", "Vibecheck; tap to open")
            else -> L.t("Vibecheck：$label，点开查看，长按更多", "Vibecheck: $label; tap to open, long-press for more")
        }
    }

    /** Where the user left it, or next to the newest message until they move it. */
    private fun bubblePosition(size: Int): Pair<Int, Int> {
        val (w, h) = screen()
        val saved = prefs.bubbleY
        val y = if (saved >= 0f) (saved * h).toInt().coerceIn(dp(40), (h - size - dp(40)).coerceAtLeast(dp(40)))   // as snap() left it
            else (anchor?.bottom ?: (h / 2)).coerceIn(dp(90), (h - dp(160)).coerceAtLeast(dp(90)))
        val x = if (prefs.bubbleLeft) dp(10) else w - size - dp(10)
        return x to y
    }

    private fun onBubbleTap() {
        if (learnCount != null) { actions.onStopLearning(); return }
        open = true
        menu = false
        renderCard()
        if (blocks.isEmpty() && pausedText == null) actions.onOpened()   // nothing judged yet: go and judge now
    }

    private fun onBubbleLongPress() {
        if (learnCount != null) return
        open = true
        menu = "menu" !in prefs.hiddenButtons
        renderCard()
        if (blocks.isEmpty() && pausedText == null) actions.onOpened()
    }

    /** Tap to open, long-press for the menu, drag to move: it snaps to the nearest edge and stays there. */
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            bubbleView?.performLongClick()   // vibrates on its own when the listener handles it
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    when {
                        dragging -> snap(v, lp)
                        !longPressed -> v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (dragging) snap(v, lp)
                }
            }
            return true
        }
    }

    private fun snap(v: View, lp: WindowManager.LayoutParams) {
        val (w, h) = screen()
        val size = v.width.takeIf { it > 0 } ?: dp(44)
        val left = lp.x + size / 2 < w / 2
        lp.x = if (left) dp(10) else w - size - dp(10)
        lp.y = lp.y.coerceIn(dp(40), (h - size - dp(40)).coerceAtLeast(dp(40)))
        runCatching { wm.updateViewLayout(v, lp) }
        shown = Chat.Box(lp.x, lp.y, lp.x + size, lp.y + size)
        prefs.bubbleLeft = left
        prefs.bubbleY = lp.y.toFloat() / h
    }

    // ---- card ----

    private fun renderCard(keepScroll: Boolean = false) {
        removeBubble()
        val p = palette()
        val s = prefs.cardScale
        val (screenW, screenH) = screen()
        val maxW = (screenW * (if (expanded) 0.9f else 0.72f)).toInt().coerceAtMost(dp(560))

        val content = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        var focus: View? = null

        content.addView(header(p, s))
        if (menu) content.addView(menuRow(p, s))

        val paused = pausedText
        if (paused != null) {
            content.addView(line(paused, 13f * s, p.title, dp(6)))
            content.addView(flow(listOf(chip(L.t("恢复解读", "Resume"), p, s) { actions.onUnpause() })))
        } else {
            if (blocks.isEmpty()) content.addView(line(L.t("正在看这段对话…", "Reading this conversation…"), 12f * s, p.body, dp(4)))
            for (b in if (expanded) blocks + moreBlocks else blocks) {
                if (b.header.isNotEmpty()) content.addView(line(b.header, 12f * s, p.title, dp(2), bold = true))
                for (l in b.lines) content.addView(line(l, 12f * s, p.body, dp(1)))
                content.addView(spacer(dp(5)))
            }
            footer?.let { f -> for (l in f.split("\n")) content.addView(line(l, 12f * s, p.title, dp(1))) }
            for ((kind, sec) in sections) {
                val v = section(sec, p, s)
                content.addView(v)
                if (kind == focusKind) focus = v
            }
            note?.let { content.addView(line(it, 11f * s, p.accent, dp(2))) }
            content.addView(toolbar(p, s))
        }
        focusKind = null

        val root = cardRoot ?: ScrollView(svc)
        val oldScroll = root.scrollY
        root.removeAllViews()
        root.addView(content)
        root.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(p.card)
            setStroke(dp(1), p.stroke)
        }
        root.elevation = dp(3).toFloat()
        root.alpha = cardAlpha()

        // Keep the whole card on screen and off the keyboard: bounded height, a position that fits.
        val top = dp(48)
        val kb = keyboardTop()?.takeIf { it > top + dp(160) }
        val bottom = minOf((screenH * 0.95f).toInt(), (kb ?: Int.MAX_VALUE) - dp(8))
        val maxH = (bottom - top).coerceAtLeast(dp(120))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
        )
        val h = root.measuredHeight.coerceAtMost(maxH)

        val a = anchor
        val below = a?.let { it.bottom + dp(6) }
        val above = a?.let { it.top - dp(6) - h }
        val y = when {
            expanded -> top + (maxH - h) / 2                           // a reading view: centre it
            below != null && below + h <= bottom -> below
            above != null && above >= top -> above
            else -> top + (maxH - h) / 2
        }.coerceIn(top, (bottom - h).coerceAtLeast(top))
        val x = (if (expanded) (screenW - maxW) / 2 else a?.left ?: dp(16))
            .coerceIn(dp(8), (screenW - maxW - dp(8)).coerceAtLeast(dp(8)))

        // Explicit height, or the window grows past the screen edge and clips instead of scrolling.
        val lp = params(maxW, x, y, h)
        try {
            // Updated in place: re-adding the window on every change flickered and threw away
            // the scroll position of whatever the user was reading.
            if (cardRoot == null) wm.addView(root, lp) else wm.updateViewLayout(root, lp)
            cardRoot = root
            shown = Chat.Box(x, y, x + maxW, y + h)
            val target = focus
            root.post {
                if (target != null) root.smoothScrollTo(0, (target.top - dp(8)).coerceAtLeast(0))
                else root.scrollTo(0, if (keepScroll) oldScroll else 0)
            }
        } catch (e: Exception) {
            runCatching { wm.removeView(root) }
            cardRoot = null
            shown = null
        }
    }

    private fun header(p: Palette, s: Float): View = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(svc).apply {
            text = title ?: "Jev"
            textSize = 12f * s
            setTextColor(p.header)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if ("menu" !in prefs.hiddenButtons && pausedText == null) {
            addView(iconChip("⋯", L.t("更多操作", "More actions"), p, s) { menu = !menu; renderCard(keepScroll = true) })
        }
        addView(iconChip("✕", L.t("收起", "Close"), p, s) {
            open = false; expanded = false; menu = false
            renderBubble()
        })
    }

    private fun menuRow(p: Palette, s: Float): View = flow(listOf(
        chip(L.t("重新判断", "Re-check"), p, s) { menu = false; actions.onRescan() },
        chip(L.t("暂停 1 小时", "Pause 1 h"), p, s) { menu = false; actions.onSnooze() },
        chip(L.t("这个聊天不再解读", "Pause this chat"), p, s) { menu = false; actions.onPauseChat() },
        chip(L.t("设置", "Settings"), p, s) { menu = false; actions.onSettings() },
    )).also { it.setPadding(0, dp(2), 0, dp(6)) }

    /** The buttons at the bottom. Think, Reply and Learn can be switched off in the Tools tab. */
    private fun toolbar(p: Palette, s: Float): View {
        val hidden = prefs.hiddenButtons
        val chips = ArrayList<View>()
        // An error on the card gets its own way out, whichever buttons are switched off.
        if (badge == "!") chips.add(chip(L.t("重试", "Try again"), p, s) { actions.onRescan() })
        chips.add(chip(if (expanded) L.t("收起", "Less") else L.t("展开", "More"), p, s) {
            expanded = !expanded
            renderCard(keepScroll = true)
        })
        if ("think" !in hidden) chips.add(chip(L.t("深思", "Think"), p, s) { actions.onDeepThink() })
        if ("reply" !in hidden) chips.add(chip(L.t("回复", "Reply"), p, s) { actions.onSuggestReplies() })
        if ("learn" !in hidden) chips.add(chip(L.t("学习", "Learn"), p, s) { actions.onLearn() })
        return flow(chips).also { it.setPadding(0, dp(6), 0, 0) }
    }

    private fun section(sec: Section, p: Palette, s: Float): View = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        addView(spacer(dp(6)))
        addView(line(sec.title, 12f * s, p.title, dp(3), bold = true))
        for (l in sec.lines) {
            val tv = line(l, 12f * s, p.body, dp(5))
            tv.setOnLongClickListener { actions.onCopy(l); true }
            if (sec.pickable) {
                tv.setTextColor(p.title)
                tv.setPadding(dp(10), dp(8), dp(10), dp(8))
                tv.background = rounded(p.pick, dp(8))
                tv.setOnClickListener { actions.onPick(l) }
            }
            addView(tv)
        }
        if (sec.pickable) addView(line(L.t("点一条填进输入框，长按复制", "Tap one to put it in the reply box · long-press to copy"), 10f * s, p.header, 0))
    }

    private fun params(w: Int, x: Int, y: Int, h: Int = WindowManager.LayoutParams.WRAP_CONTENT) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun removeCard() {
        cardRoot?.let { runCatching { wm.removeView(it) } }
        cardRoot = null
        shown = null
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        if (bubbleView != null) shown = null   // or OCR keeps cutting out a spot where nothing is drawn
        bubbleView = null
        bubbleParams = null
    }

    private fun screen(): Pair<Int, Int> = svc.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    // ---- small views ----

    private fun line(text: String, sp: Float, color: Int, marginBottom: Int, bold: Boolean = false) = TextView(svc).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = marginBottom }
    }

    private fun spacer(h: Int) = View(svc).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun chip(text: String, p: Palette, s: Float, onClick: () -> Unit) = TextView(svc).apply {
        this.text = text
        textSize = 12f * s
        gravity = Gravity.CENTER
        minHeight = dp(34)
        setTextColor(p.chipText)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(p.chip, dp(8))
        setOnClickListener { onClick() }
    }

    private fun iconChip(text: String, description: String, p: Palette, s: Float, onClick: () -> Unit) =
        chip(text, p, s, onClick).apply {
            contentDescription = description
            minWidth = dp(36)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(6) }
        }

    private fun flow(children: List<View>) = Flow(svc, dp(6)).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        children.forEach { addView(it) }
    }

    /** Chips left to right, wrapping onto a new row instead of squeezing the last ones off the card. */
    private class Flow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowH = 0
            val childW = MeasureSpec.makeMeasureSpec(avail.coerceAtLeast(0), MeasureSpec.AT_MOST)
            val childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(childW, childH)
                if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(y + rowH + paddingTop + paddingBottom, heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val avail = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += rowH + gap; rowH = 0 }
                c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }

    // ---- colours: follows the phone's dark mode, since the chat underneath does ----

    private class Palette(
        val card: Int, val stroke: Int, val header: Int, val title: Int, val body: Int,
        val chip: Int, val chipText: Int, val pick: Int, val accent: Int,
        val idle: Int, val calm: Int, val warn: Int, val alarm: Int, val ring: Int, val bubbleText: Int,
    )

    private fun palette(): Palette =
        if ((svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT

    private companion object {
        fun c(argb: Long) = argb.toInt()

        val LIGHT = Palette(
            card = c(0xFFF0F0F0), stroke = c(0xFFE0E0E0), header = c(0xFF8A8A8A), title = c(0xFF4F4F4F), body = c(0xFF6E6E6E),
            chip = c(0xFFE0E0E0), chipText = c(0xFF3C3C3C), pick = c(0xFFFFFFFF), accent = c(0xFF2E7D32),
            idle = c(0xFFE8E8E8), calm = c(0xFFDBE7DB), warn = c(0xFFF3E2BE), alarm = c(0xFFF1C7C2), ring = c(0xFFC8C8C8),
            bubbleText = c(0xFF3C3C3C),
        )
        val DARK = Palette(
            card = c(0xFF262626), stroke = c(0xFF3A3A3A), header = c(0xFF9E9E9E), title = c(0xFFE8E8E8), body = c(0xFFC4C4C4),
            chip = c(0xFF3A3A3A), chipText = c(0xFFEEEEEE), pick = c(0xFF333333), accent = c(0xFF81C784),
            idle = c(0xFF3A3A3A), calm = c(0xFF2E4632), warn = c(0xFF574821), alarm = c(0xFF5C2B27), ring = c(0xFF5A5A5A),
            bubbleText = c(0xFFEEEEEE),
        )
    }
}
