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
}
