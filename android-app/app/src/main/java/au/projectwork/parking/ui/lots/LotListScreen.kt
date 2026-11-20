package au.projectwork.parking.ui.lots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.ui.AuMark
import au.projectwork.parking.ui.FavouriteToggle
import au.projectwork.parking.ui.FieldLabel
import au.projectwork.parking.ui.MoonIcon
import au.projectwork.parking.ui.SunIcon
import au.projectwork.parking.ui.FullStamp
import au.projectwork.parking.ui.LiveIndicator
import au.projectwork.parking.ui.NavigationArrow
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.countInk
import au.projectwork.parking.ui.isStale
import au.projectwork.parking.ui.locationAllowed
import au.projectwork.parking.ui.locationOn
import au.projectwork.parking.ui.rememberLocation
import au.projectwork.parking.ui.rememberNow
import au.projectwork.parking.ui.PaintedBays
import au.projectwork.parking.ui.ParkingPlate
import au.projectwork.parking.ui.PrintStamp
import au.projectwork.parking.ui.RefreshBox
import au.projectwork.parking.ui.StateContent
import au.projectwork.parking.ui.TearRule
import au.projectwork.parking.ui.Ticket
import au.projectwork.parking.ui.navigateTo
import au.projectwork.parking.ui.theme.TicketColors
import au.projectwork.parking.ui.theme.TicketType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotListScreen(viewModel: LotListViewModel, onLotClick: (Int) -> Unit, dark: Boolean, onToggleTheme: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val here by viewModel.here.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val heroDetail by viewModel.heroDetail.collectAsStateWithLifecycle()
    val askLocation = rememberLocation(viewModel::setHere)
    val now = rememberNow()
    val stale = (state as? UiState.Content)?.let { isStale(it.fetchedAt, it.offline, now) }
    Scaffold(
        containerColor = TicketColors.current.ground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AuMark()
                        Spacer(Modifier.width(12.dp))
                        Text("AU Parking", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    if (stale != null) LiveIndicator(stale)
                    IconButton(onClick = onToggleTheme) {
                        Icon(if (dark) SunIcon else MoonIcon, contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme")
                    }
                },
            )
        },
    ) { padding ->
        StateContent(state, onRetry = viewModel::refresh, modifier = Modifier.padding(padding)) { lots, offline, fetchedAt ->
            RefreshBox(resultKey = fetchedAt to offline, onRefresh = viewModel::refresh) {
                val hero = pickHero(lots, favourites, here)
                val rows = arrange(lots, favourites, here, filter, excludeId = hero?.row?.lot?.id)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "hero") {
                        if (hero != null) {
                            HeroTicket(
                                hero,
                                spots = heroDetail?.takeIf { it.id == hero.row.lot.id },
                                offline = offline,
                                fetchedAt = fetchedAt,
                                stale = stale == true,
                                onOpen = { onLotClick(hero.row.lot.id) },
                                onToggleFavourite = { viewModel.toggleFavourite(hero.row.lot.id) },
                            )
                        } else {
                            AllFullTicket(offline, fetchedAt)
                        }
                    }
                    item(key = "filters") { Filters(filter, viewModel::setFilter) }
                    if (filter == LotFilter.Nearby && here == null) {
                        item(key = "no-location") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Sort lots by distance:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TicketColors.current.faded,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                                TextButton(onClick = askLocation) { val ctx = LocalContext.current
                                    Text(
                                        when {
                                            !ctx.locationAllowed() -> "Allow location"
                                            !ctx.locationOn() -> "Turn on location"
                                            else -> "Finding location…" // allowed and on, waiting for a fix
                                        },
                                        color = TicketColors.current.ink,
                                    ) }
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "empty") { EmptyList(filter, onlyHero = hero != null) }
                    }
                    items(rows, key = { it.lot.id }) { row ->
                        LotTicket(row, stale = stale == true, onClick = { onLotClick(row.lot.id) }, onToggleFavourite = { viewModel.toggleFavourite(row.lot.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTicket(
    hero: Hero,
    spots: LotDetail?,
    offline: Boolean,
    fetchedAt: Long,
    stale: Boolean,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val c = TicketColors.current
    val context = LocalContext.current
    val lot = hero.row.lot
    val why = when (hero.reason) {
        HeroReason.Nearest -> "Nearest with space"
        HeroReason.Favourite -> "Favourite with space"
        HeroReason.MostSpace -> "Most space now"
    }
    Ticket(Modifier.fillMaxWidth().clickable(onClickLabel = "See spots", onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // merged so TalkBack reads "12 of 16 free" as one phrase
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom) {
                    Text("${lot.freeSpots}", style = TicketType.heroCount, color = countInk(stale))
                    Text(" of ${lot.totalSpots}\n free", style = TicketType.unit, color = c.faded, modifier = Modifier.padding(bottom = 10.dp))
                }
                FavouriteToggle(hero.row.favourite, onToggleFavourite)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ParkingPlate()
                    Spacer(Modifier.width(10.dp))
                    Text("${lot.address.street} ${lot.address.houseNr}", style = MaterialTheme.typography.titleLarge, color = c.ink)
                }
                FieldLabel(listOfNotNull(why, hero.row.distanceMeters?.let(::formatDistance), lot.address.city).joinToString(" · "))
            }
            if (spots != null) {
                PaintedBays(spots.rows.flatMap { it.spots }, maxBayWidth = 32.dp, bayHeight = 40.dp, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { navigateTo(context, lot.address) }, modifier = Modifier.weight(1f)) {
                    Icon(NavigationArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Navigate")
                }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("See spots") }
            }
            TearRule()
            PrintStamp(fetchedAt, offline, printKey = lot.freeSpots to spots)
        }
    }
}

@Composable
private fun AllFullTicket(offline: Boolean, fetchedAt: Long) {
    Ticket(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FullStamp()
            Text("Every lot is full right now", style = MaterialTheme.typography.titleLarge)
            Text(
                "Counts update live. Pull down to check again.",
                style = MaterialTheme.typography.bodyMedium,
                color = TicketColors.current.faded,
            )
            TearRule()
            PrintStamp(fetchedAt, offline, printKey = null)
        }
    }
}

@Composable
private fun Filters(selected: LotFilter, onSelect: (LotFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        LotFilter.entries.forEach { f ->
            FilterChip(selected = f == selected, onClick = { onSelect(f) }, label = { Text(f.name) })
        }
    }
}

@Composable
private fun EmptyList(filter: LotFilter, onlyHero: Boolean) {
    val text = when {
        filter == LotFilter.Favourites -> "No other favourites yet. Tap the star on a lot to keep it here."
        onlyHero -> "That's the only lot so far."
        else -> "No parking lots yet."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TicketColors.current.faded, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun LotTicket(row: LotRow, stale: Boolean, onClick: () -> Unit, onToggleFavourite: () -> Unit) {
    val c = TicketColors.current
    val lot: LotSummary = row.lot
    Ticket(Modifier.fillMaxWidth().clickable(onClickLabel = "See spots", onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParkingPlate()
                Spacer(Modifier.width(10.dp))
                Text(
                    "${lot.address.street} ${lot.address.houseNr}",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                FavouriteToggle(row.favourite, onToggleFavourite)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (lot.freeSpots > 0) {
                    Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom) {
                        Text("${lot.freeSpots}", style = TicketType.count, color = countInk(stale))
                        Text(" of ${lot.totalSpots} free", style = TicketType.unit, color = c.faded, modifier = Modifier.padding(bottom = 6.dp))
                    }
                } else {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { FullStamp() }
                }
                Text(
                    row.distanceMeters?.let(::formatDistance) ?: lot.address.city,
                    style = TicketType.unit,
                    color = c.faded,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}
