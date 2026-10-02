package es.emtvalencia.live.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.wear.tiles.TileService

/** One 60s exact-alarm chain driving both the tile and the complication. */
object RefreshScheduler {
    private const val INTERVAL = 60_000L

    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, RefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.cancel(pi)
        val next = SystemClock.elapsedRealtime() + INTERVAL
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi)
        }
    }

    fun pushUpdates(context: Context) {
        runCatching {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, StopComplicationService::class.java),
            ).requestUpdateAll()
        }
        runCatching {
            TileService.getUpdater(context).requestUpdate(TransitTileService::class.java)
        }
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RefreshScheduler.pushUpdates(context)
        RefreshScheduler.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RefreshScheduler.schedule(context)
    }
}
