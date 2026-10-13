package com.jmsbrain.rolodexbridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

/**
 * One LightOS-shaped entry: one phone number. [key] stays the same while the number does,
 * so editing someone's email doesn't replace their entries.
 */
data class Piece(val key: String, val rows: List<SyncRow>) {
    /** Changes whenever the entry's content does. */
    val hash: String get() = rows.map { it.sortKey }.sorted().joinToString("\u0001").hashCode().toString(16)
    val phoneKey: String? get() = rows.firstOrNull { it.mime == Phone.CONTENT_ITEM_TYPE }?.let { Person.phoneKey(it[Phone.NUMBER].orEmpty()) }
}

/** A contact rebuilt from LightOS entries: one name, its numbers, and anything else they held. */
data class Gathered(val name: String, val rows: List<SyncRow>)

object Splitter {
    /**
     * LightOS shows one number per contact, so a contact with several numbers becomes one entry per number,
     * named "Ada Lovelace (Mobile 1)", "Ada Lovelace (Work)". Emails, addresses and the rest go on the first
     * entry only. Contacts with no number aren't included.
     */
    fun pieces(cardKey: String, rows: List<SyncRow>): List<Piece> {
        val phones = rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE && !it[Phone.NUMBER].isNullOrBlank() }
            .distinctBy { Person.phoneKey(it[Phone.NUMBER]!!) ?: it[Phone.NUMBER] }
        if (phones.isEmpty()) return emptyList()
        if (phones.size == 1) return listOf(Piece(pieceKey(cardKey, phones[0]), rows.filter { it.mime != Phone.CONTENT_ITEM_TYPE } + phones))

        val name = rows.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
        val base = displayName(rows)
        val labels = phones.map { label(it) }
        val counts = labels.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        val extras = rows.filter { it.mime != StructuredName.CONTENT_ITEM_TYPE && it.mime != Phone.CONTENT_ITEM_TYPE }
        return phones.mapIndexed { i, phone ->
            val l = labels[i]
            val n = seen.merge(l, 1, Int::plus)!!
            val full = if (counts.getValue(l) > 1) "$l $n" else l
            val nameRow = labelledName(name, base, full)
            Piece(pieceKey(cardKey, phone), listOf(nameRow, phone) + if (i == 0) extras else emptyList())
        }
    }

    /** The name with "(Label)" added, in the display name and the last name, so every app shows it. */
    private fun labelledName(name: SyncRow?, base: String, label: String): SyncRow {
        val suffix = " ($label)"
        // Phonetic names are dropped; they'd no longer match.
        val v = LinkedHashMap(name?.values.orEmpty().filterKeys { it !in PHONETIC })
        v[StructuredName.DISPLAY_NAME] = base + suffix
        when {
            !v[StructuredName.FAMILY_NAME].isNullOrBlank() -> v[StructuredName.FAMILY_NAME] = v[StructuredName.FAMILY_NAME] + suffix
            !v[StructuredName.GIVEN_NAME].isNullOrBlank() -> v[StructuredName.GIVEN_NAME] = v[StructuredName.GIVEN_NAME] + suffix
        }
        return SyncRow(StructuredName.CONTENT_ITEM_TYPE, v).normalized()
    }

    private val PHONETIC = setOf(StructuredName.PHONETIC_GIVEN_NAME, StructuredName.PHONETIC_MIDDLE_NAME, StructuredName.PHONETIC_FAMILY_NAME)

    private fun pieceKey(cardKey: String, phone: SyncRow) =
        cardKey + "|" + (Person.phoneKey(phone[Phone.NUMBER].orEmpty()) ?: phone[Phone.NUMBER].orEmpty().trim())

    fun displayName(rows: List<SyncRow>): String {
        val n = rows.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
        n?.get(StructuredName.DISPLAY_NAME)?.let { return it }
        val joined = listOf(StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.FAMILY_NAME, StructuredName.SUFFIX)
            .mapNotNull { n?.get(it) }.joinToString(" ")
        if (joined.isNotBlank()) return joined
        rows.firstOrNull { it.mime == Organization.CONTENT_ITEM_TYPE }?.get(Organization.COMPANY)?.let { return it }
        rows.firstOrNull { it.mime == Email.CONTENT_ITEM_TYPE }?.get(Email.ADDRESS)?.let { return it }
        return rows.firstOrNull { it.mime == Phone.CONTENT_ITEM_TYPE }?.get(Phone.NUMBER) ?: "No name"
    }

    fun label(phone: SyncRow): String {
        val type = phone[Phone.TYPE]?.toIntOrNull() ?: Phone.TYPE_OTHER
        if (type == Phone.TYPE_CUSTOM) return phone[Phone.LABEL]?.trim()?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "Other"
        return LABELS[type] ?: "Other"
    }

    private val LABELS = mapOf(
        Phone.TYPE_MOBILE to "Mobile", Phone.TYPE_HOME to "Home", Phone.TYPE_WORK to "Work", Phone.TYPE_OTHER to "Other",
        Phone.TYPE_MAIN to "Main", Phone.TYPE_WORK_MOBILE to "Work Mobile", Phone.TYPE_FAX_WORK to "Work Fax",
        Phone.TYPE_FAX_HOME to "Home Fax", Phone.TYPE_OTHER_FAX to "Fax", Phone.TYPE_PAGER to "Pager",
        Phone.TYPE_WORK_PAGER to "Work Pager", Phone.TYPE_CAR to "Car", Phone.TYPE_COMPANY_MAIN to "Company",
        Phone.TYPE_ASSISTANT to "Assistant", Phone.TYPE_CALLBACK to "Callback", Phone.TYPE_ISDN to "ISDN",
        Phone.TYPE_RADIO to "Radio", Phone.TYPE_TELEX to "Telex", Phone.TYPE_TTY_TDD to "TTY", Phone.TYPE_MMS to "MMS",
    )

    /** Labels LightOS and this bridge put in names, back to phone types. */
    private val WORDS = mapOf(
        "mobile" to Phone.TYPE_MOBILE, "cell" to Phone.TYPE_MOBILE, "home" to Phone.TYPE_HOME, "work" to Phone.TYPE_WORK,
        "other" to Phone.TYPE_OTHER, "main" to Phone.TYPE_MAIN, "work mobile" to Phone.TYPE_WORK_MOBILE,
        "work fax" to Phone.TYPE_FAX_WORK, "home fax" to Phone.TYPE_FAX_HOME, "fax" to Phone.TYPE_OTHER_FAX,
        "pager" to Phone.TYPE_PAGER, "work pager" to Phone.TYPE_WORK_PAGER, "car" to Phone.TYPE_CAR,
        "company" to Phone.TYPE_COMPANY_MAIN, "assistant" to Phone.TYPE_ASSISTANT,
    )

    private val SUFFIX = Regex("""^(.*\S)\s*\(([^()]+?)(?:\s+\d+)?\)\s*$""")

    /** "Ada Lovelace (Mobile 2)" → ("Ada Lovelace", mobile). Names whose brackets aren't a phone label are left whole. */
    fun parseLabel(name: String): Pair<String, Int?> {
        val m = SUFFIX.matchEntire(name.trim()) ?: return name.trim() to null
        val type = WORDS[m.groupValues[2].trim().lowercase()] ?: return name.trim() to null
        return m.groupValues[1].trim() to type
    }

    /** Puts LightOS entries for the same person ("Ada (cell)", "Ada (work)") back together as one contact. */
    fun gather(entries: List<List<SyncRow>>): List<Gathered> {
        val groups = LinkedHashMap<String, MutableList<Pair<Int?, List<SyncRow>>>>()
        val names = HashMap<String, String>()
        for (rows in entries) {
            val (base, type) = parseLabel(displayName(rows))
            val k = Person.nameKey(base) ?: base
            names.putIfAbsent(k, base)
            groups.getOrPut(k) { ArrayList() } += type to rows
        }
        return groups.map { (k, items) ->
            val base = names.getValue(k)
            val out = ArrayList<SyncRow>()
            val firstName = items.firstNotNullOfOrNull { (_, rows) -> rows.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE } }
            out += if (items.any { it.first != null } || firstName == null) {
                SyncRow.of(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to base)
            } else firstName
            val phones = LinkedHashMap<String, SyncRow>()
            for ((type, rows) in items) for (r in rows) when (r.mime) {
                StructuredName.CONTENT_ITEM_TYPE -> Unit
                Phone.CONTENT_ITEM_TYPE -> {
                    val number = r[Phone.NUMBER] ?: continue
                    val row = if (type != null) SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to number, Phone.TYPE to type) else r
                    phones.putIfAbsent(Person.phoneKey(number) ?: number, row)
                }
                else -> if (out.none { it.sortKey == r.sortKey }) out += r
            }
            Gathered(base, out + phones.values)
        }
    }
}
