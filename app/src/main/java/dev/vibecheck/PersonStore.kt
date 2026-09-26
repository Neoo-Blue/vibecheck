package dev.vibecheck

import android.content.Context
import org.json.JSONObject

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
        /** The newest messages already counted, oldest first, so rescans and scrolling do not double count. */
        var tail: List<Pair<String, String>> = emptyList(),
        /** What older versions stored instead of [tail]: the newest text seen. Used once, then dropped. */
        var legacySeen: String = "",
        /** Auto-written from a full history read (学习此人). Used when no hand-written note. */
        var bio: String = "",
        /** How many messages the history read covered, so the card can say "learned N". */
        var learned: Int = 0,
        /** Judging and watching are off for this person: nothing is sent, nothing is counted. */
        var muted: Boolean = false,
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
            name = if (id == own) name.trim() else nameOf(id),
            apps = (listOf(id.substringBefore('|')) + aliasesOf(id).map { it.substringBefore('|') }).distinct(),
            note = sp.getString("$id:note", "") ?: "",
            style = Person.loadStyle(sp.getString("$id:style", "") ?: ""),
            model = Learner.load(sp.getString("$id:learn", "") ?: ""),
            history = Person.loadHistory(sp.getString("$id:hist", "") ?: ""),
            stats = Relation.load(sp.getString("$id:stats", "") ?: ""),
            tail = Person.loadTail(sp.getString("$id:tail", "") ?: ""),
            legacySeen = sp.getString("$id:seen", "") ?: "",
            bio = sp.getString("$id:bio", "") ?: "",
            learned = sp.getInt("$id:learned", 0),
            muted = sp.getBoolean("$id:muted", false),
        )
    }

    fun save(r: Record) {
        val ids = index()
        val e = sp.edit()
            .putString("${r.id}:name", r.name)
            .putString("${r.id}:note", r.note)
            .putString("${r.id}:style", Person.saveStyle(r.style))
            .putString("${r.id}:learn", Learner.save(r.model))
            .putString("${r.id}:hist", Person.saveHistory(r.history))
            .putString("${r.id}:stats", Relation.save(r.stats))
            .putString("${r.id}:tail", Person.saveTail(r.tail))
            .putString("${r.id}:bio", r.bio)
            .putInt("${r.id}:learned", r.learned)
            .putBoolean("${r.id}:muted", r.muted)
        if (r.legacySeen.isEmpty()) e.remove("${r.id}:seen")
        if (r.id !in ids) e.putString("index", (ids + r.id).joinToString("\n"))
        e.apply()
    }

    fun index(): List<String> =
        (sp.getString("index", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun nameOf(id: String): String = sp.getString("$id:name", null) ?: id.substringAfter('|')

    fun isMuted(id: String): Boolean = sp.getBoolean("${canonical(id)}:muted", false)

    fun setMuted(id: String, muted: Boolean) = sp.edit().putBoolean("${canonical(id)}:muted", muted).apply()

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
            .remove("$id:tail").remove("$id:muted")
            .remove("$id:bio").remove("$id:learned").remove("$id:aliases")
            .putString("index", index().filter { it != id }.joinToString("\n"))
        for (x in aliasesOf(id)) e.remove("link:$x")
        e.apply()
    }

    fun forgetAll() = sp.edit().clear().apply()

    /** One row per person for the settings screen, most recently seen first. */
    class Entry(val id: String, val name: String, val apps: List<String>, val messages: Int, val lastSeen: Long, val muted: Boolean)

    fun entries(): List<Entry> = index().map { id ->
        val stats = Relation.load(sp.getString("$id:stats", "") ?: "")
        Entry(
            id, nameOf(id),
            (listOf(id.substringBefore('|')) + aliasesOf(id).map { it.substringBefore('|') }).distinct(),
            stats.theirMsgs + stats.myMsgs, stats.lastSeen, sp.getBoolean("$id:muted", false),
        )
    }.sortedByDescending { it.lastSeen }

    /**
     * Forget records with nothing in them: fewer than 3 messages, no note, no profile, nothing
     * learned. Misread titles and fingerprints of passing screens pile up as these.
     */
    fun cleanup(): Int {
        var n = 0
        for (id in index()) {
            val r = loadId(id)
            val total = r.stats.theirMsgs + r.stats.myMsgs
            if (total < 3 && r.note.isBlank() && r.bio.isBlank() && r.model.updates == 0 && !r.muted &&
                aliasesOf(id).isEmpty()) {
                forget(id); n++
            }
        }
        return n
    }

    /** One person in words, for the settings screen. */
    fun describe(id: String): String {
        val r = loadId(id)
        val bits = listOfNotNull(
            Person.styleSummary(r.style),
            Relation.summary(r.stats),
            Learner.summary(r.model).joinToString("\n  "),
            r.bio.takeIf { it.isNotBlank() }?.let { L.t("学到的档案（${r.learned} 条）：\n", "Profile (${r.learned} messages read):\n") + it },
        )
        return if (bits.isEmpty()) L.t("刚认识，还没积累", "Just met, nothing learned yet") else bits.joinToString("\n\n")
    }

    /** One line per person, for the /learn debug route. */
    fun summary(): String {
        val ids = index()
        if (ids.isEmpty()) return L.t("还没有记住任何人", "Nobody remembered yet")
        return ids.joinToString("\n") { id ->
            val r = loadId(id)
            val bits = listOfNotNull(
                Person.styleSummary(r.style),
                Relation.summary(r.stats),
                Learner.summary(r.model).first(),
                r.note.takeIf { it.isNotBlank() }?.let { L.t("背景：$it", "note: $it") },
                r.bio.takeIf { it.isNotBlank() }?.let { L.t("学到（${r.learned} 条）：${it.take(80)}", "learned (${r.learned} msgs): ${it.take(80)}") },
                L.t("已暂停", "paused").takeIf { r.muted },
            )
            "${r.name}（${r.apps.joinToString(" · ") { Apps.label(it) }}）\n  " +
                (if (bits.isEmpty()) L.t("刚认识，还没积累", "just met, nothing learned yet") else bits.joinToString("\n  "))
        }
    }

    // ---- backup: allowBackup is off, so moving to a new phone goes through a file ----

    /** Everything in this store as JSON, typed so it can be written back exactly. API keys live elsewhere and are not included. */
    fun exportJson(): String {
        val entries = JSONObject()
        for ((k, v) in sp.all) {
            when (v) {
                is String -> entries.put(k, JSONObject().put("s", v))
                is Int -> entries.put(k, JSONObject().put("i", v))
                is Boolean -> entries.put(k, JSONObject().put("b", v))
                is Long -> entries.put(k, JSONObject().put("l", v))
                is Float -> entries.put(k, JSONObject().put("f", v.toDouble()))
            }
        }
        return JSONObject().put("vibecheck", "people").put("version", 1).put("entries", entries).toString()
    }

    /** Replaces the whole store with an export. Returns how many people it holds; throws on a file that is not one. */
    fun importJson(text: String): Int {
        val root = JSONObject(text)
        require(root.optString("vibecheck") == "people") { "not a Vibecheck memory export" }
        val entries = root.getJSONObject("entries")
        val e = sp.edit().clear()
        for (k in entries.keys()) {
            val v = entries.getJSONObject(k)
            when {
                v.has("s") -> e.putString(k, v.getString("s"))
                v.has("i") -> e.putInt(k, v.getInt("i"))
                v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
                v.has("l") -> e.putLong(k, v.getLong("l"))
                v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
            }
        }
        e.commit()
        return index().size
    }
}
