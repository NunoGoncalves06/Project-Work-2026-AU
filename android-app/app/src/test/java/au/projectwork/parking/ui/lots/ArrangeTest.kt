package au.projectwork.parking.ui.lots

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrangeTest {

    // Finlandsgade is ~350 m from the phone, Åbogade ~1.1 km, Nowhere has no coordinates
    private val here = Here(56.1700, 10.1860)
    private val near = LotSummary(1, Address("Finlandsgade", "22", "Aarhus", 56.1716, 10.1897), 16, 12, 4)
    private val far = LotSummary(2, Address("Åbogade", "34", "Aarhus", 56.1795, 10.1950), 60, 48, 12)
    private val full = LotSummary(3, Address("Brabrand", "1", "Aarhus", 56.1702, 10.1862), 10, 0, 10)
    private val unplaced = LotSummary(4, Address("Nowhere", "1", "Aarhus"), 20, 30, 0)
    private val lots = listOf(far, unplaced, full, near)

    @Test
    fun heroIsTheNearestLotWithSpaceNotTheNearestFullOne() {
        assertEquals(Hero(LotRow(near, distanceMeters(here, near.address), false), HeroReason.Nearest), pickHero(lots, emptySet(), here))
    }

    @Test
    fun withoutLocationHeroIsTheFavouriteWithMostSpace() {
        assertEquals(HeroReason.Favourite to 2, pickHero(lots, setOf(1, 2), null)!!.let { it.reason to it.row.lot.id })
    }

    @Test
    fun withoutLocationOrFavouritesHeroHasTheMostSpace() {
        assertEquals(HeroReason.MostSpace to 2, pickHero(lots, emptySet(), null)!!.let { it.reason to it.row.lot.id })
    }

    @Test
    fun noHeroWhenEveryLotIsFull() {
        assertNull(pickHero(listOf(full), emptySet(), here))
    }

    @Test
    fun nearbySortsByDistanceWithUnplacedLotsLast() {
        assertEquals(listOf(3, 1, 2, 4), arrange(lots, emptySet(), here, LotFilter.Nearby, excludeId = null).map { it.lot.id })
    }

    @Test
    fun favouritesFilterAndExcludesTheHero() {
        assertEquals(listOf(2), arrange(lots, setOf(1, 2), here, LotFilter.Favourites, excludeId = 1).map { it.lot.id })
    }

    @Test
    fun allSortsByStreet() {
        assertEquals(listOf("Åbogade", "Brabrand", "Finlandsgade", "Nowhere").sorted(), arrange(lots, emptySet(), here, LotFilter.All, null).map { it.lot.address.street })
    }

    @Test
    fun distanceIsAboutRight() {
        val d = distanceMeters(here, near.address)!!
        assert(d in 250.0..350.0) { "got $d" }
    }

    @Test
    fun formatsDistances() {
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1.2 km", formatDistance(1_234.0))
        assertEquals("12 km", formatDistance(12_400.0))
    }
}
