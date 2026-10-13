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

        /** Same number on two entries: like [phoneKey], but short codes and odd text count too, matched exactly. */
        fun numberKey(s: String): String? = phoneKey(s) ?: s.trim().takeIf { it.isNotEmpty() }?.let { "=" + it }
    }
}

