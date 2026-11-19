package au.projectwork.parking

import android.app.UiModeManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    // one per process: a recreated activity must not build a second OkHttp client and update stream
    private val container get() = shared ?: AppContainer(applicationContext).also { shared = it }

    /** The launcher icon the current theme wants; swapped in onStop (see there). */
    private var iconDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            container.theme.dark.collect { picked ->
                // Android 12+ draws the launch screen before the app runs: telling it the app's choice makes the
                // launch screen use the matching icon and ground (values-night). Before the first pick: follow the system.
                if (picked != null && Build.VERSION.SDK_INT >= 31) {
                    getSystemService(UiModeManager::class.java).setApplicationNightMode(
                        if (picked) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO,
                    )
                }
                iconDark = picked ?: false // no pick: the default (light) entry, which also undoes an older build's system-theme swap
            }
        }
        // read the pick before the first frame: below Android 12 nothing else tells it (a tiny DataStore read)
        val stored = runBlocking { container.theme.dark.first() }
        setContent {
            // the sun/moon choice wins; before the first tap the app follows the system theme
            val picked by container.theme.dark.collectAsState(initial = stored)
            val dark = picked ?: isSystemInDarkTheme()
            val scope = rememberCoroutineScope()
            LaunchedEffect(dark) {
                // status bar icons follow the app theme, not the system one
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            ParkingTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "lots") {
                    composable("lots") {
                        LotListScreen(
                            viewModel(factory = viewModelFactory { initializer { LotListViewModel(container.repository, container.store, container.updates) } }),
                            onLotClick = { id -> nav.navigate("lots/$id") { launchSingleTop = true } },
                            dark = dark,
                            onToggleTheme = { scope.launch { container.theme.setDark(!dark) } },
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

    override fun onStop() {
        super.onStop()
        // Android closes the app when the launcher entry it was opened from is turned off, so swap the icon only
        // once the user has left the app (not during the recreate a theme change causes)
        if (!isChangingConfigurations) iconDark?.let(::showLauncherIcon)
    }

    /**
     * Turns on the launcher entry whose icon matches the theme (B1 day / B2 night ticket) and turns the other off.
     * Does nothing when it already matches, so the launcher is only disturbed when the theme really changed.
     * shortcut: a launcher may drop a home-screen shortcut to the entry turned off; the app drawer always has it.
     */
    private fun showLauncherIcon(dark: Boolean) {
        val light = ComponentName(this, "$packageName.LauncherLight")
        val night = ComponentName(this, "$packageName.LauncherDark")
        val (on, off) = if (dark) night to light else light to night
        val state = packageManager.getComponentEnabledSetting(on)
        val isOn = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && on == light) // manifest default: light on
        if (isOn) return
        // turn the new one on first, so there is never a moment without a launcher entry
        packageManager.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        packageManager.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    private companion object {
        var shared: AppContainer? = null
    }
}
