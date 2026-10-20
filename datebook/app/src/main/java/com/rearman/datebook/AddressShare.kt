package com.rearman.datebook

import com.rearman.palmui.*
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate

// ---------------- Address sharing and vCard files ----------------

/** The vCard text of a list of addresses, with the category names of the Address Book. */
private fun MainActivity.vCardText(list: List<Address>): String =
    VCard.export(list) { store.categoryName(it) }

/** Shares the addresses as one vCard file through the Android share sheet. */
internal fun MainActivity.shareAddresses(list: List<Address>, fileBase: String) {
    if (list.isEmpty()) { toast("No addresses to share."); return }
    val text = vCardText(list)
    // Only plain letters, digits, spaces and dashes go into the file name.
    val safe = fileBase.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' }
        .ifBlank { "address" }
    val name = "$safe.vcf"
    val dir = File(cacheDir, "share").apply { mkdirs() }
    File(dir, name).writeText(text, Charsets.UTF_8)
    // VcfProvider serves the file. The name is encoded, so it stays one path segment.
    val uri = Uri.parse("content://com.rearman.datebook.vcf/" + Uri.encode(name))
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/x-vcard")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip data lets the grant reach the share targets, as the Contacts app does.
    send.clipData = ClipData.newRawUri("", uri)
    startActivity(Intent.createChooser(send, "Share"))
}

/** Shares the business card, the address chosen with Record > Select Business Card. */
internal fun MainActivity.shareBusinessCard() {
    val card = store.address(prefs.businessCard)
    if (card == null) {
        toast("Select a business card first. Open an address, then choose Record > Select Business Card.")
        return
    }
    shareAddresses(listOf(card), card.listName(prefs.addressListBy == 1))
}

/** Exports the shown addresses to a vCard file. Private ones need a yes when the security option hides them. */
internal fun MainActivity.exportVCard() {
    val shown = visibleAddresses()
    if (prefs.security == 0 || shown.none { it.isPrivate }) {
        saveVCard(shown)
    } else {
        ask("Export Addresses", "Include private addresses?",
            "Yes" to { saveVCard(shown) },
            "No" to { saveVCard(shown.filter { !it.isPrivate }) })
    }
}

private fun MainActivity.saveVCard(list: List<Address>) {
    saveFile("addresses-${LocalDate.now()}.vcf", "text/x-vcard", vCardText(list))
}

/** Adds the addresses of a vCard file and tells what was added. */
internal fun MainActivity.importVCardText(text: String) {
    val parsed = VCard.parse(text)
    if (parsed.isEmpty()) { toast("The file has no addresses."); return }
    val (added, skipped) = store.addAddresses(parsed)
    render()
    ask("Import", "Added $added addresses. Skipped $skipped duplicate addresses.", "OK" to {})
}

/**
 * A minimal FileProvider, because the project has no AndroidX.
 * It serves only the files that shareAddresses writes to cacheDir/share/.
 */
class VcfProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The file of the URI, or null when it is not a file directly inside cacheDir/share/. */
    private fun fileFor(uri: Uri): File? {
        val root = File(context!!.cacheDir, "share").canonicalFile
        val file = File(root, uri.lastPathSegment ?: return null).canonicalFile
        return if (file.parentFile == root && file.isFile) file else null
    }

    override fun getType(uri: Uri): String = "text/x-vcard"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        fileFor(uri)?.let { cursor.addRow(arrayOf(it.name, it.length())) }
        return cursor
    }

    // The provider is read only.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
