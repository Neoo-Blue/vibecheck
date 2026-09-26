package dev.vibecheck

/**
 * Where a judgment comes from. Both routes are the real Jev: TypeSafe's own API with a
 * TypeSafe key, or OpenRouter's decisions endpoint with an OpenRouter key. Same request body,
 * same answer shape; only the host, the key and the model id differ.
 */
object Judge {
    const val TYPESAFE = "typesafe"
    const val OPENROUTER = "openrouter"

    /** OpenRouter lists Jev only under output_modalities=decisions; the ~ alias tracks the newest version. */
    const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
    const val OPENROUTER_MODEL = "~typesafe/jev-latest"

    fun ask(prefs: Prefs, body: String): Map<String, Jev.Answer> =
        if (prefs.judge == OPENROUTER) TypeSafe.ask(prefs.orKey, viaOpenRouter(body), OPENROUTER_ENDPOINT)
        else TypeSafe.ask(prefs.apiKey, body)

    /** The same body with the model id OpenRouter expects. */
    fun viaOpenRouter(body: String): String =
        body.replaceFirst("\"model\":\"${Jev.MODEL}\"", "\"model\":\"$OPENROUTER_MODEL\"")

    /** What stops judging right now, or null. */
    fun missingKey(prefs: Prefs): String? = when {
        prefs.judge == OPENROUTER && prefs.orKey.isBlank() -> L.t("还没填 OpenRouter Key", "No OpenRouter key yet")
        prefs.judge == TYPESAFE && prefs.apiKey.isBlank() -> L.t("还没填 TypeSafe API Key", "No TypeSafe API key yet")
        else -> null
    }

    /** The HTTP status behind a failure, or 0. */
    fun status(e: Throwable): Int = when (e) {
        is TypeSafe.Failure -> e.status
        is OpenRouter.Failure -> e.status
        else -> 0
    }

    /**
     * A rejected key or an empty balance does not fix itself within seconds. 403 is not one of
     * them: OpenRouter also answers 403 when a moderated model refuses one particular input.
     */
    fun isFatal(e: Throwable): Boolean = status(e) == 401 || status(e) == 402

    /** What went wrong, in words a person can act on. */
    fun describe(e: Throwable): String {
        val s = status(e)
        return when {
            s == 401 -> L.t("Key 被拒绝（401），去设置里检查", "Key rejected (401); check it in settings")
            s == 403 -> L.t("被拒绝（403）：", "Refused (403): ") + (e.message ?: "").removePrefix("HTTP 403: ").take(60)
            s == 402 -> L.t("余额不足（402）", "Out of credit (402)")
            s == 429 -> L.t("请求太频繁（429），稍后自动重试", "Rate limited (429); will retry")
            s in 500..599 -> L.t("服务暂时出错（$s），稍后自动重试", "Service error ($s); will retry")
            e is java.net.UnknownHostException || e is java.net.ConnectException -> L.t("连不上网络", "No connection")
            e is java.net.SocketTimeoutException -> L.t("请求超时", "Timed out")
            else -> (e.message ?: e.javaClass.simpleName).take(80)
        }
    }

    /** How long to wait before asking again after [streak] failures in a row: 2s, 4s, 8s … 60s. */
    fun backoffMs(streak: Int): Long = (2_000L shl (streak - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
}
