package dev.vibecheck

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-person memory, all on the phone. One SharedPreferences file, flat keys per person, so
 * there is no schema to migrate and a corrupt entry can only lose that one person.
 */
class PersonStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("people", Context.MODE_PRIVATE)
    /** Kept histories (Archive), one file per chat, in app-private storage. */
    private val dir = File(ctx.filesDir, "archive")

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
        /**
         * Who they are to me (Relationship.KEYS), or "" to work it out from each chat. Learned from
         * the history read, or set by hand; when set, Jev is told instead of asked.
         */
        var rel: String = "",
        /** [rel] was chosen by hand, so a new history read leaves it alone. */
        var relByHand: Boolean = false,
        /** How close we are (Relationship.CLOSENESS), or "" when not known. */
        var close: String = "",
        var closeByHand: Boolean = false,
        /** A name you gave them, for a chat whose own name cannot be read (emoji) or reads wrong. */
        var alias: String = "",
        /** Every app this person has been seen on: their own plus any linked records. */
        val apps: List<String> = listOf(id.substringBefore('|')),
        /** The id this chat was opened under; differs from [id] for a chat linked into another. */
        val own: String = id,
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
        if (!b.relByHand && (b.rel.isBlank() || a.relByHand) && a.rel.isNotBlank()) { b.rel = a.rel; b.relByHand = a.relByHand }
        if (!b.closeByHand && (b.close.isBlank() || a.closeByHand) && a.close.isNotBlank()) { b.close = a.close; b.closeByHand = a.closeByHand }
        if (b.alias.isBlank()) b.alias = a.alias
        b.learned += a.learned
        val aliases = (aliasesOf(intoId) + aliasId + aliasesOf(aliasId)).distinct()
        // Each chat keeps its own history file: it goes on growing under that chat's id.
        forget(aliasId, keepArchive = true)
        val e = sp.edit()
        for (x in aliases) e.putString("link:$x", intoId)
        e.putString("$intoId:aliases", aliases.joinToString("\n")).apply()
        save(b)
    }

    fun loadId(id: String): Record = load(id.substringBefore('|'), id.substringAfter('|'))

    /**
     * What the judge should be told about this person: your note and the short form of the
     * learned profile, both; the general context only for someone with neither. A note used to
     * replace the profile, so writing one line about a person threw away everything a history
     * read had found.
     */
    fun background(r: Record, fallback: String): String =
        listOf(r.note.trim(), Profile.brief(r.bio)).filter { it.isNotEmpty() }.joinToString("\n").ifBlank { fallback }

    fun load(pkg: String, name: String): Record {
        val own = Person.id(pkg, name)
        val id = canonical(own)
        val bio = sp.getString("$id:bio", "") ?: ""
        val (rel, close) = relationStored(id, bio)
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
            bio = bio,
            learned = sp.getInt("$id:learned", 0),
            muted = sp.getBoolean("$id:muted", false),
            rel = rel,
            relByHand = sp.getBoolean("$id:relset", false),
            close = close,
            closeByHand = sp.getBoolean("$id:closeset", false),
            alias = sp.getString("$id:alias", "") ?: "",
            own = own,
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
            .putString("${r.id}:rel", r.rel)
            .putBoolean("${r.id}:relset", r.relByHand)
            .putString("${r.id}:close", r.close)
            .putBoolean("${r.id}:closeset", r.closeByHand)
            .putString("${r.id}:alias", r.alias)
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
     * The stored relationship and closeness. Profiles from before there were any say it in words
     * on their first line, so those people get theirs without a new history read (saved with the
     * next save).
     */
    private fun relationStored(id: String, bio: String): Pair<String, String> {
        val rel = sp.getString("$id:rel", null)
        val close = sp.getString("$id:close", null)
        val old = if (rel == null || close == null) Relationship.parse(bio) else null
        return Relationship.pinned(rel ?: old?.rel).orEmpty() to Relationship.closeness(close ?: old?.close).orEmpty()
    }

    /** Who they are to me, or "" when it is worked out from each chat. */
    fun relationOf(id: String): String = canonical(id).let { c -> relationStored(c, sp.getString("$c:bio", "") ?: "").first }

    /** How close we are, or "" when not known. */
    fun closenessOf(id: String): String = canonical(id).let { c -> relationStored(c, sp.getString("$c:bio", "") ?: "").second }

    /** Chosen by hand ("" hands it back to history reads). */
    fun setCloseness(id: String, key: String) {
        val c = canonical(id)
        val level = Relationship.closeness(key)
        sp.edit().putString("$c:close", level.orEmpty()).putBoolean("$c:closeset", level != null).apply()
    }

    /** The name you gave them, or "". */
    fun aliasOf(id: String): String = sp.getString("${canonical(id)}:alias", "") ?: ""

    fun setAlias(id: String, alias: String) = sp.edit().putString("${canonical(id)}:alias", alias.trim().take(24)).apply()

    /** What to call them: your name for them, else the chat title; an unreadable one says so. */
    fun shownName(id: String): String {
        aliasOf(id).takeIf { it.isNotBlank() }?.let { return it }
        val n = nameOf(canonical(id))
        return if (Person.isFingerprint(n)) L.t("未命名联系人 ", "Unnamed contact ") + n.take(5) else n
    }

    /**
     * Chosen by hand, so a later history read leaves it alone. "" hands it back: the next read
     * fills it in, and until then each chat is judged on its own.
     */
    fun setRelation(id: String, key: String) {
        val c = canonical(id)
        val pinned = Relationship.pinned(key)
        sp.edit().putString("$c:rel", pinned.orEmpty()).putBoolean("$c:relset", pinned != null).apply()
    }

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

    fun forget(id: String, keepArchive: Boolean = false) {
        if (!keepArchive) deleteArchive(id)
        val e = sp.edit()
            .remove("$id:name").remove("$id:note").remove("$id:style")
            .remove("$id:learn").remove("$id:hist").remove("$id:stats").remove("$id:seen")
            .remove("$id:tail").remove("$id:muted").remove("$id:rel").remove("$id:relset")
            .remove("$id:close").remove("$id:closeset").remove("$id:alias").remove("$id:notes")
            .remove("$id:bio").remove("$id:learned").remove("$id:aliases")
            .putString("index", index().filter { it != id }.joinToString("\n"))
        for (x in aliasesOf(id)) e.remove("link:$x")
        e.apply()
    }

    fun forgetAll() {
        runCatching { dir.deleteRecursively() }
        sp.edit().clear().apply()
    }

    // ---- kept histories ----

    private fun file(own: String) = File(dir, Archive.hash(own) + ".txt")

    /** The kept history of one chat, oldest first; empty when it has none. */
    fun archive(own: String): List<Pair<String, String>> =
        runCatching { file(own).takeIf { it.exists() }?.readText()?.let { Archive.decode(it) } }.getOrNull() ?: emptyList()

    /** Everything kept for a person: their chat's history and every linked chat's. */
    fun archiveAll(id: String): List<Pair<String, String>> {
        val c = canonical(id)
        return (listOf(c) + aliasesOf(c)).distinct().flatMap { archive(it) }
    }

    fun hasArchive(own: String): Boolean = file(own).exists()

    /** Did the history read that wrote it reach the very first message? */
    fun archiveComplete(own: String): Boolean = sp.getBoolean("$own:archivedone", false) && hasArchive(own)

    /**
     * How many messages are kept for a person, across linked chats. Only files that are there
     * count: a memory backup brings the numbers to a new phone, not the histories.
     */
    fun archiveCount(id: String): Int {
        val c = canonical(id)
        return (listOf(c) + aliasesOf(c)).distinct().sumOf { if (hasArchive(it)) sp.getInt("$it:archived", 0) else 0 }
    }

    /** Written whole, through a temporary file, so a crash cannot leave half a history. */
    fun saveArchive(own: String, lines: List<Pair<String, String>>, complete: Boolean) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file(own).name + ".tmp")
            tmp.writeText(Archive.encode(lines))
            if (!tmp.renameTo(file(own))) { file(own).delete(); tmp.renameTo(file(own)) }
        }
        sp.edit().putInt("$own:archived", lines.size).putBoolean("$own:archivedone", complete).apply()
    }

    /** New messages seen live, added to a chat whose history is kept. Nothing is started for anyone else. */
    fun appendArchive(own: String, lines: List<Pair<String, String>>) {
        val f = file(own)
        if (lines.isEmpty() || !f.exists()) return
        if (runCatching { f.appendText(Archive.encode(lines)) }.isSuccess)
            sp.edit().putInt("$own:archived", sp.getInt("$own:archived", 0) + lines.size).apply()
    }

    /** Deletes the kept histories of a person (every linked chat) and the notes written from them. */
    fun deleteArchive(id: String) {
        val c = canonical(id)
        val e = sp.edit().remove("$c:notes")
        for (own in (listOf(c) + aliasesOf(c)).distinct()) {
            runCatching { file(own).delete() }
            e.remove("$own:archived").remove("$own:archivedone")
        }
        e.apply()
    }

    /** Notes already written on stretches of this person's history, by the stretch's hash. */
    fun notes(id: String): Map<String, String> = Profile.loadNotes(sp.getString("${canonical(id)}:notes", "") ?: "")

    fun saveNotes(id: String, notes: Map<String, String>) =
        sp.edit().putString("${canonical(id)}:notes", Profile.saveNotes(notes)).apply()

    // ---- chats whose name cannot be read ----

    private fun titleLinks(pkg: String): List<Pair<String, String>> =
        (sp.getString("tlinks:$pkg", "") ?: "").lineSequence()
            .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toList()

    /**
     * The title bar of a chat whose name cannot be read, remembered against the avatar that
     * identifies it, so a screen with only my own messages (no avatar in sight) still finds them.
     */
    fun rememberTitle(pkg: String, titleHash: String, name: String) {
        if (titleOwner(pkg, titleHash) == name) return
        val links = (titleLinks(pkg).filter { it.first != titleHash } + (titleHash to name)).takeLast(200)
        sp.edit().putString("tlinks:$pkg", links.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    /** Whose avatar a title bar was seen with, allowing a couple of bits of rendering wobble. */
    fun titleOwner(pkg: String, titleHash: String, maxDist: Int = 2): String? =
        titleLinks(pkg).map { it to Person.hamming(it.first, titleHash) }
            .filter { it.second <= maxDist }
            .minByOrNull { it.second }?.first?.second

    /** One row per person for the settings screen, most recently seen first. */
    class Entry(val id: String, val name: String, val apps: List<String>, val messages: Int, val lastSeen: Long, val muted: Boolean)

    fun entries(): List<Entry> = index().map { id ->
        val stats = Relation.load(sp.getString("$id:stats", "") ?: "")
        Entry(
            id, shownName(id),
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
            if (total < 3 && r.note.isBlank() && r.bio.isBlank() && r.model.updates == 0 && !r.muted && !r.relByHand &&
                !r.closeByHand && r.alias.isBlank() && !hasArchive(id) &&
                aliasesOf(id).isEmpty()) {
                forget(id); n++
            }
        }
        return n
    }

    /** One person in words, for the settings screen. */
    fun describe(id: String): String {
        val r = loadId(id)
        val kept = archiveCount(id)
        val bits = listOfNotNull(
            kept.takeIf { it > 0 }?.let { L.t("聊天记录存档：$it 条（只存在这台手机上）", "Kept history: $it messages (on this phone only)") },
            Person.styleSummary(r.style),
            Relation.summary(r.stats),
            Learner.summary(r.model).joinToString("\n  "),
            r.bio.takeIf { it.isNotBlank() }?.let { L.t("学到的档案（${r.learned} 条）：\n", "Profile (${r.learned} messages read):\n") + it },
        )
        return if (bits.isEmpty()) L.t("刚认识，还没积累", "Just met, nothing learned yet") else bits.joinToString("\n\n")
    }

    /** "关系：朋友 · 很铁（学习得出）", or null when neither is known. */
    fun relationLine(r: Record): String? {
        val key = Relationship.pinned(r.rel)
        val close = Relationship.closeness(r.close)
        if (key == null && close == null) return null
        val what = listOfNotNull(key?.let { L.label(it) }, close?.let { L.label(it) }).joinToString(" · ")
        val how = if (r.relByHand || r.closeByHand) L.t("（你设定的）", " (set by you)") else L.t("（学习得出）", " (learned)")
        return L.t("关系：", "Relationship: ") + what + how
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
                relationLine(r),
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
