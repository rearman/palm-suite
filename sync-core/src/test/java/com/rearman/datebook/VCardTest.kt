package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {
    private fun card(vararg lines: String) = (listOf("BEGIN:VCARD", "VERSION:3.0") + lines + "END:VCARD").joinToString("\r\n")

    @Test fun roundTripKeepsEveryField() {
        val a = Address(
            id = 5, lastName = "Carter", firstName = "Melanie", title = "Dr.",
            company = "Acme, Inc.; Ltd", phoneLabels = listOf(Address.WORK, Address.MOBILE, Address.FAX, Address.HOME, Address.EMAIL),
            phones = listOf("555-0100", "555-0101", "555-0102", "555-0103", "carter@example.com"), showPhone = 1,
            address = "123 Main St\nSuite 4", city = "Springfield", state = "IL", zip = "62701", country = "USA",
            custom = listOf("c1", "c2, x", "c3;y", "c4"), note = "Line one\nLine two, with comma; and semicolon",
            category = 3, isPrivate = true
        )
        val parsed = VCard.parse(VCard.export(listOf(a)) { if (it == 3) "Business" else null })
        assertEquals(1, parsed.size)
        assertEquals(a.copy(id = 0, uid = "", category = 0), parsed[0].address)
        assertEquals("Business", parsed[0].categoryName)
    }

    @Test fun exportUsesCrlfAndFoldsLongLines() {
        val note = "0123456789".repeat(20)
        val text = VCard.export(listOf(Address(lastName = "Long", note = note)))
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0"))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(note, VCard.parse(text).single().address.note)
    }

    @Test fun vCard21LabelsAndQuotedPrintableNote() {
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\nN:Carter;Melanie\r\nFN:Melanie Carter\r\n" +
            "TEL;CELL;VOICE:555-1234\r\nTEL;WORK;FAX:555-9\r\nEMAIL;INTERNET:a@b.c\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE:Hello=0D=0AWorld and so=\r\non\r\nEND:VCARD"
        val a = VCard.parse(text).single().address
        assertEquals(Address.MOBILE, a.phoneLabels[a.phones.indexOf("555-1234")])
        assertEquals(Address.FAX, a.phoneLabels[a.phones.indexOf("555-9")])
        assertEquals(Address.EMAIL, a.phoneLabels[a.phones.indexOf("a@b.c")])
        assertEquals("Hello\nWorld and soon", a.note.replace("\r\n", "\n"))
    }

    @Test fun vCard40UriPhoneWithPref() {
        val text = card("FN:Jo Doe", "TEL;VALUE=uri;TYPE=\"cell,voice\";PREF=1:tel:+1-555-0100")
        val a = VCard.parse(text).single().address
        assertEquals("+1-555-0100", a.phones[a.showPhone])
    }

    @Test fun groupPrefixIsRead() {
        val a = VCard.parse(card("item1.TEL;TYPE=WORK:555-0199", "item1.EMAIL:x@y.z")).single().address
        assertTrue(a.phones.contains("555-0199"))
    }

    @Test fun fnOnlySplitsName() {
        val a = VCard.parse(card("FN:Jean Luc Picard")).single().address
        assertEquals("Jean Luc", a.firstName)
        assertEquals("Picard", a.lastName)
    }

    @Test fun sevenNumbersOverflowIntoNote() {
        val text = card(
            "FN:Seven Phones", "TEL;WORK:555-0101", "TEL;HOME:555-0102", "TEL;FAX:555-0103",
            "TEL;CELL:555-0104", "TEL;VOICE:555-0105", "TEL;PAGER:555-0106", "TEL;CELL:555-0107"
        )
        val a = VCard.parse(text).single().address
        assertEquals(listOf("555-0101", "555-0102", "555-0103", "555-0104", "555-0105"), a.phones)
        assertTrue(a.note.contains("Pager: 555-0106"))
        assertTrue(a.note.contains("Mobile: 555-0107"))
    }

    @Test fun twoCardsGiveTwoAndEmptyCardGivesNone() {
        val two = card("FN:A One") + "\r\n" + card("FN:B Two") + "\r\n"
        assertEquals(2, VCard.parse(two).size)
        assertTrue(VCard.parse(card()).isEmpty())
    }

    @Test fun categoriesAndClassPrivate() {
        val parsed = VCard.parse(card("FN:Pat Private", "CATEGORIES:Business,Work", "CLASS:PRIVATE")).single()
        assertEquals("Business", parsed.categoryName)
        assertTrue(parsed.address.isPrivate)
    }
}
