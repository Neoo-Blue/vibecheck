package dev.vibecheck

/**
 * The model calls that learn about me, with the Think model. Blocking: call from a background
 * thread. Each call is tried again after a pause when the connection drops or the service is busy.
 */
class MeLearner(private val me: MeStore, private val people: PersonStore, private val prefs: Prefs) {

    /**
     * Writes up each finished day that has a log and no write-up yet, or one written before the
     * day's log grew, oldest first. Today is left until it is over. Returns how many were written.
     */
    fun summarizeDays(today: String, max: Int = 7): Int {
        var n = 0
        for (d in me.days().filter { it < today }.sorted().takeLast(max)) {
            val lines = me.lines(d)
            if (lines.isEmpty() || (me.summary(d) != null && me.summarizedLines(d) >= lines.size)) continue
            me.saveSummary(d, ask(Me.DAY_SYSTEM, Me.dayPrompt(d, lines), 800), lines.size)
            n++
        }
        return n
    }

    /** Today so far, written up now. Null when nothing was logged today. */
    fun summarizeToday(now: Long): String? {
        val d = Me.day(now)
        val lines = me.lines(d)
        if (lines.isEmpty()) return null
        return ask(Me.DAY_SYSTEM, Me.dayPrompt(d, lines), 800).also { me.saveSummary(d, it, lines.size) }
    }

    /**
     * The profile of me: notes about me from the newest stretches of each learned person's kept
     * history (cached, so only new stretches cost anything), the recent day write-ups and the
     * people in my life, merged into one. [progress] gets (done, of) as the notes come in.
     */
    fun rebuild(progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val cached = me.notes()
        val work = ArrayList<Triple<String, String, String>>()   // whose chat, stretch hash, prompt
        val mine = ArrayList<Pair<String, String>>()
        for (id in people.index()) {
            val history = people.archiveAll(id)
            if (history.size < MIN_HISTORY) continue
            mine += history.filter { it.first == "我" }
            val name = people.shownName(id)
            val chunks = Archive.chunks(history, CHUNK_CHARS).takeLast(PER_PERSON)
            for ((i, c) in chunks.withIndex()) {
                val text = Archive.text(history, c)
                work += Triple(name, Archive.hash("me\n$text"), Me.notesPrompt(name, i + 1, chunks.size, text))
            }
        }
        val todo = work.takeLast(MAX_STRETCHES)
        val kept = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        for ((i, job) in todo.withIndex()) {
            val (name, hash, prompt) = job
            val n = cached[hash] ?: runCatching { ask(Me.NOTES_SYSTEM, prompt, 1500) }.getOrNull()
            if (n != null) {
                kept[hash] = n
                notes += L.t("【和 $name 的聊天】\n", "[Chat with $name]\n") + n
                // Saved as each lands: a rebuild cut short pays only for what is missing next time.
                me.saveNotes(kept)
            }
            progress(i + 1, todo.size)
        }
        val days = me.summaries(14)
        if (notes.isEmpty() && days.isEmpty()) throw IllegalStateException(
            L.t("还没有能学的：先学习一个人，或者聊一天", "Nothing to learn from yet: learn someone first, or chat for a day"))
        // More notes than one pass can take: merged a group at a time first.
        var layer: List<String> = notes
        while (layer.size > 1 && layer.sumOf { it.length } > REDUCE_CHARS) {
            val groups = Profile.groups(layer, REDUCE_CHARS / 2)
            if (groups.size >= layer.size) break
            layer = groups.map { g -> if (g.size == 1) g[0] else ask(Me.MERGE_SYSTEM, g.joinToString("\n\n"), 3000) }
        }
        val circle = people.index().mapNotNull { id ->
            people.relationLine(people.loadId(id))?.let { people.shownName(id) + L.t("：", ": ") + it }
        }
        val text = ask(Me.profileSystem(), Me.profilePrompt(layer, days, circle, Archive.phraseLine(Archive.phrases(mine, "我"))), 5000).trim()
        me.profile = text
        me.learnedAt = System.currentTimeMillis()
        return text
    }

    /** The Think model, and when it still fails after its retries, the other model set (see [Models.backup]). */
    private fun ask(system: String, prompt: String, tokens: Int): String {
        val model = prefs.deepModel
        return try {
            once(model, system, prompt, tokens)
        } catch (e: Exception) {
            val backup = Models.backup(model, prefs.fastModel, prefs.deepModel)
            if (backup == null || !OpenRouter.worthAnotherModel(e)) throw e
            Diag.log("me: $model failed (${Judge.describe(e)}); asking $backup")
            once(backup, system, prompt, tokens)
        }
    }

    private fun once(model: String, system: String, prompt: String, tokens: Int): String {
        var wait = 5_000L
        var tries = 0
        while (true) {
            prefs.countUse(Prefs.USE_BIO)
            try {
                return OpenRouter.chat(prefs.orKey, model, system, prompt, null, maxTokens = tokens, timeoutMs = 180_000, temperature = 0.4)
            } catch (e: Exception) {
                if (++tries >= 3 || !Judge.isTransient(e)) throw e
                Thread.sleep(wait)
                wait *= 3
            }
        }
    }

    companion object {
        private const val CHUNK_CHARS = 12_000
        private const val REDUCE_CHARS = 40_000
        /** A chat this short says little about me. */
        private const val MIN_HISTORY = 20
        /** The newest stretches of each chat: who I am now, not years ago. */
        private const val PER_PERSON = 6
        private const val MAX_STRETCHES = 30
    }
}
