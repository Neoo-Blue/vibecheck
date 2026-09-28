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

    /** A stretch of a kept history to take notes on. */
    private class Stretch(val name: String, val hash: String, val start: String, val chars: Int, val newest: Boolean, val prompt: String)

    /**
     * The profile of me: notes about me from the newest stretches of each learned person's kept
     * history (cached, so only new stretches cost anything), the recent day write-ups and the
     * people in my life, merged into one. [progress] gets (done, of) as the notes come in.
     *
     * Asked for by hand ([byHand]), every stretch is noted as it is now and the profile is written
     * whatever happened since. On its own (after a history read, every few days) the newest
     * stretch of a chat keeps its notes until it has grown enough to matter (Me.stillServes), and
     * a profile whose makings are all as they were when it was last written is not written again.
     */
    fun rebuild(byHand: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val cached = me.notes()
        val starts = if (byHand) emptyMap() else me.starts()
        val work = ArrayList<Stretch>()
        val mine = ArrayList<Pair<String, String>>()
        for (id in people.index()) {
            val history = people.archiveAll(id)
            if (history.size < MIN_HISTORY) continue
            mine += history.filter { it.first == "我" }
            val name = people.shownName(id)
            val chunks = Archive.chunks(history, CHUNK_CHARS).takeLast(PER_PERSON)
            for ((i, c) in chunks.withIndex()) {
                val text = Archive.text(history, c)
                work += Stretch(name, Archive.hash("me\n$text"), Me.startKey(id, history, c), text.length,
                    i == chunks.lastIndex, Me.notesPrompt(name, i + 1, chunks.size, text))
            }
        }
        val todo = work.takeLast(MAX_STRETCHES)
        val kept = LinkedHashMap<String, String>()
        val keptStarts = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        for ((i, job) in todo.withIndex()) {
            val found = Me.reuse(cached, starts, job.hash, job.start, job.chars, job.newest)
            val n = found?.second ?: runCatching { ask(Me.NOTES_SYSTEM, job.prompt, 1500) }.getOrNull()
            if (n != null) {
                val key = found?.first ?: job.hash
                kept[key] = n
                // Where these notes were taken, and on how much of the stretch.
                keptStarts[job.start] = if (key == job.hash) "$key ${job.chars}" else starts.getValue(job.start)
                notes += L.t("【和 ${job.name} 的聊天】\n", "[Chat with ${job.name}]\n") + n
                // Saved as each lands: a rebuild cut short pays only for what is missing next time.
                me.saveNotes(kept)
                me.saveStarts(keptStarts)
            }
            progress(i + 1, todo.size)
        }
        val days = me.summaries(14)
        if (notes.isEmpty() && days.isEmpty()) throw IllegalStateException(
            L.t("还没有能学的：先学习一个人，或者聊一天", "Nothing to learn from yet: learn someone first, or chat for a day"))
        // More notes than one pass can take: merged a group at a time first. A group merged
        // before, word for word, is not merged again.
        var layer: List<String> = notes
        while (layer.size > 1 && layer.sumOf { it.length } > REDUCE_CHARS) {
            val groups = Profile.groups(layer, REDUCE_CHARS / 2)
            if (groups.size >= layer.size) break
            layer = groups.map { g ->
                if (g.size == 1) g[0] else {
                    val k = Profile.mergeKey(g)
                    (cached[k] ?: ask(Me.MERGE_SYSTEM, g.joinToString("\n\n"), 3000)).also { kept[k] = it; me.saveNotes(kept) }
                }
            }
        }
        val circle = people.index().mapNotNull { id ->
            people.relationLine(people.loadId(id))?.let { people.shownName(id) + L.t("：", ": ") + it }
        }
        val system = Me.profileSystem()
        val prompt = Me.profilePrompt(layer, days, circle, Archive.phraseLine(Archive.phrases(mine, "我")))
        val basis = Archive.hash("${prefs.deepModel}\n$system\n$prompt")
        if (!byHand && basis == me.basis && me.profile.isNotBlank()) return me.profile
        // Weighing it all up into one profile is where thinking first pays.
        val text = ask(system, prompt, 5000, OpenRouter.Think.LOW).trim()
        me.profile = text
        me.basis = basis
        me.learnedAt = System.currentTimeMillis()
        return text
    }

    /**
     * The Think model, and when it still fails after its retries, the other model set (see
     * [Models.backup]). Write-ups of a day, notes and merges put down what is there: they are
     * written without thinking first, which was most of what those calls cost.
     */
    private fun ask(system: String, prompt: String, tokens: Int, think: OpenRouter.Think = OpenRouter.Think.OFF): String {
        val model = prefs.deepModel
        return try {
            once(model, system, prompt, tokens, think)
        } catch (e: Exception) {
            val backup = Models.backup(model, prefs.fastModel, prefs.deepModel)
            if (backup == null || !OpenRouter.worthAnotherModel(e)) throw e
            Diag.log("me: $model failed (${Judge.describe(e)}); asking $backup")
            once(backup, system, prompt, tokens, think)
        }
    }

    private fun once(model: String, system: String, prompt: String, tokens: Int, think: OpenRouter.Think): String {
        var wait = 5_000L
        var tries = 0
        while (true) {
            prefs.countUse(Prefs.USE_ME)
            try {
                return OpenRouter.chat(prefs.orKey, model, system, prompt, null, maxTokens = tokens, timeoutMs = 180_000, temperature = 0.4,
                    think = think, kind = Prefs.USE_ME)
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
