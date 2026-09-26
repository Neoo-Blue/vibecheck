package dev.vibecheck

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Thin TypeSafe System One client: one POST, typed answers back. */
object TypeSafe {

    private const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    class Failure(val status: Int, message: String) : Exception(message)

    /** Blocking. Call from a background thread. Retries busy or briefly unreachable twice, with a short backoff. */
    fun ask(apiKey: String, body: String, endpoint: String = ENDPOINT): Map<String, Jev.Answer> {
        var attempt = 0
        while (true) {
            try {
                return parse(post(apiKey, body, endpoint))
            } catch (e: Exception) {
                if (retryable(e) && attempt < 2) {
                    attempt++
                    Thread.sleep(700L * attempt)
                } else throw e
            }
        }
    }

    /**
     * Overloaded, or the connection dropped mid-request: worth another try. A rejected key, a bad
     * request, no network at all, or a slow server (another 20s wait) is not.
     */
    fun retryable(e: Exception): Boolean = when (e) {
        is Failure -> e.status == 429 || e.status == 529 || e.status in 500..504
        is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.NoRouteToHostException, is javax.net.ssl.SSLException -> false
        is java.io.IOException -> true   // reset or cut off mid-request
        else -> false
    }

    private fun post(apiKey: String, body: String, endpoint: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Vibecheck")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Diag.lastResponse = text
            if (code !in 200..299) throw Failure(code, "HTTP $code: ${text.take(180)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** One malformed answer is skipped rather than failing the whole judgment. */
    private fun parse(json: String): Map<String, Jev.Answer> {
        val answers = JSONObject(json).getJSONObject("answers")
        val out = LinkedHashMap<String, Jev.Answer>()
        for (id in answers.keys()) {
            val a = answers.optJSONObject(id) ?: continue
            when (a.optString("type")) {
                "noul" -> a.optDouble("noul").takeIf { !it.isNaN() }?.let { out[id] = Jev.Answer.Noul(it) }
                "choice" -> {
                    val probs = a.optJSONObject("probabilities") ?: continue
                    val map = probs.keys().asSequence().associateWith { probs.optDouble(it, 0.0) }
                    val top = a.optString("choice").ifEmpty { map.maxByOrNull { it.value }?.key.orEmpty() }
                    if (top.isNotEmpty()) out[id] = Jev.Answer.Dist(top, map)
                }
                "score" -> {
                    val score = a.optDouble("score").takeIf { !it.isNaN() } ?: continue
                    val levels = a.optJSONObject("legend")?.length() ?: a.optJSONArray("legend")?.length() ?: 0
                    out[id] = Jev.Answer.Scored(score, if (levels > 0) levels else maxOf(2, Math.ceil(score).toInt() + 1))
                }
            }
        }
        return out
    }
}
