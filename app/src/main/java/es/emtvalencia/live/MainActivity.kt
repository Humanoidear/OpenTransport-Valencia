package es.emtvalencia.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            EmtTheme {
                EmtApp()
            }
        }
    }
}
