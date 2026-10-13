package com.jmsbrain.rolodexbridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private fun person(name: String? = null, phone: String? = null, email: String? = null) = Person.of(
        listOfNotNull(
            name?.let { SyncRow.of(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to it) },
            phone?.let { SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to it, Phone.TYPE to 2) },
            email?.let { SyncRow.of(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to it, Email.TYPE to 1) },
        ),
    )

    @Test fun phonesMatchAcrossFormats() {
        assertTrue(person(phone = "+1 (919) 555-0101").matches(person(phone = "919.555.0101")))
        assertFalse(person(phone = "919-555-0101").matches(person(phone = "919-555-0102")))
    }

    @Test fun namesMatchIgnoringCaseAndPunctuation() {
        assertTrue(person("Dr. Evelyn Okafor").matches(person("dr evelyn  okafor")))
        assertFalse(person("Evelyn Okafor").matches(person("Evelyn Okafore")))
    }

    @Test fun emailsMatchIgnoringCase() {
        assertTrue(person(email = "Sam@Example.com").matches(person(email = "sam@example.com")))
    }

    @Test fun shortCodesDontMatch() {
        assertFalse(person(phone = "611").matches(person(phone = "611")))
    }

    @Test fun startingPointIsLeftAlone() {
        val phone = mapOf(1L to person("Ada"), 2L to person("Bert"))
        val server = mapOf("a" to person("Cleo"))
        val plan = Planner.plan(phone, server, seenPhone = setOf(1L, 2L), seenServer = setOf("a"))
        assertTrue(plan.toServer.isEmpty())
        assertTrue(plan.toPhone.isEmpty())
    }

    @Test fun newOnEachSideCrosses() {
        val phone = mapOf(1L to person("Ada"), 2L to person("Dummy", "919-555-0199"))
        val server = mapOf("a" to person("Ada"), "b" to person("Cleo", email = "cleo@example.com"))
        val plan = Planner.plan(phone, server, seenPhone = setOf(1L), seenServer = setOf("a"))
        assertEquals(listOf(2L), plan.toServer)
        assertEquals(listOf("b"), plan.toPhone)
        assertEquals(setOf(2L), plan.knownPhone)
        assertEquals(setOf("b"), plan.knownServer)
    }

    @Test fun alreadyOnTheOtherSideIsOnlyRemembered() {
        val phone = mapOf(1L to person("Ada Lovelace", "919-555-0101"))
        val server = mapOf("a" to person("Ada L.", "+19195550101"))
        val plan = Planner.plan(phone, server, seenPhone = emptySet(), seenServer = emptySet())
        assertTrue(plan.toServer.isEmpty())
        assertTrue(plan.toPhone.isEmpty())
        assertEquals(setOf(1L), plan.knownPhone)
        assertEquals(setOf("a"), plan.knownServer)
    }

    @Test fun sameNewPersonOnBothSidesDoesntCross() {
        val phone = mapOf(1L to person("Ada"))
        val server = mapOf("a" to person("Ada"))
        val plan = Planner.plan(phone, server, emptySet(), emptySet())
        assertTrue(plan.toServer.isEmpty() && plan.toPhone.isEmpty())
    }

    @Test fun twoNewCopiesOfOnePersonCrossOnce() {
        val phone = mapOf(1L to person("Ada", "919-555-0101"), 2L to person("Ada", "919-555-0101"))
        val plan = Planner.plan(phone, emptyMap(), emptySet(), emptySet())
        assertEquals(1, plan.toServer.size)
        assertEquals(setOf(1L, 2L), plan.knownPhone)
    }

    @Test fun emptyContactsArentCopied() {
        val plan = Planner.plan(mapOf(1L to person()), mapOf("a" to person()), emptySet(), emptySet())
        assertTrue(plan.toServer.isEmpty() && plan.toPhone.isEmpty())
    }

    @Test fun copiedServerContactMatchesItsPhoneCopyNextTime() {
        // Pass 1 adds server card "b" to the phone as id 9; pass 2 sees id 9 as already handled.
        val server = mapOf("b" to person("Cleo", "919-555-0123"))
        val plan = Planner.plan(mapOf(9L to person("Cleo", "919-555-0123")), server, seenPhone = setOf(9L), seenServer = setOf("b"))
        assertTrue(plan.toServer.isEmpty() && plan.toPhone.isEmpty())
    }
}
