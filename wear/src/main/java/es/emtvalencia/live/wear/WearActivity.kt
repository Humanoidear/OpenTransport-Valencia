package es.emtvalencia.live.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold {
                    WearApp()
                }
            }
        }
    }

    @Composable
    private fun WearApp() {
        val context = this
        val data = remember { WearData.load(context) }
        val repository = remember { WearRepository(data) }
        var nearby by remember { mutableStateOf<List<NearStop>>(emptyList()) }
        var selected by remember { mutableStateOf<NearStop?>(null) }
        var loading by remember { mutableStateOf(true) }

        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) locationTick++ }

        var locationTick by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            runCatching { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        }
        LaunchedEffect(locationTick) {
            val location = rememberLocation(context)
            nearby = if (location == null) emptyList() else repository.stopsNear(location.first, location.second, 800.0)
            loading = false
        }

        val current = selected
        if (current != null) {
            ArrivalsScreen(current, repository) { selected = null }
        } else {
            StopsNearScreen(nearby, loading) { selected = it }
        }
    }
}
