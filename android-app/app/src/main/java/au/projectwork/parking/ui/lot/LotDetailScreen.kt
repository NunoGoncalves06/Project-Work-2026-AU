package au.projectwork.parking.ui.lot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import au.projectwork.parking.ui.BayLegend
import au.projectwork.parking.ui.FavouriteToggle
import au.projectwork.parking.ui.FieldLabel
import au.projectwork.parking.ui.FullStamp
import au.projectwork.parking.ui.PaintedBays
import au.projectwork.parking.ui.PrintStamp
import au.projectwork.parking.ui.RefreshBox
import au.projectwork.parking.ui.StateContent
import au.projectwork.parking.ui.TearRule
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.Ticket
import au.projectwork.parking.ui.navigateTo
import au.projectwork.parking.ui.NavigationArrow
import au.projectwork.parking.ui.countInk
import au.projectwork.parking.ui.isStale
import au.projectwork.parking.ui.rememberNow
import au.projectwork.parking.ui.theme.TicketColors
import au.projectwork.parking.ui.theme.TicketType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotDetailScreen(viewModel: LotDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favourite by viewModel.favourite.collectAsStateWithLifecycle()
    val title = (state as? UiState.Content)?.data?.address?.let { "${it.street} ${it.houseNr}" } ?: ""
    Scaffold(
        containerColor = TicketColors.current.ground,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { if (title.isNotEmpty()) FavouriteToggle(favourite, viewModel::toggleFavourite) },
            )
        },
    ) { padding ->
        StateContent(state, onRetry = viewModel::refresh, onBack = onBack, modifier = Modifier.padding(padding)) { lot, offline, fetchedAt ->
            RefreshBox(resultKey = fetchedAt to offline, onRefresh = viewModel::refresh) {
                LotDetailContent(lot, offline, fetchedAt)
            }
        }
    }
}

@Composable
private fun LotDetailContent(lot: LotDetail, offline: Boolean, fetchedAt: Long) {
    val c = TicketColors.current
    val context = LocalContext.current
    val stale = isStale(fetchedAt, offline, rememberNow())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ticket(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lot.freeSpots > 0) {
                    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom) {
                        Text("${lot.freeSpots}", style = TicketType.heroCount, color = countInk(stale))
                        Text(" of ${lot.totalSpots}\n free", style = TicketType.unit, color = c.faded, modifier = Modifier.padding(bottom = 10.dp))
                    }
                } else {
                    FullStamp()
                }
                FieldLabel("${lot.address.city} · ${lot.carsInside} cars inside")
                Button(onClick = { navigateTo(context, lot.address) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(NavigationArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Navigate")
                }
                TearRule()
                PrintStamp(fetchedAt, offline, printKey = lot)
            }
        }
        Ticket(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                lot.rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Row ${row.id} · ${row.freeSpots} free", style = TicketType.unit, color = c.ink)
                        PaintedBays(row.spots)
                    }
                }
                TearRule()
                BayLegend()
            }
        }
    }
}
