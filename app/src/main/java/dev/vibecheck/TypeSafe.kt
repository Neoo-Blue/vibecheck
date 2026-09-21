package dev.vibecheck

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Thin TypeSafe System One client: one POST, typed answers back. */
object TypeSafe {

    private const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    class Failure(val status: Int, message: String) : Exception(message)

    /** Blocking. Call from a background thread. Retries 429/529 once with a short backoff. */
    fun ask(apiKey: String, body: String, endpoint: String = ENDPOINT): Map<String, Jev.Answer> {
        var attempt = 0
        while (true) {
            try {
                return parse(post(apiKey, body, endpoint))
            } catch (e: Failure) {
                if ((e.status == 429 || e.status == 529) && attempt < 2) {
                    attempt++
                    Thread.sleep(700L * attempt)
                } else throw e
            }
        }
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

    private fun parse(json: String): Map<String, Jev.Answer> {
        val answers = JSONObject(json).getJSONObject("answers")
        val out = LinkedHashMap<String, Jev.Answer>()
        for (id in answers.keys()) {
            val a = answers.getJSONObject(id)
            when (a.optString("type")) {
                "noul" -> out[id] = Jev.Answer.Noul(a.getDouble("noul"))
                "choice" -> {
                    val probs = a.getJSONObject("probabilities")
                    out[id] = Jev.Answer.Dist(
                        a.getString("choice"),
                        probs.keys().asSequence().associateWith { probs.getDouble(it) }
                    )
                }
                "score" -> out[id] = Jev.Answer.Scored(
                    a.getDouble("score"),
                    a.getJSONObject("legend").length()
                )
            }
        }
        return out
    }
}
