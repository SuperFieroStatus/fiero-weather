package dev.mike.fieroweather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ForecastTest {

    // Trimmed Open-Meteo response: current at 15:15, hourly from midnight, two days.
    private val sample = """
        {"current":{"time":"2026-11-06T15:15","temperature_2m":71.6,"apparent_temperature":70.2,
          "relative_humidity_2m":45,"weather_code":2,"is_day":1,"wind_speed_10m":8.4,"wind_direction_10m":300},
         "hourly":{"time":["2026-11-06T14:00","2026-11-06T15:00","2026-11-06T16:00","2026-11-06T17:00"],
          "temperature_2m":[70.0,71.5,72.4,null],"weather_code":[1,2,3,61],
          "precipitation_probability":[0,5,null,40],"is_day":[1,1,1,0]},
         "daily":{"time":["2026-11-06","2026-11-07"],"weather_code":[2,95],
          "temperature_2m_max":[78.1,65.0],"temperature_2m_min":[60.4,55.2],
          "precipitation_probability_max":[20,80],
          "sunrise":["2026-11-06T06:58","2026-11-07T06:59"],"sunset":["2026-11-06T17:41","2026-11-07T17:40"]}}
    """.trimIndent()

    private val f = Forecast.parse(JSONObject(sample))

    @Test fun current() {
        assertEquals(LocalDateTime.of(2026, 11, 6, 15, 15), f.now.time)
        assertEquals("72°", Fmt.temp(f.now.temp))
        assertEquals("Partly cloudy", Wmo.text(f.now.code))
        assertEquals("8 mph NW", Fmt.wind(f.now.wind, f.now.windDir))
    }

    @Test fun hoursStartAtTheCurrentHourAndSkipGaps() {
        assertEquals(listOf(15, 16), f.hours.map { it.time.hour }) // 14:00 is past, 17:00 has no temperature
        assertEquals(-1, f.hours[1].rain) // null probability → unknown, shown blank
        assertEquals("", Fmt.rain(f.hours[1].rain))
    }

    @Test fun days() {
        assertEquals(2, f.days.size)
        assertEquals("78°", Fmt.temp(f.days[0].hi))
        assertEquals(80, f.days[1].rain)
        assertEquals("5:41 PM", Fmt.clock(f.days[0].sunset!!))
        val today = LocalDate.of(2026, 11, 6)
        assertEquals("Today", Fmt.weekday(f.days[0].date, today))
        assertEquals("Tmrw", Fmt.weekday(f.days[1].date, today))
        assertEquals("Mon", Fmt.weekday(LocalDate.of(2026, 11, 9), today))
    }

    @Test fun rainSoonIsTheHighestUpcomingHour() {
        assertEquals(5, f.rainSoon) // 15:00 = 5%, 16:00 unknown; 14:00's hour is past
        val noHours = f.copy(hours = emptyList())
        assertEquals(20, noHours.rainSoon) // falls back to today's chance
    }

    @Test fun compassAndCalm() {
        assertEquals("N", Fmt.compass(350))
        assertEquals("N", Fmt.compass(10))
        assertEquals("E", Fmt.compass(90))
        assertEquals("SW", Fmt.compass(225))
        assertEquals("Calm", Fmt.wind(0.3, 180))
    }

    @Test fun urlRoundsPositionAndAsksForUsUnits() {
        val url = Forecast.url(30.267153, -97.743057)
        assertTrue(url, url.contains("latitude=30.27&longitude=-97.74"))
        assertTrue(url.contains("temperature_unit=fahrenheit") && url.contains("wind_speed_unit=mph"))
    }

    @Test fun everyCodeHasWordsAndSymbol() {
        for (c in listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)) {
            assertTrue("text $c", Wmo.text(c) != "—")
            assertTrue("symbol $c", Wmo.symbol(c) != "·")
        }
    }
}
