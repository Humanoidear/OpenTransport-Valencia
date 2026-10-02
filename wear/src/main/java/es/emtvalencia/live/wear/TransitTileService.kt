package es.emtvalencia.live.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_FIT
import androidx.wear.protolayout.LayoutElementBuilders.FontSetting
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.layout.basicImage
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.layout.inlineImageResource
import androidx.wear.protolayout.layout.row
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.EventBuilders.TileAddEvent
import androidx.wear.tiles.EventBuilders.TileRemoveEvent
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import java.io.ByteArrayOutputStream

/** Material 3 Expressive tile: logo, stop title, line badge + big next departure. */
class TransitTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val context = this@TransitTileService
        val item = StopData.resolve(context)
        val arrivals = item?.let { StopData.arrivals(context, it) }.orEmpty()
        return tile(Timeline.fromLayoutElement(tileLayout(item, arrivals)))
    }

    override fun onTileAddEvent(event: TileAddEvent) {
        super.onTileAddEvent(event)
        RefreshScheduler.schedule(this)
    }

    override fun onTileRemoveEvent(event: TileRemoveEvent) {
        super.onTileRemoveEvent(event)
    }

    private suspend fun MaterialScope.tileLayout(item: NearItem?, arrivals: List<Live>): LayoutElement {
        val context = this@TransitTileService
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, WearActivity::class.java)
                .putExtra(KEY_COMP_STOP, item?.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val service = item?.service ?: Svc.Emt
        val logo = logoResource(context, service)
        val next = arrivals.firstOrNull()
        val badge = next?.let { lineBadgeResource(context, it.line, service) }
        return primaryLayout(
            titleSlot = null,
            mainSlot = {
                column(
                    *buildList<LayoutElement> {
                        if (logo != null) {
                            add(
                                protoLayoutScope.basicImage(
                                    resource = logo,
                                    width = dp(36f),
                                    height = dp(36f),
                                    contentScaleMode = CONTENT_SCALE_MODE_FIT,
                                ),
                            )
                        }
                        add(
                            text(
                                text = (item?.name ?: "Nearby").layoutString,
                                maxLines = 2,
                                settings = listOf(FontSetting.weight(700), FontSetting.width(105F)),
                            ),
                        )
                        add(
                            if (badge != null) {
                                row(
                                    *listOfNotNull(
                                        protoLayoutScope.basicImage(
                                            resource = badge,
                                            width = dp(46f),
                                            height = dp(32f),
                                            contentScaleMode = CONTENT_SCALE_MODE_FIT,
                                        ),
                                        text(
                                            text = (next?.minutes ?: "—").layoutString,
                                            settings = listOf(FontSetting.weight(700), FontSetting.width(165F)),
                                        ),
                                    ).toTypedArray(),
                                    verticalAlignment = VERTICAL_ALIGN_CENTER,
                                )
                            } else {
                                text(
                                    text = (next?.let { "${it.line} · ${it.minutes}" } ?: "—").layoutString,
                                    settings = listOf(FontSetting.weight(700), FontSetting.width(165F)),
                                )
                            },
                        )
                        next?.destination?.takeIf { it.isNotBlank() }?.let {
                            add(text(text = it.layoutString, maxLines = 1))
                        }
                    }.toTypedArray(),
                    width = expand(),
                    height = expand(),
                    horizontalAlignment = HORIZONTAL_ALIGN_CENTER,
                )
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = protoLayoutScope.clickable(openApp, "open"),
                ) { text("Open".layoutString) }
            },
        )
    }

    private fun logoResource(context: Context, service: Svc): ResourceBuilders.ImageResource? = runCatching {
        val bytes = context.assets.open(svcAsset(service)).use { it.readBytes() }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        imageResource(inlineImage = inlineImageResource(bytes, opts.outWidth, opts.outHeight))
    }.getOrNull()

    private suspend fun lineBadgeResource(context: Context, line: String, service: Svc): ResourceBuilders.ImageResource? {        val bitmap = StopData.lineBadge(context, line, service) ?: return null
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return imageResource(inlineImage = inlineImageResource(bytes, bitmap.width, bitmap.height))
    }
}
