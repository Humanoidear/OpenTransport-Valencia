package es.emtvalencia.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** First-launch onboarding, one page per topic, with a page indicator. */
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
    val pages = 5

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(48.dp))
        // Page indicator, with extra top margin so a camera cutout doesn't hide it.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (index == page) 22.dp else 10.dp, 10.dp)
                        .background(
                            if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { index ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (index) {
                    0 -> {
                        Spacer(Modifier.height(24.dp))
                        Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.opentransport_logo),
                            contentDescription = null,
                            modifier = Modifier.height(140.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            t.appName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            t.welcome,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    1 -> Page(
                        icon = Icons.Filled.DirectionsBus,
                        title = t.onboardFeatures,
                        body = listOf(
                            Icons.Filled.DirectionsBus to t.featureBus,
                            Icons.Filled.DirectionsSubway to t.featureMetro,
                            Icons.Filled.Train to t.featureRodalies,
                            Icons.Filled.DirectionsBike to t.featureBikes,
                        ),
                    )
                    2 -> Page(
                        icon = Icons.Filled.Notifications,
                        title = t.onboardMore,
                        body = listOf(
                            Icons.Filled.Notifications to t.featureFavs,
                            Icons.Filled.Route to t.featurePlan,
                            Icons.Filled.Warning to t.featureAlerts,
                        ),
                    )
                    3 -> {
                        PageHeader(Icons.Filled.Map, t.onboardMap, "")
                        Network.entries.forEach { network ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                ServiceLogo(network, 26.dp)
                                Spacer(Modifier.size(12.dp))
                                Text(network.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Switch(checked = network in networks, onCheckedChange = { onToggleNetwork(network) })
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(t.mapStyle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        listOf("default" to t.auto, "light" to t.light, "dark" to t.dark).forEach { (value, label) ->
                            BigChoice(selected = mapType == value, label = label, onClick = { onMapType(value) })
                        }
                    }
                    else -> {
                        PageHeader(Icons.Filled.Map, t.appearance, "")
                        Text(t.languageTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Language.entries.forEach { entry ->
                            BigChoice(selected = entry == language, label = entry.label, onClick = { onLanguage(entry) })
                        }
                        Spacer(Modifier.height(20.dp))
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

@Composable
private fun BigChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, body: String) {
    PageHeader(icon, title, body)
}

@Composable
private fun Page(icon: ImageVector, title: String, body: List<Pair<ImageVector, String>>) {
    PageHeader(icon, title, "")
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        body.forEach { (rowIcon, text) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(rowIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(14.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PageHeader(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    if (body.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
