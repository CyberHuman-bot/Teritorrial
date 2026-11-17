package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.example.firebase.configureAppCheck
import com.example.ui.AppNavigation
import com.example.ui.theme.AlpacaShellTheme
import com.example.ui.theme.ObsidianBg

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before any UI, ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(ObsidianBg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(ObsidianBg.toArgb()),
        )

        setContent {
            AlpacaShellTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBg,
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
