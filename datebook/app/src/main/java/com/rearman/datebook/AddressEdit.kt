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

/*
 * Address Edit screen, after the Palm OS 3.5 Address Book. The working copy is
 * MainActivity.addressEditing. Each field writes to it on every change.
 */

/** Address Edit screen for [MainActivity.addressEditing]. One row per field, in Palm order. */
internal fun MainActivity.buildAddressEdit(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val e = addressEditing!!
    tab(h, "Address Edit")
    h.addView(popupTrigger(categoryLabel(e.category)) { tv ->
        endEdit()
        pickCategory(tv, e.category, includeAll = false, app = 2) { id ->
            addressEditing?.category = id
            render()
        }
    })

    val col = vbox().apply { setPadding(pp(2), pp(1), pp(2), pp(1)) }
    fun row(name: TextView, input: View) {
        col.addView(hbox(Gravity.TOP).apply {
            addView(name, LP(pp(45), WRAP))
            addView(input, LP(0, WRAP, 1f).apply { marginStart = pp(2) })
        }, LP(MATCH, WRAP).apply { topMargin = pp(1) })
    }

    val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    val lastName = addressField(e.lastName, words) { s -> addressEditing?.lastName = s }
    row(nameLabel("Last name:"), lastName)
    row(nameLabel("First name:"), addressField(e.firstName, words) { s -> addressEditing?.firstName = s })
    row(nameLabel("Title:"), addressField(e.title, words) { s -> addressEditing?.title = s })
    row(nameLabel("Company:"), addressField(e.company, words) { s -> addressEditing?.company = s })

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
    row(nameLabel("City:"), addressField(e.city, words) { s -> addressEditing?.city = s })
    row(nameLabel("State:"), addressField(e.state, words) { s -> addressEditing?.state = s })
    row(nameLabel("Zip Code:"), addressField(e.zip, words) { s -> addressEditing?.zip = s })
    row(nameLabel("Country:"), addressField(e.country, words) { s -> addressEditing?.country = s })

    for (i in 0 until 4) {
        row(nameLabel(prefs.customNames[i] + ":"), addressField(e.custom[i], words) { s ->
            addressEditing?.let { it.custom = it.custom.toMutableList().also { l -> l[i] = s } }
        })
    }

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

/** A bold field name, right-aligned in the 45 Palm px label column. The fields start at x 49, as in the Palm III screen. */
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
