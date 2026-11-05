package com.rearman.datebook

import com.rearman.palmui.*
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil

/*
 * Address Edit screen, after the Palm OS 3.5 Address Book. The working copy is
 * MainActivity.addressEditing. Each field writes to it on every change.
 */

/** Address Edit screen for [MainActivity.addressEditing]. One row per field, in Palm order. */
internal fun MainActivity.buildAddressEdit(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val e = addressEditing!!
    tab(h, "Address Edit")
    // Palm: the category in a dotted box, with no arrow.
    h.addView(selector(categoryLabel(e.category)) { tv ->
        endEdit()
        pickCategory(tv, e.category, includeAll = false, app = 2) { id ->
            addressEditing?.category = id
            render()
        }
    })

    val col = vbox().apply { setPadding(pp(2), pp(1), pp(2), pp(1)) }
    // The label column is 45 Palm px, or as wide as its widest label (at most half the screen), so no label wraps.
    val names = mutableListOf<TextView>()
    fun row(name: TextView, input: View) {
        names.add(name.apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(hbox(Gravity.TOP).apply {
            addView(name, LP(pp(45), WRAP))
            addView(input, LP(0, WRAP, 1f).apply { marginStart = pp(2) })
        }, LP(MATCH, WRAP).apply { topMargin = pp(1) })
    }

    val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    val lastName = addressField(e.lastName, words) { s -> addressEditing?.lastName = s }
    row(nameLabel("Last name:"), lastName)
    row(nameLabel("First name:"), addressField(e.firstName, words) { s -> addressEditing?.firstName = s })
    // Palm: Title, Company, City and State fill in the first match from the other entries as you type.
    fun others(f: (Address) -> String) = store.addresses.filter { it.id != e.id }.map(f).filter { it.isNotBlank() }.distinct().sorted()
    row(nameLabel("Title:"), addressField(e.title, words) { s -> addressEditing?.title = s }.autoFill(others { it.title }))
    row(nameLabel("Company:"), addressField(e.company, words) { s -> addressEditing?.company = s }.autoFill(others { it.company }))

    // Each phone row has a label pop-up. The label decides the input type, so a change of label re-renders.
    for (i in 0 until 5) {
        val kind = e.phoneLabels[i]
        val type = if (Address.LABELS[kind] == "E-mail") {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        } else InputType.TYPE_CLASS_PHONE
        val trigger = popupTrigger(Address.LABELS[kind] + ":") { tv ->
            endEdit()
            popupList(tv, Address.LABELS, kind) { k ->
                addressEditing?.let { it.phoneLabels = it.phoneLabels.toMutableList().also { l -> l[i] = k } }
                render()
            }
        }.apply { gravity = Gravity.END }
        row(trigger, addressField(e.phones[i], type) { s ->
            addressEditing?.let { it.phones = it.phones.toMutableList().also { l -> l[i] = s } }
        })
    }

    row(nameLabel("Address:"), addressField(e.address, words or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { s -> addressEditing?.address = s })
    row(nameLabel("City:"), addressField(e.city, words) { s -> addressEditing?.city = s }.autoFill(others { it.city }))
    row(nameLabel("State:"), addressField(e.state, words) { s -> addressEditing?.state = s }.autoFill(others { it.state }))
    row(nameLabel("Zip Code:"), addressField(e.zip, words) { s -> addressEditing?.zip = s })
    row(nameLabel("Country:"), addressField(e.country, words) { s -> addressEditing?.country = s })

    for (i in 0 until 4) {
        row(nameLabel(prefs.customNames[i] + ":"), addressField(e.custom[i], words) { s ->
            addressEditing?.let { it.custom = it.custom.toMutableList().also { l -> l[i] = s } }
        })
    }

    val nameW = names.maxOf { n -> ceil(android.text.Layout.getDesiredWidth(n.text, n.paint)).toInt() + n.paddingLeft + n.paddingRight }
        .coerceIn(pp(45), resources.displayMetrics.widthPixels / 2)
    names.forEach { it.layoutParams.width = nameW }

    withScrollBar(content, ScrollView(this).apply {
        addView(col)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    })
    // A new entry starts with the keyboard open on Last name, as in Palm OS.
    if (e.id == 0L) {
        lastName.post { lastName.requestFocus() }
        showKeyboardSoon()
    }

    cmd(cmds, "Done", gap = 0) { closeAddressEdit() }
    cmd(cmds, "Details...") { endEdit(); showAddressDetails() }
    cmd(cmds, "Note") { endEdit(); openAddressNote(addressEditing!!) }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** A bold field name, right-aligned in the label column (45 Palm px or more). With 45, the fields start at x 49, as in the Palm III screen. */
private fun MainActivity.nameLabel(text: String): TextView =
    label(text, bold = true).apply { gravity = Gravity.END }

/**
 * A palm-ui field with [type] as its input type. [onChange] runs on every text change.
 * The field becomes [MainActivity.lastEdit] when it gets focus, so the Edit menu works in it.
 */
private fun MainActivity.addressField(text: String, type: Int, onChange: (String) -> Unit): EditText =
    field(text).apply {
        inputType = type
        setOnFocusChangeListener { _, has -> if (has) lastEdit = this }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
        })
    }

/**
 * Palm auto-fill: when a letter typed at the end makes the text the start of one of [values], the
 * rest of that value is added and selected, so the next letter replaces it. A deletion fills nothing.
 */
private fun EditText.autoFill(values: List<String>): EditText = apply {
    var filling = false
    var typedAtEnd = false
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            typedAtEnd = !filling && count > before && s != null && start + count == s.length
        }
        override fun afterTextChanged(s: Editable?) {
            if (!typedAtEnd || s == null) return
            typedAtEnd = false
            val typed = s.toString()
            val hit = values.firstOrNull { it.length > typed.length && it.startsWith(typed, ignoreCase = true) } ?: return
            filling = true
            s.append(hit.substring(typed.length))
            setSelection(typed.length, s.length)
            filling = false
        }
    })
}

/** Palm Duplicate Address: a copy of [a], with its category and note, opens in Address Edit. Done stores it. */
internal fun MainActivity.duplicateAddress(a: Address) {
    endEdit()
    addressEditing = a.copy(id = 0, uid = "")
    addressOpen = null
    render()
}

/** Starts a new address in the category shown in the list, then opens it for editing. [initial] fills Last name. */
internal fun MainActivity.newAddress(initial: String = "") {
    endEdit()
    addressEditing = Address(category = if (prefs.addressCategory > 0) prefs.addressCategory else 0, lastName = initial)
    addressOpen = null
    render()
}

/** Opens [a] for editing. The edit works on a copy, so the stored record changes only at Done. */
internal fun MainActivity.editAddress(a: Address) {
    endEdit()
    addressEditing = a.copy()
    render()
}

/**
 * Done in Address Edit: ends the edit. An empty address is deleted from the store. Any other
 * address is saved, and the Address View shows it. The Address List shows when no view is open.
 */
internal fun MainActivity.closeAddressEdit() {
    val e = addressEditing ?: return
    endEdit()
    hideKeyboard()
    if (e.isEmpty) {
        if (e.id != 0L) store.address(e.id)?.let { store.delete(it) }
        addressOpen = null
    } else {
        store.save(e) // Sets the id of a new address.
        addressOpen = e
    }
    addressEditing = null
    render()
}
