package dev.mike.fieroweather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.mike.fieroweather.ui.KeyRouter

/**
 * Where the phone is, without Google Play services: the newest last-known fix from any provider, and a
 * one-shot fresh fix (network if the phone has it, else GPS) for when that's old or missing.
 */
object Locate {
    /** A fix younger than this is good enough for weather. */
    const val FRESH_MS = 2 * 60 * 60 * 1000L

    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java)
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    fun isFresh(l: Location) = System.currentTimeMillis() - l.time < FRESH_MS

    /** Calls [done] once on the main thread with a fresh fix, or null after [timeoutMs]. */
    @SuppressLint("MissingPermission")
    fun current(c: Context, timeoutMs: Long, done: (Location?) -> Unit): CancellationSignal {
        val lm = c.getSystemService(LocationManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val cancel = CancellationSignal()
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            if (l == null) cancel.cancel()
            done(l)
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            main.post { finish(null) }
            return cancel
        }
        try {
            lm.getCurrentLocation(provider, cancel, c.mainExecutor) { finish(it) }
            main.postDelayed({ finish(null) }, timeoutMs)
        } catch (e: SecurityException) {
            Log.w(KeyRouter.TAG, "no location permission: $e")
            main.post { finish(null) }
        }
        return cancel
    }
}
