package free.dmo.tagdump

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Profile(
    val idx: Int,
    val name: String,
    val description: String,
    val active: Boolean,
    val uploaded: Boolean
)

class FreeDmoClient(var baseUrl: String) {

    class HttpError(val code: Int, message: String) : IOException(message)

    fun getProfiles(): List<Profile> {
        val body = httpGet("/profiles")
        val arr = JSONArray(body)
        val out = ArrayList<Profile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Profile(
                    idx = o.getInt("idx"),
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    active = o.optBoolean("active", false),
                    uploaded = o.optBoolean("uploaded", false)
                )
            )
        }
        return out
    }

    fun getCurrent(): Profile {
        val body = httpGet("/current")
        val o = JSONObject(body)
        return Profile(
            idx = o.getInt("idx"),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            active = true,
            uploaded = o.optBoolean("uploaded", false)
        )
    }

    fun switchProfile(idx: Int): String =
        httpPost("/switch?idx=$idx", null)

    fun uploadProfile(name: String, description: String, blocksHex: String): Int {
        val body = httpPost(
            "/profiles",
            mapOf(
                "name" to name,
                "description" to description,
                "blocks_hex" to blocksHex
            )
        )
        return JSONObject(body).getInt("idx")
    }

    fun renameProfile(idx: Int, name: String, description: String): String =
        httpPost(
            "/profiles/rename?idx=$idx",
            mapOf("name" to name, "description" to description)
        )

    fun deleteProfile(idx: Int): String =
        httpPost("/profiles/delete?idx=$idx", null)

    fun wipeProfiles(): String =
        httpPost("/profiles/wipe", null)

    fun reset(): String =
        httpPost("/reset", null)

    fun wifiReset(): String =
        httpPost("/wifi/reset", null)

    private fun httpGet(path: String): String {
        val conn = openConnection(path, "GET")
        return readResponse(conn)
    }

    private fun httpPost(path: String, form: Map<String, String>?): String {
        val conn = openConnection(path, "POST")
        conn.doOutput = true
        if (form != null) {
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val encoded = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(encoded) }
        } else {
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
        }
        return readResponse(conn)
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val trimmed = baseUrl.trimEnd('/')
        val url = URL(trimmed + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = false
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, "HTTP $code: $text")
        return text
    }
}
