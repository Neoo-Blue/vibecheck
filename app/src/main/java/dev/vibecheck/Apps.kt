package dev.vibecheck

/**
 * Chat apps the overlay knows by name. Bubble detection is geometry plus text, so any app with
 * a left/right conversation works; this list only feeds the settings screen and labels.
 * Discord and Slack are left out on purpose: every message sits on the left there, so the
 * geometry cannot tell mine from theirs. Telegram draws bubbles on canvas and reads via OCR.
 */
object Apps {
    val KNOWN: List<Pair<String, String>> = listOf(
        "com.tencent.mm" to "微信",
        "cn.soulapp.android" to "Soul",
        "com.facebook.orca" to "Messenger",
        "com.google.android.apps.messaging" to "Google Messages（短信 / RCS）",
        "com.samsung.android.messaging" to "三星信息（短信 / RCS）",
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
}
