package com.rearman.datebook

import com.rearman.palmui.*
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ScrollView

/*
 * Address Book forms, after the Palm OS 3.5 Address Book. Each function is an extension on
 * MainActivity, the same way Details.kt and Memo.kt build their screens. The form heights and
 * the positions are the Palm form resources (forms 1200 to 2100 of the Address Book).
 */

/** Address Entry Details form for [MainActivity.addressEditing]. OK applies the choices to the working copy. */
internal fun MainActivity.showAddressDetails() {
    val e = addressEditing ?: return
    var show = e.showPhone
    var cat = e.category
    var priv = e.isPrivate
    val labels = (0..4).map { Address.LABELS[e.phoneLabels[it]] }
    val d = PalmDialog(this, "Address Entry Details", height = 84)
    fun commit() {
        e.showPhone = show
        e.category = cat
        e.isPrivate = priv
    }
    // Palm form 1200: the bold labels end at form x 66, the controls start at x 69.
    val area = formArea(d)
    rightLabel(area, "Show in List:", 66, 18)
    placeForm(area, popupTrigger(labels[show]) { tv ->
        popupList(tv, labels, show) { i -> show = i; tv.setTrigger(labels[i]) }
    }, 69, 17, h = 12)
    rightLabel(area, "Category:", 66, 33)
    placeForm(area, popupTrigger(categoryLabel(cat)) { tv ->
        pickCategory(tv, cat, includeAll = false, app = 2) { id -> cat = id; tv.setTrigger(categoryLabel(id)) }
    }, 69, 32, h = 12)
    rightLabel(area, "Private:", 66, 48)
    placeForm(area, checkBox("", priv) { b -> priv = b }.apply { setPadding(0, 0, 0, 0) }, 70, 46)

    d.button("OK", 25) { commit(); d.dismiss(); render() }
    d.button("Cancel", 35) { d.dismiss() }
    d.button("Delete...", 40) { d.dismiss(); deleteAddressAsk(e) }
    d.button("Note", 28) { commit(); d.dismiss(); openAddressNote(e) }
    d.show()
}

/** Palm form 1600: asks before the address is deleted. The archive copy is saved first when checked. */
internal fun MainActivity.deleteAddressAsk(a: Address) {
    palmAlert("Delete Address", "Delete selected\nAddress entry?", 97, archiveY = 54) { archive ->
        val stored = if (a.id != 0L) store.address(a.id) else null
        val doIt = {
            stored?.let { store.delete(it) }
            addressEditing = null
            addressOpen = null
            render()
        }
        if (archive && stored != null) {
            exportFile("address-archive", store.exportJson(emptyList(), emptyList(), emptyList(), listOf(stored)), doIt)
        } else doIt()
    }
}

/** Note for [a], as in To Do. A record in Address Edit is saved by its OK, not here. */
internal fun MainActivity.openAddressNote(a: Address) {
    fun saveNote(text: String) {
        a.note = text
        if (a.id != 0L && a !== addressEditing) store.save(a)
        render()
    }
    NoteDialog(
        this, "Note: " + a.listName(prefs.addressListBy == 1).take(24), a.note,
        { t -> saveNote(t) },
        if (a.note.isNotEmpty()) ({ saveNote("") }) else null
    ).show()
}

/**
 * Address Book Preferences: the last-category check box and the List By choice. OK saves them.
 * Palm form 2000 has an Enable Tap-Dialing check box at form y 33, which the Palm III Address Book
 * does not have. That row is left out and the rows below it move up its 15 px.
 */
internal fun MainActivity.showAddressPreferences() {
    var remember = prefs.addressRemember
    val d = PalmDialog(this, "Address Book Preferences", height = 109)
    val area = formArea(d)
    placeForm(area, checkBox("Remember last category", remember) { b -> remember = b }, 7, 18, 148, 11)
    placeForm(area, label("List By:", bold = true), 24, 37)
    // Two stacked push buttons, 102 by 13 px, as in the Palm form.
    val listBy = listOf("Last Name, First Name", "Company, Last Name").mapIndexed { i, s ->
        PushGroup(this, listOf(s), if (i == prefs.addressListBy) 0 else -1, height = 13, widths = listOf(102))
    }
    listBy.forEachIndexed { i, g ->
        g.view.getChildAt(0).setOnClickListener { listBy.forEachIndexed { j, o -> o.select(if (j == i) 0 else -1) } }
        placeForm(area, g.view, 28, 53 + 14 * i)
    }

    d.button("OK", 36) {
        prefs.addressRemember = remember
        prefs.addressListBy = if (listBy[1].selected == 0) 1 else 0
        d.dismiss(); render()
    }
    d.button("Cancel", 36) { d.dismiss() }
    d.show()
}

/** Palm form 1900, Rename Custom Fields: four field names. OK saves them; a blank name keeps "Custom n". */
internal fun MainActivity.showRenameCustomFields() {
    val d = PalmDialog(this, "Rename Custom Fields", height = 156)
    val area = formArea(d)
    placeForm(area, label("Create your own field names\nby editing the text on the\nlines below:", bold = true), 6, 19)
    val fields = prefs.customNames.mapIndexed { i, s ->
        field(s).also { placeForm(area, it, 35, 65 + 15 * i, 80, 13) }
    }

    d.button("OK", 40) {
        prefs.customNames = fields.mapIndexed { i, f -> f.text.toString().trim().ifEmpty { "Custom ${i + 1}" } }
        d.dismiss(); render()
    }
    d.button("Cancel", 40) { d.dismiss() }
    d.show()
}

/** Asks whether [a] becomes the business card. Yes saves its id in the prefs. */
internal fun MainActivity.selectBusinessCardAsk(a: Address) {
    ask("Select Business Card", "Make this address your business card?",
        "Yes" to { prefs.businessCard = a.id; render() },
        "No" to {})
}

/** Address Book About box. */
internal fun MainActivity.showAddressAbout() {
    ask("About Address Book", "Address Book, after the Palm OS 3.5 Address Book.", "OK" to {})
}

/**
 * Phone Number Lookup (manual page 72, Palm form 2100). A full-screen form: the non-private
 * addresses that have a phone, filtered by the start of the name in the Look Up field. A tap
 * selects a row. Add puts "Name number" into [ed] in place of its selection, then closes.
 * Add does nothing with no selection.
 */
internal fun MainActivity.showPhoneLookup(ed: EditText) {
    val byCompany = prefs.addressListBy == 1
    val all = addressOrder(store.addresses.filter { !it.isPrivate && it.listPhone() != null }, byCompany)
    val d = PalmDialog(this, "Phone Number Lookup:", scroll = false, height = 160)
    var sel: Address? = null
    val list = vbox()
    val look = field("")

    fun fill() {
        list.removeAllViews()
        val shown = all.filter { it.listName(byCompany).startsWith(look.text.toString(), ignoreCase = true) }
        // A selected row that the filter hides is no longer selected.
        sel = sel?.takeIf { s -> shown.any { it === s } }
        for (a in shown) {
            val phone = a.listPhone()!!
            val on = a === sel
            list.addView(label("${a.listName(byCompany)}   ${phone.first} ${phone.second}", bold = on).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(pp(2), pp(1), pp(2), pp(1))
                if (on) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
                setOnClickListener { sel = a; fill() }
            }, lp(MATCH))
        }
    }
    look.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = fill()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
    fill()

    // The list takes the body (form y 18 to 139). Look Up and the buttons share the bottom row, as in the Palm form.
    d.body.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LP(MATCH, 0, 1f))
    d.buttons.addView(label("Look Up:"), lp())
    d.buttons.addView(look, LP(pp(35), pp(11)).apply { marginStart = pp(2); marginEnd = pp(9) })
    d.button("Add", 26) {
        val a = sel ?: return@button
        val name = listOf(a.firstName, a.lastName).filter { it.isNotBlank() }.joinToString(" ")
        val text = listOf(name, a.listPhone()!!.first).filter { it.isNotBlank() }.joinToString(" ")
        val from = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val to = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        ed.text.replace(from, to, text)
        ed.setSelection(from + text.length)
        d.dismiss()
    }
    d.button("Cancel", 30) { d.dismiss() }
    d.show()
}
