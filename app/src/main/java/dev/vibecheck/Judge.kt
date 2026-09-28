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

    fun ask(prefs: Prefs, body: String, kind: String = Prefs.USE_JUDGE): Map<String, Jev.Answer> =
        if (prefs.judge == OPENROUTER) TypeSafe.ask(prefs.orKey, viaOpenRouter(body), OPENROUTER_ENDPOINT, kind)
        else TypeSafe.ask(prefs.apiKey, body, kind = kind)

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
            // A certificate problem, not a drop: say what it is.
            e is javax.net.ssl.SSLHandshakeException -> (e.message ?: e.javaClass.simpleName).take(80)
            // "Software caused connection abort", "Connection reset": what Android does to a request
            // in flight when it takes the network away, as it does for us once the screen goes off.
            // Mid-read on a TLS connection that arrives as an SSLException.
            e is java.net.SocketException || e is javax.net.ssl.SSLException || e is java.io.EOFException ->
                L.t("网络中途断了（锁屏或切换网络时会这样）", "The connection dropped (locking the screen or switching networks does this)")
            else -> (e.message ?: e.javaClass.simpleName).take(80)
        }
    }

    /**
     * Worth asking again in a moment: the connection dropped or timed out, the service was busy,
     * or the model stopped before answering. Not a rejected key, an empty balance or a refusal.
     */
    fun isTransient(e: Throwable): Boolean {
        val s = status(e)
        return when {
            s == 408 || s == 429 || s in 500..599 -> true
            s != 0 -> false
            e is javax.net.ssl.SSLHandshakeException -> false
            e is java.io.IOException -> true
            else -> e is OpenRouter.Failure || e is TypeSafe.Failure
        }
    }

    /**
     * A burst of messages judged once, when it stops. People send three or four short messages
     * in a row; each used to be judged as it came, and every reading but the last was paid for,
     * shown for a moment and replaced. A new message is judged once it has been the newest for
     * [quietMs], and a run of changing screens (someone still typing, or a screen read that
     * differs a little each time) at the latest [maxMs] after it began.
     */
    class Settle(private val quietMs: Long = 3_000L, private val maxMs: Long = 8_000L) {
        private var key = ""
        private var since = 0L
        private var began = 0L
        private var done = true

        /** How much longer to wait before judging [key], seen at [now]; 0 to judge it now. */
        fun wait(key: String, now: Long): Long {
            if (key != this.key) {
                // The one before was judged, or stood long enough to be: a new run starts here.
                if (done || now - since >= quietMs) began = now
                this.key = key
                since = now
                done = false
            }
            val left = (minOf(since + quietMs, began + maxMs) - now).coerceAtLeast(0L)
            if (left == 0L) done = true
            return left
        }
    }

    /** How long to wait before asking again after [streak] failures in a row: 2s, 4s, 8s … 60s. */
    fun backoffMs(streak: Int): Long = (2_000L shl (streak - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
}
