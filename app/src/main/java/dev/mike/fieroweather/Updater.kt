package dev.mike.fieroweather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import dev.mike.fieroweather.ui.KeyRouter
import org.json.JSONObject

/** Fetches a forecast, caches it, and hands the current conditions to Fiero Launcher. Blocking: call off the UI thread. */
object Updater {

    class Result(val placeName: String, val forecast: Forecast)

    /** [name] null = current location: the town name is looked up (or reused when you haven't moved far). */
    fun fetch(c: Context, name: String?, lat: Double, lon: Double): Result {
        val raw = Net.getJson(Forecast.url(lat, lon)).toString()
        val forecast = Forecast.parse(JSONObject(raw))
        val label = name ?: reusableName(c, lat, lon) ?: Geocode.nameFor(lat, lon) ?: "Here"
        Store.cache(c, label, name == null, lat, lon, raw)
        Launcher.send(c, forecast, label)
        return Result(label, forecast)
    }

    private fun reusableName(c: Context, lat: Double, lon: Double): String? {
        val cached = Store.cached(c) ?: return null
        if (!cached.here || cached.lat.isNaN()) return null
        val d = FloatArray(1)
        Location.distanceBetween(lat, lon, cached.lat, cached.lon, d)
        return cached.placeName.takeIf { d[0] < 3000 }
    }

    /** Background refresh: the chosen place, else where the phone last was. Returns false to retry later. */
    fun refreshInBackground(c: Context): Boolean {
        val cached = Store.cached(c)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < MIN_INTERVAL_MS) {
            Launcher.resend(c) // fresh enough; just make sure the launcher has it
            return true
        }
        return try {
            val place = Store.selected(c)
            when {
                place != null -> fetch(c, place.name, place.lat, place.lon)
                else -> {
                    // No live fix in the background: the newest one any app got, else the last place we used.
                    val l = Locate.lastKnown(c)
                    when {
                        l != null -> fetch(c, null, l.latitude, l.longitude)
                        cached != null && !cached.lat.isNaN() -> fetch(c, null, cached.lat, cached.lon)
                        else -> return true // nowhere to look up yet; the app will do it when opened
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(KeyRouter.TAG, "background refresh failed: $e")
            false
        }
    }

    /** Skip background fetches more often than this (the launcher asks whenever it shows stale weather). */
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L
}

/** Every 30 minutes while there's a network, plus one-off refreshes the launcher asks for. */
object Jobs {
    private const val PERIODIC = 1
    private const val NOW = 2

    fun schedulePeriodic(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(PERIODIC) != null) return
        js.schedule(
            JobInfo.Builder(PERIODIC, ComponentName(c, RefreshJob::class.java))
                .setPeriodic(30 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build(),
        )
    }

    fun refreshSoon(c: Context) {
        c.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(NOW, ComponentName(c, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
    }
}

class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Net.io.execute {
            val ok = Updater.refreshInBackground(applicationContext)
            Log.i(KeyRouter.TAG, "background refresh ${if (ok) "done" else "failed, will retry"}")
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Fiero Launcher asks for fresh weather (it has no internet of its own). Signature permission: only Fiero apps. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Jobs.schedulePeriodic(context)
        Jobs.refreshSoon(context)
    }
}

/**
 * Current conditions for Fiero Launcher's home screen and the outer screen, as ready-to-show text, sent
 * as an explicit broadcast that only a Fiero app (same signing key) can send or receive.
 */
object Launcher {
    private const val PACKAGE = "dev.mike.fierolauncher"
    private const val ACTION = "dev.mike.fierolauncher.action.WEATHER"
    const val PERMISSION = "dev.mike.fiero.permission.WEATHER"

    fun send(c: Context, f: Forecast, place: String, at: Long = System.currentTimeMillis()) {
        val today = f.days.firstOrNull()
        val intent = Intent(ACTION).setPackage(PACKAGE)
            .putExtra("symbol", Wmo.symbol(f.now.code, f.now.isDay))
            .putExtra("temp", Fmt.temp(f.now.temp))
            .putExtra("text", Wmo.text(f.now.code))
            .putExtra("hilo", today?.let { "H ${Fmt.temp(it.hi)} L ${Fmt.temp(it.lo)}" } ?: "")
            .putExtra("rain", f.rainSoon) // % chance in the next 12 hours, -1 unknown
            .putExtra("place", place)
            .putExtra("at", at)
        c.sendBroadcast(intent, PERMISSION)
    }

    /** Re-sends the cached forecast (e.g. the launcher was reinstalled and lost it). */
    fun resend(c: Context) {
        val cached = Store.cached(c) ?: return
        try {
            send(c, Forecast.parse(cached.json), cached.placeName, cached.fetchedAt)
        } catch (e: Exception) {
            Log.w(KeyRouter.TAG, "resend failed: $e")
        }
    }
}
