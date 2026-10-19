package com.rearman.datebook

import android.text.InputType
import android.widget.TextView
import com.rearman.palmui.*

/**
 * Sync form, opened from the Options menu. The device token comes from `palmsync token add`
 * on the server. The passphrase must be the same on every device. A token or passphrase
 * field that is empty keeps the saved value when sync is on.
 */
fun MainActivity.showSync() {
    val d = PalmDialog(this, "Sync", height = 120)
    val serverField = field(prefs.syncUrl)
    val tokenField = field("", if (prefs.syncOn) "Saved" else "Device token")
    val passField = field("", if (prefs.syncOn) "Saved" else "Sync passphrase")
    passField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    d.row("Server:", serverField, labelEnd = 50)
    d.row("Token:", tokenField, labelEnd = 50)
    d.row("Passphrase:", passField, labelEnd = 50)

    val last = prefs.syncLast
    d.message(
        when {
            last.isNotEmpty() -> last
            prefs.syncOn -> "Sync is on."
            else -> "Sync is off."
        }
    )

    lateinit var ok: TextView
    ok = d.button("OK", 22) {
        val server = serverField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        val pass = passField.text.toString()
        if (token.isEmpty() || pass.isEmpty()) {
            if (prefs.syncOn && token.isEmpty() && pass.isEmpty() && server == prefs.syncUrl) {
                d.dismiss()
            } else {
                toast("Enter the token and the passphrase.")
            }
            return@button
        }
        toast("Please wait. Setting up sync.")
        ok.isEnabled = false
        SyncEngine.setUp(this, server, token, pass.toCharArray()) { err ->
            ok.isEnabled = true
            if (err == null) {
                d.dismiss()
                toast("Sync is on.")
                changed()
            } else {
                ask("Sync", err, "OK" to {})
            }
        }
    }
    if (prefs.syncOn) {
        d.button("Sync Now", 0) {
            d.dismiss()
            toast("Syncing.")
            SyncEngine.run(this) { err ->
                if (err == null) {
                    toast("Sync done.")
                } else {
                    ask("Sync", err, "OK" to {})
                }
                changed()
            }
        }
        d.button("Turn Off") {
            ask("Sync", "Turn off sync on this device? Your records stay here.",
                "Yes" to { SyncEngine.turnOff(this); d.dismiss() },
                "No" to {})
        }
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}
