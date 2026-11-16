package com.rearman.datebook

import java.nio.charset.Charset

/**
 * vCard files of Address Book records. Import reads vCard 2.1, 3.0 and 4.0. Export writes vCard 3.0.
 * The id, the sync uid and the category id stay on the phone; a category travels as its name.
 */
object VCard {
    /** One card: its address, and the name of its first category (null when it has none). */
    data class Parsed(val address: Address, val categoryName: String?)

    /** One content line. The value stays bytes until its CHARSET reads it. */
    private class Prop(val name: String, val params: Map<String, List<String>>, val raw: ByteArray)

    /** A phone or e-mail field of a card, before it takes one of the five phone slots. */
    private class PhoneEntry(val label: Int, val number: String, val pref: Boolean)

    private val encodings = setOf("QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT")

    /**
     * Reads every BEGIN:VCARD to END:VCARD card of [text]. A card with no usable field is skipped.
     * The phones fill the five phone slots in file order. Phones past five and later ADR fields
     * go to the note.
     */
    fun parse(text: String): List<Parsed> = parse(text.toByteArray(Charsets.UTF_8))

    /**
     * Reads the cards of a file's bytes. Folds join as bytes, and each value is read in its CHARSET, UTF-8
     * by default. [parse] of a String reads its UTF-8 bytes.
     */
    fun parse(bytes: ByteArray): List<Parsed> {
        val out = mutableListOf<Parsed>()
        var card: MutableList<Prop>? = null
        for (l in unfold(bytes)) {
            val p = prop(l) ?: continue
            val open = card
            when {
                p.name == "BEGIN" && value(p).trim().equals("VCARD", true) -> card = mutableListOf()
                p.name == "END" && value(p).trim().equals("VCARD", true) -> {
                    if (open != null) build(open)?.let { out.add(it) }
                    card = null
                }
                open != null -> open.add(p)
            }
        }
        return out
    }

    /**
     * Writes [list] as vCard 3.0 with CRLF line ends. [categoryName] gives the name of a category id.
     * Category 0 (Unfiled) writes no CATEGORIES line.
     */
    fun export(list: List<Address>, categoryName: (Int) -> String? = { null }): String {
        val out = StringBuilder()
        fun line(s: String) = ICal.fold(s, out)
        for (a in list) {
            line("BEGIN:VCARD")
            line("VERSION:3.0")
            line("N:${ICal.escape(a.lastName)};${ICal.escape(a.firstName)};;;")
            // FN is the full name. Without a name it is the company, which parse reads as a company.
            val full = listOf(a.firstName, a.lastName).filter { it.isNotBlank() }.joinToString(" ").trim()
            line("FN:${ICal.escape(full.ifEmpty { a.company })}")
            if (a.company.isNotEmpty()) line("ORG:${ICal.escape(a.company)}")
            if (a.title.isNotEmpty()) line("TITLE:${ICal.escape(a.title)}")
            for (i in 0 until 5) {
                val number = a.phones.getOrElse(i) { "" }
                if (number.isEmpty()) continue
                val pref = if (i == a.showPhone) ",PREF" else ""
                line(phoneType(a.phoneLabels.getOrElse(i) { Address.OTHER }) + pref + ":" + ICal.escape(number))
            }
            val adr = listOf("", "", a.address, a.city, a.state, a.zip, a.country)
            if (adr.any { it.isNotEmpty() }) line("ADR;TYPE=HOME:" + adr.joinToString(";") { ICal.escape(it) })
            if (a.note.isNotEmpty()) line("NOTE:${ICal.escape(a.note)}")
            a.custom.take(4).forEachIndexed { i, c -> if (c.isNotEmpty()) line("X-PALM-CUSTOM${i + 1}:${ICal.escape(c)}") }
            if (a.category != 0) categoryName(a.category)?.let { line("CATEGORIES:${ICal.escape(it)}") }
            if (a.isPrivate) line("CLASS:PRIVATE")
            line("END:VCARD")
        }
        return out.toString()
    }

    /** The TEL or EMAIL type of a phone label, with the Palm label kept on the way back. */
    private fun phoneType(label: Int) = when (label) {
        Address.EMAIL -> "EMAIL;TYPE=INTERNET"
        Address.WORK -> "TEL;TYPE=WORK,VOICE"
        Address.HOME -> "TEL;TYPE=HOME,VOICE"
        Address.FAX -> "TEL;TYPE=WORK,FAX"
        Address.MAIN -> "TEL;TYPE=X-MAIN"
        Address.PAGER -> "TEL;TYPE=PAGER"
        Address.MOBILE -> "TEL;TYPE=CELL"
        else -> "TEL;TYPE=VOICE"
    }

    /** Builds the address of one card. Null when the card has no usable field. */
    private fun build(props: List<Prop>): Parsed? {
        fun firstProp(n: String) = props.firstOrNull { it.name == n }
        fun text(n: String) = firstProp(n)?.let { ICal.unescape(value(it)) }.orEmpty()
        val company = firstProp("ORG")?.let { parts(it, ';')[0] }.orEmpty()
        val n = firstProp("N")?.let { parts(it, ';') }.orEmpty()
        var last = n.getOrElse(0) { "" }
        var given = n.getOrElse(1) { "" }
        if (last.isEmpty() && given.isEmpty()) {
            // An FN that repeats the company names the company only, so it gives no name.
            val fn = text("FN").trim()
            if (fn.isNotEmpty() && fn != company.trim()) {
                val words = fn.split(Regex("\\s+"))
                last = words.last()
                given = words.dropLast(1).joinToString(" ")
            }
        }

        val entries = mutableListOf<PhoneEntry>()
        for (p in props) {
            if (p.name != "TEL" && p.name != "EMAIL") continue
            var number = ICal.unescape(value(p))
            if (p.name == "TEL" && number.startsWith("tel:", true)) number = number.substring(4)
            if (number.isEmpty()) continue
            val label = if (p.name == "EMAIL") Address.EMAIL else telLabel(types(p))
            entries.add(PhoneEntry(label, number, isPref(p)))
        }
        // The first five take slots 0 to 4. The other slots keep the default labels that no kept phone uses.
        val kept = entries.take(5)
        val phones = MutableList(5) { "" }
        val labels = MutableList(5) { Address.OTHER }
        kept.forEachIndexed { i, e -> phones[i] = e.number; labels[i] = e.label }
        val used = kept.map { it.label }
        val free = listOf(Address.WORK, Address.HOME, Address.FAX, Address.OTHER, Address.EMAIL).filter { it !in used }
        for (i in kept.size until 5) labels[i] = free[i - kept.size]

        val adrs = props.filter { it.name == "ADR" }.map { parts(it, ';') }
        val adr = adrs.firstOrNull().orEmpty()
        val noteLines = mutableListOf<String>()
        text("NOTE").takeIf { it.isNotEmpty() }?.let { noteLines.add(it) }
        for (c in adrs.drop(1)) {
            val s = c.filter { it.isNotBlank() }.joinToString(", ")
            if (s.isNotEmpty()) noteLines.add("Address: $s")
        }
        for (e in entries.drop(5)) noteLines.add("${Address.LABELS[e.label]}: ${e.number}")

        val card = Address(
            lastName = last, firstName = given, title = text("TITLE"), company = company,
            phoneLabels = labels, phones = phones,
            address = adr.getOrElse(2) { "" }, city = adr.getOrElse(3) { "" }, state = adr.getOrElse(4) { "" },
            zip = adr.getOrElse(5) { "" }, country = adr.getOrElse(6) { "" },
            custom = (1..4).map { i -> firstProp("X-PALM-CUSTOM$i")?.let { ICal.unescape(value(it)) }.orEmpty() },
            note = noteLines.joinToString("\n"),
            showPhone = kept.indexOfFirst { it.pref }.coerceAtLeast(0),
            isPrivate = firstProp("CLASS")?.let { value(it).trim().equals("PRIVATE", true) } == true
        )
        if (card.isEmpty) return null
        val category = firstProp("CATEGORIES")?.let { parts(it, ',')[0] }?.takeIf { it.isNotEmpty() }
        return Parsed(card, category)
    }

    /** The Palm label of a TEL field, from its TYPE values. FAX wins over WORK and HOME. */
    private fun telLabel(t: List<String>): Int = when {
        "FAX" in t -> Address.FAX
        "CELL" in t || "MOBILE" in t -> Address.MOBILE
        "PAGER" in t -> Address.PAGER
        "MAIN" in t || "X-MAIN" in t || "COMPANY" in t -> Address.MAIN
        "WORK" in t -> Address.WORK
        "HOME" in t -> Address.HOME
        else -> Address.OTHER
    }

    private fun types(p: Prop): List<String> = p.params["TYPE"].orEmpty().map { it.uppercase() }

    private fun isPref(p: Prop): Boolean = "PREF" in types(p) || p.params.containsKey("PREF")

    /**
     * The text of [p]: its bytes, with quoted-printable decoded, read in its CHARSET, UTF-8 when it names
     * none or an unknown one. A quoted-printable CRLF becomes LF.
     */
    private fun value(p: Prop): String {
        val cs = p.params["CHARSET"]?.firstOrNull()?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        if (p.params["ENCODING"].orEmpty().none { it.equals("QUOTED-PRINTABLE", true) }) return String(p.raw, cs)
        return String(decodeQp(p.raw), cs).replace("\r\n", "\n")
    }

    /** The value of an ASCII hex digit, or -1. */
    private fun hex(b: Byte): Int = Character.digit(b.toInt().toChar(), 16).takeIf { b >= 0 } ?: -1

    /** Decodes "=XX" to one byte. A "=" with no hex digits is a soft break that unfolding did not join. */
    private fun decodeQp(s: ByteArray): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.size) {
            val c = s[i]
            val eq = c == '='.code.toByte()
            when {
                eq && i + 2 < s.size && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0 -> {
                    bytes.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                    i += 3
                }
                eq -> i++
                else -> { bytes.write(c.toInt()); i++ }
            }
        }
        return bytes.toByteArray()
    }

    /** The structured parts of [p] split at [sep], each unescaped. */
    private fun parts(p: Prop, sep: Char): List<String> = split(value(p), sep).map { ICal.unescape(it) }

    /** Splits [s] at [sep] where [sep] is not escaped. The parts keep their escapes. */
    private fun split(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
            } else {
                if (c == sep) { out.add(cur.toString()); cur.clear() } else cur.append(c)
                i++
            }
        }
        out.add(cur.toString())
        return out
    }

    /**
     * Joins the continuation lines of [bytes], as bytes. A line that starts with a space or tab continues
     * the line before it. A quoted-printable line that ends in "=" continues on the next line.
     */
    private fun unfold(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var soft = false
        for (l in ICal.lines(bytes)) {
            if (out.isNotEmpty() && (soft || ICal.continues(l))) {
                val last = out.removeAt(out.lastIndex)
                val joined = if (soft) last.copyOf(last.size - 1) + l else last + l.copyOfRange(1, l.size)
                out.add(joined)
                soft = isSoft(joined)
            } else if (String(l, Charsets.UTF_8).isNotBlank()) {
                out.add(l)
                soft = isSoft(l)
            }
        }
        return out
    }

    private fun isSoft(line: ByteArray) = line.isNotEmpty() && line.last() == '='.code.toByte() &&
        String(line, Charsets.ISO_8859_1).substringBefore(':').contains("QUOTED-PRINTABLE", true)

    private fun prop(line: ByteArray): Prop? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            if (line[i] == '"'.code.toByte()) quoted = !quoted
            else if (line[i] == ':'.code.toByte() && !quoted) { colon = i; break }
        }
        if (colon < 0) return null
        val head = String(line, 0, colon, Charsets.UTF_8).split(';')
        val params = mutableMapOf<String, MutableList<String>>()
        for (p in head.drop(1)) {
            val i = p.indexOf('=')
            if (i > 0) {
                params.getOrPut(p.substring(0, i).uppercase()) { mutableListOf() }
                    .addAll(p.substring(i + 1).trim('"').split(','))
            } else if (p.isNotBlank()) {
                // A 2.1 parameter with no name: an encoding, else a type such as CELL.
                val word = p.trim().uppercase()
                params.getOrPut(if (word in encodings) "ENCODING" else "TYPE") { mutableListOf() }.add(word)
            }
        }
        return Prop(head[0].substringAfterLast('.').uppercase(), params, line.copyOfRange(colon + 1, line.size))
    }
}
