package dev.vibecheck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    /** Pictures of names OCR cannot read (emoji), cut from the chat's title bar. */
    private val pictureDir = File(ctx.filesDir, "names")
    /** Decoded pictures with the file time they were read at: the service and the app each hold one. */
    private val pictures = HashMap<String, Pair<Long, Bitmap>>()

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
        /** When the profile was last written, or 0 for never. */
        var learnedAt: Long = 0L,
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
        /** How they write, from their messages as they come and from history reads. */
        var theirStyle: Person.Style = Person.Style(),
        /** How a turn with them usually reads, from every turn judged. */
        var norm: Person.Norm = Person.Norm(),
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
        Person.mergeStyle(b.theirStyle, a.theirStyle)
        Person.mergeNorm(b.norm, a.norm)
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
            tail = Archive.withoutQuotes(Archive.withoutCard(Archive.withoutStrips(Person.loadTail(sp.getString("$id:tail", "") ?: ""))), quoteNames(own), emojiNamed(own)),
            legacySeen = sp.getString("$id:seen", "") ?: "",
            bio = bio,
            learned = sp.getInt("$id:learned", 0),
            learnedAt = sp.getLong("$id:learnedat", 0L),
            muted = sp.getBoolean("$id:muted", false),
            rel = rel,
            relByHand = sp.getBoolean("$id:relset", false),
            close = close,
            closeByHand = sp.getBoolean("$id:closeset", false),
            alias = sp.getString("$id:alias", "") ?: "",
            own = own,
            theirStyle = Person.loadStyle(sp.getString("$id:tstyle", "") ?: ""),
            norm = Person.loadNorm(sp.getString("$id:norm", "") ?: ""),
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
            .putLong("${r.id}:learnedat", r.learnedAt)
            .putBoolean("${r.id}:muted", r.muted)
            .putString("${r.id}:rel", r.rel)
            .putBoolean("${r.id}:relset", r.relByHand)
            .putString("${r.id}:close", r.close)
            .putBoolean("${r.id}:closeset", r.closeByHand)
            .putString("${r.id}:alias", r.alias)
            .putString("${r.id}:tstyle", Person.saveStyle(r.theirStyle))
            .putString("${r.id}:norm", Person.saveNorm(r.norm))
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

    /**
     * What to call them in words: your name for them (or the one their notifications gave), else
     * the chat title. A name that could not be read is never shown as its internal code.
     */
    fun shownName(id: String): String {
        aliasOf(id).takeIf { it.isNotBlank() }?.let { return it }
        val n = nameOf(canonical(id))
        return if (Person.isFingerprint(n)) L.t("未命名联系人", "Unnamed contact") else n
    }

    private fun pictureFile(id: String) = File(pictureDir, Archive.hash(canonical(id)) + ".png")

    /** The picture of an unreadable name, to show, or null. Only for people with no name in words. */
    fun namePicture(id: String): Bitmap? = if (aliasOf(id).isNotBlank()) null else pictureOf(id)

    /**
     * The picture of an unreadable name as kept, also for someone named from a notification: it
     * is how their chat is found when none of their messages is on screen.
     */
    fun pictureOf(id: String): Bitmap? {
        val c = canonical(id)
        if (!Person.isFingerprint(nameOf(c))) return null
        // Replaced or deleted by the other process (service or app) since it was read: read again.
        val stamp = pictureFile(c).lastModified()
        if (stamp == 0L) { pictures.remove(c); return null }
        pictures[c]?.takeIf { it.first == stamp }?.let { return it.second }
        val picture = runCatching { BitmapFactory.decodeFile(pictureFile(c).path) }.getOrNull() ?: return null
        // Kept by an older version from a strip under the title bar: gone, so a right one is taken.
        if (!Person.plausiblePicture(picture.width, picture.height)) {
            deleteNamePicture(c)
            return null
        }
        pictures[c] = stamp to picture
        return picture
    }

    /** Person.emojiPrint of [pictureOf], worked out once per picture. */
    fun namePrint(id: String): IntArray? {
        val c = canonical(id)
        val picture = pictureOf(c) ?: return null
        prints[c]?.takeIf { it.first === picture }?.let { return it.second }
        val px = IntArray(picture.width * picture.height)
        picture.getPixels(px, 0, picture.width, 0, 0, picture.width, picture.height)
        return Person.emojiPrint(px, picture.width, picture.height).also { prints[c] = picture to it }
    }

    private val prints = HashMap<String, Pair<Bitmap, IntArray?>>()

    fun hasNamePicture(id: String): Boolean = pictureFile(id).exists()

    fun saveNamePicture(id: String, picture: Bitmap) {
        val c = canonical(id)
        runCatching {
            pictureDir.mkdirs()
            pictureFile(c).outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        pictures[c] = pictureFile(c).lastModified() to picture
    }

    /** A wrong picture of their name goes; the next clear looks at the chat's title bar take a new one. */
    fun deleteNamePicture(id: String) {
        val c = canonical(id)
        pictures.remove(c)
        runCatching { pictureFile(c).delete() }
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
     * The person an older-style avatar fingerprint names, allowing a few bits of wobble, or null
     * when nobody is that close: it never makes up someone new.
     * Records merged into another are looked at too, and give the one they were merged into.
     */
    fun existingFingerprint(pkg: String, hash: String, maxDist: Int = 3): String? {
        if (!Person.isFingerprint(hash)) return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var bestSeen = -1
        for (id in fingerprintIds(pkg)) {
            val d = Person.hamming(hash, id.substringAfter('|'))
            if (d > maxDist) continue
            val c = canonical(id)
            val seen = Relation.load(sp.getString("$c:stats", "") ?: "").let { it.theirMsgs + it.myMsgs }
            if (d < bestDist || (d == bestDist && seen > bestSeen)) { best = c.substringAfter('|'); bestDist = d; bestSeen = seen }
        }
        return best
    }

    /** Everyone in [pkg] known by a fingerprint, as listed (not those merged into someone). */
    fun fingerprintPeople(pkg: String): List<String> = index().filter { it.startsWith("$pkg|#") }

    /** Everyone in [pkg] known by a fingerprint: in the list, or merged into someone who is. */
    private fun fingerprintIds(pkg: String): List<String> =
        index().filter { it.startsWith("$pkg|#") } + sp.all.keys.filter { it.startsWith("link:$pkg|#") }.map { it.removePrefix("link:") }

    private fun printLinks(pkg: String): List<Pair<String, String>> =
        (sp.getString("plinks:$pkg", "") ?: "").lineSequence()
            .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toList()

    /**
     * The inside of an avatar ([Person.avatarHash], or [Person.insideHash] before it) remembered
     * against the person it belongs to.
     */
    fun rememberPrint(pkg: String, hash: String, name: String) {
        val links = printLinks(pkg)
        // A bit of wobble on an avatar already remembered for them is not worth rewriting the store.
        val wobble = if (Person.isAvatarHash(hash)) Person.AVATAR_SAME / 2 else 1
        if (links.any { it.second == name && Person.distance(it.first, hash) <= wobble }) return
        val kept = (links.filter { it.first != hash } + (hash to name)).takeLast(300)
        sp.edit().putString("plinks:$pkg", kept.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    /** [name] has been seen with an avatar fingerprint of the newest kind ([Person.avatarHash]), or is named by one. */
    fun hasAvatarPrint(pkg: String, name: String): Boolean {
        val c = canonical("$pkg|$name")
        if (Person.isAvatarHash(c.substringAfter('|'))) return true
        return printLinks(pkg).any { Person.isAvatarHash(it.first) && canonical("$pkg|${it.second}") == c }
    }

    /**
     * Whose avatar this is by its inside, and how far off ([Person.distance]): from the
     * remembered links, and from people named by such a fingerprint in the first place. The one
     * they were merged into, if they were. Only fingerprints of the same kind as [hash] can be
     * near it.
     */
    fun printOwner(pkg: String, hash: String, maxDist: Int = 3): Pair<String, Int>? {
        val named = fingerprintIds(pkg).map { it.substringAfter('|') }
            .filter { Person.isInsideHash(it) || Person.isAvatarHash(it) }.map { it to it }
        return (printLinks(pkg) + named)
            .map { (h, name) -> name to Person.distance(h, hash) }
            .filter { it.second <= maxDist }
            .minByOrNull { it.second }
            ?.let { (name, d) -> canonical("$pkg|$name").substringAfter('|') to d }
    }

    /**
     * The fingerprint a chat with this avatar ([Person.avatarHash]) may have been filed under by
     * mistake: its name is words, which up to 6.9.0 went unread when they sat low in the title bar
     * (a name in small letters), and the chat went as one named by an emoji. The fingerprint the
     * avatar is remembered for, when the picture kept for its name has no colour to it, as words
     * have none and emoji do. Null when there is none.
     */
    fun misfiled(pkg: String, avatar: String): String? {
        val owner = printOwner(pkg, avatar, Person.AVATAR_SAME)?.first ?: return null
        if (!Person.isFingerprint(owner)) return null
        val id = "$pkg|$owner"
        return owner.takeIf { pictureOf(id) != null && namePrint(id) == null }
    }

    /**
     * The chat [from], filed under a fingerprint, is [to] (see [misfiled]): what is known about the
     * person goes to [to] as [link] takes it, and so does what belongs to the chat itself: its
     * kept history, the newest messages already counted, the notes already written on it, whether
     * it is paused. The fingerprint then leads to [to], so the avatar still finds the chat on a
     * look that cannot read its name.
     */
    fun adopt(from: String, to: String) {
        val into = canonical(to)
        if (from == into || canonical(from) != from) return
        val tail = sp.getString("$from:tail", null)
        val notes = sp.getString("$from:notes", null)
        val muted = sp.getBoolean("$from:muted", false)
        val learnedAt = sp.getLong("$from:learnedat", 0L)
        val archived = sp.getInt("$from:archived", 0)
        val complete = sp.getBoolean("$from:archivedone", false)
        // Kept under the chat it was read from: the history file goes with the chat, where more is
        // added to it and where a reply looks for what came before the screen.
        val moved = file(from).exists() && !file(to).exists() && runCatching { file(from).renameTo(file(to)) }.getOrDefault(false)
        link(from, into)
        val e = sp.edit()
        if (moved) {
            e.putInt("$to:archived", archived).putBoolean("$to:archivedone", complete)
                .remove("$from:archived").remove("$from:archivedone")
        }
        if (!tail.isNullOrEmpty() && sp.getString("$into:tail", null).isNullOrEmpty()) e.putString("$into:tail", tail)
        if (!notes.isNullOrEmpty() && sp.getString("$into:notes", null).isNullOrEmpty()) e.putString("$into:notes", notes)
        if (muted) e.putBoolean("$into:muted", true)
        if (learnedAt > sp.getLong("$into:learnedat", 0L)) e.putLong("$into:learnedat", learnedAt)
        e.apply()
    }

    /** How much is known about someone in [pkg], by the name their record goes under. */
    fun known(pkg: String, name: String): Person.Known {
        val c = canonical("$pkg|$name")
        val stats = Relation.load(sp.getString("$c:stats", "") ?: "")
        val learned = sp.getInt("$c:learned", 0).takeIf { it > 0 } ?: if ((sp.getString("$c:bio", "") ?: "").isNotBlank()) 1 else 0
        return Person.Known(c.substringAfter('|'), learned, stats.theirMsgs + stats.myMsgs)
    }

    fun forget(id: String, keepArchive: Boolean = false) {
        if (!keepArchive) deleteArchive(id)
        runCatching { pictureFile(id).delete() }
        pictures.remove(id)
        val e = sp.edit()
            .remove("$id:name").remove("$id:note").remove("$id:style")
            .remove("$id:learn").remove("$id:hist").remove("$id:stats").remove("$id:seen")
            .remove("$id:tail").remove("$id:muted").remove("$id:rel").remove("$id:relset")
            .remove("$id:close").remove("$id:closeset").remove("$id:alias").remove("$id:notes")
            .remove("$id:bio").remove("$id:learned").remove("$id:aliases").remove("$id:tstyle").remove("$id:norm")
            .putString("index", index().filter { it != id }.joinToString("\n"))
        for (x in aliasesOf(id)) e.remove("link:$x")
        e.apply()
    }

    fun forgetAll() {
        runCatching { dir.deleteRecursively() }
        runCatching { pictureDir.deleteRecursively() }
        pictures.clear()
        sp.edit().clear().apply()
    }

    // ---- kept histories ----

    private fun file(own: String) = File(dir, Archive.hash(own) + ".txt")

    /** The kept history of one chat, oldest first; empty when it has none. */
    fun archive(own: String): List<Pair<String, String>> =
        runCatching {
            file(own).takeIf { it.exists() }?.readText()?.let { text ->
                // Kept before they were known not to be messages: call records (「已取消」, "Canceled O"),
                // voice lengths, Soul's quick replies and its card of the other person, and quotes
                // kept as the replier's own words.
                Archive.withoutQuotes(Archive.withoutCard(Archive.withoutStrips(Archive.decode(text))), quoteNames(own), emojiNamed(own))
                    .filterNot { Chat.isNotification(it.second) }
            }
        }.getOrNull() ?: emptyList()

    /**
     * The names quotes in the chat [own] start with: theirs as its title reads, the one given
     * them, and mine in that app (see [myNames]).
     */
    fun quoteNames(own: String): List<String> {
        val pkg = own.substringBefore('|')
        val name = own.substringAfter('|')
        return listOfNotNull(name.takeUnless { Person.isFingerprint(it) }, aliasOf(own).takeIf { it.isNotBlank() }) + myNames(pkg)
    }

    /** The chat [own] is named by a fingerprint: its title is an emoji name OCR cannot read. */
    fun emojiNamed(own: String): Boolean = Person.isFingerprint(own.substringAfter('|'))

    /**
     * My own name in [pkg], as its quotes of my messages spell it (Chat.myQuotedNames), newest
     * last. A quote of my words that starts with one of these is mine, even with my message long
     * off screen.
     */
    fun myNames(pkg: String): List<String> =
        (sp.getString("mynames:$pkg", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun rememberMyName(pkg: String, name: String) {
        val names = myNames(pkg)
        if (names.any { Chat.sameName(it, name) }) return
        // A few: a name changed now and then, or read a little differently by OCR.
        sp.edit().putString("mynames:$pkg", (names + name.trim()).takeLast(5).joinToString("\n")).apply()
        Diag.log("my name in $pkg: $name")
    }

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

    /**
     * Up to 6.8.8 a chat with none of their messages on screen was found by a fingerprint of
     * the title bar's middle. Every emoji name, one bright or dark shape in the middle of a plain
     * bar, gave that fingerprint the same few bits, so the chat went to whoever had been seen
     * last; while it did, that person's name picture was replaced with the other chat's. Those
     * links go, and so do the kept name pictures, which may be the wrong person's: each is taken
     * again from the next looks at its chat with their messages in sight.
     */
    private fun forgetTitleLinks() {
        if (sp.getInt("namesv", 0) >= 2) return
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("tlinks:")) e.remove(k)
        e.putInt("namesv", 2).apply()
        runCatching { pictureDir.listFiles()?.forEach { it.delete() } }
        pictures.clear()
    }

    /**
     * Up to 6.9.1 a screen that is not a chat could be read as one and a label in its title bar
     * kept as the person: WeChat's Moments and official accounts, the photo picker, the clock,
     * Soul's follow button, 「1分钟前」 (Person.isLabel). Those records go, unless something was
     * learned, written or kept for them.
     */
    private fun forgetLabels() {
        if (sp.getInt("labelsv", 0) >= 1) return
        for (id in index()) {
            val name = id.substringAfter('|')
            if (Person.isFingerprint(name) || !Person.isLabel(name)) continue
            val kept = sp.getInt("$id:learned", 0) > 0 || file(id).exists() || aliasesOf(id).isNotEmpty() ||
                listOf("bio", "note", "alias").any { !sp.getString("$id:$it", "").isNullOrBlank() }
            if (!kept) forget(id)
        }
        sp.edit().putInt("labelsv", 1).apply()
    }

    init { forgetTitleLinks(); forgetLabels() }

    /** One row per person for the settings screen, most recently seen first. */
    /**
     * One person as the People list shows them: [learned] messages read into a profile, [kind]
     * "朋友 · 很铁" when known, and one line from the profile.
     */
    class Entry(
        val id: String,
        val name: String,
        val apps: List<String>,
        val messages: Int,
        val lastSeen: Long,
        val muted: Boolean,
        val learned: Int = 0,
        val kind: String? = null,
        val line: String? = null,
    )

    fun entries(): List<Entry> = index().map { id ->
        val stats = Relation.load(sp.getString("$id:stats", "") ?: "")
        val bio = sp.getString("$id:bio", "") ?: ""
        val (rel, close) = relationStored(id, bio)
        Entry(
            id, shownName(id),
            (listOf(id.substringBefore('|')) + aliasesOf(id).map { it.substringBefore('|') }).distinct(),
            stats.theirMsgs + stats.myMsgs, stats.lastSeen, sp.getBoolean("$id:muted", false),
            learned = sp.getInt("$id:learned", 0),
            kind = listOfNotNull(Relationship.pinned(rel)?.let { L.label(it) }, Relationship.closeness(close)?.let { L.label(it) })
                .joinToString(" · ").ifBlank { null },
            line = Profile.oneLine(bio),
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
            "${shownName(id)}（${r.apps.joinToString(" · ") { Apps.label(it) }}）\n  " +
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
