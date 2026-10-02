package es.emtvalencia.live.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

/** Last known location via Play Services, or null when not permitted/available. */
suspend fun rememberLocation(context: Context): Pair<Double, Double>? {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    return runCatching {
        LocationServices.getFusedLocationProviderClient(context).lastLocation.await()?.let { it.latitude to it.longitude }
    }.getOrNull()
}
