package com.jmsbrain.rolodexbridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {
    private fun name(display: String, given: String? = null, family: String? = null) =
        SyncRow.of(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to display, StructuredName.GIVEN_NAME to given, StructuredName.FAMILY_NAME to family)

    private fun phone(number: String, type: Int, label: String? = null) =
        SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to number, Phone.TYPE to type, Phone.LABEL to label)

    private fun email(address: String) = SyncRow.of(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to address, Email.TYPE to 1)

    private fun displayOf(p: Piece) = p.rows.first { it.mime == StructuredName.CONTENT_ITEM_TYPE }[StructuredName.DISPLAY_NAME]

    // ---- matching ----

    private fun person(vararg rows: SyncRow) = Person.of(rows.toList())

    @Test fun phonesMatchAcrossFormats() {
        assertTrue(person(phone("+1 (919) 555-0101", 2)).matches(person(phone("919.555.0101", 1))))
        assertFalse(person(phone("919-555-0101", 2)).matches(person(phone("919-555-0102", 2))))
        assertFalse(person(phone("611", 2)).matches(person(phone("611", 2))))
    }

    @Test fun namesMatchIgnoringCaseAndPunctuation() {
        assertTrue(person(name("Dr. Evelyn Okafor")).matches(person(name("dr evelyn  okafor"))))
        assertFalse(person(name("Evelyn Okafor")).matches(person(name("Evelyn Okafore"))))
    }

    @Test fun shortNumbersMatchEntriesExactlyButNeverPeople() {
        assertEquals("=24273", Person.numberKey("24273"))
        assertEquals(Person.numberKey("+1 919 555 0101"), Person.numberKey("(919) 555-0101"))
        assertNull(Person.numberKey("  "))
        assertFalse(person(phone("24273", 2)).matches(person(phone("24273", 2))))
        assertEquals("=24273", Splitter.pieces("card", listOf(name("Pharmacy"), phone("24273", 2))).single().phoneKey)
    }

    // ---- splitting ----

    @Test fun oneNumberIsLabelledToo() {
        val rows = listOf(name("Ada Lovelace", "Ada", "Lovelace"), phone("919-555-0101", 2), email("ada@example.com"))
        val pieces = Splitter.pieces("card", rows)
        assertEquals(1, pieces.size)
        assertEquals("Ada Lovelace (Mobile)", displayOf(pieces[0]))
        // Everything else stays on it.
        assertEquals(1, pieces[0].rows.count { it.mime == Email.CONTENT_ITEM_TYPE })
    }

    @Test fun noNumberIsLeftOut() {
        assertTrue(Splitter.pieces("card", listOf(name("Ada"), email("ada@example.com"))).isEmpty())
    }

    @Test fun severalNumbersSplitWithLabels() {
        val rows = listOf(
            name("Ada Lovelace", "Ada", "Lovelace"),
            phone("919-555-0101", Phone.TYPE_MOBILE), phone("919-555-0102", Phone.TYPE_MOBILE),
            phone("919-555-0103", Phone.TYPE_WORK), phone("919-555-0104", Phone.TYPE_HOME),
            email("ada@example.com"),
        )
        val pieces = Splitter.pieces("card", rows)
        assertEquals(
            listOf("Ada Lovelace (Mobile 1)", "Ada Lovelace (Mobile 2)", "Ada Lovelace (Work)", "Ada Lovelace (Home)"),
            pieces.map { displayOf(it) },
        )
        // The label is in the last name too, for apps that build the name from its parts.
        assertEquals("Lovelace (Work)", pieces[2].rows.first { it.mime == StructuredName.CONTENT_ITEM_TYPE }[StructuredName.FAMILY_NAME])
        // Each piece has exactly its own number, typed.
        assertEquals(listOf("919-555-0103"), pieces[2].rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.map { it[Phone.NUMBER] })
        assertEquals(Phone.TYPE_WORK.toString(), pieces[2].rows.first { it.mime == Phone.CONTENT_ITEM_TYPE }[Phone.TYPE])
        // Email only on the first.
        assertEquals(listOf(1, 0, 0, 0), pieces.map { p -> p.rows.count { it.mime == Email.CONTENT_ITEM_TYPE } })
    }

    @Test fun customLabelsAndSingleNamesWork() {
        val rows = listOf(name("Hoss", "Hoss"), phone("919-555-0101", Phone.TYPE_CUSTOM, "boat"), phone("919-555-0102", Phone.TYPE_OTHER))
        assertEquals(listOf("Hoss (Boat)", "Hoss (Other)"), Splitter.pieces("card", rows).map { displayOf(it) })
    }

    @Test fun sameNumberTwiceIsOneEntry() {
        val rows = listOf(name("Ada"), phone("+1 919 555 0101", Phone.TYPE_MOBILE), phone("(919) 555-0101", Phone.TYPE_HOME))
        assertEquals(1, Splitter.pieces("card", rows).size)
    }

    @Test fun keysFollowTheNumberNotTheOtherDetails() {
        val a = Splitter.pieces("card", listOf(name("Ada"), phone("919-555-0101", 2), phone("919-555-0103", 3)))
        val b = Splitter.pieces("card", listOf(name("Ada"), phone("919-555-0101", 2), phone("919-555-0103", 3), email("new@example.com")))
        assertEquals(a.map { it.key }, b.map { it.key })
        assertFalse(a[0].hash == b[0].hash)
        assertEquals(a[1].hash, b[1].hash)
    }

    // ---- back from LightOS ----

    @Test fun labelsAreReadBack() {
        assertEquals("Ada Lovelace" to Phone.TYPE_MOBILE, Splitter.parseLabel("Ada Lovelace (Mobile 2)"))
        assertEquals("Ada Lovelace" to Phone.TYPE_MOBILE, Splitter.parseLabel("Ada Lovelace (cell)"))
        assertEquals("Ada Lovelace" to Phone.TYPE_OTHER, Splitter.parseLabel("Ada Lovelace (other 2)"))
        assertEquals("Ada" to Phone.TYPE_FAX_WORK, Splitter.parseLabel("Ada (Work Fax)"))
        // Brackets that aren't a phone label belong to the name.
        val (base, type) = Splitter.parseLabel("Ricky (The New One)")
        assertEquals("Ricky (The New One)", base)
        assertNull(type)
    }

    @Test fun lightOsEntriesGatherIntoOnePerson() {
        val gathered = Splitter.gather(
            listOf(
                listOf(name("Cleo New (cell)"), phone("919-555-0123", Phone.TYPE_MOBILE)),
                listOf(name("Cleo New (work)"), phone("919-555-0124", Phone.TYPE_MOBILE)),
                listOf(name("Dummy"), phone("919-555-0199", Phone.TYPE_MOBILE)),
            ),
        )
        assertEquals(listOf("Cleo New", "Dummy"), gathered.map { it.name })
        val cleo = gathered[0].rows
        assertEquals("Cleo New", cleo.first { it.mime == StructuredName.CONTENT_ITEM_TYPE }[StructuredName.DISPLAY_NAME])
        assertEquals(
            listOf("919-555-0123" to Phone.TYPE_MOBILE.toString(), "919-555-0124" to Phone.TYPE_WORK.toString()),
            cleo.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.map { it[Phone.NUMBER] to it[Phone.TYPE] },
        )
    }

    @Test fun splitThenGatherGivesTheNumbersBack() {
        val rows = listOf(name("Ada Lovelace", "Ada", "Lovelace"), phone("919-555-0101", Phone.TYPE_MOBILE), phone("919-555-0102", Phone.TYPE_MOBILE), phone("919-555-0103", Phone.TYPE_WORK))
        val back = Splitter.gather(Splitter.pieces("card", rows).map { it.rows }).single()
        assertEquals("Ada Lovelace", back.name)
        assertEquals(
            rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.map { it[Phone.NUMBER] to it[Phone.TYPE] },
            back.rows.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.map { it[Phone.NUMBER] to it[Phone.TYPE] },
        )
    }
}
