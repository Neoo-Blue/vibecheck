package dev.vibecheck

/**
 * The judgment set and how it renders. One TypeSafe request asks all of these over the same
 * state; they are independent, so they run in parallel server-side.
 */
object Jev {

    sealed class Answer {
        data class Noul(val p: Double) : Answer()
        data class Dist(val top: String, val probs: Map<String, Double>) : Answer()
        data class Scored(val score: Double, val levels: Int) : Answer()
    }

    data class Block(val header: String, val lines: List<String>)

    /** id -> how to show it. Order here is the order on the card. */
    val DISPLAY: List<Pair<String, String>> = listOf(
        "literal" to "对方真的在说字面意思吗？",
        "intent" to "当前真实意图",
        "danger" to "翻车风险",
        "need" to "现在需要什么",
        "action" to "最佳动作",
    )

    /** Shown only in the expanded card: useful context, but not worth the space every turn. */
    val DISPLAY_MORE: List<Pair<String, String>> = listOf(
        "situation" to "这是什么场合",
        "urgency" to "需要马上回吗？",
    )

    fun pct(p: Double): String = "${Math.round(p * 100)}%"

    /**
     * The full card. With a situation, the headers are that situation's own question set on top
     * of the shared intent and risk, so a work chat and a first chat with a stranger no longer
     * show the same five blocks.
     */
    fun card(answers: Map<String, Answer>, maxLines: Int = 3, situation: String? = null): List<Block> =
        render(answers, headers(situation), maxLines)

    /** The ids and headers a turn was judged on: the shared pair, then the situation's own set. */
    fun headers(situation: String?): List<Pair<String, String>> =
        if (situation == null) DISPLAY
        else listOf("intent" to "当前真实意图", "danger" to "翻车风险") + displayFor(situation)

    private val CASUAL_INTENTS = setOf("在开玩笑或一起感慨", "在分享观点或心情", "单纯想知道答案")

    /** Normalized 0..1 danger, or null if absent. */
    fun risk(answers: Map<String, Answer>): Double? {
        val d = answers["danger"] as? Answer.Scored ?: return null
        return d.score / (d.levels - 1).coerceAtLeast(1)
    }

    /**
     * Is there anything here worth a full read? Most chat is not: a joke, a shared thought, a
     * plain question. Dumping five probability blocks on those made every card look the same
     * and buried the turns that actually needed attention. Routine gets one line; the rest gets
     * the full breakdown and a deep pass.
     */
    fun isRoutine(answers: Map<String, Answer>): Boolean {
        val r = risk(answers) ?: return false
        val intent = answers["intent"] as? Answer.Dist ?: return false
        val topP = intent.probs[intent.top] ?: 0.0
        val urgent = (answers["urgency"] as? Answer.Noul)?.p ?: 0.0
        return r <= 0.25 && intent.top in CASUAL_INTENTS && topP >= 0.55 && urgent < 0.5
    }

    /** The one-line card for a routine turn. Says what kind of turn it is, then gets out of the way. */
    fun routineCard(answers: Map<String, Answer>): List<Block> {
        val intent = (answers["intent"] as? Answer.Dist)?.top ?: "闲聊"
        val action = (answers["action"] as? Answer.Dist)?.top
        val hint = when (intent) {
            "在开玩笑或一起感慨" -> "在开玩笑，接着玩就行"
            "在分享观点或心情" -> "在分享，回应一下就好"
            "单纯想知道答案" -> "在问事，直接答"
            else -> intent
        }
        val line = if (action != null && action !in setOf("接梗顺着聊", "简单回应或认同", "正面回答问题"))
            "$hint（建议：$action）" else hint
        return listOf(Block("轻松", listOf(line)))
    }

    /** Expanded card only: useful, but not worth the space on every message. */
    fun more(answers: Map<String, Answer>, maxLines: Int = 3): List<Block> =
        render(answers, DISPLAY_MORE, maxLines)

    /** Generic renderer: whatever answers come back get laid out in the given order. */
    private fun render(answers: Map<String, Answer>, display: List<Pair<String, String>>, maxLines: Int): List<Block> {
        val blocks = ArrayList<Block>()
        for ((id, header) in display) {
            when (val a = answers[id] ?: continue) {
                is Answer.Noul -> {
                    // A near-coin-flip noul says nothing useful; keep the card short instead.
                    if (a.p in 0.35..0.65) continue
                    blocks.add(Block(header, listOf("- 是: ${pct(a.p)}", "- 不是: ${pct(1 - a.p)}")))
                }
                is Answer.Dist -> {
                    val lines = a.probs.entries.sortedByDescending { it.value }.take(maxLines)
                        .map { "- ${it.key}: ${pct(it.value)}" }
                    blocks.add(Block(header, lines))
                }
                is Answer.Scored -> {
                    val shown = Math.round(a.score).toInt() + 1   // levels are 0-based
                    blocks.add(Block(header, listOf("$shown / ${a.levels}")))
                }
            }
        }
        return blocks
    }

    /**
     * One short takeaway, built from what this turn actually says rather than a fixed template.
     * Only fires when it adds something: a real risk to flag, or a genuinely relaxed moment.
     */
    fun footer(answers: Map<String, Answer>): String? {
        val danger = (answers["danger"] as? Answer.Scored) ?: return null
        val level = danger.score / (danger.levels - 1).coerceAtLeast(1)
        val action = (answers["action"] as? Answer.Dist)?.top
        val need = (answers["need"] as? Answer.Dist)?.top
        val urgent = (answers["urgency"] as? Answer.Noul)?.p ?: 0.0
        return when {
            level >= 0.6 && action != null ->
                "提醒\n风险偏高，$action" + (need?.let { "，先满足「$it」" } ?: "") + "。"
            level <= 0.2 && action != null ->
                "很轻松，$action 就好，别想多。"
            urgent < 0.3 && level < 0.5 -> "不急，晚点回也没关系。"
            else -> null
        }
    }

    /**
     * The request body. state carries the relationship context and the visible transcript;
     * every question is asked over that same state.
     */
    fun stateJson(
        context: String,
        transcript: List<Pair<String, String>>,
        peer: String? = null,
        style: String? = null,
        history: String? = null,
        relation: String? = null,
        source: String? = null,
    ): String {
        val msgs = transcript.joinToString(",") { (who, text) ->
            """{"谁":${q(who)},"内容":${q(text)}}"""
        }
        val parts = ArrayList<String>()
        peer?.takeIf { it.isNotBlank() }?.let { parts.add(""""对方":${q(it)}""") }
        parts.add(""""关系背景":${q(context.ifBlank { "一段亲密关系中的日常聊天" })}""")
        style?.takeIf { it.isNotBlank() }?.let { parts.add(""""我平时的说话方式":${q(it)}""") }
        history?.takeIf { it.isNotBlank() }?.let { parts.add(""""我们最近几轮的走向":${q(it)}""") }
        relation?.takeIf { it.isNotBlank() }?.let { parts.add(""""这段关系的长期观察":${q(it)}""") }
        // The model should know the transcript has holes, rather than read silence as meaning.
        source?.takeIf { it.isNotBlank() }?.let { parts.add(""""转写说明":${q(it)}""") }
        parts.add(""""对话":[$msgs]""")
        val state = "{${parts.joinToString(",")}}"
        return state
    }

    /** Back-compat: the triage call. */
    fun requestBody(
        context: String,
        transcript: List<Pair<String, String>>,
        peer: String? = null,
        style: String? = null,
        history: String? = null,
        relation: String? = null,
        source: String? = null,
    ): String = triageBody(stateJson(context, transcript, peer, style, history, relation, source))

    /**
     * Stage one, every turn: what kind of situation is this, what are they after, how risky,
     * how urgent. Cheap, and enough to decide whether the turn deserves anything more.
     */
    fun triageBody(state: String): String = """
        {"state":$state,"model":"jev-latest","questions":{
          "situation":{"type":"choice",
            "instructions":"这段对话属于哪一类关系和场合？只看对话本身和背景，不要假设一定是恋爱。",
            "criteria":{
              "恋爱或亲密关系":"伴侣、对象之间的日常或争执",
              "暧昧试探":"还没确定关系，双方在互相试探和拉扯",
              "朋友":"朋友之间的闲聊、约饭、吐槽",
              "家人":"父母、兄弟姐妹、亲戚",
              "同事或上下级":"工作场合，涉及任务、进度、责任",
              "客户或生意":"甲乙方、买卖、谈价格和条件",
              "陌生人或刚加上":"刚认识，信息很少，礼貌距离",
              "客服或办事":"办业务、求助、走流程"}},
          "intent":{"type":"choice",
            "instructions":"对方发出最后一条消息时，真正想要达成的是什么？多数聊天是轻松的，别默认有潜台词。",
            "criteria":{
              "在开玩笑或一起感慨":"轻松调侃、吐槽、或就一个话题一起有感而发，没有针对你的情绪",
              "在分享观点或心情":"表达自己的想法和感受，想被听到、想有人一起聊",
              "单纯想知道答案":"就是缺一个信息，没有情绪负担",
              "想确认你在不在乎":"用一个具体问题检验你的投入、记性或态度",
              "在表达不满":"对你积了情绪，在找一个开口",
              "想要你主动承担":"希望你提出安排、认领责任，而不是等对方开口",
              "在提要求或谈条件":"想要你答应某件事、给资源、让价格或改期限",
              "明确想结束话题或拉开距离":"清楚地表示不想再聊这个、或想跟你保持距离"}},
          "danger":{"type":"score",
            "instructions":"此刻回得不好会有多大代价？大部分轻松聊天怎么接都不会出事，别把玩笑当危机。",
            "criteria":["轻松玩笑或闲聊，怎么接都不会出事","有点内容，但还很安全","对方在意你的回应，敷衍会扣分","已经有情绪或分歧，回错会升级","很危险，一句话就可能引爆或谈崩","正在爆发，任何解释都会火上浇油"]},
          "urgency":{"type":"noul",
            "instructions":"这条消息需要你现在就回吗？",
            "criteria":{"true":"拖着会让事情变糟，或对方正在等","false":"可以晚点再回，甚至过一会儿更好"}}
        }}
        """.trimIndent()

    /**
     * Stage two, only for turns that matter: a question set built for the situation, so a work
     * chat is asked about deadlines and commitment traps, a new contact about interest and who
     * should push, an intimate chat about what they need. Every set ends in an "action" so the
     * learner and the footer keep working across all of them.
     */
    fun detailBody(state: String, situation: String): String {
        val questions = when (situation) {
            "同事或上下级", "客户或生意", "客服或办事" -> """
          "ask":{"type":"choice",
            "instructions":"对方这条消息实际在向你要什么？",
            "criteria":{
              "要结果":"要交付、要成品、要答案",
              "要进度":"想知道做到哪了、什么时候能好",
              "要一个决定":"需要你拍板：行不行、选哪个",
              "要资源或让步":"要人、要钱、要时间、要你降价或加量",
              "只是同步信息":"告知，不需要你做什么"}},
          "pressure":{"type":"score",
            "instructions":"这条消息里的时间压力有多大？",
            "criteria":["不急，随时","这周内的事","今天要有回应","现在就要，对方在等"]},
          "trap":{"type":"noul",
            "instructions":"顺着回会不会等于默认了一个我没打算给的承诺（时间、范围、价格）？",
            "criteria":{"true":"回一句好就等于答应了新的条件或期限","false":"没有隐含的承诺，直接回没问题"}},
          "action":{"type":"choice",
            "instructions":"你接下来最该做的一步是什么？",
            "criteria":{
              "直接给结果或时间点":"能给就给，附上明确时间",
              "先确认范围再答应":"问清楚要什么、到哪为止，再承诺",
              "明确拒绝并给替代":"说不行，同时给一个能做的版本",
              "拖一下争取时间":"先回收到，晚点给准话",
              "守住边界不让步":"对方在压价或加码，这一步该稳住条件",
              "反问细节":"信息不够，先问"}}"""
            "陌生人或刚加上" -> """
          "interest":{"type":"score",
            "instructions":"从对方的回复长度、主动性和问题数量看，对方对这段对话的兴趣有多高？",
            "criteria":["冷淡，敷衍应付","一般，礼貌回应","有兴趣，在主动延展","很热，在主动推进"]},
          "push":{"type":"noul",
            "instructions":"现在该由我来推进（抛话题、约、加速），还是该等对方？",
            "criteria":{"true":"对方在等我接力，不推就冷了","false":"对方已经在推，我跟上就好，别抢"}},
          "topic":{"type":"choice",
            "instructions":"当前话题该怎么处理？",
            "criteria":{
              "深入这个话题":"对方对这个有兴趣，继续挖",
              "自然换个话题":"这个聊干了，换一个",
              "从话题转到约见或下一步":"聊得够了，可以推进",
              "礼貌收尾":"对方兴趣不高，别硬聊"}},
          "action":{"type":"choice",
            "instructions":"你接下来最该做的一步是什么？",
            "criteria":{
              "接梗顺着聊":"顺着对方轻松接下去",
              "抛一个具体问题":"用一个好回答的问题把球递过去",
              "分享一点自己的":"对等地讲一点自己，不要只问",
              "提一个具体的下一步":"约时间、约地方，或转到别的平台",
              "先停一下":"对方冷，别追"}}"""
            "朋友", "家人" -> """
          "mood":{"type":"score",
            "instructions":"对方此刻的心情是什么状态？",
            "criteria":["低落或烦躁","平淡","不错","很开心，兴致高"]},
          "need":{"type":"choice",
            "instructions":"对方此刻最需要从你这里得到的是什么？",
            "criteria":{
              "接梗一起玩":"顺着这个玩笑或话题一起聊下去就好",
              "被听到和认同":"想要你回应感受或观点，共鸣一下就够",
              "情绪安抚":"心情不好，需要被理解和接住",
              "帮忙或建议":"遇到事了，需要你出主意或出手",
              "一个明确答复":"约不约、去不去、要不要"}},
          "action":{"type":"choice",
            "instructions":"你接下来最该做的一步是什么？",
            "criteria":{
              "接梗顺着聊":"顺着对方的玩笑或话题轻松接下去",
              "简单回应或认同":"一句共鸣、认同就够",
              "先回应情绪":"先接住感受，再谈事情",
              "正面回答问题":"如实、完整地回答",
              "给具体的帮忙":"直接说你能做什么、什么时候",
              "给一点空间":"不追问，稍后再联系"}}"""
            else -> """
          "literal":{"type":"noul",
            "instructions":"对方最后一条消息，说的是字面上那件事本身吗？",
            "criteria":{"true":"就是字面意思，照着回答事实即可","false":"字面之下另有所指，真正在试探态度、表达不满、或在争取别的东西"}},
          "need":{"type":"choice",
            "instructions":"对方此刻最需要从你这里得到的是什么？",
            "criteria":{
              "接梗一起玩":"顺着这个玩笑或话题一起聊下去就好",
              "被听到和认同":"想要你回应感受或观点，共鸣一下就够",
              "情绪安抚":"心情不好，需要被理解和接住，不需要解决方案",
              "解释":"需要你说明原因和来龙去脉",
              "行动":"需要你给出具体的、你来执行的安排",
              "一个明确答复":"需要你给准话：行还是不行、什么时候、多少",
              "道歉":"需要你先认错、承认让对方不舒服了",
              "空间":"此刻不想被追问，需要你退一步"}},
          "action":{"type":"choice",
            "instructions":"你接下来最该做的一步是什么？",
            "criteria":{
              "接梗顺着聊":"顺着对方的玩笑或话题轻松接下去",
              "简单回应或认同":"一句共鸣、认同就够，别上纲上线",
              "先回应情绪":"承认对方的感受，再谈事情本身",
              "正面回答问题":"如实、完整地回答，不绕",
              "翻聊天记录找事实":"对方在考具体内容，答错的代价高于晚答",
              "直接给具体方案":"给出时间、地点、数字，由你安排的明确计划",
              "先道歉":"不解释，先认错",
              "给一点空间":"不追问，稍后再联系"}}"""
        }
        return """{"state":$state,"model":"jev-latest","questions":{$questions
        }}"""
    }

    /** Card headers for a situation's detail set, in display order. */
    fun displayFor(situation: String): List<Pair<String, String>> = when (situation) {
        "同事或上下级", "客户或生意", "客服或办事" -> listOf(
            "ask" to "对方在要什么", "pressure" to "时间压力", "trap" to "顺着回会不会等于答应了？", "action" to "最佳动作")
        "陌生人或刚加上" -> listOf(
            "interest" to "对方兴趣", "push" to "该我推进吗？", "topic" to "话题怎么走", "action" to "最佳动作")
        "朋友", "家人" -> listOf(
            "mood" to "对方心情", "need" to "现在需要什么", "action" to "最佳动作")
        else -> listOf(
            "literal" to "对方真的在说字面意思吗？", "need" to "现在需要什么", "action" to "最佳动作")
    }


    /** Minimal JSON string escaping; the transcript is arbitrary user text. */
    fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
