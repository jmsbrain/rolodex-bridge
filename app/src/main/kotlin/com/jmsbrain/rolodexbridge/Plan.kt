package com.jmsbrain.rolodexbridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

/** What a contact is matched on: its name, phone numbers and emails. */
data class Person(val names: Set<String>, val phones: Set<String>, val emails: Set<String>) {
    val isEmpty: Boolean get() = names.isEmpty() && phones.isEmpty() && emails.isEmpty()

    /** Same person if any name, number or email is shared. Errs toward "already there" so nothing is doubled. */
    fun matches(o: Person): Boolean =
        names.any { it in o.names } || phones.any { it in o.phones } || emails.any { it in o.emails }

    companion object {
        fun of(rows: List<SyncRow>): Person {
            val names = HashSet<String>()
            val phones = HashSet<String>()
            val emails = HashSet<String>()
            for (r in rows) when (r.mime) {
                StructuredName.CONTENT_ITEM_TYPE -> {
                    r[StructuredName.DISPLAY_NAME]?.let { nameKey(it) }?.let { names += it }
                    val parts = listOf(StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.FAMILY_NAME, StructuredName.SUFFIX)
                        .mapNotNull { r[it] }
                    nameKey(parts.joinToString(" "))?.let { names += it }
                    nameKey(listOfNotNull(r[StructuredName.GIVEN_NAME], r[StructuredName.FAMILY_NAME]).joinToString(" "))?.let { names += it }
                }
                Phone.CONTENT_ITEM_TYPE -> r[Phone.NUMBER]?.let { phoneKey(it) }?.let { phones += it }
                Email.CONTENT_ITEM_TYPE -> r[Email.ADDRESS]?.trim()?.lowercase()?.takeIf { '@' in it }?.let { emails += it }
            }
            return Person(names, phones, emails)
        }

        fun nameKey(s: String): String? =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().takeIf { it.isNotEmpty() }

        /** Last 10 digits, so +1 919… and (919)… match. Short codes are ignored. */
        fun phoneKey(s: String): String? = s.filter { it.isDigit() }.takeIf { it.length >= 7 }?.takeLast(10)
    }
}

/** What one pass will copy. Everything else new is already on the other side and only gets remembered. */
data class Plan(val toServer: List<Long>, val toPhone: List<String>, val knownPhone: Set<Long>, val knownServer: Set<String>)

object Planner {
    /**
     * [phone] is LightOS's address book (raw contact id → person), [server] the mailbox.org book (href key → person).
     * Contacts in [seenPhone] / [seenServer] were handled before (or were there at the start) and are left alone.
     */
    fun plan(
        phone: Map<Long, Person>,
        server: Map<String, Person>,
        seenPhone: Set<Long>,
        seenServer: Set<String>,
    ): Plan {
        val toServer = ArrayList<Long>()
        val toPhone = ArrayList<String>()
        val knownPhone = HashSet<Long>()
        val knownServer = HashSet<String>()
        // Contacts about to be copied count as present, so two new copies of one person cross once.
        val serverSide = server.values.toMutableList()
        val phoneSide = phone.values.toMutableList()

        for ((id, p) in phone) {
            if (id in seenPhone) continue
            knownPhone += id
            if (p.isEmpty || serverSide.any { it.matches(p) }) continue
            toServer += id
            serverSide += p
        }
        for ((href, p) in server) {
            if (href in seenServer) continue
            knownServer += href
            if (p.isEmpty || phoneSide.any { it.matches(p) }) continue
            toPhone += href
            phoneSide += p
        }
        return Plan(toServer, toPhone, knownPhone, knownServer)
    }
}
