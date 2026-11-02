package app.ailectric.map

import android.content.Intent
import android.net.Uri
import app.ailectric.core.geo.GeoPoint

/** Destination for any installed navigation app. Own routing can later replace this one call. */
object Navigation {
    fun geoUri(target: GeoPoint, label: String?): Uri {
        val query = "${target.lat},${target.lon}" + (label?.let { "(${Uri.encode(it)})" } ?: "")
        return Uri.parse("geo:${target.lat},${target.lon}?q=$query")
    }

    fun phoneIntent(target: GeoPoint, label: String?) = Intent(Intent.ACTION_VIEW, geoUri(target, label))
}
