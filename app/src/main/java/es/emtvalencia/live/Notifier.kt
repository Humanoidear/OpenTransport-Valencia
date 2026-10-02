package es.emtvalencia.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Posts arrival alerts for pinned stop+line pairs. */
object Notifier {
    private const val CHANNEL = "emt_arrivals"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Arrival alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    /**
     * @param network which operator, so the notification shows that service logo
     * @param stopName the stop/station name (shown as the description)
     */
    fun arrival(context: Context, id: Int, line: String, network: Network, stopName: String, minutes: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val logo = runCatching {
            context.assets.open(serviceAsset(network)).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("$line in $minutes min")
            .setContentText(stopName)
            .setAutoCancel(true)
        logo?.let { builder.setLargeIcon(it) }
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, builder.build()) }
    }

    private fun serviceAsset(network: Network): String = when (network) {
        Network.Emt -> "emt.png"
        Network.Metro -> "metrovalencia.png"
        Network.Valenbisi -> "valenbisi.png"
        Network.Metrobus -> "metrobus.png"
        Network.Rodalies -> "rodalies.png"
    }
}
