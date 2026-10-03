package es.emtvalencia.live.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant

const val COMP_NEAREST = "nearest"

private fun text(s: String) = PlainComplicationText.Builder(s).build()

/** Shows the next departures for a chosen stop (or the nearest one); tap opens it in the app. */
class StopComplicationService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        RefreshScheduler.pushUpdates(this)
        RefreshScheduler.schedule(this)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val item = StopData.resolve(this) ?: return null
        val arrivals = StopData.arrivals(this, item)
        val tap = PendingIntent.getActivity(
            this, request.complicationInstanceId,
            Intent(this, WearActivity::class.java)
                .putExtra(KEY_COMP_STOP, item.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Keep the last value visible if the watch delays a background refresh.
        val validity = TimeRange.between(Instant.now(), Instant.now().plusSeconds(300))
        val next = arrivals.firstOrNull()
        // Line in the title (stacked above the time), time in the text.
        val longText = arrivals.take(2).joinToString(" · ") { "${it.line} ${it.minutes}" }.ifBlank { "No times" }
        val icon = Icon.createWithResource(this, R.drawable.ic_complication)
        val mono = MonochromaticImage.Builder(icon).build()
        return when (request.complicationType) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = text(longText),
                contentDescription = text("Departures at ${item.name}"),
            ).setTitle(text(item.name))
                .setMonochromaticImage(mono)
                .setTapAction(tap)
                .setValidTimeRange(validity)
                .build()
            ComplicationType.RANGED_VALUE -> {
                val m = (minutesOf(next) ?: 0).coerceIn(0, 30).toFloat()
                RangedValueComplicationData.Builder(
                    m, 0f, 30f,
                    text("Next departure at ${item.name}"),
                ).setText(text(next?.minutes ?: "–"))
                    .setTitle(text(if (item.service == Svc.Valenbisi) "bikes" else next?.line ?: item.name))
                    .setColorRamp(ColorRamp(intArrayOf(0xFF4CAF50.toInt(), 0xFFFFC107.toInt(), 0xFFF44336.toInt()), true))
                    .setMonochromaticImage(mono)
                    .setTapAction(tap)
                    .setValidTimeRange(validity)
                    .build()
            }
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.ICON).setAmbientImage(icon).build(),
                text("Next departure at ${item.name}"),
            ).setTapAction(tap)
                .setValidTimeRange(validity)
                .build()
            else -> {
                ShortTextComplicationData.Builder(
                    text = text(next?.minutes ?: "–"),
                    contentDescription = text("Next departure at ${item.name}"),
                ).setTitle(text(if (item.service == Svc.Valenbisi) "bikes" else next?.line ?: item.name))
                    .setMonochromaticImage(mono)
                    .setSmallImage(SmallImage.Builder(icon, SmallImageType.ICON).build())
                    .setTapAction(tap)
                    .setValidTimeRange(validity)
                    .build()
            }
        }
    }

    private fun minutesOf(live: Live?): Int? = live?.let {
        if (it.minutes.trim().lowercase(java.util.Locale.ROOT).startsWith("now")) 0
        else Regex("^(\\d+)").find(it.minutes)?.groupValues?.get(1)?.toIntOrNull()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, WearActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = text("L4 5 min · L19 12 min"),
                contentDescription = text("Preview departures"),
            ).setTitle(text("Colón")).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text("5 min"),
                contentDescription = text("Preview next bus"),
            ).setTitle(text("L4")).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                5f, 0f, 30f, text("Preview departure"),
            ).setText(text("5 min")).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_complication), SmallImageType.ICON).build(),
                text("Preview departure"),
            ).setTapAction(tap).build()
            else -> null
        }
    }
}
