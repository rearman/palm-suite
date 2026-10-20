package com.rearman.datebook

import com.rearman.palmui.*
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/*
 * Address List screen, after the Palm OS 3.5 Address Book. Each function is an extension on
 * MainActivity, the same way Memo.kt builds its screens.
 */

/** Addresses shown in the list: the category filter, the private filter, and the sort order. */
internal fun MainActivity.visibleAddresses(): List<Address> {
    val cat = prefs.addressCategory
    val hidePrivate = prefs.security == 2
    return addressOrder(store.addresses, prefs.addressListBy == 1).filter { a ->
        (cat < 0 || a.category == cat) && !(hidePrivate && a.isPrivate)
    }
}

/**
 * Address List screen. One line per record: the list name, then the phone number and its letter.
 * A tap on a line opens the record. Look Up scrolls to the first name that starts with the text.
 */
internal fun MainActivity.buildAddressList(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    tab(h, "Address")
    // As in Memo List: a 13 px high trigger at the right edge. The name grows the trigger to the left.
    h.addView(popupTrigger(categoryLabel(prefs.addressCategory)) { tv ->
        endEdit()
        pickCategory(tv, prefs.addressCategory, includeAll = true, app = 2) { id -> prefs.addressCategory = id; render() }
    }.apply { minWidth = pp(17) }, LP(WRAP, pp(13)).apply { marginEnd = -pp(1) })

    val byCompany = prefs.addressListBy == 1
    val items = visibleAddresses()
    val col = vbox()
    if (items.isEmpty()) col.addView(label("No addresses. Tap New to add one.").apply {
        setPadding(pp(5), pp(6.5f), pp(5), pp(6.5f))
    })
    val rows = items.map { a -> addressRow(a, byCompany).also { col.addView(it.view, lp(MATCH)) } }
    val sv = ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false }
    withScrollBar(content, sv)

    // The Look Up match: the first row whose name starts with the text is inverted and set at the top.
    // A masked row has no name here, so it never matches.
    fun showMatch() {
        val text = addressLookup.lowercase()
        val hit = if (text.isEmpty()) null else rows.firstOrNull { r -> r.name?.lowercase()?.startsWith(text) == true }
        rows.forEach { r -> r.setInverted(r === hit) }
        if (text.isEmpty()) sv.scrollTo(0, 0)
        else if (hit != null) sv.scrollTo(0, hit.view.top)
    }
    val lookup = field(addressLookup).apply {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                addressLookup = s?.toString() ?: ""
                showMatch()
            }
        })
    }
    lastEdit = lookup
    sv.post { showMatch() }
    lookup.post { lookup.requestFocus(); lookup.setSelection(lookup.text.length) }

    cmds.addView(label("Look Up:"))
    // Palm form 1000: Look Up at x 0, the field at x 36, 55 px wide, and New at x 96.
    cmds.addView(lookup, LP(pp(55), WRAP).apply { marginStart = pp(2) })
    cmd(cmds, "New", gap = 5) { newAddress() }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** Types [text] into the Look Up field of the Address List. The text is appended and the list is drawn again. */
internal fun MainActivity.addressTyped(text: String) {
    addressLookup += text
    render()
}

/** A list row. [name] is null for a masked record, which has no name to match. */
private class AddressRow(val view: LinearLayout, val name: String?, private val texts: List<TextView>) {
    /** Inverts the row (Palm.INK background, Palm.paper text) for a Look Up match. */
    fun setInverted(on: Boolean) {
        view.setBackgroundColor(if (on) Palm.INK else Color.TRANSPARENT)
        texts.forEach { it.setTextColor(if (on) Palm.paper else Palm.INK) }
    }
}

/**
 * One list row for [a]. A masked record shows a gray bar; a tap asks to show it. The phone
 * number is cut at its left when it is too wide, so its letter always shows.
 */
private fun MainActivity.addressRow(a: Address, byCompany: Boolean): AddressRow {
    if (isMasked(a)) {
        val row = hbox().apply {
            addView(label("").apply { background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f) },
                LP(0, pp(7), 1f).apply { topMargin = pp(2); marginEnd = pp(3) })
            setOnClickListener { revealAsk(a) }
        }
        return AddressRow(row, null, emptyList())
    }
    val name = a.listName(byCompany)
    val nameView = label(name).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(pp(3), 0, pp(3), 0)
    }
    val texts = mutableListOf(nameView)
    val row = hbox().apply { addView(nameView, LP(0, WRAP, 1f)) }
    a.listPhone()?.let { (number, letter) ->
        // Half the screen at most, so the name keeps the rest of the line.
        val phone = label("$number $letter").apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            maxWidth = resources.displayMetrics.widthPixels / 2
            setPadding(0, 0, pp(3), 0)
        }
        row.addView(phone, LP(WRAP, WRAP))
        texts += phone
    }
    row.setOnClickListener { endEdit(); addressLookup = ""; addressOpen = a; render() }
    return AddressRow(row, name, texts)
}
