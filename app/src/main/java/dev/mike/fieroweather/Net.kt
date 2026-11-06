package dev.mike.fieroweather

import android.util.Log
import dev.mike.fieroweather.ui.KeyRouter
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Plain HttpURLConnection + org.json: no dependencies. Call off the UI thread. */
object Net {
    const val USER_AGENT = "FieroWeather/0.1 (keypad weather; https://github.com/SuperFieroStatus/fiero-weather)"

    val io: ExecutorService = Executors.newSingleThreadExecutor()

    class HttpError(val code: Int, msg: String) : IOException(msg)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getJson(url: String, timeoutMs: Int = 10000): JSONObject {
        var lastError: IOException? = null
        // One retry: weak signal and stale keep-alive sockets are common on this phone.
        repeat(2) { attempt ->
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (attempt > 0) conn.setRequestProperty("Connection", "close")
                val code = conn.responseCode
                if (code !in 200..299) throw HttpError(code, "HTTP $code")
                return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } catch (e: HttpError) {
                throw e
            } catch (e: IOException) {
                Log.w(KeyRouter.TAG, "GET failed (attempt ${attempt + 1}): ${url.substringBefore('?')} $e")
                lastError = e
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: IOException("request failed")
    }
}
