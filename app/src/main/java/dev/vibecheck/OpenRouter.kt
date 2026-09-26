package dev.vibecheck

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand deep reasoning through OpenRouter. Jev answers every turn; this runs only when you
 * press a button, because it costs money and takes seconds rather than milliseconds.
 *
 * When the transcript came from OCR the screenshot is attached and a vision model is used, so
 * the stickers and emoji that OCR cannot read are still part of the analysis.
 */
object OpenRouter {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /** [status] is the HTTP status, or the error code OpenRouter put in a 200 body; 0 when unknown. */
    class Failure(val status: Int, message: String) : Exception(message)

    /** Blocking. Call from a background thread. imageJpegBase64 null means text-only. */
    fun chat(apiKey: String, model: String, system: String, user: String, imageJpegBase64: String?): String {
        val content = if (imageJpegBase64 == null) JSONObject().put("role", "user").put("content", user)
        else JSONObject().put("role", "user").put(
            "content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", user))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageJpegBase64")
                    )
                )
        )

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.7)
            .put("max_tokens", 2500)
            // The output is three short lines. Left to itself the reasoning model spent 18s and
            // its whole budget thinking (one run looped on a homophone), then had nothing to say.
            .put("reasoning", JSONObject().put("effort", "low"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(content)
            ).toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Vibecheck")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) throw Failure(code, "HTTP $code: ${text.take(160)}")
            val root = JSONObject(text)
            val choices = root.optJSONArray("choices")?.takeIf { it.length() > 0 }
                ?: root.optJSONObject("error").let { err ->
                    throw Failure(err?.optInt("code") ?: 0, err?.optString("message") ?: "no choices: ${text.take(160)}")
                }
            val choice = choices.getJSONObject(0)
            val msg = choice.getJSONObject("message")
            // optString() on a JSON null returns the literal "null" on Android, which is how a
            // truncated answer ended up printed on the card as the word null.
            val content = if (msg.isNull("content")) "" else msg.optString("content").trim()
            if (content.isNotEmpty()) return content
            // Never fall back to the reasoning field: that is the model's private scratchpad, and
            // showing it produced a card full of "但用户要求…" deliberation instead of replies.
            throw Failure(0, L.t("模型没写完，再点一次", "The model stopped before answering; try again"))
        } finally {
            conn.disconnect()
        }
    }

    // ---- prompts ----

    val DEEP_SYSTEM: String get() = L.t(
        "你是一个懂中文聊天潜台词的分析者。只说有用的话，不寒暄，不复述原文，不写免责声明。" +
            "输出三行，每行以「•」开头：第一行说对方真正在意什么，第二行说这一步最容易踩的坑，" +
            "第三行给一个具体到可以照做的下一步。每行不超过 40 个字。",
        "You read the subtext of chat messages. Only say what is useful: no pleasantries, no quoting the " +
            "messages back, no disclaimers. Output three lines, each starting with \"•\": what they actually " +
            "care about; the trap in this step; one next move concrete enough to do as written. Max 20 words each.")

    val REPLY_SYSTEM: String get() = L.t(
        "你替用户起草回复，用这段对话本身的语言（对话是英文就写英文）。必须贴着用户平时的说话方式：长度、语气、标点、是否用表情都要像他本人。" +
            "给三条不同策略的候选：第一条先接住情绪，第二条给具体方案，第三条轻松化解。" +
            "每条单独一行，用「1. 」「2. 」「3. 」开头，每条不超过 30 个字，不要解释。",
        "You draft replies for the user, in the language the conversation is in. Match how the user " +
            "actually writes: length, tone, punctuation, whether they use emoji. Give three candidates with " +
            "different strategies: 1 catches the feeling first, 2 gives a concrete plan, 3 defuses it lightly. " +
            "One per line, starting \"1. \" \"2. \" \"3. \", max 20 words each, no explanations.")

    val BIO_SYSTEM: String get() = L.t(
        "你在读一段完整的聊天记录，写一份关于「对方」的档案给「我」以后用。只写记录里能看出来的，不编。" +
            "四到六行，每行以「•」开头，各不超过 40 字：Ta 是我的什么人（关系与亲疏）；我们常聊什么；" +
            "Ta 的说话风格（长短、语气、口头禅、用不用表情）；我对 Ta 的说话风格；" +
            "反复出现的事或雷区；Ta 在乎什么。",
        "You are reading a complete chat history to write a profile of \"them\" for \"me\" to use later. " +
            "Only what the history shows; invent nothing. Four to six lines, each starting with \"•\", max 20 words: " +
            "who they are to me (relationship, closeness); what we usually talk about; how they write (length, tone, " +
            "catchphrases, emoji); how I write to them; recurring topics or sore spots; what they care about.")

    /** "1. ", "1.你好", "1．", "2、", "3) ", "- ", "• ": a numbered or bulleted line, but not "1.5 hours works". */
    private val DRAFT_PREFIX = Regex("""^\s*(\d{1,2}([.．](?!\d)|[、)）])\s*|[-•*·]\s+)""")

    /**
     * The reply drafts in a model answer, ready to paste: without the numbering and without the
     * quotes around them. When the answer has numbered lines, anything else ("Here are three
     * options:") is dropped instead of becoming a draft of its own.
     */
    fun drafts(answer: String): List<String> {
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val numbered = lines.filter { DRAFT_PREFIX.containsMatchIn(it) }
        return (if (numbered.size >= 2) numbered else lines).map { draftText(it) }.filter { it.isNotEmpty() }
    }

    fun draftText(line: String): String {
        var s = line.replaceFirst(DRAFT_PREFIX, "").trim()
        for ((open, close) in listOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')) {
            if (s.length >= 2 && s.first() == open && s.last() == close) s = s.substring(1, s.length - 1).trim()
        }
        return s.ifEmpty { line.trim() }
    }

    /** A history read, oldest first, trimmed to what the model needs. */
    fun bioPrompt(peer: String, history: List<Pair<String, String>>, maxChars: Int = 6000): String {
        val sb = StringBuilder(L.t("对方：", "Them: ")).append(peer)
            .append(L.t("\n共 ${history.size} 条，从旧到新：\n", "\n${history.size} messages, oldest first:\n"))
        // Keep the newest part when it does not all fit: recent history says more about now.
        // One line per message, so a pasted article cannot eat the whole budget or split the
        // speaker prefix off its own second line.
        val lines = history.map { (who, text) -> "${L.who(who)}${L.t("：", ": ")}${text.replace('\n', ' ').take(300)}" }
        var total = 0
        val kept = ArrayList<String>()
        for (l in lines.asReversed()) {
            if (total + l.length > maxChars) break
            kept.add(l); total += l.length + 1
        }
        kept.asReversed().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    /** Everything the deep model needs: the same state Jev saw, plus what Jev concluded. */
    fun deepPrompt(
        peer: String,
        note: String,
        style: String?,
        history: String?,
        relation: String?,
        transcript: List<Pair<String, String>>,
        answers: Map<String, Jev.Answer>,
        fromOcr: Boolean,
        hasImage: Boolean,
        situation: String? = null,
    ): String = buildString {
        append(L.t("对方：", "Them: ")).append(peer).append('\n')
        if (note.isNotBlank()) append(L.t("关系背景：", "Context: ")).append(note).append('\n')
        style?.let { append(L.t("我平时的说话方式：", "How I usually write: ")).append(it).append('\n') }
        history?.let { append(L.t("我们最近几轮的走向：", "Where the last few turns went: ")).append(it).append('\n') }
        relation?.let { append(L.t("这段关系的长期观察：", "Long-term observations: ")).append(it).append('\n') }
        append(L.t("\n最近的对话（从上到下）：\n", "\nRecent messages (top to bottom):\n"))
        transcript.forEach { (who, text) -> append(L.who(who)).append(L.t("：", ": ")).append(text).append('\n') }
        append(L.t("\n小模型对最后一条的判断：\n", "\nThe judgment model's read of the last message:\n"))
        for ((id, header) in Jev.headers(situation)) {
            when (val a = answers[id] ?: continue) {
                is Jev.Answer.Noul -> append("• ").append(L.label(header)).append(L.t("：", ": ")).append(Jev.pct(a.p)).append('\n')
                is Jev.Answer.Dist -> append("• ").append(L.label(header)).append(L.t("：", ": ")).append(L.label(a.top))
                    .append(L.t("（", " (")).append(Jev.pct(a.probs[a.top] ?: 0.0)).append(L.t("）\n", ")\n"))
                is Jev.Answer.Scored -> append("• ").append(L.label(header)).append(L.t("：", ": "))
                    .append(Jev.shownLevel(a)).append(" / ").append(a.levels)
                    .append(Jev.levelName(id, a)?.let { L.t("（${L.label(it)}）", " (${L.label(it)})") } ?: "")
                    .append('\n')
            }
        }
        if (fromOcr) {
            append(L.t("\n注意：这些文字是从手机屏幕识别出来的，表情符号和表情包图片没有被识别出来。",
                "\nNote: this text was OCR'd from the screen; emoji and stickers were not captured."))
            if (hasImage) append(L.t("随附的截图是完整画面，表情包和表情以截图为准。",
                " The attached screenshot is the full picture; trust it for stickers and emoji."))
        }
    }
}
