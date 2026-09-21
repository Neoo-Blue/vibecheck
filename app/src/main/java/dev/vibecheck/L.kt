package dev.vibecheck

/**
 * UI language. Chinese is the source of truth; English is a display layer.
 *
 * The question sets sent to Jev, and therefore the option keys the learner and the routine
 * rule key on, stay Chinese in both languages: switching the UI must not orphan what has been
 * learned. Jev reads either language, so an English chat is judged just as well.
 */
object L {
    @Volatile var en = false

    fun t(zh: String, en: String): String = if (this.en) en else zh

    /** Display text for a Jev option key or card header; the key itself is never translated. */
    fun label(key: String): String = if (!en) key else LABELS[key] ?: key

    /** Speaker labels used inside prompts. */
    fun who(w: String): String = if (!en) w else if (w == "对方") "them" else "me"

    private val LABELS: Map<String, String> = mapOf(
        // headers
        "当前真实意图" to "What they really mean",
        "翻车风险" to "Risk of this going wrong",
        "最佳动作" to "Best move",
        "对方真的在说字面意思吗？" to "Are they being literal?",
        "现在需要什么" to "What they need now",
        "这是什么场合" to "What kind of chat",
        "需要马上回吗？" to "Answer now?",
        "对方在要什么" to "What they're asking for",
        "时间压力" to "Time pressure",
        "顺着回会不会等于答应了？" to "Does going along commit you?",
        "对方兴趣" to "Their interest",
        "该我推进吗？" to "Should I push?",
        "话题怎么走" to "Where to take the topic",
        "对方心情" to "Their mood",
        "轻松" to "Easy",
        // situation
        "恋爱或亲密关系" to "romance", "暧昧试探" to "flirting", "朋友" to "friend", "家人" to "family",
        "同事或上下级" to "colleague", "客户或生意" to "client / business",
        "陌生人或刚加上" to "stranger / new contact", "客服或办事" to "customer service / errand",
        // intent
        "在开玩笑或一起感慨" to "joking or musing together", "在分享观点或心情" to "sharing a thought or mood",
        "单纯想知道答案" to "just wants an answer", "想确认你在不在乎" to "testing whether you care",
        "在表达不满" to "expressing displeasure", "想要你主动承担" to "wants you to step up",
        "在提要求或谈条件" to "making an ask or negotiating", "明确想结束话题或拉开距离" to "ending the topic / pulling away",
        // action
        "先道歉" to "apologize first", "先停一下" to "pause", "反问细节" to "ask for details",
        "接梗顺着聊" to "play along", "给一点空间" to "give some space", "先回应情绪" to "acknowledge the feeling first",
        "正面回答问题" to "answer directly", "给具体的帮忙" to "offer concrete help", "简单回应或认同" to "a simple acknowledgement",
        "拖一下争取时间" to "buy time", "分享一点自己的" to "share something of your own", "抛一个具体问题" to "ask a specific question",
        "守住边界不让步" to "hold the line", "直接给具体方案" to "give a concrete plan",
        "明确拒绝并给替代" to "decline and offer an alternative", "先确认范围再答应" to "confirm the scope before agreeing",
        "翻聊天记录找事实" to "check the chat history for facts", "直接给结果或时间点" to "give the result or a time",
        "提一个具体的下一步" to "propose a concrete next step",
        // ask
        "要结果" to "wants a result", "要进度" to "wants a status update", "要一个决定" to "wants a decision",
        "只是同步信息" to "just informing", "要资源或让步" to "wants resources or a concession",
        // need
        "解释" to "an explanation", "空间" to "space", "行动" to "action", "道歉" to "an apology", "情绪安抚" to "comfort",
        "帮忙或建议" to "help or advice", "接梗一起玩" to "play along", "一个明确答复" to "a clear answer", "被听到和认同" to "to be heard",
        // topic
        "礼貌收尾" to "wrap up politely", "自然换个话题" to "change the subject", "深入这个话题" to "go deeper on this",
        "从话题转到约见或下一步" to "move toward meeting up",
        // routine fallbacks
        "闲聊" to "small talk",
    )
}
