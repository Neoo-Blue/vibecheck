package dev.vibecheck

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jev", Context.MODE_PRIVATE)

    init { forgetStoredDefaults(); autoDeepOffOnce() }

    /**
     * The automatic deep read was on by default and ran a paid call on every turn that mattered,
     * unasked; the card with Jev's read and the drafts is what is wanted. Off from 6.8.7, once
     * for everyone; it can be switched back on in Setup.
     */
    private fun autoDeepOffOnce() {
        if (sp.getInt("autodeepv", 0) >= 2) return
        sp.edit().putBoolean("autodeep", false).putInt("autodeepv", 2).apply()
    }

    /**
     * Settings pages before 6.7.4 stored the default model as if it had been chosen, so a newer
     * default never reached anyone who had opened them. Those are cleared once; from now on a
     * default is stored as nothing and follows the app.
     */
    private fun forgetStoredDefaults() {
        if (sp.getInt("modelsv", 0) >= 2) return
        val e = sp.edit()
        if (wasDefault(sp.getString("vismodel", "") ?: "", OLD_FAST)) e.putString("vismodel", "")
        if (wasDefault(sp.getString("deepmodel", "") ?: "", OLD_DEEP)) e.putString("deepmodel", "")
        e.putInt("modelsv", 2).apply()
    }

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

    /**
     * Every how many days the profile of someone learned is written again by itself, from what
     * their kept history has gained since (Learning.keepUpDue): one of [KEEP_UP_CHOICES], 0 for
     * only when Learn is tapped.
     */
    var keepUpDays: Int
        get() = sp.getInt("keepupdays", 3)
        set(v) = sp.edit().putInt("keepupdays", v).apply()

    /**
     * Learn about me across chats: keep a day log of what is said live, write each day up, and a
     * profile of me that reply drafts follow. The log stays on the phone.
     */
    var aboutMe: Boolean
        get() = sp.getBoolean("aboutme", true)
        set(v) = sp.edit().putBoolean("aboutme", v).apply()

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

    /** Deep reads and profiles: depth over speed. The default is stored as nothing. */
    var deepModel: String
        get() = sp.getString("deepmodel", "")?.trim()?.ifBlank { null } ?: DEFAULT_DEEP
        set(v) = sp.edit().putString("deepmodel", stored(v, DEFAULT_DEEP)).apply()

    /**
     * Reply drafts, and every call with a screenshot attached (so stickers are actually seen):
     * it has to write well, quickly, and read images. Deep reads and profiles use [deepModel].
     */
    var fastModel: String
        get() = fastOrDefault(sp.getString("vismodel", "") ?: "")
        set(v) = sp.edit().putString("vismodel", stored(v, DEFAULT_FAST)).apply()

    /**
     * Reply drafts may be adult when the chat already is, between two adults (18+). Off unless
     * switched on: the drafts follow the other person's lead and stop at a no.
     */
    var adult: Boolean
        get() = sp.getBoolean("adult", false)
        set(v) = sp.edit().putBoolean("adult", v).apply()

    /**
     * Run the deep model automatically on turns Jev flags as non-routine, without Think being
     * tapped. Off by default: each is a paid call nobody asked for.
     */
    var autoDeep: Boolean
        get() = sp.getBoolean("autodeep", false)
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

    private fun dayStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * A new day: yesterday's counts for today are cleared in [e]. True when they were, so what is
     * counted next starts from zero rather than from yesterday's figures still in the store.
     */
    private fun newDay(e: SharedPreferences.Editor): Boolean {
        val day = dayStamp()
        if (sp.getString("use:day", "") == day) return false
        for (k in USES + USE_OTHER) {
            e.remove("use:today:$k")
            for (f in SPENT) e.remove("use:today:$f:$k")
        }
        e.putString("use:day", day)
        return true
    }

    /** Counts one paid call of this kind, today and in total. Safe from any thread. */
    fun countUse(kind: String) = synchronized(USE_LOCK) {
        val e = sp.edit()
        val today = if (newDay(e)) 0 else sp.getInt("use:today:$kind", 0)
        e.putInt("use:today:$kind", today + 1)
        e.putInt("use:total:$kind", sp.getInt("use:total:$kind", 0) + 1).apply()
    }

    /**
     * What one call used (OpenRouter's own count), added to its kind today and in total: tokens
     * in, of those how many the provider had cached, tokens out, of those how many were thinking,
     * and the cost. Safe from any thread.
     */
    fun addUsage(kind: String, u: OpenRouter.Usage) = synchronized(USE_LOCK) {
        val e = sp.edit()
        val fresh = newDay(e)
        val add = mapOf("in" to u.prompt.toLong(), "cached" to u.cached.toLong(), "out" to u.completion.toLong(),
            "think" to u.reasoning.toLong(), "micro" to Math.round(u.cost * 1_000_000))
        for ((f, n) in add) {
            val today = "use:today:$f:$kind"
            val total = "use:total:$f:$kind"
            e.putLong(today, (if (fresh) 0L else sp.getLong(today, 0L)) + n)
            e.putLong(total, sp.getLong(total, 0L) + n)
        }
        e.apply()
    }

    /** What calls of [kind] have used, [today] or in total. */
    fun spent(kind: String, today: Boolean): Spent {
        val scope = if (today) "today" else "total"
        if (today && sp.getString("use:day", "") != dayStamp()) return Spent(0, 0, 0, 0, 0, 0.0)
        fun long(f: String) = sp.getLong("use:$scope:$f:$kind", 0L)
        return Spent(sp.getInt("use:$scope:$kind", 0), long("in"), long("cached"), long("out"), long("think"), long("micro") / 1_000_000.0)
    }

    /** Calls, tokens in (of which cached), tokens out (of which thinking), and dollars. */
    data class Spent(val calls: Int, val prompt: Long, val cached: Long, val completion: Long, val reasoning: Long, val cost: Double) {
        val tokens: Long get() = prompt + completion
        operator fun plus(o: Spent) = Spent(calls + o.calls, prompt + o.prompt, cached + o.cached,
            completion + o.completion, reasoning + o.reasoning, cost + o.cost)
    }

    companion object {
        const val DEFAULT_PACKAGES = "com.tencent.mm,cn.soulapp.android,com.facebook.orca," +
            "com.google.android.apps.messaging,com.whatsapp,org.telegram.messenger,com.instagram.android"
        const val DEFAULT_DEEP = "deepseek/deepseek-v4-pro"
        /**
         * Kimi K2.6: the best creative writer among open-weight models (EQ-Bench Creative Writing),
         * Chinese first, reads images, about 70 tokens a second, served by many providers.
         */
        const val DEFAULT_FAST = "moonshotai/kimi-k2.6"
        /** A model no longer served, which counts as no choice. */
        private val RETIRED_FAST = setOf("deepseek/deepseek-v4-flash-vision-exp")
        /** What earlier versions used as defaults, and their settings page stored as if chosen. */
        private val OLD_FAST = setOf("deepseek/deepseek-v4-flash-vision-exp", "deepseek/deepseek-v4.1-flash")
        private val OLD_DEEP = setOf("deepseek/deepseek-v4-pro")

        fun fastOrDefault(stored: String): String =
            stored.trim().takeIf { it.isNotEmpty() && it !in RETIRED_FAST } ?: DEFAULT_FAST

        /** A stored model that an earlier version put there as its default. */
        fun wasDefault(stored: String, old: Set<String>): Boolean = stored.trim() in old

        /** What to store for a chosen model: nothing for the default, so a newer default reaches it. */
        fun stored(chosen: String, default: String): String = chosen.trim().takeIf { it != default }.orEmpty()

        /** What [keepUpDays] can be: off, daily, every three days, weekly. */
        val KEEP_UP_CHOICES = listOf(0, 1, 3, 7)

        const val USE_JUDGE = "judge"
        const val USE_DEEP = "deep"
        const val USE_REPLY = "reply"
        const val USE_BIO = "bio"
        /** The profile of me and the write-ups of my days, counted with profiles before 6.8.8. */
        const val USE_ME = "me"
        /** A Test in settings, and anything else not one of the above. */
        const val USE_OTHER = "other"
        val USES = listOf(USE_JUDGE, USE_DEEP, USE_REPLY, USE_BIO, USE_ME)
        /** What is added up per call: tokens in, cached, out, thinking, and millionths of a dollar. */
        private val SPENT = listOf("in", "cached", "out", "think", "micro")
        /** One lock for every Prefs: the service and the settings page each have their own, over one store. */
        private val USE_LOCK = Any()

        /** 850, 18k, 25.3k, 125k, 1.25M. */
        fun tokens(n: Long): String = when {
            n < 1_000 -> "$n"
            n < 100_000 -> String.format(Locale.US, "%.1f", n / 1_000.0).removeSuffix(".0") + "k"
            n < 1_000_000 -> "${Math.round(n / 1_000.0)}k"
            else -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
        }

        /** $0.0042, $0.123, $3.40; a trace below a hundredth of a cent as <$0.0001. */
        fun dollars(d: Double): String = when {
            d > 0 && d < 0.0001 -> "<$0.0001"
            d < 0.01 -> String.format(Locale.US, "$%.4f", d)
            d < 1 -> String.format(Locale.US, "$%.3f", d)
            else -> String.format(Locale.US, "$%.2f", d)
        }

        /** "5 次 · 21.4k token · $0.012", tokens and cost only once any were counted. */
        fun describe(s: Spent): String {
            val calls = L.t("${s.calls} 次", "${s.calls} calls")
            if (s.tokens == 0L) return calls
            // Tests in settings are not counted as calls, only what they used.
            return (if (s.calls > 0) "$calls · " else "") + "${tokens(s.tokens)} token" + (if (s.cost > 0) " · ${dollars(s.cost)}" else "")
        }

        /**
         * Where the tokens went: how much of what was sent the provider had cached (billed at a
         * fraction), and how much of what came back was the model thinking. Null before any.
         */
        fun breakdown(s: Spent): String? {
            if (s.tokens == 0L) return null
            val cached = if (s.prompt > 0) Math.round(s.cached * 100.0 / s.prompt) else 0L
            val think = if (s.completion > 0) Math.round(s.reasoning * 100.0 / s.completion) else 0L
            return L.t("发出 ${tokens(s.prompt)}（$cached% 命中缓存）· 收回 ${tokens(s.completion)}（$think% 是思考）",
                "Sent ${tokens(s.prompt)} ($cached% cached) · received ${tokens(s.completion)} ($think% thinking)")
        }
    }
}
