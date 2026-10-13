package com.jmsbrain.rolodexbridge

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Where one piece lives: its Contacts Light card and its LightOS entry, and what each last held. */
data class Managed(
    var lightHref: String? = null,
    var lightEtag: String? = null,
    var lightHash: String? = null,
    var rawId: Long? = null,
    var rawHash: String? = null,
)

/** A Clean Contacts card as last downloaded. */
data class CleanCard(val url: String, val etag: String?, val text: String)

/** What the bridge remembers between passes. */
class State(
    val pieces: MutableMap<String, Managed> = HashMap(),
    /** LightOS entries the bridge leaves alone (no number, or a number mailbox.org wouldn't take). */
    val ignored: MutableSet<Long> = HashSet(),
    val problems: MutableList<String> = ArrayList(),
    var people: Int = 0,
    var lastRun: Long = 0,
    var lastError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pieces", JSONObject().also { o ->
            pieces.forEach { (k, m) ->
                o.put(k, JSONObject().put("lh", m.lightHref ?: "").put("le", m.lightEtag ?: "").put("lx", m.lightHash ?: "")
                    .put("r", m.rawId ?: -1L).put("rx", m.rawHash ?: ""))
            }
        })
        .put("ignored", JSONArray(ignored.toList()))
        .put("problems", JSONArray(problems))
        .put("people", people)
        .put("lastRun", lastRun)
        .put("lastError", lastError ?: "")

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "state.json")
        private fun cleanFile(ctx: Context) = File(ctx.filesDir, "clean.json")

        fun load(ctx: Context): State = runCatching { fromJson(JSONObject(file(ctx).readText())) }.getOrDefault(State())
        fun save(ctx: Context, s: State) = write(file(ctx), s.toJson().toString())

        fun clear(ctx: Context) {
            file(ctx).delete()
            cleanFile(ctx).delete()
        }

        /** Clean Contacts as last downloaded: href key → card. */
        fun loadClean(ctx: Context): MutableMap<String, CleanCard> = runCatching {
            val o = JSONObject(cleanFile(ctx).readText())
            o.keys().asSequence().associateWithTo(HashMap()) { k ->
                val c = o.getJSONObject(k)
                CleanCard(c.getString("u"), c.optString("e").ifEmpty { null }, c.getString("t"))
            }
        }.getOrDefault(HashMap())

        fun saveClean(ctx: Context, cards: Map<String, CleanCard>) {
            val o = JSONObject()
            cards.forEach { (k, c) -> o.put(k, JSONObject().put("u", c.url).put("e", c.etag ?: "").put("t", c.text)) }
            write(cleanFile(ctx), o.toString())
        }

        private fun write(f: File, text: String) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        }

        fun fromJson(o: JSONObject): State {
            val pieces = HashMap<String, Managed>()
            o.optJSONObject("pieces")?.let { p ->
                p.keys().forEach { k ->
                    val m = p.getJSONObject(k)
                    pieces[k] = Managed(
                        m.optString("lh").ifEmpty { null }, m.optString("le").ifEmpty { null }, m.optString("lx").ifEmpty { null },
                        m.optLong("r", -1).takeIf { it >= 0 }, m.optString("rx").ifEmpty { null },
                    )
                }
            }
            val ign = o.optJSONArray("ignored")
            val probs = o.optJSONArray("problems")
            return State(
                pieces = pieces,
                ignored = (0 until (ign?.length() ?: 0)).map { ign!!.getLong(it) }.toHashSet(),
                problems = (0 until (probs?.length() ?: 0)).map { probs!!.getString(it) }.toMutableList(),
                people = o.optInt("people"),
                lastRun = o.optLong("lastRun"),
                lastError = o.optString("lastError").ifEmpty { null },
            )
        }
    }
}
