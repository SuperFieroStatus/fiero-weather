package dev.mike.fieroweather

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CancellationSignal
import android.text.format.DateUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.fieroweather.ui.KeyAction
import dev.mike.fieroweather.ui.KeyActionHandler
import dev.mike.fieroweather.ui.KeyRouter
import dev.mike.fieroweather.ui.ListItem
import dev.mike.fieroweather.ui.ListScreen
import dev.mike.fieroweather.ui.RoutedKey
import org.json.JSONObject

/**
 * Three pages for one place: Now, Hourly (next 24 h), 7 days. ◀ ▶ (or 1 2 3) switch pages, ▲ ▼ scroll.
 * Left soft = Places, right soft = Refresh. Shows the last forecast at once, then refreshes it when it's
 * over [STALE_MS] old (or the place changed).
 */
class MainActivity : Activity(), KeyActionHandler {

    private enum class Page { NOW, HOURS, DAYS }

    private lateinit var title: TextView
    private lateinit var tabs: List<TextView>
    private lateinit var nowView: View
    private lateinit var list: ListScreen
    private lateinit var symbol: TextView
    private lateinit var temp: TextView
    private lateinit var condition: TextView
    private lateinit var lines: List<TextView>
    private lateinit var status: TextView

    private var page = Page.NOW
    private var forecast: Forecast? = null
    private var placeName = ""
    /** The forecast shown is for the current location (not a saved place). */
    private var shownHere = true
    private var fetchedAt = 0L
    private var loading = false
    private var statusText = ""
    private var locating: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = findViewById(R.id.title)
        tabs = listOf(findViewById(R.id.tab_now), findViewById(R.id.tab_hours), findViewById(R.id.tab_days))
        nowView = findViewById(R.id.now)
        list = findViewById(R.id.list)
        symbol = findViewById(R.id.symbol)
        temp = findViewById(R.id.temp)
        condition = findViewById(R.id.condition)
        lines = listOf(findViewById(R.id.line1), findViewById(R.id.line2), findViewById(R.id.line3))
        status = findViewById(R.id.status)
        findViewById<TextView>(R.id.soft_left).text = "Places"
        findViewById<TextView>(R.id.soft_right).text = "Refresh"
        list.isFocusable = false
        list.showFooter = true

        Store.cached(this)?.let { c ->
            try {
                forecast = Forecast.parse(c.json)
                placeName = c.placeName
                shownHere = c.here
                fetchedAt = c.fetchedAt
            } catch (e: Exception) {
                Log.w(KeyRouter.TAG, "cached forecast unreadable: $e")
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        val want = Store.selected(this)
        val placeChanged = (want == null) != shownHere || (want != null && want.name != placeName)
        val stale = System.currentTimeMillis() - fetchedAt > STALE_MS
        if (placeChanged) {
            forecast = null // don't show the old place's weather under the new name
            placeName = want?.name ?: ""
            shownHere = want == null
        }
        if (forecast == null || stale || placeChanged) refresh() else render()
    }

    override fun onPause() {
        locating?.cancel()
        locating = null
        super.onPause()
    }

    // ---- fetching ----

    private fun refresh() {
        if (loading) return
        val place = Store.selected(this)
        if (place != null) {
            fetch(place.name, place.lat, place.lon)
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            setStatus("Allow location, or pick a place")
            return
        }
        val last = Locate.lastKnown(this)
        if (last != null) fetchHere(last)
        if (last == null || !Locate.isFresh(last)) {
            if (last == null) setStatus("Finding you…")
            locating?.cancel()
            locating = Locate.current(this, LOCATE_TIMEOUT_MS) { fresh ->
                locating = null
                when {
                    fresh != null && (last == null || fresh.distanceTo(last) > 5000) -> fetchHere(fresh)
                    fresh == null && last == null -> setStatus("Can't find you. Places → pick a town")
                }
            }
        }
    }

    private fun fetchHere(l: Location) = fetch(null, l.latitude, l.longitude)

    /** [name] null = current location: look the town name up too. */
    private fun fetch(name: String?, lat: Double, lon: Double) {
        loading = true
        setStatus("Updating…")
        Net.io.execute {
            val result = try {
                val raw = Net.getJson(Forecast.url(lat, lon)).toString()
                val parsed = Forecast.parse(JSONObject(raw))
                val label = name ?: (Geocode.nameFor(lat, lon) ?: "Here")
                Triple(raw, parsed, label)
            } catch (e: Exception) {
                Log.w(KeyRouter.TAG, "forecast failed: $e")
                null
            }
            runOnUiThread {
                loading = false
                if (isDestroyed) return@runOnUiThread
                if (result == null) {
                    setStatus(if (forecast == null) "No connection. Refresh to try again" else "Offline · ${updatedText()}")
                    return@runOnUiThread
                }
                val (raw, parsed, label) = result
                Store.cache(this, label, name == null, raw)
                forecast = parsed
                placeName = label
                shownHere = name == null
                fetchedAt = System.currentTimeMillis()
                statusText = ""
                render()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) refresh()
    }

    // ---- rendering ----

    private fun updatedText(): String =
        if (fetchedAt == 0L) "" else "Updated " + DateUtils.formatDateTime(this, fetchedAt, DateUtils.FORMAT_SHOW_TIME)

    private fun setStatus(text: String) {
        statusText = text
        renderStatus()
    }

    private fun renderStatus() {
        status.text = statusText.ifEmpty { updatedText() }
    }

    private fun render() {
        title.text = when {
            placeName.isEmpty() -> if (shownHere) "📍 Current location" else "Weather"
            shownHere -> "📍 $placeName"
            else -> placeName
        }
        tabs.forEachIndexed { i, t ->
            val on = i == page.ordinal
            t.setTextColor(getColor(if (on) R.color.highlight_text else R.color.text_dim))
            t.setBackgroundColor(if (on) getColor(R.color.highlight) else 0)
        }
        nowView.visibility = if (page == Page.NOW) View.VISIBLE else View.GONE
        list.visibility = if (page == Page.NOW) View.GONE else View.VISIBLE
        renderStatus()

        val f = forecast
        if (f == null) {
            symbol.text = ""
            temp.text = ""
            condition.text = ""
            lines.forEach { it.text = "" }
            list.setEmptyText(statusText.ifEmpty { "No forecast yet" })
            list.setListItems(emptyList())
            return
        }
        val n = f.now
        val today = f.days.firstOrNull()
        symbol.text = Wmo.symbol(n.code, n.isDay)
        temp.text = Fmt.temp(n.temp)
        condition.text = Wmo.text(n.code)
        lines[0].text = "Feels ${Fmt.temp(n.feels)}" + (today?.let { " · H ${Fmt.temp(it.hi)} L ${Fmt.temp(it.lo)}" } ?: "")
        lines[1].text = listOfNotNull(
            today?.rain?.takeIf { it >= 0 }?.let { "Rain $it%" },
            "Wind ${Fmt.wind(n.wind, n.windDir)}",
        ).joinToString(" · ")
        lines[2].text = listOfNotNull(
            n.humidity.takeIf { it >= 0 }?.let { "Humidity $it%" },
            today?.let { if (n.time.isBefore(it.sunset ?: n.time)) it.sunset?.let { s -> "Sunset ${Fmt.clock(s)}" } else null },
        ).joinToString(" · ")

        when (page) {
            Page.NOW -> Unit
            Page.HOURS -> list.setListItems(f.hours.mapIndexed { i, h ->
                val time = if (i == 0) "Now" else Fmt.hour(h.time)
                ListItem(
                    "${time.padEnd(6)} ${Wmo.symbol(h.code, h.isDay)} ${Fmt.temp(h.temp)}  ${Fmt.rain(h.rain)}".trimEnd(),
                    detail = Wmo.text(h.code),
                )
            }, list.selectedIndex)
            Page.DAYS -> {
                val todayDate = f.now.time.toLocalDate()
                list.setListItems(f.days.map { d ->
                    ListItem(
                        "${Fmt.weekday(d.date, todayDate).padEnd(5)} ${Wmo.symbol(d.code)} ${Fmt.temp(d.hi)}/${Fmt.temp(d.lo)}  ${Fmt.rain(d.rain)}".trimEnd(),
                        detail = Wmo.text(d.code),
                    )
                }, list.selectedIndex)
            }
        }
    }

    private fun showPage(p: Page) {
        if (p == page) return
        page = p
        list.setListItems(emptyList()) // start each list at the top
        render()
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.LEFT -> if (!repeat) showPage(Page.entries[Math.floorMod(page.ordinal - 1, 3)])
            KeyAction.RIGHT -> if (!repeat) showPage(Page.entries[(page.ordinal + 1) % 3])
            KeyAction.UP -> list.move(-1)
            KeyAction.DOWN -> list.move(+1)
            KeyAction.DIGIT -> if (!repeat && key.digit in 1..3) showPage(Page.entries[key.digit - 1])
            KeyAction.SOFT_LEFT -> if (!repeat) startActivity(Intent(this, PlacesActivity::class.java))
            KeyAction.SOFT_RIGHT -> if (!repeat) refresh()
            KeyAction.BACK -> if (!repeat) finish()
            KeyAction.OK, KeyAction.STAR, KeyAction.POUND -> Unit // swallowed: unhandled keys open the dialer here
            KeyAction.CALL -> return false
        }
        return true
    }

    companion object {
        private const val SCREEN = "Main"
        private const val REQ_LOCATION = 1
        private const val STALE_MS = 30 * 60 * 1000L
        private const val LOCATE_TIMEOUT_MS = 60_000L
    }
}
