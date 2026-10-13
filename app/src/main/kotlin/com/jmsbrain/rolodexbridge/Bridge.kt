package com.jmsbrain.rolodexbridge

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How many contacts a one-time copy would move each way. */
data class Missing(val toPhone: Int, val toServer: Int)

/** Copies new contacts between LightOS's address book and the mailbox.org address book. */
object Bridge {
    private val lock = Mutex()

    private enum class Mode {
        /** After a contacts change: new LightOS contacts only, and no network unless there are some. */
        Quick,
        /** New contacts both ways. */
        Full,
        /** One-time: every mailbox.org contact LightOS's address book doesn't have. */
        AllToPhone,
        /** One-time: every LightOS contact mailbox.org doesn't have. */
        AllToServer,
    }

    /** Runs a pass. Returns an error message, or null. */
    suspend fun run(ctx: Context, full: Boolean): String? =
        if (!Settings.running(ctx)) null else pass(ctx, if (full) Mode.Full else Mode.Quick).second

    /** Records where things stand: everything there now counts as already handled. */
    suspend fun start(ctx: Context): String? = locked(ctx) { book, phone, dav, server ->
        val state = State.load(ctx)
        state.seenPhone.clear()
        state.seenPhone += phone.ids(book)
        state.seenServer.clear()
        state.seenServer += refreshCards(dav, server, state).keys
        state.lastError = null
        State.save(ctx, state)
        Settings.setRunning(ctx, true)
        null
    }.second

    suspend fun missing(ctx: Context): Pair<Missing?, String?> {
        val (toPhone, e1) = pass(ctx, Mode.AllToPhone, dryRun = true)
        if (e1 != null) return null to e1
        val (toServer, e2) = pass(ctx, Mode.AllToServer, dryRun = true)
        if (e2 != null) return null to e2
        return Missing(toPhone.toPhone.size, toServer.toServer.size) to null
    }

    suspend fun copyAllToPhone(ctx: Context): String? = pass(ctx, Mode.AllToPhone).second
    suspend fun copyAllToServer(ctx: Context): String? = pass(ctx, Mode.AllToServer).second

    private suspend fun pass(ctx: Context, mode: Mode, dryRun: Boolean = false): Pair<Plan, String?> {
        var plan = Plan(emptyList(), emptyList(), emptySet(), emptySet())
        val error = locked(ctx) { book, phone, dav, serverBook ->
            val state = State.load(ctx)
            val ids = phone.ids(book)
            state.seenPhone.retainAll(ids)
            val newIds = ids - state.seenPhone
            if (mode == Mode.Quick && newIds.isEmpty()) {
                State.save(ctx, state)
                return@locked null
            }

            val urls = HashMap<String, String>()
            val server = refreshCards(dav, serverBook, state, urls)
            val rows = phone.read(if (mode == Mode.Quick) newIds else ids)
            val people = rows.mapValues { Person.of(it.value) }
            plan = when (mode) {
                Mode.Quick -> Planner.plan(people, server, state.seenPhone, server.keys)
                Mode.Full -> Planner.plan(people, server, state.seenPhone, state.seenServer)
                Mode.AllToPhone -> Planner.plan(people, server, ids, emptySet())
                Mode.AllToServer -> Planner.plan(people, server, emptySet(), server.keys)
            }
            if (dryRun) return@locked null

            for (id in plan.toServer) {
                val href = upload(dav, serverBook, rows[id].orEmpty())
                state.seenServer += key(href.first)
                state.cards[key(href.first)] = CachedCard(href.second, people.getValue(id))
                state.seenPhone += id
                state.sentToServer++
                State.save(ctx, state)
            }
            for (chunk in plan.toPhone.chunked(50)) {
                val cards = dav.multiget(serverBook, chunk.map { urls.getValue(it) }).associateBy { key(it.url) }
                for (k in chunk) {
                    val doc = cards[k]?.let { VCardDoc.parse(it.text).firstOrNull() } ?: continue
                    phone.add(book, doc.rows())?.let { state.seenPhone += it }
                    state.seenServer += k
                    state.addedToPhone++
                }
                State.save(ctx, state)
            }
            state.seenPhone += plan.knownPhone
            state.seenServer += plan.knownServer
            state.lastRun = System.currentTimeMillis()
            state.lastError = null
            State.save(ctx, state)
            null
        }.second
        return plan to error
    }

    /** Creates a card from LightOS's rows. Returns its URL and ETag. */
    private fun upload(dav: CardDav, book: String, rows: List<SyncRow>): Pair<String, String?> {
        var attempt = 0
        while (true) {
            val uid = UUID.randomUUID().toString()
            val url = book.trimEnd('/') + "/" + uid + ".vcf"
            try {
                return url to dav.put(url, VCardDoc.fromRows(rows, uid).text(), null, create = true)
            } catch (e: DavError) {
                // 412: that name is taken; try another.
                if (e.status != 412 || ++attempt == 3) throw e
            }
        }
    }

    /** The mailbox.org book: href key → person. Only cards that changed since last time are downloaded. */
    private fun refreshCards(dav: CardDav, book: String, state: State, urls: MutableMap<String, String> = HashMap()): Map<String, Person> {
        val listing = dav.list(book).mapKeys { (url, _) -> key(url).also { urls[it] = url } }
        state.cards.keys.retainAll(listing.keys)
        val stale = listing.filter { (k, etag) -> state.cards[k]?.let { it.etag == null || it.etag != etag } ?: true }.keys
        for (chunk in stale.chunked(100)) {
            for (f in dav.multiget(book, chunk.map { urls.getValue(it) })) {
                val doc = VCardDoc.parse(f.text).firstOrNull() ?: continue
                // Contact groups aren't people; an empty person is never copied.
                val person = if (doc.isGroup) Person(emptySet(), emptySet(), emptySet()) else Person.of(doc.rows())
                state.cards[key(f.url)] = CachedCard(f.etag, person)
            }
        }
        return listing.keys.associateWith { state.cards[it]?.person ?: Person(emptySet(), emptySet(), emptySet()) }
    }

    /** One pass at a time, with everything it needs. Errors become the returned message and are saved. */
    private suspend fun <T> locked(
        ctx: Context,
        block: suspend (PhoneBook, PhoneContacts, CardDav, String) -> T?,
    ): Pair<T?, String?> = lock.withLock {
        withContext(Dispatchers.IO) {
            val book = Settings.phoneBook(ctx) ?: return@withContext null to "Choose LightOS's address book."
            val serverBook = Settings.serverBook(ctx) ?: return@withContext null to "Choose a mailbox.org address book."
            val user = Settings.user(ctx)
            val password = Settings.password(ctx)
            if (user == null || password == null) return@withContext null to "Sign in again."
            try {
                block(book, PhoneContacts(ctx.contentResolver), CardDav(user, password), serverBook) to null
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
