package au.projectwork.parking.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import au.projectwork.parking.data.Address

/** Starts turn-by-turn navigation to the lot; uses its coordinates when the API has them, else the address. */
fun navigateTo(context: Context, address: Address) {
    val text = "${address.street} ${address.houseNr}, ${address.city}"
    val destination = if (address.latitude != null && address.longitude != null) "${address.latitude},${address.longitude}" else text
    val candidates = listOf(
        "google.navigation:q=${Uri.encode(destination)}",
        "geo:0,0?q=${Uri.encode(destination)}",
        // no maps app installed: Google Maps directions in the browser
        "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}",
    )
    for (uri in candidates) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
