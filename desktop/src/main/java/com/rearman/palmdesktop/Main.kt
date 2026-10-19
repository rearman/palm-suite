package com.rearman.palmdesktop

import com.rearman.palmsync.SyncClient
import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.unb64
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.util.Properties
import java.util.concurrent.ExecutionException
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.table.DefaultTableModel

/**
 * Palm Desktop, first stage: sync setup and a list of the synced records. It reads the records
 * from palmsync and never writes, so it cannot change your data.
 */
fun main() {
	SwingUtilities.invokeLater {
		runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
		MainWindow().show()
	}
}

/**
 * The server address and the device token, in desktop.properties in the user's config folder
 * (%APPDATA%\palm-suite on Windows, ~/.config/palm-suite on Linux). Only the user can read the
 * file. The passphrase and the key are never saved.
 */
private object Settings {
	private val dir = File(
		System.getenv("APPDATA") ?: System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"),
		"palm-suite"
	)
	private val file = File(dir, "desktop.properties")
	private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

	val server: String get() = props.getProperty("server", "https://sync.earman.xyz")
	val token: String get() = props.getProperty("token", "")

	fun save(server: String, token: String) {
		props.setProperty("server", server)
		props.setProperty("token", token)
		dir.mkdirs()
		file.createNewFile()
		file.setReadable(false, false)
		file.setWritable(false, false)
		file.setReadable(true, true)
		file.setWritable(true, true)
		file.outputStream().use { props.store(it, "Palm Desktop") }
	}
}

private class MainWindow {
	private val frame = JFrame("Palm Desktop")
	private val server = JTextField(Settings.server, 22)
	private val token = JPasswordField(Settings.token, 22)
	private val passphrase = JPasswordField(14)
	private val syncButton = JButton("Sync")
	private val status = JLabel("Enter the passphrase, then click Sync.")
	private val model = object : DefaultTableModel(arrayOf("Type", "Description", "Details"), 0) {
		override fun isCellEditable(row: Int, column: Int) = false
	}

	// The key stays in memory for this session, for the server and token it was made with.
	private var key: ByteArray? = null
	private var keyFor = ""

	init {
		val form = JPanel(FlowLayout(FlowLayout.LEFT))
		form.add(JLabel("Server:"))
		form.add(server)
		form.add(JLabel("Token:"))
		form.add(token)
		form.add(JLabel("Passphrase:"))
		form.add(passphrase)
		form.add(syncButton)
		val table = JTable(model).apply {
			autoCreateRowSorter = true
			fillsViewportHeight = true
		}
		frame.contentPane.add(form, BorderLayout.NORTH)
		frame.contentPane.add(JScrollPane(table), BorderLayout.CENTER)
		frame.contentPane.add(status, BorderLayout.SOUTH)
		frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
		frame.setSize(960, 600)
		frame.setLocationRelativeTo(null)
		syncButton.addActionListener { sync() }
		passphrase.addActionListener { sync() }
	}

	fun show() {
		frame.isVisible = true
	}

	/** Reads all records from the server on a worker thread, then fills the list. */
	private fun sync() {
		val url = server.text.trim()
		val tok = String(token.password).trim()
		val pass = passphrase.password
		val known = if (keyFor == "$url $tok") key else null
		if (tok.isEmpty() || (known == null && pass.isEmpty())) {
			status.text = "Enter the token and the passphrase."
			return
		}
		syncButton.isEnabled = false
		status.text = if (known == null) "Checking the passphrase. Please wait." else "Syncing."
		object : SwingWorker<List<Row>, Unit>() {
			var usedKey: ByteArray? = null

			override fun doInBackground(): List<Row> {
				val client = SyncClient(url, tok)
				val k = known ?: newKey(client, pass)
				usedKey = k
				// since 0 and no push: the whole server state, read only.
				val result = client.sync(0, emptyList())
				val records = result.changes.filter { !it.deleted }
					.map { it.id to String(SyncCrypto.open(k, it.id, it.blob), Charsets.UTF_8) }
				return rows(records)
			}

			override fun done() {
				pass.fill(' ')
				syncButton.isEnabled = true
				try {
					val list = get()
					key = usedKey
					keyFor = "$url $tok"
					passphrase.text = ""
					Settings.save(url, tok)
					model.rowCount = 0
					for (r in list) model.addRow(arrayOf(r.type, r.description, r.details))
					status.text = "${list.size} records."
				} catch (e: ExecutionException) {
					status.text = "Sync failed: " + (e.cause?.message ?: e.message)
				}
			}
		}.execute()
	}

	/** Derives the key from the server salt and checks it. The phone sets up the salt first. */
	private fun newKey(client: SyncClient, pass: CharArray): ByteArray {
		val meta = client.getMeta()
		check(meta.salt.isNotEmpty()) { "No device has set up sync yet. Set it up on the phone first." }
		val k = SyncCrypto.deriveKey(pass, meta.salt.unb64())
		check(SyncCrypto.checkOk(k, meta.check)) { "Wrong passphrase." }
		return k
	}
}
