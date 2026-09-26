package dev.vibecheck

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jev", Context.MODE_PRIVATE)

    /** SharedPreferences holds listeners weakly: the caller keeps a strong reference. */
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v.trim()).apply()

    var context: String
        get() = sp.getString("ctx", "") ?: ""
        set(v) = sp.edit().putString("ctx", v).apply()

    var packages: String
        get() = sp.getString("pkgs", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(v) = sp.edit().putString("pkgs", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("on", true)
        set(v) = sp.edit().putBoolean("on", v).apply()

    /** Shows what the service actually reads off the screen. First thing to check when no card appears. */
    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = sp.edit().putBoolean("debug", v).apply()

    /** Learn from what happens on the next turn. */
    var learning: Boolean
        get() = sp.getBoolean("learn", true)
        set(v) = sp.edit().putBoolean("learn", v).apply()

    /** Keep per-person statistics from what is on screen, even when judging is off. */
    var passive: Boolean
        get() = sp.getBoolean("passive", true)
        set(v) = sp.edit().putBoolean("passive", v).apply()

    /** "zh" or "en", defaulting to the phone's language. Applied to L on every read so the service and the settings screen agree. */
    var lang: String
        get() = (sp.getString("lang", null) ?: if (Locale.getDefault().language == "zh") "zh" else "en")
            .also { L.en = it == "en" }
        set(v) { sp.edit().putString("lang", v).apply(); L.en = v == "en" }

    /** Which engine answers Jev's questions: Judge.TYPESAFE or Judge.OPENROUTER. */
    var judge: String
        get() = sp.getString("judge", Judge.TYPESAFE) ?: Judge.TYPESAFE
        set(v) = sp.edit().putString("judge", v).apply()

    /** OpenRouter: 深思 / 回复 / 学习此人, and judging when judge == OPENROUTER. */
    var orKey: String
        get() = sp.getString("orkey", "") ?: ""
        set(v) = sp.edit().putString("orkey", v.trim()).apply()

    var deepModel: String
        get() = sp.getString("deepmodel", DEFAULT_DEEP)?.ifBlank { DEFAULT_DEEP } ?: DEFAULT_DEEP
        set(v) = sp.edit().putString("deepmodel", v.trim()).apply()

    /** Used instead of deepModel when a screenshot is attached, so stickers are actually seen. */
    var visionModel: String
        get() = sp.getString("vismodel", DEFAULT_VISION)?.ifBlank { DEFAULT_VISION } ?: DEFAULT_VISION
        set(v) = sp.edit().putString("vismodel", v.trim()).apply()

    /**
     * Run the deep model automatically on turns Jev flags as non-routine. Costs roughly a tenth
     * of a cent each, only on the turns that matter, and is where the actual insight comes from.
     */
    var autoDeep: Boolean
        get() = sp.getBoolean("autodeep", true)
        set(v) = sp.edit().putBoolean("autodeep", v).apply()

    /** Read the screen with on-device OCR when the app hides its accessibility tree (WeChat does). */
    var ocr: Boolean
        get() = sp.getBoolean("ocr", true)
        set(v) = sp.edit().putBoolean("ocr", v).apply()

    /** A short vibration when a turn comes back high-risk, so it is noticed without looking at the bubble. */
    var buzz: Boolean
        get() = sp.getBoolean("buzz", false)
        set(v) = sp.edit().putBoolean("buzz", v).apply()

    /** Card text size: 0 small, 1 normal, 2 large. */
    var cardSize: Int
        get() = sp.getInt("cardsize", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("cardsize", v.coerceIn(0, 2)).apply()

    val cardScale: Float get() = when (cardSize) { 0 -> 0.9f; 2 -> 1.2f; else -> 1f }

    /** Where the user dragged the bubble: the side (true = left) and the height as a share of the screen, or -1 for "next to the newest message". */
    var bubbleLeft: Boolean
        get() = sp.getBoolean("bubbleleft", false)
        set(v) = sp.edit().putBoolean("bubbleleft", v).apply()

    var bubbleY: Float
        get() = sp.getFloat("bubbley", -1f)
        set(v) = sp.edit().putFloat("bubbley", v).apply()

    /** Tiles the user hid from the Tools tab. They stay one tap away at the bottom of it. */
    var hiddenTools: Set<String>
        get() = sp.getStringSet("hiddentools", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hiddentools", HashSet(v)).apply()

    /** Card buttons the user does not want: "think", "reply", "learn", "menu". */
    var hiddenButtons: Set<String>
        get() = sp.getStringSet("hiddenbuttons", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hiddenbuttons", HashSet(v)).apply()

    /** The settings screen reopens on the tab it was left on. */
    var tab: Int
        get() = sp.getInt("tab", 0)
        set(v) = sp.edit().putInt("tab", v).apply()

    /** Judging is paused everywhere until this time (epoch ms). */
    var snoozeUntil: Long
        get() = sp.getLong("snooze", 0L)
        set(v) = sp.edit().putLong("snooze", v).apply()

    val snoozed: Boolean get() = snoozeUntil > System.currentTimeMillis()

    /** LAN troubleshooting server. Off by default: everything it serves is chat content. */
    var remoteDiag: Boolean
        get() = sp.getBoolean("diag", false)
        set(v) = sp.edit().putBoolean("diag", v).apply()

    /** Generated once, required by every debug-server route. */
    val diagToken: String
        get() = sp.getString("token", null) ?: newToken()

    fun newToken(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString("token", t).apply()
        return t
    }

    /** Tells the running service that something just worked (a Test in settings): retry failed calls now. */
    fun kick() = sp.edit().putLong("kick", System.currentTimeMillis()).apply()

    val packageList: Array<String>
        get() = packages.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

    // ---- what the API calls add up to ----

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Counts one paid call of this kind, today and in total. Safe from any thread. */
    @Synchronized fun countUse(kind: String) {
        val day = today()
        val e = sp.edit()
        if (sp.getString("use:day", "") != day) {
            for (k in USES) e.putInt("use:today:$k", 0)
            e.putString("use:day", day)
            e.putInt("use:today:$kind", 1)
        } else {
            e.putInt("use:today:$kind", sp.getInt("use:today:$kind", 0) + 1)
        }
        e.putInt("use:total:$kind", sp.getInt("use:total:$kind", 0) + 1).apply()
    }

    fun usedToday(kind: String): Int = if (sp.getString("use:day", "") == today()) sp.getInt("use:today:$kind", 0) else 0
    fun usedTotal(kind: String): Int = sp.getInt("use:total:$kind", 0)

    companion object {
        const val DEFAULT_PACKAGES = "com.tencent.mm,cn.soulapp.android,com.facebook.orca," +
            "com.google.android.apps.messaging,com.whatsapp,org.telegram.messenger,com.instagram.android"
        const val DEFAULT_DEEP = "deepseek/deepseek-v4-pro"
        const val DEFAULT_VISION = "deepseek/deepseek-v4-flash-vision-exp"

        const val USE_JUDGE = "judge"
        const val USE_DEEP = "deep"
        const val USE_REPLY = "reply"
        const val USE_BIO = "bio"
        val USES = listOf(USE_JUDGE, USE_DEEP, USE_REPLY, USE_BIO)
    }
}
