package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {
    private fun card(vararg lines: String) = (listOf("BEGIN:VCARD", "VERSION:3.0") + lines + "END:VCARD").joinToString("\r\n")

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

    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { acc, p ->
        acc + when (p) { is String -> p.toByteArray(); is Int -> byteArrayOf(p.toByte()); else -> ByteArray(0) }
    }

    @Test fun charsetReadsThePlainBytes() {
        val a = VCard.parse(bytes("BEGIN:VCARD\r\nN;CHARSET=ISO-8859-1:M", 0xFC, "ller;J", 0xF6, "rg\r\nEND:VCARD\r\n")).single().address
        assertEquals("Müller", a.lastName)
        assertEquals("Jörg", a.firstName)
    }

    @Test fun foldInsideAUtf8SequenceJoinsTheBytes() {
        val a = VCard.parse(bytes("BEGIN:VCARD\r\nFN:Ann\r\nNOTE:M", 0xC3, "\r\n ", 0xBC, "ller\r\nEND:VCARD\r\n")).single().address
        assertEquals("Müller", a.note)
    }

    @Test fun quotedPrintableLineEndIsLf() {
        val a = VCard.parse(card("FN:Ann", "NOTE;ENCODING=QUOTED-PRINTABLE:a=0D=0Ab")).single().address
        assertEquals("a\nb", a.note)
    }
}
