package au.projectwork.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.projectwork.parking.ui.lot.LotDetailScreen
import au.projectwork.parking.ui.lot.LotDetailViewModel
import au.projectwork.parking.ui.lots.LotListScreen
import au.projectwork.parking.ui.lots.LotListViewModel
import au.projectwork.parking.ui.theme.ParkingTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParkingTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "lots") {
                    composable("lots") {
                        LotListScreen(
                            viewModel(factory = viewModelFactory { initializer { LotListViewModel(container.repository, container.store, container.updates) } }),
                            onLotClick = { id -> nav.navigate("lots/$id") { launchSingleTop = true } },
                        )
                    }
                    composable("lots/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
                        val id = entry.arguments!!.getInt("id")
                        LotDetailScreen(
                            viewModel(factory = viewModelFactory { initializer { LotDetailViewModel(container.repository, container.store, id, container.updates) } }),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
