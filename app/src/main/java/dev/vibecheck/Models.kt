package dev.vibecheck

/**
 * The OpenRouter models offered on the settings page, with what each is good at. Any other
 * OpenRouter id can still be typed in.
 *
 * Replies want Chinese that reads like a person wrote it, quickly. Open-weight models are served
 * by many providers, so the 18+ switch works with them; Qwen goes through Alibaba's own API,
 * which filters content.
 */
object Models {

    class Option(val id: String, val name: String, private val zh: String, private val en: String) {
        val about: String get() = L.t(zh, en)
    }

    val REPLY: List<Option> = listOf(
        Option(Prefs.DEFAULT_FAST, "Kimi K2.6",
            "默认。开源模型里创意写作第一，中文母语；每秒约 70 字，便宜。能看截图，能写成人内容。",
            "Default. The best creative writer among open-weight models, Chinese first; about 70 tokens a second, cheap. Reads screenshots; can write adult replies."),
        Option("moonshotai/kimi-k3", "Kimi K3",
            "写得最好：创意写作榜全球第二、国产第一。慢一些，贵约三倍。能看截图，能写成人内容。",
            "Writes best: #2 in the world on EQ-Bench Creative Writing. Slower, about three times the price. Reads screenshots; can write adult replies."),
        Option("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash",
            "最快最省，文笔普通。能看截图，能写成人内容。",
            "Fastest and cheapest; plain writing. Reads screenshots; can write adult replies."),
        Option("qwen/qwen3.8-max-0902", "通义千问 3.8 Max",
            "中文语感最自然，贵一些。阿里的接口会过滤内容，不写成人回复。",
            "The most natural Chinese; costs more. Alibaba's API filters content, so no adult replies."),
    )

    val DEEP: List<Option> = listOf(
        Option(Prefs.DEFAULT_DEEP, "DeepSeek V4 Pro",
            "默认。分析扎实，便宜。",
            "Default. Solid analysis, cheap."),
        Option("moonshotai/kimi-k3", "Kimi K3",
            "更强、更细，慢一些、贵一些。",
            "Stronger and finer, slower and dearer."),
        Option("qwen/qwen3.8-max-0902", "通义千问 3.8 Max",
            "中文理解很好；成人话题会被过滤。",
            "Understands Chinese very well; adult topics are filtered."),
    )

    /** The option a model id is, or null for one typed in by hand. */
    fun option(list: List<Option>, id: String): Option? = list.firstOrNull { it.id == id.trim() }

    /** "Kimi K2.6" for a model offered here, the id itself for one typed in. */
    fun nameOf(id: String): String = option(REPLY + DEEP, id)?.name ?: id.trim()

    /** Asked when both models set are the same one and it fails: quick, cheap, and it writes adult replies too. */
    const val FALLBACK = "deepseek/deepseek-v4.1-flash"

    /**
     * The model to ask when [primary] fails: the other one set in Setup (replies and Think stand in
     * for each other), or when both are the same, [FALLBACK], or the default reply model when
     * that is the one failing. Null only when there is nothing else left to try.
     */
    fun backup(primary: String, fast: String, deep: String): String? {
        val p = primary.trim()
        val other = (if (p == fast.trim()) deep else fast).trim()
        return when {
            other.isNotEmpty() && other != p -> other
            p != FALLBACK -> FALLBACK
            p != Prefs.DEFAULT_FAST -> Prefs.DEFAULT_FAST
            else -> null
        }
    }
}
