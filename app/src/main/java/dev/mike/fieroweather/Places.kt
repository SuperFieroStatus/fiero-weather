package dev.mike.fieroweather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val lat: Double, val lon: Double) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

/**
 * Saved places, which one is shown ("here" = current location), and the last forecast so the app
 * opens instantly and still shows something with no signal.
 */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun saved(c: Context): List<Place> {
        val arr = JSONArray(prefs(c).getString("places", "[]"))
        return (0 until arr.length()).map { Place.fromJson(arr.getJSONObject(it)) }
    }

    private fun setSaved(c: Context, list: List<Place>) =
        prefs(c).edit().putString("places", JSONArray(list.map { it.toJson() }).toString()).apply()

    fun add(c: Context, p: Place) {
        val list = saved(c).filterNot { same(it, p) }
        setSaved(c, list + p)
    }

    fun remove(c: Context, p: Place) {
        setSaved(c, saved(c).filterNot { same(it, p) })
        if (selected(c)?.let { same(it, p) } == true) select(c, null)
    }

    /** The place being shown, or null for the current location. */
    fun selected(c: Context): Place? =
        prefs(c).getString("selected", null)?.let { Place.fromJson(JSONObject(it)) }

    fun select(c: Context, p: Place?) = prefs(c).edit().putString("selected", p?.toJson()?.toString()).apply()

    // ---- cache ----

    /** [here]: the forecast is for the current location rather than a saved place. */
    data class Cached(val placeName: String, val here: Boolean, val json: JSONObject, val fetchedAt: Long)

    fun cache(c: Context, placeName: String, here: Boolean, raw: String) = prefs(c).edit()
        .putString("cache", JSONObject()
            .put("place", placeName).put("here", here)
            .put("at", System.currentTimeMillis()).put("raw", raw).toString())
        .apply()

    fun cached(c: Context): Cached? = try {
        prefs(c).getString("cache", null)?.let {
            val o = JSONObject(it)
            Cached(o.getString("place"), o.optBoolean("here", true), JSONObject(o.getString("raw")), o.getLong("at"))
        }
    } catch (e: Exception) {
        null
    }

    private fun same(a: Place, b: Place) = Math.abs(a.lat - b.lat) < 0.01 && Math.abs(a.lon - b.lon) < 0.01
}

/** Place search (Open-Meteo geocoding) and a name for the current location (Photon reverse). */
object Geocode {
    fun search(query: String): List<Place> {
        val json = Net.getJson("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=${Net.enc(query)}")
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val region = r.optString("admin1").ifBlank { r.optString("country") }
            val label = listOf(r.getString("name"), region).filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** Town name for a position, or null (offline, or nothing nearby). */
    fun nameFor(lat: Double, lon: Double): String? = try {
        val json = Net.getJson("https://photon.komoot.io/reverse?limit=1&lat=${Forecast.coord(lat)}&lon=${Forecast.coord(lon)}")
        val p = json.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        listOf("city", "town", "village", "district", "county", "name")
            .map { p.optString(it) }.firstOrNull { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
