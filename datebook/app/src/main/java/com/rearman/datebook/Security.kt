package com.rearman.datebook

import com.rearman.palmsync.SyncCrypto
import com.rearman.palmui.*
import android.text.InputType
import android.widget.EditText
import java.security.MessageDigest
import java.util.Base64

/*
 * Palm Security, after the Palm OS 3.5 Security app: the Current Privacy setting with its Hide and
 * Mask confirmations, the password, and Show Private Records.
 */

/**
 * The Security password, kept as "salt:hash" (Base64) of PBKDF2-HMAC-SHA256.
 * Limit: the hash runs on the main thread, about a tenth of a second on a phone; a slower hash would need a worker thread.
 */
object Password {
    private const val ROUNDS = 100_000

    fun make(password: String): String {
        val salt = SyncCrypto.newSalt()
        return b64(salt) + ":" + b64(SyncCrypto.deriveKey(password.toCharArray(), salt, ROUNDS))
    }

    /** True when [password] matches [stored], a value from [make]. */
    fun check(stored: String, password: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = try { Base64.getDecoder().decode(parts[0]) } catch (e: IllegalArgumentException) { return false }
        val hash = SyncCrypto.deriveKey(password.toCharArray(), salt, ROUNDS)
        return MessageDigest.isEqual(b64(hash).toByteArray(), parts[1].toByteArray())
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/** Palm "Change Security": Current Privacy and the password. Hide and Mask ask first; Show asks for the password. */
fun MainActivity.showSecurity() {
    val d = PalmDialog(this, "Change Security", height = 100,
        tips = "Private records are the records with Private checked in their Details.\n\n" +
            "Show Records shows them. Mask Records shows a gray bar in their place. Hide Records leaves them out.\n\n" +
            "With a password, you must enter it to show private records.")
    val area = formArea(d)
    placeForm(area, label("Current Privacy:", bold = true), 13, 24)
    val options = listOf("Show Records", "Mask Records", "Hide Records")
    var pick = prefs.security
    placeForm(area, popupTrigger(options[pick]) { tv ->
        popupList(tv, options, pick) { i -> pick = i; tv.setTrigger(options[i]) }
    }, 59, 35)
    rightLabel(area, "Password:", 54, 55)
    placeForm(area, selector(passwordText()) { tv -> editPassword { tv.text = passwordText() } }, 59, 55, WRAP, 11)
    d.button("OK", 37) {
        d.dismiss()
        // The watch agenda hides or masks private records as the security option does.
        val set = { prefs.security = pick; revealed.clear(); render(); WearBridge.pushSoon(this) }
        when {
            pick == prefs.security -> {}
            pick == 0 -> askPassword(set)
            else -> {
                val word = if (pick == 1) "masked" else "hidden"
                palmAlert(options[pick], "Records marked\nPrivate in the details\ndialog will be $word.\n\n" +
                    "If a password is\nassigned, you must\nenter it to show\nprivate records.", 130,
                    tips = "To show private records again, choose Show Records in Security" +
                        if (pick == 1) ", or tap a masked record." else ".") { set() }
            }
        }
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

private fun MainActivity.passwordText() = if (prefs.password.isEmpty()) "-Unassigned-" else "-Assigned-"

/** A Palm password form: [prompt] and one field. The keyboard opens with it. */
private fun MainActivity.passwordForm(title: String, prompt: String): Pair<PalmDialog, EditText> {
    val d = PalmDialog(this, title)
    d.message(prompt)
    val f = d.field("")
    f.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    return d to f
}

private fun MainActivity.invalidPassword() = ask("Invalid Password", "The password is not correct.", "OK" to {})

/**
 * Palm Security Password: with no password, assigns one (typed twice). With one, asks for it, then OK
 * assigns a new one and Delete removes it. [done] runs after a change.
 */
private fun MainActivity.editPassword(done: () -> Unit) {
    if (prefs.password.isEmpty()) { newPassword(done); return }
    val (d, f) = passwordForm("Password", "Enter the current password:")
    d.button("OK", 37) {
        d.dismiss()
        if (Password.check(prefs.password, f.text.toString())) newPassword(done) else invalidPassword()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.button("Delete", 37) {
        d.dismiss()
        if (Password.check(prefs.password, f.text.toString())) { prefs.password = ""; done() } else invalidPassword()
    }
    d.show()
}

private fun MainActivity.newPassword(done: () -> Unit) {
    val (d, f) = passwordForm("Password", "Enter a password:")
    d.button("OK", 37) {
        val first = f.text.toString()
        d.dismiss()
        if (first.isEmpty()) return@button
        val (v, g) = passwordForm("Verify", "Enter the password again to verify:")
        v.button("OK", 37) {
            v.dismiss()
            if (g.text.toString() == first) { prefs.password = Password.make(first); done() }
            else ask("Invalid Password", "The passwords do not match.", "OK" to {})
        }
        v.button("Cancel", 37) { v.dismiss() }
        v.show()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

/** Palm Show Private Records: runs [onOk] at once with no password, or after the right password. */
internal fun MainActivity.askPassword(onOk: () -> Unit) {
    if (prefs.password.isEmpty()) { onOk(); return }
    val (d, f) = passwordForm("Show Private Records", "Enter a password:")
    d.button("OK", 37) {
        d.dismiss()
        if (Password.check(prefs.password, f.text.toString())) onOk() else invalidPassword()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}
