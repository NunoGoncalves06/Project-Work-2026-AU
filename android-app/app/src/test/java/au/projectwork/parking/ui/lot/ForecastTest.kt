package au.projectwork.parking.ui.lot

import au.projectwork.parking.data.Forecast
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ForecastTest {

    @Test
    fun groupsHoursByLocalDay() {
        // 22:00 and 23:00 UTC are 23:00 Wednesday and 00:00 Thursday in Copenhagen (UTC+1 in November)
        val days = forecastByDay(
            listOf(Forecast("2026-11-11T22:00:00Z", 5), Forecast("2026-11-11T23:00:00Z", 7)),
            ZoneId.of("Europe/Copenhagen"),
        )
        assertEquals(
            mapOf(
                LocalDate.of(2026, 11, 11) to listOf(LocalTime.of(23, 0) to 5),
                LocalDate.of(2026, 11, 12) to listOf(LocalTime.of(0, 0) to 7),
            ),
            days,
        )
    }

    @Test
    fun labelsDays() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Tomorrow", dayLabel(today.plusDays(1), today))
        assertEquals("Fri 13", dayLabel(today.plusDays(2), today))
    }
}
