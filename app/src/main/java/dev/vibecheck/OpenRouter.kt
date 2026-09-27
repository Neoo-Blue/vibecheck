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

    /**
     * Blocking. Call from a background thread. imageJpegBase64 null means text-only. A profile
     * from a long history needs more room and time than a three-line read: [maxTokens] and
     * [timeoutMs].
     */
    fun chat(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        imageJpegBase64: String?,
        maxTokens: Int = 2500,
        timeoutMs: Int = 60_000,
        temperature: Double = 0.7,
    ): String {
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
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
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
            readTimeout = timeoutMs
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
        "你帮我（对话里的「我」）看懂这段聊天的潜台词，话是说给我听的：称我为「你」，称对方为「Ta」或 Ta 的名字。" +
            "只说有用的话，不寒暄，不复述原文，不写免责声明。" +
            "有 Ta 的档案就用上：Ta 的性格、喜好、雷区、我们之间的梗，说只对这个人成立的话，别说放在谁身上都对的套话。" +
            "输出三行，每行以「•」开头：第一行说 Ta 此刻真正在意或想要什么（说的是 Ta，不是我）；第二行说我这一步最容易踩的坑；" +
            "第三行给我一个具体到可以照做的下一步，最后一句是我说的、Ta 还没回时，说清该等还是该补一句、补什么。每行不超过 40 个字。",
        "You help me (\"Me\" in the chat) read the subtext of this conversation, and you are talking to me: call me " +
            "\"you\" and the other person \"they\" or their name. Only say what is useful: no pleasantries, no quoting the " +
            "messages back, no disclaimers. If there is a profile of them, use it: their character, likes, sore spots, " +
            "our running jokes. Say what is true of this person, not what would fit anyone. Output three lines, each " +
            "starting with \"•\": what they actually care about or want right now (them, not me); the trap for me in this " +
            "step; one next move for me, concrete enough to do as written. When the last message is mine and they have " +
            "not answered, say whether to wait or add something, and what. Max 20 words each.")

    /**
     * Drafts of my next message. The old prompt asked for the same three strategies every time
     * (catch the feeling, a concrete plan, defuse it), in thirty characters: for a flirt that had
     * gone quiet it offered 「下午好」「歇会」「嗯」. Now the model first says in one line what
     * the moment is ([replies] shows that line above the drafts), knows whose turn it is, and
     * writes three different things I would actually send.
     */
    val REPLY_SYSTEM: String get() = L.t(
        "你帮我（对话里的「我」）想下一条要发的消息。先看清局面再写：最后一句是谁说的，Ta 此刻的情绪和意图，我们是什么关系，聊到哪了。\n" +
            "• 像我本人在说话：用我平时对 Ta 的称呼、口头禅、语气词、标点、表情和长短。如果下面附了我以前对 Ta 的真实回复，就照那个味道写，学语气和用词，不要照抄原句。用这段对话本身的语言。\n" +
            "• 每条都要接住最近几句里具体的内容，放进这段对话里读起来自然、有来有回。可以用上 Ta 的喜好、近况和我们的梗，但别硬塞。\n" +
            "• 不写放在哪段对话里都成立的话：和上文无关的问候（「下午好」「在吗」），只回「嗯」「哦」「好的」这种把天聊死的话，说教，客服腔，心理咨询腔。\n" +
            "• 判断模型说要给空间时：可以短、可以不追问，但要让 Ta 舒服、好接话，不是冷淡。\n" +
            "• 最后一句是我说的、Ta 还没回：写的是追发的一句，要自然、不追问、不重复我刚说过的、不给压力；这时如果更适合先不发，就在判断里直说。\n" +
            "• 三条要真的不一样：方向、语气或力度不同，但都得是我真会发出去的话。\n" +
            "输出：第一行以「判断：」开头，一句话说现在是什么局面、该往哪走，不超过 30 个字。然后三行，分别以「1. 」「2. 」「3. 」开头，每条不超过 40 个字，不加引号，不解释。",
        "You help me (\"Me\" in the chat) write my next message. Read the moment first: who sent the last message, what " +
            "they feel and want right now, what we are to each other, where the conversation is.\n" +
            "• Sound like me: the names I use for them, my catchphrases, fillers, punctuation, emoji and length. Where my " +
            "real past replies to them are given, write in that voice: learn the tone and words, don't copy the lines. Use " +
            "the language the conversation is in.\n" +
            "• Every draft picks up something specific from the last few messages and reads naturally as the next line of " +
            "this conversation. Work in their likes, news and our running jokes where they fit, never forced.\n" +
            "• Nothing that would fit any conversation: greetings unrelated to what was said (\"good afternoon\", \"you " +
            "there?\"), a bare \"ok\" or \"mm\" that ends the conversation, lecturing, customer-service or therapist talk.\n" +
            "• When the judgment model says to give them space: short and no questions is fine, but easy and warm to " +
            "answer, not cold.\n" +
            "• When the last message is mine and they have not answered: write a natural follow-up that asks nothing, does " +
            "not repeat what I just said and puts no pressure on them; if it is better to send nothing yet, say so in the " +
            "read.\n" +
            "• The three drafts differ for real, in direction, tone or strength, and each is something I would actually send.\n" +
            "Output: a first line starting \"Read: \" saying in one sentence what the moment is and where to take it (max 20 " +
            "words). Then three lines starting \"1. \" \"2. \" \"3. \", max 25 words each, no quotes, no explanations.")

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

    /** A reply answer: the model's one-line read of the moment (shown above the drafts), and the drafts. */
    class Replies(val read: String?, val drafts: List<String>)

    /** "判断：", "**判断**：", "Read: ". */
    private val READ_PREFIX = Regex("""^[\s*_#>]*(判断|局面|read|reading|situation)[\s*_]*[:：]\s*""", RegexOption.IGNORE_CASE)

    fun replies(answer: String): Replies {
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.indexOfFirst { READ_PREFIX.containsMatchIn(it) }
        if (at < 0) return Replies(null, drafts(answer))
        val read = lines[at].replaceFirst(READ_PREFIX, "").trim().trim('*', '_').trim().takeIf { it.isNotEmpty() }
        return Replies(read, drafts(lines.filterIndexed { i, _ -> i != at }.joinToString("\n")))
    }

    fun draftText(line: String): String {
        var s = line.replaceFirst(DRAFT_PREFIX, "").trim()
        for ((open, close) in listOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')) {
            if (s.length >= 2 && s.first() == open && s.last() == close) s = s.substring(1, s.length - 1).trim()
        }
        return s.ifEmpty { line.trim() }
    }

    /**
     * Everything the deep model needs: the same state Jev saw, plus what Jev concluded.
     * [relationship] and [closeness] are who they are to me when that is known, rather than
     * guessed this turn; [profile] is the full learned profile (the judge only gets its brief).
     */
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
        relationship: String? = null,
        closeness: String? = null,
        profile: String? = null,
    ): String = buildString {
        append(L.t("对方：", "Them: ")).append(peer).append('\n')
        val who = listOfNotNull(relationship?.takeIf { it.isNotBlank() }, closeness?.takeIf { it.isNotBlank() })
        if (who.isNotEmpty()) append(L.t("我和对方的关系：", "Relationship: ")).append(who.joinToString(" · ") { L.label(it) }).append('\n')
        if (note.isNotBlank()) append(L.t("关系背景：", "Context: ")).append(note).append('\n')
        profile?.takeIf { it.isNotBlank() }?.let { append(L.t("\nTa 的档案（从我们的全部聊天记录里学的）：\n", "\nTheir profile (learned from our whole history):\n")).append(it.trim()).append("\n\n") }
        style?.let { append(L.t("我平时的说话方式：", "How I usually write: ")).append(it).append('\n') }
        history?.let { append(L.t("我们最近几轮的走向：", "Where the last few turns went: ")).append(it).append('\n') }
        relation?.let { append(L.t("这段关系的长期观察：", "Long-term observations: ")).append(it).append('\n') }
        append(L.t("\n最近的对话（从上到下）：\n", "\nRecent messages (top to bottom):\n"))
        transcript.forEach { (who, text) -> append(L.who(who)).append(L.t("：", ": ")).append(text).append('\n') }
        turn(transcript)?.let { append(it).append('\n') }
        append(L.t("\n判断模型的快速读数（供参考，和对话本身对不上时以对话为准）：\n",
            "\nThe judgment model's quick read (a hint; where it disagrees with the conversation, the conversation wins):\n"))
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

    /**
     * Whose move it is. Without it a draft could answer my own last message as if it were theirs,
     * and a chat where I am waiting for an answer got replies to what they said before.
     */
    fun turn(transcript: List<Pair<String, String>>): String? {
        val (who, text) = transcript.lastOrNull() ?: return null
        return if (who == "我") L.t("最后一句是我说的（「${text.take(40)}」），Ta 还没回。", "The last message is mine (\"${text.take(40)}\"); they have not answered yet.")
        else L.t("最后一句是 Ta 说的，轮到我了。", "The last message is theirs; it is my turn.")
    }

    /**
     * For reply drafts: how I really talk to this person. Real past exchanges (the ones most like
     * what they just said, and the latest) and what I say most often, counted from the history.
     */
    fun voice(examples: List<Archive.Exchange>, myPhrases: List<Pair<String, Int>>): String = buildString {
        if (examples.isNotEmpty()) {
            append(L.t("\n我以前对 Ta 的真实回复（学这个语气，不要照抄）：\n", "\nHow I actually replied to them before (learn the voice, don't copy):\n"))
            for (e in examples) {
                append(L.t("对方：", "Them: ")).append(e.theirs.take(80)).append('\n')
                append(L.t("我：", "Me: ")).append(e.mine.take(80)).append('\n')
            }
        }
        Archive.phraseLine(myPhrases)?.let { append(L.t("\n我对 Ta 最常说的：", "\nWhat I say to them most: ")).append(it).append('\n') }
    }
}
