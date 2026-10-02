package es.emtvalencia.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** First-launch onboarding, one animated page per topic, with a page indicator. */
@Composable
fun Onboarding(
    language: Language,
    onLanguage: (Language) -> Unit,
    theme: String,
    onTheme: (String) -> Unit,
    mapType: String,
    onMapType: (String) -> Unit,
    networks: Set<Network>,
    onToggleNetwork: (Network) -> Unit,
    onDone: () -> Unit,
) {
    val t = Strings(language)
    var page by remember { mutableIntStateOf(0) }
    val pages = 6

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(48.dp))
            PageIndicator(page, pages)
            Spacer(Modifier.height(20.dp))

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    val slide = if (forward) slideInHorizontally { it / 2 } else slideInHorizontally { -it / 2 }
                    val out = if (forward) slideOutHorizontally { -it / 2 } else slideOutHorizontally { it / 2 }
                    (slide + fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith (out + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "onboarding",
            ) { index ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (index) {
                        0 -> Page(
                            image = "oobe_welcome",
                            fallback = Icons.Filled.Map,
                            title = t.appName,
                            body = t.welcome,
                        )
                        1 -> Page(
                            image = "oobe_emt",
                            fallback = Icons.Filled.DirectionsBus,
                            title = t.onboardFeatures,
                            body = listOf(
                                Icons.Filled.DirectionsBus to t.featureBus,
                                Icons.Filled.DirectionsSubway to t.featureMetro,
                            ),
                        )
                        2 -> Page(
                            image = "oobe_rodalies",
                            fallback = Icons.Filled.Train,
                            title = t.onboardMore,
                            body = listOf(
                                Icons.Filled.Train to t.featureRodalies,
                                Icons.Filled.DirectionsBike to t.featureBikes,
                            ),
                        )
                        3 -> {
                            PageHero("oobe_alerts", Icons.Filled.Notifications)
                            Text(t.onboardMore, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            FeatureCard(Icons.Filled.Notifications, t.featureFavs)
                            FeatureCard(Icons.Filled.Route, t.featurePlan)
                            FeatureCard(Icons.Filled.Warning, t.featureAlerts)
                        }
                        4 -> {
                            PageHero("oobe_map", Icons.Filled.Map)
                            Text(t.onboardMap, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Network.entries.forEach { network ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ServiceLogo(network, 26.dp)
                                    Spacer(Modifier.size(12.dp))
                                    Text(network.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Switch(checked = network in networks, onCheckedChange = { onToggleNetwork(network) })
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(t.mapStyle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            listOf("default" to t.auto, "light" to t.light, "dark" to t.dark).forEach { (value, label) ->
                                BigChoice(selected = mapType == value, label = label, onClick = { onMapType(value) })
                            }
                        }
                        else -> {
                            PageHero("oobe_welcome", Icons.Filled.Map)
                            Text(t.appearance, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text(t.languageTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Language.entries.forEach { entry ->
                                BigChoice(selected = entry == language, label = entry.label, onClick = { onLanguage(entry) })
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(t.appearance, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            listOf("system" to t.auto, "light" to t.light, "dark" to t.dark).forEach { (value, label) ->
                                BigChoice(selected = theme == value, label = label, onClick = { onTheme(value) })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text(t.previous) }
                Spacer(Modifier.weight(1f))
                if (page < pages - 1) {
                    Button(onClick = { page++ }) { Text(t.next) }
                } else {
                    Button(onClick = onDone) { Text(t.start) }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(page: Int, pages: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(pages) { index ->
            val width by animateDpAsState(if (index == page) 24.dp else 10.dp, spring(), label = "dot")
            Box(
                Modifier
                    .padding(horizontal = 5.dp)
                    .size(width, 10.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/** Rounded hero image from assets/oobe/<name>.png, or a themed icon fallback. */
@Composable
private fun PageHero(image: String, fallback: ImageVector) {
    val context = LocalContext.current
    val bitmap = remember(image) {
        runCatching { context.assets.open("oobe/$image.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    val scale by animateFloatAsState(1f, spring(), label = "hero")
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(180.dp).scale(scale),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(120.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(60.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Page(image: String, fallback: ImageVector, title: String, body: String) {
    PageHero(image, fallback)
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Page(image: String, fallback: ImageVector, title: String, body: List<Pair<ImageVector, String>>) {
    PageHero(image, fallback)
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    body.forEach { (icon, text) -> FeatureCard(icon, text) }
}

@Composable
private fun FeatureCard(icon: ImageVector, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BigChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}
