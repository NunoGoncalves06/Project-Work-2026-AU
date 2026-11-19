package au.projectwork.parking.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import au.projectwork.parking.BuildConfig
import au.projectwork.parking.ui.lots.Here

private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.locationAllowed() = LOCATION.any(::granted)

/** The device's location switch (separate from the app's permission). */
fun Context.locationOn() = getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Asks once per launch for location (and, in debug on Android 17+, the local-network permission the dev
 * backend needs, in the same dialog so the two requests don't collide), then reports where the phone is.
 * Returns an action that asks again, or opens the app's settings once Android stops showing the dialog.
 */
@Composable
fun rememberLocation(onHere: (Here) -> Unit): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LOCATION.any(context::granted)) requestHere(context, onHere)
    }
    // on every return to the screen (also from the location settings): look again where the phone is
    LifecycleResumeEffect(Unit) {
        if (LOCATION.any(context::granted)) requestHere(context, onHere)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            addAll(LOCATION.filterNot(context::granted))
            if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 37 && !context.granted(LOCAL_NETWORK)) add(LOCAL_NETWORK)
        }
        if (wanted.isNotEmpty() && !asked) {
            asked = true
            launcher.launch(wanted.toTypedArray())
        }
    }
    return {
        val activity = context.findActivity()
        // after a denial Android shows the rationale flag; once it's false again the dialog won't appear
        if (activity != null && LOCATION.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
            launcher.launch(LOCATION.toTypedArray())
        } else if (context.locationAllowed()) {
            // allowed already, so what's missing is the device's location switch
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("MissingPermission") // only called after a location permission is granted
private fun requestHere(context: Context, onHere: (Here) -> Unit) {
    val lm = context.getSystemService(LocationManager::class.java) ?: return
    val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    // last known first so the list sorts instantly, then a fresh fix from every provider; whichever answers wins
    providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }?.let { onHere(Here(it.latitude, it.longitude)) }
    providers.forEach { provider ->
        LocationManagerCompat.getCurrentLocation(lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { loc ->
            if (loc != null) onHere(Here(loc.latitude, loc.longitude))
        }
    }
}
