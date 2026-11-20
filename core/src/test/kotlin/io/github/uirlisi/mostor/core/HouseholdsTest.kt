package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The households on one phone (SPEC §9.4 "Households on one phone"). */
class HouseholdsTest {
    @Test
    fun `a fresh install and an upgrade from 0_6 have one household, the existing database file`() {
        val list = HouseholdList.first()
        assertEquals(1, list.slots.size)
        assertEquals("mostor.db", list.active.file)
        assertEquals(1, list.active.number)
        assertTrue(list.active.isFirst)
        assertFalse(list.several)
        assertNull(list.active.name)
    }

    @Test
    fun `a new household gets the next number and its own file, and isn't active until switched to`() {
        val list = HouseholdList.first().add("abc")
        assertTrue(list.several)
        assertEquals("first", list.activeId)
        val added = list.slot("abc")!!
        assertEquals(2, added.number)
        assertEquals("household-abc.db", added.file)
        assertFalse(added.isFirst)
        assertEquals(listOf("abc"), list.others.map { it.id })
        val switched = list.activate("abc")
        assertEquals("abc", switched.active.id)
        assertEquals(listOf("first"), switched.others.map { it.id })
    }

    @Test
    fun `numbers are never reused while a household exists`() {
        val list = HouseholdList.first().add("a").add("b").remove("a").add("c")
        assertEquals(listOf(1, 3, 4), list.slots.map { it.number })
        assertEquals(5, list.nextNumber)
    }

    @Test
    fun `numbers are never reused after the newest household is deleted either, also after a restart`() {
        val list = HouseholdList.first().add("a").add("b").remove("b")
        assertEquals(4, list.add("c").slot("c")!!.number)
        assertEquals(4, HouseholdList.decode(list.encode())!!.add("c").slot("c")!!.number)
    }

    @Test
    fun `a deleted household's file waits to be deleted and never comes back as a household`() {
        val list = HouseholdList.first().add("a").remove("a")
        assertEquals(setOf("household-a.db"), list.pendingDelete)
        assertEquals(list, list.adopt(listOf("mostor.db", "household-a.db")))
        assertEquals(list, HouseholdList.decode(list.encode()))
        val done = list.deleted("household-a.db")
        assertTrue(done.pendingDelete.isEmpty())
        // Once deleted, a file of that name (it can't be, ids are unique) would be adopted again.
        assertEquals(listOf("first", "a"), done.adopt(listOf("household-a.db")).slots.map { it.id })
    }

    @Test
    fun `a list saved by the previous version reads back`() {
        val old = listOf("households-1\tfirst", "first\t1\tmostor.db\tMurphy", "a\t3\thousehold-a.db\t").joinToString("\n")
        val list = HouseholdList.decode(old)!!
        assertEquals(listOf(1, 3), list.slots.map { it.number })
        assertEquals(4, list.nextNumber)
        assertTrue(list.pendingDelete.isEmpty())
    }

    @Test
    fun `a household added by hand can be named at once`() {
        val list = HouseholdList.first().add("m", "  Murphy ").add("n", "  ")
        assertEquals("Murphy", list.slot("m")!!.name)
        assertNull(list.slot("n")!!.name)
    }

    @Test
    fun `removing the active household switches to another, and the last one can't be removed`() {
        val list = HouseholdList.first().add("a").activate("a")
        val after = list.remove("a")
        assertEquals("first", after.activeId)
        assertThrows(IllegalArgumentException::class.java) { after.remove("first") }
        assertThrows(IllegalArgumentException::class.java) { after.activate("a") }
    }

    @Test
    fun `names are trimmed, kept to one line, and blank means the default`() {
        val list = HouseholdList.first()
        assertEquals("Murphy", list.rename("first", "  Murphy ").active.name)
        assertEquals("Ní Bhriain Ó Sé", list.rename("first", "Ní Bhriain\t\nÓ Sé").active.name)
        assertNull(list.rename("first", "   ").active.name)
        assertNull(list.rename("first", null).active.name)
        assertEquals(40, list.rename("first", "x".repeat(100)).active.name!!.length)
    }

    @Test
    fun `joining copies the inviting phone's name once, unless a merged household has its own`() {
        val list = HouseholdList.first().add("apart")
        assertEquals("Murphy", list.joined("apart", "Murphy", JoinWay.APART).slot("apart")!!.name)
        assertEquals("Murphy", list.rename("apart", "Mine").joined("apart", " Murphy ", JoinWay.APART).slot("apart")!!.name)
        assertEquals("Murphy", list.joined("first", "Murphy", JoinWay.MERGE).active.name)
        assertEquals("Mine", list.rename("first", "Mine").joined("first", "Murphy", JoinWay.MERGE).active.name)
        // An inviting phone's household without a name of its own gives none.
        assertEquals(list, list.joined("apart", null, JoinWay.APART))
        assertEquals(list, list.joined("apart", "  ", JoinWay.APART))
        assertEquals(list, list.joined("gone", "Murphy", JoinWay.APART))
    }

    @Test
    fun `the list reads back as written, and anything else reads as nothing`() {
        val list = HouseholdList.first().rename("first", "Murphy").add("0193-ab").activate("0193-ab")
        assertEquals(list, HouseholdList.decode(list.encode()))
        assertEquals(HouseholdList.first(), HouseholdList.decode(HouseholdList.first().encode()))
        assertNull(HouseholdList.decode(null))
        assertNull(HouseholdList.decode(""))
        assertNull(HouseholdList.decode("households-9\tfirst"))
        assertNull(HouseholdList.decode("households-1\tmissing\nfirst\t1\tmostor.db\t"))
        assertNull(HouseholdList.decode("households-1\tfirst\nfirst\tone\tmostor.db\t"))
    }

    @Test
    fun `household files missing from the list come back as households, other files don't`() {
        val list = HouseholdList.first().add("a")
        val adopted = list.adopt(listOf("mostor.db", "household-a.db", "household-b.db", "household-b.db-wal", "other.db", "sync-a.db"))
        assertEquals(listOf("first", "a", "b"), adopted.slots.map { it.id })
        assertEquals("household-b.db", adopted.slot("b")!!.file)
        assertEquals("first", adopted.activeId)
        assertEquals(list, list.adopt(listOf("mostor.db", "household-a.db")))
    }

    private fun member(id: String, removedBy: String? = null) =
        Member(id, "Phone $id", addedAt = 1, removedAt = removedBy?.let { 2L }, removedBy = removedBy, hlc = "000000000001-0000-$id")

    @Test
    fun `a database merges into another household only when no other phone is active in its own`() {
        // Never shared, or no list at all (a household never invited anyone): both ways.
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), Joining.ways(emptyList(), "me"))
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), Joining.ways(listOf(member("me")), "me"))
        // Every other phone removed: on its own again.
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), Joining.ways(listOf(member("me"), member("b", removedBy = "me")), "me"))
        // Another phone still in it: only apart.
        assertEquals(listOf(JoinWay.APART), Joining.ways(listOf(member("me"), member("b")), "me"))
        // This phone is leaving (its own row removed by itself), others still in: only apart.
        assertEquals(listOf(JoinWay.APART), Joining.ways(listOf(member("me", removedBy = "me"), member("b")), "me"))
    }

    @Test
    fun `only the untouched starter child merges without a question`() {
        assertEquals(JoinWay.MERGE, Joining.without(emptyList(), "me", blank = true))
        assertNull(Joining.without(emptyList(), "me", blank = false))
        // Blank, but shared with another phone: it may only go apart, which the owner chooses.
        assertNull(Joining.without(listOf(member("me"), member("b")), "me", blank = true))
    }
}
