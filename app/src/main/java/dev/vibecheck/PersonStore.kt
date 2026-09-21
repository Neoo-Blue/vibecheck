package dev.vibecheck

import android.content.Context

/**
 * Per-person memory, all on the phone. One SharedPreferences file, flat keys per person, so
 * there is no schema to migrate and a corrupt entry can only lose that one person.
 */
class PersonStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("people", Context.MODE_PRIVATE)

    class Record(
        val id: String,
        val name: String,
        var note: String,
        var style: Person.Style,
        var model: Learner.Model,
        var history: List<Person.Turn>,
        var stats: Relation.Stats,
        /** Newest message of any side already counted, so rescans do not double count. */
        var lastSeenText: String,
        /** Auto-written from a full history read (学习此人). Used when no hand-written note. */
        var bio: String = "",
        /** How many messages the history read covered, so the card can say "learned N". */
        var learned: Int = 0,
        /** Every app this person has been seen on: their own plus any linked records. */
        val apps: List<String> = listOf(id.substringBefore('|')),
    )

    /** Linked records point at the one that holds the memory. */
    private fun canonical(id: String): String = sp.getString("link:$id", null) ?: id

    private fun aliasesOf(id: String): List<String> =
        (sp.getString("$id:aliases", "") ?: "").split("\n").filter { it.isNotBlank() }

    /**
     * Same person, two apps: fold `aliasId` into `intoId` and remember the link, so the next
     * chat on either app opens the one shared memory, bio and bandit.
     */
    fun link(aliasId: String, intoId: String) {
        if (aliasId == intoId) return
        val a = loadId(aliasId)
        val b = loadId(intoId)
        Relation.merge(b.stats, a.stats)
        Person.mergeStyle(b.style, a.style)
        Learner.merge(b.model, a.model)
        b.history = (a.history + b.history).takeLast(Person.HISTORY)
        if (b.note.isBlank()) b.note = a.note
        if (b.bio.isBlank()) b.bio = a.bio
        b.learned += a.learned
        val aliases = (aliasesOf(intoId) + aliasId + aliasesOf(aliasId)).distinct()
        forget(aliasId)
        val e = sp.edit()
        for (x in aliases) e.putString("link:$x", intoId)
        e.putString("$intoId:aliases", aliases.joinToString("\n")).apply()
        save(b)
    }

    fun loadId(id: String): Record = load(id.substringBefore('|'), id.substringAfter('|'))

    /** What the model should be told about this person: your note wins, then the learned bio. */
    fun background(r: Record, fallback: String): String =
        r.note.ifBlank { r.bio }.ifBlank { fallback }

    fun load(pkg: String, name: String): Record {
        val own = Person.id(pkg, name)
        val id = canonical(own)
        return Record(
            id = id,
            name = if (id == own) name else nameOf(id),
            apps = (listOf(id.substringBefore('|')) + aliasesOf(id).map { it.substringBefore('|') }).distinct(),
            note = sp.getString("$id:note", "") ?: "",
            style = Person.loadStyle(sp.getString("$id:style", "") ?: ""),
            model = Learner.load(sp.getString("$id:learn", "") ?: ""),
            history = Person.loadHistory(sp.getString("$id:hist", "") ?: ""),
            stats = Relation.load(sp.getString("$id:stats", "") ?: ""),
            lastSeenText = sp.getString("$id:seen", "") ?: "",
            bio = sp.getString("$id:bio", "") ?: "",
            learned = sp.getInt("$id:learned", 0),
        )
    }

    fun save(r: Record) {
        val ids = index()
        sp.edit()
            .putString("${r.id}:name", r.name)
            .putString("${r.id}:note", r.note)
            .putString("${r.id}:style", Person.saveStyle(r.style))
            .putString("${r.id}:learn", Learner.save(r.model))
            .putString("${r.id}:hist", Person.saveHistory(r.history))
            .putString("${r.id}:stats", Relation.save(r.stats))
            .putString("${r.id}:seen", r.lastSeenText)
            .putString("${r.id}:bio", r.bio)
            .putInt("${r.id}:learned", r.learned)
            .putString("index", (ids + r.id).distinct().joinToString("\n"))
            .apply()
    }

    fun index(): List<String> =
        (sp.getString("index", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun nameOf(id: String): String = sp.getString("$id:name", null) ?: id.substringAfter('|')

    /**
     * Snap a freshly computed fingerprint onto the closest existing one for this app, so a
     * pixel-level wobble reuses the contact's memory instead of creating a stranger. Prefers the
     * record with the most history when several are within tolerance.
     */
    fun resolveFingerprint(pkg: String, hash: String, maxDist: Int = 3): String {
        if (!Person.isFingerprint(hash)) return hash
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var bestSeen = -1
        for (id in index()) {
            if (!id.startsWith("$pkg|#")) continue
            val existing = id.substringAfter('|')
            val d = Person.hamming(hash, existing)
            if (d > maxDist) continue
            val seen = Relation.load(sp.getString("$id:stats", "") ?: "").let { it.theirMsgs + it.myMsgs }
            if (d < bestDist || (d == bestDist && seen > bestSeen)) {
                best = existing; bestDist = d; bestSeen = seen
            }
        }
        return best ?: hash
    }

    fun forget(id: String) {
        val e = sp.edit()
            .remove("$id:name").remove("$id:note").remove("$id:style")
            .remove("$id:learn").remove("$id:hist").remove("$id:stats").remove("$id:seen")
            .remove("$id:bio").remove("$id:learned").remove("$id:aliases")
            .putString("index", index().filter { it != id }.joinToString("\n"))
        for (x in aliasesOf(id)) e.remove("link:$x")
        e.apply()
    }

    fun forgetAll() = sp.edit().clear().apply()

    /** One line per person, for the settings screen and the /people route. */
    fun summary(): String {
        val ids = index()
        if (ids.isEmpty()) return "还没有记住任何人"
        return ids.joinToString("\n") { id ->
            val r = loadId(id)
            val bits = listOfNotNull(
                Person.styleSummary(r.style),
                Relation.summary(r.stats),
                Learner.summary(r.model).first(),
                r.note.takeIf { it.isNotBlank() }?.let { "背景：$it" },
                r.bio.takeIf { it.isNotBlank() }?.let { "学到（${r.learned} 条）：${it.take(80)}" },
            )
            "${r.name}（${r.apps.joinToString(" · ") { Apps.label(it) }}）\n  " +
                (if (bits.isEmpty()) "刚认识，还没积累" else bits.joinToString("\n  "))
        }
    }
}
