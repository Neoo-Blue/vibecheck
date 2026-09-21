package dev.vibecheck

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A small bubble that sits at the edge of the screen whenever you are in a watched chat, and the
 * judgment card that opens when you tap it.
 *
 * The bubble is the resting state. Judgments keep arriving and tint the bubble by how badly the
 * turn could go, but nothing covers the conversation until you ask for it.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY: an accessibility service may add it without the "display over
 * other apps" permission, it is a trusted window (so taps on it are not dropped as untrusted
 * touches on Android 12+), and an app calling setHideOverlayWindows() cannot hide it.
 * NOT_TOUCH_MODAL means taps anywhere outside it still reach the chat underneath.
 */
class OverlayCard(private val svc: AccessibilityService, private val actions: Actions) {

    interface Actions {
        fun onOpened()
        fun onDeepThink()
        fun onSuggestReplies()
        fun onLearn()
        fun onCopy(text: String)
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val d = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var cardView: View? = null
    private var bubbleView: TextView? = null
    private var shown: Chat.Box? = null

    private var blocks: List<Jev.Block> = emptyList()
    private var moreBlocks: List<Jev.Block> = emptyList()
    private var footer: String? = null
    private var anchor: Rect? = null
    private var open = false
    private var expanded = false

    private var badge: String? = null
    private var risk = 0f
    /** While learn mode drags the chat, a tap must not open a card onto the drag path. */
    private var frozen = false

    /** Extra panel filled in by the deep model: a title plus lines, each tappable to copy. */
    private var extraTitle: String? = null
    private var extraLines: List<String> = emptyList()
    private var extraCopyable = false

    /** Only the opened card occludes the chat and needs cutting out of the screenshot. */
    fun bounds(): Chat.Box? = shown

    /** In a watched conversation with nothing judged yet: just sit there as a bubble. */
    fun idle(anchor: Rect?) {
        if (anchor != null) this.anchor = anchor
        if (!open) renderBubble()
    }

    fun showText(text: String, anchor: Rect?) = show(listOf(Jev.Block("Jev：", listOf(text))), null, anchor)

    fun show(
        blocks: List<Jev.Block>,
        footer: String?,
        anchor: Rect?,
        more: List<Jev.Block> = emptyList(),
        badge: String? = null,
        risk: Float = 0f,
    ) {
        this.blocks = blocks
        this.moreBlocks = more
        this.footer = footer
        this.anchor = anchor
        this.badge = badge
        this.risk = risk
        if (blocks.isEmpty()) { hide(); return }
        if (open) renderCard() else renderBubble()
    }

    /** Called when a new judgment arrives, so stale deep-model output is not shown with it. */
    fun clearExtra() {
        extraTitle = null
        extraLines = emptyList()
        extraCopyable = false
    }

    /**
     * @param openCard true when the user asked for this (深思/回复): pop the card open. False for
     *   the automatic deep pass: keep whatever state the user has, and just make the bubble say
     *   there is something to read.
     */
    fun setExtra(title: String, lines: List<String>, copyable: Boolean, openCard: Boolean = true) {
        frozen = false
        extraTitle = title
        extraLines = lines
        extraCopyable = copyable
        if (openCard) {
            open = true
            expanded = true
            renderCard()
        } else if (open) {
            renderCard()
        } else {
            badge = "深"
            renderBubble()
        }
    }

    fun hide() {
        removeCard()
        removeBubble()
        open = false
        expanded = false
        frozen = false
    }

    /**
     * Drop to the bubble and label it. Learn mode drags the chat underneath, and an open card
     * sitting on the drag path scrolled itself instead of the conversation.
     */
    fun collapse(badge: String?, frozen: Boolean = false) {
        open = false
        expanded = false
        this.badge = badge
        this.frozen = frozen
        renderBubble()
    }

    // ---- bubble ----

    private fun renderBubble() {
        removeCard()
        val screenW = svc.resources.displayMetrics.widthPixels
        val screenH = svc.resources.displayMetrics.heightPixels
        val size = dp(44)
        val label = badge ?: "Jev"

        // Already up: just restyle it, so a new judgment does not make the bubble blink.
        bubbleView?.let {
            it.text = label
            it.textSize = if (badge == null) 10f else 15f
            it.background = bubbleBackground(size)
            return
        }

        val dot = TextView(svc).apply {
            text = label
            textSize = if (badge == null) 10f else 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#3C3C3C"))
            background = bubbleBackground(size)
            elevation = dp(2).toFloat()
            setOnClickListener {
                if (frozen) return@setOnClickListener
                open = true
                renderCard()
                if (blocks.isEmpty()) actions.onOpened()   // nothing judged yet: go and judge now
            }
        }
        val y = (anchor?.bottom ?: (screenH / 2)).coerceIn(dp(90), screenH - dp(160))
        val x = screenW - size - dp(10)
        try {
            wm.addView(dot, params(size, x, y, size))
            bubbleView = dot
            shown = Chat.Box(x, y, x + size, y + size)
        } catch (e: Exception) {
            bubbleView = null
            shown = null
        }
    }

    /** Grey until something is judged, then tinted by how badly this turn could go. */
    private fun bubbleBackground(size: Int) = GradientDrawable().apply {
        cornerRadius = size / 2f
        setColor(
            when {
                badge == null -> Color.parseColor("#E8E8E8")
                risk >= 0.7f -> Color.parseColor("#F1C7C2")
                risk >= 0.45f -> Color.parseColor("#F3E2BE")
                else -> Color.parseColor("#DBE7DB")
            }
        )
        setStroke(dp(1), Color.parseColor("#C8C8C8"))
    }

    // ---- card ----

    private fun renderCard() {
        removeCard()
        removeBubble()

        val screenW = svc.resources.displayMetrics.widthPixels
        val screenH = svc.resources.displayMetrics.heightPixels
        val maxW = (screenW * (if (expanded) 0.88f else 0.66f)).toInt()

        val content = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        content.addView(line("Jev：", 12f, HEADER, dp(4)))
        if (blocks.isEmpty()) content.addView(line(L.t("正在看这段对话…", "Reading this conversation…"), 12f, BODY, dp(2)))
        for (b in if (expanded) blocks + moreBlocks else blocks) {
            content.addView(line(b.header, 12f, TITLE, dp(2)))
            for (l in b.lines) content.addView(line(l, 12f, BODY, dp(1)))
            content.addView(line("", 4f, BODY, 0))
        }
        footer?.let { for (l in it.split("\n")) content.addView(line(l, 12f, TITLE, dp(1))) }

        extraTitle?.let { title ->
            content.addView(line("", 6f, BODY, 0))
            content.addView(line(title, 12f, TITLE, dp(3)))
            for (l in extraLines) {
                val tv = line(l, 12f, BODY, dp(4))
                if (extraCopyable) {
                    tv.setPadding(dp(8), dp(6), dp(8), dp(6))
                    tv.background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#E4E4E4"))
                    }
                    tv.setOnClickListener { actions.onCopy(l.substringAfter(". ", l)) }
                }
                content.addView(tv)
            }
            if (extraCopyable) content.addView(line(L.t("点一条即可复制", "Tap one to copy"), 10f, HEADER, 0))
        }

        content.addView(toolbar())

        // Always scrollable: a compact card with a long deep answer overflowed the screen and
        // the bottom simply got clipped, because the window was WRAP_CONTENT.
        val root: View = ScrollView(svc).apply { addView(content) }
        root.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#F0F0F0"))
            setStroke(dp(1), Color.parseColor("#E0E0E0"))
        }
        root.elevation = dp(2).toFloat()

        // Keep the whole card on screen: bounded height, and a position that actually fits.
        val top = dp(56)
        val bottom = (screenH * 0.93f).toInt()
        val maxH = ((bottom - top) * 0.92f).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val h = root.measuredHeight.coerceAtMost(maxH)

        val below = anchor?.let { it.bottom + dp(6) }
        val above = anchor?.let { it.top - dp(6) - h }
        val y = when {
            expanded -> (screenH - h) / 2                              // a reading view: centre it
            below != null && below + h <= bottom -> below
            above != null && above >= top -> above
            else -> (screenH - h) / 2
        }.coerceIn(top, (bottom - h).coerceAtLeast(top))

        val x = (if (expanded) (screenW - maxW) / 2 else anchor?.left ?: dp(16))
            .coerceIn(dp(8), (screenW - maxW - dp(8)).coerceAtLeast(dp(8)))

        try {
            // Explicit height, or the window grows past the screen edge and clips instead of scrolling.
            wm.addView(root, params(maxW, x, y, h))
            cardView = root
            shown = Chat.Box(x, y, x + maxW, y + h)
        } catch (e: Exception) {
            cardView = null
            shown = null
        }
    }

    private fun toolbar(): View = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(chip(if (expanded) L.t("收起", "Less") else L.t("展开", "More")) { expanded = !expanded; renderCard() })
        addView(chip(L.t("深思", "Think")) { actions.onDeepThink() })
        addView(chip(L.t("回复", "Reply")) { actions.onSuggestReplies() })
        addView(chip(L.t("学习", "Learn")) { actions.onLearn() })
        addView(chip("✕") { open = false; expanded = false; renderBubble() })
    }

    private fun params(w: Int, x: Int, y: Int, h: Int = WindowManager.LayoutParams.WRAP_CONTENT) =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun removeCard() {
        cardView?.let { runCatching { wm.removeView(it) } }
        cardView = null
        shown = null
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
    }

    private fun line(text: String, sp: Float, color: String, marginBottom: Int) = TextView(svc).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = marginBottom }
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(svc).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#3C3C3C"))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#E0E0E0"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6); topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private companion object {
        const val HEADER = "#8A8A8A"
        const val TITLE = "#4F4F4F"
        const val BODY = "#6E6E6E"
    }
}
