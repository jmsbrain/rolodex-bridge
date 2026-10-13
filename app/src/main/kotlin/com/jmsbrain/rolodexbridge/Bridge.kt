package com.jmsbrain.rolodexbridge

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a build would do, for the screen before it starts. */
data class Preview(
    val people: Int,
    val noNumber: Int,
    val fromLightOs: Int,
    val lightCards: Int,
    val lightStrangers: Int,
    val lightOsNow: Int,
)

/**
 * Clean Contacts is the full list. Contacts Light is a copy with one number per card, and LightOS's address book
 * matches Contacts Light. Contacts made in LightOS are folded into Clean Contacts first.
 */
object Bridge {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What a running build is doing, for the screen; null when idle. */
    val progress: StateFlow<String?> get() = _progress
    private val _progress = MutableStateFlow<String?>(null)

    private enum class Mode {
        /** After a contacts change: only if LightOS has entries the bridge didn't make. */
        Quick,
        Full,
        /** The first time: also clears Contacts Light of cards the bridge didn't make. */
        Build,
    }

    suspend fun run(ctx: Context, full: Boolean): String? =
        if (!Settings.running(ctx)) null else pass(ctx, if (full) Mode.Full else Mode.Quick, dryRun = false).second

    suspend fun preview(ctx: Context): Pair<Preview?, String?> = pass(ctx, Mode.Build, dryRun = true)

    /** Starts the first build; it carries on if the screen closes, and picks up where it stopped if interrupted. */
    fun build(ctx: Context, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            val error = pass(app, Mode.Build, dryRun = false).second
            if (error == null) {
                Settings.setRunning(app, true)
                BridgeWork.start(app)
            }
            _progress.value = null
            withContext(Dispatchers.Main) { done(error) }
        }
    }

    private suspend fun pass(ctx: Context, mode: Mode, dryRun: Boolean): Pair<Preview?, String?> = locked(ctx) { env ->
        val (book, phone, dav, mainBook, lightBook) = env
        val state = State.load(ctx)
        val clean = State.loadClean(ctx)
        fun save() {
            if (!dryRun) State.save(ctx, state)
        }

        val ids = phone.ids(book)
        val ours = state.pieces.values.mapNotNull { it.rawId }.toSet()
        state.ignored.retainAll(ids)
        val loose = ids - ours - state.ignored
        if (mode == Mode.Quick && loose.isEmpty()) {
            save()
            return@locked null
        }

        // ---- Clean Contacts ----
        refreshClean(dav, mainBook, clean) { done, total -> say("Reading ${Settings.mainBookName(ctx)}: $done of $total") }
        // Only a cache, so the preview keeps it too and BUILD doesn't download everything again.
        State.saveClean(ctx, clean)
        var docs = parse(clean)
        var desired = piecesOf(docs)
        var covered = desired.values.mapNotNull { it.phoneKey }.toSet()

        // LightOS entries with a number Clean Contacts doesn't have, or a new person with no number, go into Clean Contacts.
        val looseRows = phone.read(loose)
        val people = docs.values.filter { !it.isGroup }.map { Person.of(it.rows()) }
        val newcomers = looseRows.filter { (_, rows) ->
            val keys = phoneKeys(rows)
            if (keys.isNotEmpty()) keys.any { it !in covered }
            else Person.of(rows).let { p -> !p.isEmpty && people.none { it.matches(p) } }
        }
        val gathered = Splitter.gather(newcomers.values.toList())

        if (dryRun) {
            val newKeys = gathered.flatMap { phoneKeys(it.rows) }.filter { it !in covered }.toSet()
            val strangers = dav.list(lightBook).keys.map { key(it) }.toSet() - state.pieces.values.mapNotNull { it.lightHref?.let(::key) }.toSet()
            return@locked Preview(
                people = people.size,
                noNumber = docs.values.count { !it.isGroup && it.rows().none { r -> r.mime == Phone.CONTENT_ITEM_TYPE } },
                fromLightOs = gathered.size,
                lightCards = desired.size + newKeys.size,
                lightStrangers = strangers.size,
                lightOsNow = ids.size,
            )
        }

        if (gathered.isNotEmpty()) {
            gathered.forEachIndexed { i, g ->
                say("Adding LightOS contacts to ${Settings.mainBookName(ctx)}: ${i + 1} of ${gathered.size}")
                addToClean(dav, mainBook, clean, docs, g, state)
            }
            State.saveClean(ctx, clean)
            docs = parse(clean)
            desired = piecesOf(docs)
            covered = desired.values.mapNotNull { it.phoneKey }.toSet()
        }

        // ---- Contacts Light ----
        val lightRaw = dav.list(lightBook)
        val listing = lightRaw.mapKeys { key(it.key) }
        val lightUrls = lightRaw.keys.associateBy { key(it) }
        if (mode == Mode.Build) {
            val mine = state.pieces.values.mapNotNull { it.lightHref?.let(::key) }.toSet()
            val strangers = listing.keys - mine
            strangers.forEachIndexed { i, k ->
                say("Clearing ${Settings.lightBookName(ctx)}: ${i + 1} of ${strangers.size}")
                dav.delete(lightUrls.getValue(k), null)
            }
        }
        var n = 0
        for (p in desired.values) {
            n++
            val m = state.pieces.getOrPut(p.key) { Managed() }
            val exists = m.lightHref?.let { key(it) in listing } == true
            if (exists && m.lightHash == p.hash) continue
            say("Updating ${Settings.lightBookName(ctx)}: $n of ${desired.size}")
            if (exists) {
                m.lightEtag = putLight(dav, m.lightHref!!, p, m.lightEtag ?: listing[key(m.lightHref!!)])
            } else {
                val (url, etag) = createLight(dav, lightBook, p)
                m.lightHref = url
                m.lightEtag = etag
            }
            m.lightHash = p.hash
            if (n % 20 == 0) save()
        }

        // ---- LightOS ----
        val present = phone.ids(book)
        val byNumber = HashMap<String, MutableList<Long>>()
        looseRows.forEach { (id, rows) -> if (id in present) phoneKeys(rows).forEach { byNumber.getOrPut(it) { ArrayList() } += id } }
        val adopted = HashSet<Long>()
        n = 0
        for (p in desired.values) {
            n++
            val m = state.pieces.getValue(p.key)
            var has = m.rawId != null && m.rawId in present
            if (!has) {
                // Reuse a LightOS entry with this number instead of adding another.
                val reuse = p.phoneKey?.let { k -> byNumber[k]?.firstOrNull { it !in adopted } }
                if (reuse != null) {
                    adopted += reuse
                    m.rawId = reuse
                    m.rawHash = null
                    has = true
                }
            }
            when {
                has && m.rawHash == p.hash -> continue
                has -> {
                    say("Updating LightOS: $n of ${desired.size}")
                    phone.update(m.rawId!!, p.rows)
                }
                // Gone from LightOS but unchanged here: deleted on the phone, so leave it deleted.
                m.rawId != null && m.rawHash == p.hash && mode != Mode.Build -> continue
                else -> {
                    say("Adding to LightOS: $n of ${desired.size}")
                    m.rawId = phone.add(book, p.rows)
                }
            }
            m.rawHash = p.hash
            if (n % 20 == 0) save()
        }

        // Pieces whose contact or number went away.
        for (k in state.pieces.keys - desired.keys) {
            val m = state.pieces.getValue(k)
            m.lightHref?.let { dav.delete(it, null) }
            m.rawId?.let { if (it in present) phone.delete(it) }
            state.pieces.remove(k)
        }

        // LightOS entries left over. The first build replaces the book, so duplicates of numbers that now have an entry go;
        // after that, nothing typed on the phone is deleted.
        for ((id, rows) in looseRows) {
            if (id in adopted || id !in present) continue
            val keys = phoneKeys(rows)
            if (mode == Mode.Build && keys.isNotEmpty() && keys.all { it in covered }) phone.delete(id) else state.ignored += id
        }

        state.people = docs.values.count { !it.isGroup }
        state.lastRun = System.currentTimeMillis()
        state.lastError = null
        save()
        null
    }

    // ---- Clean Contacts ----

    /** Brings [clean] up to date with the server, downloading only cards that changed. */
    private fun refreshClean(dav: CardDav, book: String, clean: MutableMap<String, CleanCard>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val listing = dav.list(book)
        val byKey = listing.keys.associateBy { key(it) }
        clean.keys.retainAll(byKey.keys)
        val stale = byKey.filter { (k, url) -> clean[k]?.let { it.etag == null || it.etag != listing[url] } ?: true }
        var done = 0
        progress(0, stale.size)
        for (chunk in stale.values.chunked(50)) {
            for (f in dav.multiget(book, chunk)) clean[key(f.url)] = CleanCard(f.url, f.etag, f.text)
            done += chunk.size
            progress(done, stale.size)
        }
    }

    private fun parse(clean: Map<String, CleanCard>): Map<String, VCardDoc> =
        clean.mapNotNull { (k, c) -> VCardDoc.parse(c.text).firstOrNull()?.let { k to it } }.toMap()

    private fun piecesOf(docs: Map<String, VCardDoc>): Map<String, Piece> =
        docs.filter { !it.value.isGroup }.flatMap { (k, d) -> Splitter.pieces(k, d.rows()) }.associateBy { it.key }

    /** Adds the numbers in [g] to the Clean Contacts card with the same name, or makes a new card. */
    private fun addToClean(dav: CardDav, book: String, clean: MutableMap<String, CleanCard>, docs: Map<String, VCardDoc>, g: Gathered, state: State) {
        val nameKey = Person.nameKey(g.name)
        val target = docs.entries.firstOrNull { (_, d) -> !d.isGroup && nameKey != null && nameKey in Person.of(d.rows()).names }
        val phones = g.rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE }
        val url: String
        val sent: List<String>
        if (target != null) {
            val card = clean.getValue(target.key)
            val existing = target.value.rows().filter { it.mime == Phone.CONTENT_ITEM_TYPE }
            val have = phoneKeys(existing).toSet()
            val add = phones.filter { p -> Person.phoneKey(p[Phone.NUMBER].orEmpty()).let { it == null || it !in have } }
            if (add.isEmpty()) return
            url = card.url
            sent = phoneKeys(add)
            dav.put(url, target.value.patched(mapOf(Phone.CONTENT_ITEM_TYPE to existing + add)).text(), card.etag, create = false)
        } else {
            val uid = UUID.randomUUID().toString()
            url = book.trimEnd('/') + "/" + uid + ".vcf"
            sent = phoneKeys(phones)
            dav.put(url, VCardDoc.fromRows(g.rows, uid).text(), null, create = true)
        }
        // mailbox.org has a fixed number of slots per kind of number; check what it kept.
        val stored = dav.get(url) ?: return
        clean[key(url)] = CleanCard(url, stored.etag, stored.text)
        val kept = VCardDoc.parse(stored.text).firstOrNull()?.rows()?.let { phoneKeys(it).toSet() }.orEmpty()
        val lost = sent.filter { it !in kept }
        if (lost.isNotEmpty()) {
            state.problems += "The server didn't keep ${lost.size} number(s) for ${g.name}; it has no room for more of that kind."
            while (state.problems.size > 20) state.problems.removeAt(0)
        }
    }

    // ---- Contacts Light ----

    private fun createLight(dav: CardDav, book: String, p: Piece): Pair<String, String?> {
        var attempt = 0
        while (true) {
            val uid = UUID.randomUUID().toString()
            val url = book.trimEnd('/') + "/" + uid + ".vcf"
            try {
                return url to dav.put(url, VCardDoc.fromRows(p.rows, uid).text(), null, create = true)
            } catch (e: DavError) {
                // 412: that name is taken; try another.
                if (e.status != 412 || ++attempt == 3) throw e
            }
        }
    }

    /** Overwrites a Contacts Light card; the bridge owns these, so a change made elsewhere is replaced. */
    private fun putLight(dav: CardDav, url: String, p: Piece, etag: String?): String? {
        val uid = url.substringAfterLast('/').removeSuffix(".vcf")
        val text = VCardDoc.fromRows(p.rows, uid).text()
        return try {
            dav.put(url, text, etag, create = false)
        } catch (e: DavError) {
            when (e.status) {
                412 -> dav.put(url, text, dav.get(url)?.etag, create = false)
                404 -> dav.put(url, text, null, create = true)
                else -> throw e
            }
        }
    }

    // ---- plumbing ----

    private fun phoneKeys(rows: List<SyncRow>): List<String> =
        rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.mapNotNull { Person.phoneKey(it[Phone.NUMBER].orEmpty()) }.distinct()

    private fun say(text: String) {
        _progress.value = text
    }

    private data class Env(val book: PhoneBook, val phone: PhoneContacts, val dav: CardDav, val mainBook: String, val lightBook: String)

    /** One pass at a time. Errors become the returned message and are saved. */
    private suspend fun <T> locked(ctx: Context, block: suspend (Env) -> T?): Pair<T?, String?> = lock.withLock {
        withContext(Dispatchers.IO) {
            val book = Settings.phoneBook(ctx) ?: return@withContext null to "Choose LightOS's address book."
            val main = Settings.mainBook(ctx) ?: return@withContext null to "Choose the full address book."
            val light = Settings.lightBook(ctx) ?: return@withContext null to "Choose the LightOS-shaped address book."
            val user = Settings.user(ctx)
            val password = Settings.password(ctx)
            if (user == null || password == null) return@withContext null to "Sign in again."
            try {
                block(Env(book, PhoneContacts(ctx.contentResolver), CardDav(user, password), main, light)) to null
            } catch (e: DavError) {
                fail(ctx, e.message ?: "Couldn't reach the server.")
            } catch (e: SecurityException) {
                fail(ctx, "Rolodex Bridge needs access to contacts.")
            } catch (e: Exception) {
                fail(ctx, "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun <T> fail(ctx: Context, message: String): Pair<T?, String?> {
        val state = State.load(ctx)
        state.lastError = message
        State.save(ctx, state)
        return null to message
    }

    /** Compares URLs by decoded path, since servers encode hrefs differently. */
    fun key(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.joinToString("/") ?: url
}
