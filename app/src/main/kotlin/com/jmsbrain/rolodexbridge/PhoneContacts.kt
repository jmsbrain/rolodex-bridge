package com.jmsbrain.rolodexbridge

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/** A contacts account on the phone, with how many contacts it holds and its newest contact's id. */
data class PhoneBookInfo(val book: PhoneBook, val count: Int, val newestId: Long)

/** The phone's contacts database: LightOS's address book only, read and added to as an ordinary app. */
class PhoneContacts(private val resolver: ContentResolver) {

    /**
     * Accounts that could be LightOS's address book. Messaging apps' contact lists are left out.
     * The one holding the newest contact is likely where LightOS saves.
     */
    fun books(): List<PhoneBookInfo> {
        val count = HashMap<PhoneBook, Int>()
        val newest = HashMap<PhoneBook, Long>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(2) ?: continue
                if (type in SKIP_TYPES) continue
                val b = PhoneBook(c.getString(1) ?: continue, type)
                count[b] = (count[b] ?: 0) + 1
                newest[b] = maxOf(newest[b] ?: 0, c.getLong(0))
            }
        }
        return count.map { (b, n) -> PhoneBookInfo(b, n, newest[b] ?: 0) }.sortedByDescending { it.newestId }
    }

    /** Every contact in [book]: raw contact id → its rows. */
    fun read(book: PhoneBook): Map<Long, List<SyncRow>> = read(ids(book))

    /** The rows of [ids]: raw contact id → its rows. */
    fun read(ids: Collection<Long>): Map<Long, List<SyncRow>> {
        val rows = HashMap<Long, MutableList<SyncRow>>()
        ids.forEach { rows[it] = ArrayList() }
        val mimes = SyncFields.mimes.toList()
        for (chunk in ids.chunked(400)) resolver.query(
            Data.CONTENT_URI,
            null,
            "${Data.RAW_CONTACT_ID} IN (${chunk.joinToString(",")}) AND ${Data.MIMETYPE} IN (${mimes.joinToString { "?" }})",
            mimes.toTypedArray(),
            "${Data._ID} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
            val mimeCol = c.getColumnIndexOrThrow(Data.MIMETYPE)
            while (c.moveToNext()) {
                val list = rows[c.getLong(idCol)] ?: continue
                val mime = c.getString(mimeCol)
                val cols = SyncFields.columns[mime] ?: continue
                val values = cols.mapNotNull { col ->
                    val i = c.getColumnIndex(col)
                    if (i < 0 || c.isNull(i)) null else c.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { col to it }
                }.toMap()
                if (values.isNotEmpty()) list += SyncRow(mime, values).normalized()
            }
        }
        return rows
    }

    /** Raw contact ids in [book], without reading their details. */
    fun ids(book: PhoneBook): Set<Long> {
        val ids = HashSet<Long>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(book.name, book.type),
            null,
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids
    }

    /**
     * Adds a contact to [book] the way LightOS does, as an ordinary app, so the address book's own sync
     * picks it up. Returns the new raw contact id.
     */
    fun add(book: PhoneBook, rows: List<SyncRow>): Long? {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_NAME, book.name)
            .withValue(RawContacts.ACCOUNT_TYPE, book.type)
            .build()
        for (row in rows) {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, row.mime)
            row.values.forEach { (k, v) -> b.withValue(k, v) }
            // A name with no parts (a company, an email address): keep it whole, or Android splits it.
            if (row.mime == StructuredName.CONTENT_ITEM_TYPE && row.values.keys == setOf(StructuredName.DISPLAY_NAME)) {
                b.withValue(StructuredName.GIVEN_NAME, row[StructuredName.DISPLAY_NAME])
            }
            ops += b.build()
        }
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return results.firstOrNull()?.uri?.let { ContentUris.parseId(it) }
    }

    companion object {
        /** Messaging apps' own contact lists, and Rolodex's synced copy. */
        val SKIP_TYPES = setOf("com.whatsapp", "im.mollylight.app", "org.thoughtcrime.securesms", "org.telegram.messenger", "com.jms.fullcontacts")
    }
}
