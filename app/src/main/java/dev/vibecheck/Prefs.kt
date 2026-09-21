package dev.vibecheck

import android.content.Context
import java.security.SecureRandom

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jev", Context.MODE_PRIVATE)

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

    /** OpenRouter, used only for the on-demand 深思 / 回复 buttons. */
    var orKey: String
        get() = sp.getString("orkey", "") ?: ""
        set(v) = sp.edit().putString("orkey", v.trim()).apply()

    var deepModel: String
        get() = sp.getString("deepmodel", DEFAULT_DEEP) ?: DEFAULT_DEEP
        set(v) = sp.edit().putString("deepmodel", v.trim()).apply()

    /** Used instead of deepModel when a screenshot is attached, so stickers are actually seen. */
    var visionModel: String
        get() = sp.getString("vismodel", DEFAULT_VISION) ?: DEFAULT_VISION
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

    val packageList: Array<String>
        get() = packages.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

    companion object {
        const val DEFAULT_PACKAGES = "com.tencent.mm,cn.soulapp.android,com.facebook.orca," +
            "com.google.android.apps.messaging,com.whatsapp,org.telegram.messenger,com.instagram.android"
        const val DEFAULT_DEEP = "deepseek/deepseek-v4-pro"
        const val DEFAULT_VISION = "deepseek/deepseek-v4-flash-vision-exp"
    }
}
