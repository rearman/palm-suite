package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** An error answer from the sync server. [status] is the HTTP status code. */
class SyncException(val status: Int, message: String) : Exception(message)

data class SyncMeta(val salt: String, val check: String)

data class PushItem(val id: String, val baseRev: Long, val deleted: Boolean, val blob: String)

data class Change(val id: String, val rev: Long, val deleted: Boolean, val blob: String)

data class SyncResult(
    val rev: Long,
    val accepted: Map<String, Long>,
    val conflicts: Set<String>,
    val changes: List<Change>,
)

/**
 * HTTP client for the sync server. The protocol is in sync-server/PROTOCOL.md.
 *
 * This class does network I/O. Do not call it on the main thread.
 */
class SyncClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("https://")) { "The server address must start with https://" }
    }

    /** Reads the setup values. Throws [SyncException] on any status other than 200. */
    fun getMeta(): SyncMeta {
        val (code, text) = call("GET", "/v1/meta", null)
        if (code != 200) throw failure(code, text)
        return parseMeta(text)
    }

    /** Sets the setup values. Returns true on 204 and false on 409 (already set). */
    fun putMeta(m: SyncMeta): Boolean {
        val body = JSONObject().put("salt", m.salt).put("check", m.check).toString()
        val (code, text) = call("PUT", "/v1/meta", body)
        if (code == 204) return true
        if (code == 409) return false
        throw failure(code, text)
    }

    /** Pushes the local changes and returns the server changes after [since]. */
    fun sync(since: Long, push: List<PushItem>): SyncResult {
        val (code, text) = call("POST", "/v1/sync", pushBody(since, push))
        if (code != 200) throw failure(code, text)
        return parseResult(text)
    }

    private fun call(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun failure(code: Int, text: String): SyncException {
        if (code == 401) return SyncException(code, "The server refused the token.")
        val error = try {
            JSONObject(text).optString("error", "")
        } catch (e: Exception) {
            ""
        }
        return SyncException(code, error.ifEmpty { "HTTP $code" })
    }

    companion object {
        /** The JSON body of POST /v1/sync. A deleted item sends an empty blob. */
        fun pushBody(since: Long, push: List<PushItem>): String {
            val items = JSONArray()
            for (p in push) {
                items.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("baseRev", p.baseRev)
                        .put("deleted", p.deleted)
                        .put("blob", if (p.deleted) "" else p.blob)
                )
            }
            return JSONObject().put("since", since).put("push", items).toString()
        }

        /** Reads the body of a 200 answer from POST /v1/sync. */
        fun parseResult(text: String): SyncResult {
            val o = JSONObject(text)
            val accepted = HashMap<String, Long>()
            val acc = o.optJSONArray("accepted") ?: JSONArray()
            for (i in 0 until acc.length()) {
                val a = acc.getJSONObject(i)
                accepted[a.getString("id")] = a.getLong("rev")
            }
            val conflicts = HashSet<String>()
            val con = o.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until con.length()) conflicts.add(con.getString(i))
            val changes = ArrayList<Change>()
            val ch = o.optJSONArray("changes") ?: JSONArray()
            for (i in 0 until ch.length()) {
                val c = ch.getJSONObject(i)
                changes.add(
                    Change(
                        c.getString("id"),
                        c.getLong("rev"),
                        c.optBoolean("deleted", false),
                        c.optString("blob", ""),
                    )
                )
            }
            return SyncResult(o.getLong("rev"), accepted, conflicts, changes)
        }

        /** Reads the body of GET /v1/meta. Missing fields are empty strings. */
        fun parseMeta(text: String): SyncMeta {
            val o = JSONObject(text)
            return SyncMeta(o.optString("salt", ""), o.optString("check", ""))
        }
    }
}
