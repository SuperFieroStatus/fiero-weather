package dev.mike.fieroweather

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** One Open-Meteo forecast, already in °F / mph and the place's local time. */
data class Forecast(val now: Now, val hours: List<Hour>, val days: List<Day>) {

    data class Now(
        val time: LocalDateTime,
        val temp: Double,
        val feels: Double,
        val humidity: Int,
        val code: Int,
        val isDay: Boolean,
        val wind: Double,
        val windDir: Int,
    )

    data class Hour(val time: LocalDateTime, val temp: Double, val code: Int, val rain: Int, val isDay: Boolean)

    data class Day(
        val date: LocalDate,
        val code: Int,
        val hi: Double,
        val lo: Double,
        val rain: Int,
        val sunrise: LocalDateTime?,
        val sunset: LocalDateTime?,
    )

    companion object {
        /** Hours shown on the Hourly page, starting with the current hour. */
        const val HOURS = 24

        fun url(lat: Double, lon: Double): String =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day," +
                "wind_speed_10m,wind_direction_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
                "&timezone=auto&forecast_days=7"

        /** Two decimals (~1 km): plenty for weather, and the exact position never leaves the phone. */
        fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

        fun parse(json: JSONObject): Forecast {
            val c = json.getJSONObject("current")
            val now = Now(
                time = LocalDateTime.parse(c.getString("time")),
                temp = c.getDouble("temperature_2m"),
                feels = c.optDouble("apparent_temperature", c.getDouble("temperature_2m")),
                humidity = c.optInt("relative_humidity_2m", -1),
                code = c.optInt("weather_code", -1),
                isDay = c.optInt("is_day", 1) == 1,
                wind = c.optDouble("wind_speed_10m", 0.0),
                windDir = c.optInt("wind_direction_10m", -1),
            )

            val h = json.getJSONObject("hourly")
            val ht = h.getJSONArray("time")
            val thisHour = now.time.truncatedTo(ChronoUnit.HOURS)
            val hours = (0 until ht.length())
                .map { i ->
                    Hour(
                        time = LocalDateTime.parse(ht.getString(i)),
                        temp = h.getJSONArray("temperature_2m").num(i),
                        code = h.getJSONArray("weather_code").int(i),
                        rain = h.optJSONArray("precipitation_probability")?.int(i) ?: -1,
                        isDay = (h.optJSONArray("is_day")?.int(i) ?: 1) == 1,
                    )
                }
                .filter { !it.time.isBefore(thisHour) && !it.temp.isNaN() }
                .take(HOURS)

            val d = json.getJSONObject("daily")
            val dt = d.getJSONArray("time")
            val days = (0 until dt.length()).map { i ->
                Day(
                    date = LocalDate.parse(dt.getString(i)),
                    code = d.getJSONArray("weather_code").int(i),
                    hi = d.getJSONArray("temperature_2m_max").num(i),
                    lo = d.getJSONArray("temperature_2m_min").num(i),
                    rain = d.optJSONArray("precipitation_probability_max")?.int(i) ?: -1,
                    sunrise = d.optJSONArray("sunrise")?.time(i),
                    sunset = d.optJSONArray("sunset")?.time(i),
                )
            }
            return Forecast(now, hours, days)
        }

        private fun JSONArray.num(i: Int): Double = if (isNull(i)) Double.NaN else getDouble(i)
        private fun JSONArray.int(i: Int): Int = if (isNull(i)) -1 else getInt(i)
        private fun JSONArray.time(i: Int): LocalDateTime? = if (isNull(i)) null else LocalDateTime.parse(getString(i))
    }
}

/** Text formatting shared by the screens (and the tests). */
object Fmt {
    private val HOUR = DateTimeFormatter.ofPattern("h a", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US)

    fun temp(v: Double): String = if (v.isNaN()) "--°" else "${v.roundToInt()}°"
    fun hour(t: LocalDateTime): String = HOUR.format(t)
    fun clock(t: LocalDateTime): String = CLOCK.format(t)
    fun rain(p: Int): String = if (p < 0) "" else "$p%"

    fun weekday(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tmrw"
        else -> WEEKDAY.format(d)
    }

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Where the wind comes from, e.g. 300° → "NW". */
    fun compass(deg: Int): String = if (deg < 0) "" else COMPASS[Math.floorMod(((deg + 22.5) / 45).toInt(), 8)]

    fun wind(mph: Double, deg: Int): String {
        val speed = mph.roundToInt()
        return if (speed == 0) "Calm" else "$speed mph ${compass(deg)}".trim()
    }
}
