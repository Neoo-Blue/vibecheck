package dev.vibecheck

import kotlin.math.abs

/**
 * Whose message a text is, from what is drawn around it rather than from the text alone.
 *
 * A message that fills a line reads the same from both sides by its text: in WeChat the widest
 * bubble of theirs and the widest of mine leave the same margins, and the right end of the text is
 * ragged, so my long messages were read as theirs. Beside the text there is more to go on: the
 * avatar next to the bubble, or in apps without avatars the bubble itself, hugs the sender's edge
 * of the window. Pure (pixels or boxes in, a side out) so it can be tested on the JVM.
 */
object Sides {

    const val UNKNOWN = 0
    const val LEFT = -1
    const val RIGHT = 1

    /**
     * Which edge one row of pixels through a message is drawn against. [row] runs across the
     * window and [from]..[to] is the text on it. Going in from each edge, past a scrollbar's width,
     * the first pixel that is not the background is where the avatar or the bubble begins: the
     * side where that is clearly nearer is the sender's. [dp] is pixels per dp.
     */
    fun rowSide(row: IntArray, from: Int, to: Int, dp: Float): Int {
        val skip = (EDGE_DP * dp).toInt().coerceAtLeast(2)
        val last = row.size - 1
        if (from - skip < 2 || last - to - skip < 2) return UNKNOWN
        val left = ink(row, skip, from, 1) ?: return UNKNOWN
        val right = ink(row, last - skip, to, -1) ?: return UNKNOWN
        val margin = MARGIN_DP * dp
        return when {
            left + margin <= right -> LEFT
            right + margin <= left -> RIGHT
            else -> UNKNOWN
        }
    }

    /**
     * How far in from [start], going by [dir] toward [stop], the first pixel that is not the
     * background is: the distance to [stop] when there is none. Null when the strip between the
     * window's edge and [start] is not one flat colour (a wallpaper, a scrollbar), which leaves no
     * background to go by.
     */
    private fun ink(row: IntArray, start: Int, stop: Int, dir: Int): Int? {
        val bg = row[start]
        var x = if (dir > 0) 1 else row.size - 2
        while (x != start) {
            if (differs(row[x], bg)) return null
            x += dir
        }
        while (x != stop) {
            if (differs(row[x], bg)) return abs(x - start)
            x += dir
        }
        return abs(stop - start)
    }

    private fun differs(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > TOLERANCE ||
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > TOLERANCE ||
            abs((a and 0xFF) - (b and 0xFF)) > TOLERANCE

    /**
     * The side of a message from several rows through it (see [rowSide]), taken where an avatar
     * sits: level with its first line, or its last where an app puts the avatar at the bottom.
     * Rows beside no avatar usually say nothing; any that disagree leave it [UNKNOWN].
     */
    fun blockSide(rows: List<IntArray>, from: Int, to: Int, dp: Float): Int {
        var left = 0
        var right = 0
        for (row in rows) when (rowSide(row, from, to, dp)) {
            LEFT -> left++
            RIGHT -> right++
        }
        return when {
            left > 0 && right == 0 -> LEFT
            right > 0 && left == 0 -> RIGHT
            else -> UNKNOWN
        }
    }

    /**
     * The rows [blockSide] reads for a text from [top] to [bottom]: a few dp in from its first and
     * last lines, inside the text and the picture [0, [height]).
     */
    fun rowsFor(top: Int, bottom: Int, height: Int, dp: Float): List<Int> =
        listOf(top + 3 * dp, top + 10 * dp, bottom - 10 * dp, bottom - 3 * dp)
            .map { it.toInt() }
            .filter { it >= top && it < bottom && it >= 0 && it < height }
            .distinct()

    /**
     * An avatar in the node tree: a square picture, the size of an avatar, along an edge of the
     * window below the title bar. WeChat labels its avatars "…头像".
     */
    fun isAvatar(box: Chat.Box, win: Chat.Box, dp: Float): Boolean {
        val w = box.width
        val h = box.bottom - box.top
        if (w <= 0 || h <= 0 || abs(w - h) > maxOf(w, h) / 5) return false
        if (w < 20 * dp || w > 64 * dp) return false
        if (box.top < win.top + (win.bottom - win.top) * 0.07) return false
        val inset = minOf(box.left - win.left, win.right - box.right)
        return inset >= 0 && inset <= 28 * dp
    }

    /**
     * The side of a message from the avatars in the node tree: one beside the text and level with
     * some of it. Pictures on both sides, or none, leave it [UNKNOWN].
     */
    fun byAvatars(text: Chat.Box, avatars: List<Chat.Box>): Int {
        var left = false
        var right = false
        for (a in avatars) {
            if (a.top >= text.bottom || text.top >= a.bottom) continue
            if (a.right <= text.left + 2) left = true
            else if (a.left >= text.right - 2) right = true
        }
        return when {
            left && !right -> LEFT
            right && !left -> RIGHT
            else -> UNKNOWN
        }
    }

    /** A scrollbar or rounded screen corner fits in this much of either edge. */
    private const val EDGE_DP = 5f
    /** How much nearer one edge the avatar or bubble must be. WeChat's avatar sits 12dp in, the widest bubble about 58dp. */
    private const val MARGIN_DP = 12f
    /** Screen captures are exact; this only allows for the odd colour-managed rounding. */
    private const val TOLERANCE = 6
}
