package dev.mike.fieroweather

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.fieroweather.ui.KeyAction
import dev.mike.fieroweather.ui.KeyActionHandler
import dev.mike.fieroweather.ui.KeyRouter
import dev.mike.fieroweather.ui.ListItem
import dev.mike.fieroweather.ui.ListScreen
import dev.mike.fieroweather.ui.MultiTap
import dev.mike.fieroweather.ui.RoutedKey
import dev.mike.fieroweather.ui.say

/**
 * Current location, saved places, and town search. Typing (multi-tap, `#` = Abc/abc/ABC/123) starts a
 * search; OK runs it, OK on a result saves and shows it. Left soft removes a saved place.
 */
class PlacesActivity : Activity(), KeyActionHandler {

    private sealed class Row {
        object Here : Row()
        data class Saved(val place: Place) : Row()
        data class Result(val place: Place) : Row()
    }

    private lateinit var title: TextView
    private lateinit var entry: TextView
    private lateinit var modeBadge: TextView
    private lateinit var list: ListScreen
    private lateinit var softLeft: TextView
    private lateinit var softCenter: TextView
    private lateinit var softRight: TextView

    private var rows: List<Row> = emptyList()
    private var searching = false
    private var searched = ""
    private var busy = false

    private val multiTap = MultiTap { text, pending -> renderEntry(text, pending) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = findViewById(R.id.title)
        entry = findViewById(R.id.info)
        modeBadge = findViewById(R.id.mode)
        list = findViewById(R.id.list)
        softLeft = findViewById(R.id.soft_left)
        softCenter = findViewById(R.id.soft_center)
        softRight = findViewById(R.id.soft_right)
        list.onSelectionChanged = { renderSoftKeys() }
        list.requestFocus()
        showPlaces()
    }

    // ---- places list ----

    private fun showPlaces() {
        searching = false
        searched = ""
        multiTap.clear()
        title.text = "Places"
        entry.visibility = View.GONE
        modeBadge.visibility = View.GONE
        val chosen = Store.selected(this)
        rows = listOf(Row.Here) + Store.saved(this).map { Row.Saved(it) }
        val items = rows.map { r ->
            when (r) {
                Row.Here -> ListItem("📍 Current location", if (chosen == null) "Showing" else "")
                is Row.Saved -> ListItem(r.place.name, if (chosen?.name == r.place.name) "Showing" else "")
                is Row.Result -> ListItem(r.place.name)
            }
        }
        val sel = rows.indexOfFirst { (it is Row.Saved && it.place.name == chosen?.name) || (it is Row.Here && chosen == null) }
        list.setEmptyText("")
        list.setListItems(items + ListItem("Type a town to add it", dim = true), sel.coerceAtLeast(0))
        renderSoftKeys()
    }

    // ---- search ----

    private fun startSearch() {
        if (searching) return
        searching = true
        title.text = "Find a town"
        entry.visibility = View.VISIBLE
        modeBadge.visibility = View.VISIBLE
        rows = emptyList()
        list.setEmptyText("OK to search")
        list.setListItems(emptyList())
        renderEntry(multiTap.text, null)
    }

    private fun renderEntry(text: String, pending: Char?) {
        val sb = SpannableStringBuilder(text)
        if (pending != null) {
            // The letter still cycling is shown highlighted at the end.
            val start = sb.length
            sb.append(pending)
            sb.setSpan(BackgroundColorSpan(getColor(R.color.highlight)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(getColor(R.color.highlight_text)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append("▏")
        }
        entry.text = sb
        modeBadge.text = multiTap.mode.label
        // Typing more after a search: back to "OK to search".
        if (searching && searched.isNotEmpty() && multiTap.text.trim() != searched) {
            searched = ""
            rows = emptyList()
            list.setEmptyText("OK to search")
            list.setListItems(emptyList())
        }
        renderSoftKeys()
    }

    private fun search() {
        val q = multiTap.text.trim()
        if (q.length < 2) return say("Type at least 2 letters")
        if (busy) return
        busy = true
        searched = q
        list.setEmptyText("Searching…")
        list.setListItems(emptyList())
        Net.io.execute {
            val found = try {
                Geocode.search(q)
            } catch (e: Exception) {
                Log.w(KeyRouter.TAG, "search failed: $e")
                null
            }
            runOnUiThread {
                busy = false
                if (isDestroyed || !searching || searched != q) return@runOnUiThread
                rows = found.orEmpty().map { Row.Result(it) }
                list.setEmptyText(if (found == null) "No connection" else "No towns found")
                list.setListItems(rows.map { ListItem((it as Row.Result).place.name) })
                renderSoftKeys()
            }
        }
    }

    // ---- actions ----

    private fun choose(row: Row) {
        when (row) {
            Row.Here -> Store.select(this, null)
            is Row.Saved -> Store.select(this, row.place)
            is Row.Result -> {
                Store.add(this, row.place)
                Store.select(this, row.place)
            }
        }
        finish()
    }

    private fun renderSoftKeys() {
        val row = rows.getOrNull(list.selectedIndex)
        softLeft.text = if (!searching && row is Row.Saved) "Remove" else ""
        softCenter.text = when {
            searching && rows.isEmpty() -> "Search"
            row != null -> "Show"
            else -> ""
        }
        softRight.text = if (searching) "Clear" else ""
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.UP -> list.move(-1)
            KeyAction.DOWN -> list.move(+1)
            KeyAction.LEFT -> list.page(-1)
            KeyAction.RIGHT -> list.page(+1)
            KeyAction.DIGIT -> if (!repeat) {
                startSearch()
                multiTap.press(key.digit)
            } else if (event.isLongPress && searching) {
                multiTap.longPress(key.digit) // hold a key: type its digit in any mode
            }
            KeyAction.POUND -> if (!repeat) {
                startSearch()
                multiTap.nextMode()
                say(MODE_NAMES.getValue(multiTap.mode))
            }
            KeyAction.OK -> if (!repeat) {
                val row = rows.getOrNull(list.selectedIndex)
                when {
                    row != null -> choose(row)
                    searching -> search()
                }
            }
            KeyAction.SOFT_LEFT -> if (!repeat && !searching) {
                (rows.getOrNull(list.selectedIndex) as? Row.Saved)?.let {
                    Store.remove(this, it.place)
                    say("Removed ${it.place.name}")
                    showPlaces()
                }
            }
            KeyAction.SOFT_RIGHT -> if (!repeat && searching) showPlaces()
            KeyAction.BACK -> if (searching) {
                if (!multiTap.backspace() && !repeat) showPlaces()
            } else if (!repeat) {
                finish()
            }
            KeyAction.STAR -> Unit
            KeyAction.CALL -> return false
        }
        return true
    }

    companion object {
        private const val SCREEN = "Places"
        private val MODE_NAMES = mapOf(
            MultiTap.Mode.SENTENCE to "Abc: capital first letter",
            MultiTap.Mode.LOWER to "abc: lower case",
            MultiTap.Mode.UPPER to "ABC: capitals",
            MultiTap.Mode.DIGITS to "123: numbers",
        )
    }
}
