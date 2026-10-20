package com.rearman.datebook

import com.rearman.palmui.*
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/*
 * Address View screen, after the Palm OS 3.5 Address Book. Each function is an extension on
 * MainActivity, the same way Memo.kt builds its screens.
 */

/**
 * Address View for [MainActivity.addressOpen]. The name, the phone fields, the address, the custom
 * fields and the note, with a gap between the sections that have text. A phone number dials it,
 * the Msg button texts it, an E-mail address opens a mail, and the address opens a map.
 */
internal fun MainActivity.buildAddressView(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val a = addressOpen!!
    tab(h, "Address View")
    val col = vbox().apply { setPadding(pp(2), 0, pp(2), 0) }
    if (isMasked(a)) {
        // A masked record shows a gray bar. A tap asks to show it, as in the list.
        col.addView(label("").apply {
            background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f)
            setOnClickListener { revealAsk(a) }
        }, LP(MATCH, pp(7)).apply { topMargin = pp(2) })
    } else {
        val sections = listOf(nameSection(a), phoneSection(a), addressSection(a), customSection(a), noteSection(a))
            .filter { it.childCount > 0 }
        sections.forEachIndexed { i, s ->
            if (i > 0) col.addView(label(" ")) // A blank line between sections.
            col.addView(s, lp(MATCH))
        }
    }
    withScrollBar(content, ScrollView(this).apply {
        addView(col)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    })

    cmd(cmds, "Done", gap = 0) { endEdit(); addressOpen = null; render() }
    cmd(cmds, "Edit") { editAddress(a) }
    cmd(cmds, "New") { newAddress() }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** The name in bold, then the title and the company. */
private fun MainActivity.nameSection(a: Address): LinearLayout = vbox().apply {
    val name = listOf(a.firstName, a.lastName).filter { it.isNotBlank() }.joinToString(" ")
    if (name.isNotBlank()) addView(valueLine(name, bold = true), lp(MATCH))
    if (a.title.isNotBlank()) addView(valueLine(a.title), lp(MATCH))
    if (a.company.isNotBlank()) addView(valueLine(a.company), lp(MATCH))
}

/** One line per phone field that has text: "Label: number", or "E-mail: address". */
private fun MainActivity.phoneSection(a: Address): LinearLayout = vbox().apply {
    val smsLabels = listOf(Address.WORK, Address.HOME, Address.OTHER, Address.MAIN, Address.MOBILE)
    for (i in 0 until 5) {
        val value = a.phones.getOrElse(i) { "" }
        if (value.isBlank()) continue
        val lab = a.phoneLabels.getOrElse(i) { Address.WORK }
        val email = lab == Address.EMAIL
        val line = valueLine(value, prefix = Address.LABELS[lab] + ": ", link = true, onTap = {
            if (email) openIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(value))))
            else openIntent(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null)))
        })
        if (!email && lab in smsLabels) {
            addView(hbox().apply {
                addView(line, LP(0, WRAP, 1f))
                addView(palmButton("Msg") {
                    openIntent(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", value, null)))
                }, LP(WRAP, pp(12)).apply { marginStart = pp(3) })
            }, lp(MATCH))
        } else {
            addView(line, lp(MATCH))
        }
    }
}

/** The street, "City, State Zip" and the country on their own lines. A tap opens a map. */
private fun MainActivity.addressSection(a: Address): LinearLayout = vbox().apply {
    val cityState = listOf(a.city, a.state).filter { it.isNotBlank() }.joinToString(", ")
    val cityLine = listOf(cityState, a.zip).filter { it.isNotBlank() }.joinToString(" ")
    val lines = listOf(a.address, cityLine, a.country).filter { it.isNotBlank() }
    if (lines.isEmpty()) return@apply
    val oneLine = lines.joinToString(", ")
    addView(valueLine(lines.joinToString("\n"), link = true, onTap = {
        openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(oneLine))))
    }), lp(MATCH))
}

/** One line per custom field that has text: "<custom name>: value". */
private fun MainActivity.customSection(a: Address): LinearLayout = vbox().apply {
    a.custom.forEachIndexed { i, value ->
        if (value.isNotBlank()) {
            val name = prefs.customNames.getOrElse(i) { "Custom ${i + 1}" }
            addView(valueLine(value, prefix = "$name: "), lp(MATCH))
        }
    }
}

/** The note text. */
private fun MainActivity.noteSection(a: Address): LinearLayout = vbox().apply {
    if (a.note.isNotBlank()) addView(valueLine(a.note), lp(MATCH))
}

/**
 * One text line of the Address View. [value] follows [prefix]. A [link] value is underlined and a
 * tap runs [onTap]. A long press copies [value] to the clipboard.
 */
private fun MainActivity.valueLine(
    value: String,
    prefix: String = "",
    bold: Boolean = false,
    link: Boolean = false,
    onTap: (() -> Unit)? = null
): TextView {
    val text = SpannableString(prefix + value)
    if (link) text.setSpan(UnderlineSpan(), prefix.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return label(text, bold = bold).apply {
        setOnLongClickListener { copyValue(value); true }
        onTap?.let { tap -> setOnClickListener { tap() } }
    }
}

/** Puts [value] on the clipboard. */
private fun MainActivity.copyValue(value: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Address", value))
    toast("Copied.")
}

/** Starts [intent] in a phone app. Shows a message when no app can take it. */
private fun MainActivity.openIntent(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast("No app can do this.")
    }
}
