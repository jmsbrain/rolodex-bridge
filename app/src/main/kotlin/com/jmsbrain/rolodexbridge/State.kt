package com.jmsbrain.rolodexbridge

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A mailbox.org card as last seen: its ETag and what it's matched on. */
data class CachedCard(val etag: String?, val person: Person)

/** What the bridge remembers between passes, in one JSON file. */
class State(
    val seenPhone: MutableSet<Long> = HashSet(),
    val seenServer: MutableSet<String> = HashSet(),
    val cards: MutableMap<String, CachedCard> = HashMap(),
    var sentToServer: Int = 0,
    var addedToPhone: Int = 0,
    var lastRun: Long = 0,
    var lastError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("seenPhone", JSONArray(seenPhone.toList()))
        .put("seenServer", JSONArray(seenServer.toList()))
        .put("cards", JSONObject().also { o ->
            cards.forEach { (href, c) ->
                o.put(href, JSONObject().put("etag", c.etag ?: "").put("n", JSONArray(c.person.names.toList()))
                    .put("p", JSONArray(c.person.phones.toList())).put("e", JSONArray(c.person.emails.toList())))
            }
        })
        .put("sent", sentToServer)
        .put("added", addedToPhone)
        .put("lastRun", lastRun)
        .put("lastError", lastError ?: "")
        .put("v", CARD_CACHE_VERSION)

    companion object {
        /** Bump when what's cached per card changes, so old entries are read again. */
        private const val CARD_CACHE_VERSION = 2

        private fun file(ctx: Context) = File(ctx.filesDir, "state.json")

        fun load(ctx: Context): State = runCatching { fromJson(JSONObject(file(ctx).readText())) }.getOrDefault(State())

        fun save(ctx: Context, s: State) {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(s.toJson().toString())
            tmp.renameTo(f)
        }

        fun clear(ctx: Context) {
            file(ctx).delete()
        }

        fun fromJson(o: JSONObject): State {
            fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            val cards = HashMap<String, CachedCard>()
            o.optJSONObject("cards")?.takeIf { o.optInt("v") == CARD_CACHE_VERSION }?.let { c ->
                c.keys().forEach { href ->
                    val v = c.getJSONObject(href)
                    cards[href] = CachedCard(
                        v.optString("etag").ifEmpty { null },
                        Person(strings(v.optJSONArray("n")).toSet(), strings(v.optJSONArray("p")).toSet(), strings(v.optJSONArray("e")).toSet()),
                    )
                }
            }
            val phone = o.optJSONArray("seenPhone")
            return State(
                seenPhone = (0 until (phone?.length() ?: 0)).map { phone!!.getLong(it) }.toHashSet(),
                seenServer = strings(o.optJSONArray("seenServer")).toHashSet(),
                cards = cards,
                sentToServer = o.optInt("sent"),
                addedToPhone = o.optInt("added"),
                lastRun = o.optLong("lastRun"),
                lastError = o.optString("lastError").ifEmpty { null },
            )
        }
    }
}
