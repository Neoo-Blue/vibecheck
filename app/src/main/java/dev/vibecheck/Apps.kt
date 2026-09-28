package dev.vibecheck

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Chat apps the overlay knows by name. Bubble detection is geometry plus text, so any app with
 * a left/right conversation works; this list only feeds the settings screen and labels.
 * Discord and Slack are left out on purpose: every message sits on the left there, so the
 * geometry cannot tell mine from theirs. Telegram draws bubbles on canvas and reads via OCR.
 */
object Apps {
    val KNOWN: List<Pair<String, String>> = listOf(
        "com.tencent.mm" to "WeChat 微信",
        "cn.soulapp.android" to "Soul",
        "com.facebook.orca" to "Messenger",
        "com.google.android.apps.messaging" to "Google Messages (SMS / RCS)",
        "com.samsung.android.messaging" to "Samsung Messages (SMS / RCS)",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.instagram.android" to "Instagram",
        "jp.naver.line.android" to "LINE",
        "org.thoughtcrime.securesms" to "Signal",
        "com.snapchat.android" to "Snapchat",
        "com.tencent.mobileqq" to "QQ",
        "com.kakao.talk" to "KakaoTalk",
        "com.viber.voip" to "Viber",
        "com.twitter.android" to "X",
        "com.microsoft.teams" to "Teams",
    )

    fun label(pkg: String): String = KNOWN.firstOrNull { it.first == pkg }?.second ?: pkg.substringAfterLast('.')

    /** A window on screen, as far as telling which app is in front goes; its app is asked for only when needed. */
    class Window(val type: Int, val layer: Int, val area: Long, app: () -> String?) {
        val app: String? by lazy(app)
    }

    /**
     * Is one of [apps] what the user is looking at, on a screen of [screen] pixels? The top window
     * by layer decides, not counting the keyboard, an accessibility overlay (our own card), a
     * split-screen divider or magnification, nor another app's small panel floating over it
     * without replacing it (a heads-up notification, the volume slider, a picture-in-picture video).
     */
    fun inFront(windows: List<Window>, screen: Long, apps: Set<String>): Boolean {
        val top = windows.sortedByDescending { it.layer }.firstOrNull { w ->
            w.type !in PASSED_OVER && (w.area > screen * 0.4 || w.app in apps)
        } ?: return false
        return top.type == AccessibilityWindowInfo.TYPE_APPLICATION && top.app in apps
    }

    private val PASSED_OVER = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
    )
}
