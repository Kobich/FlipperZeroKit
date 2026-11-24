package dev.flipper.zerokit.sample

import android.os.Bundle
import javax.inject.Inject
import dev.flipper.zerokit.sample.data.repository.ConnectionSession
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.flipper.zerokit.sample.presentation.pages.MainScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var connectionSession: ConnectionSession

    override fun onStart() { super.onStart(); connectionSession.onForeground() }
    override fun onStop() {
        if (!isChangingConfigurations) connectionSession.onBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT
            ),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            MainScreen(onThemeResolved = { dark ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            })
        }
    }
}
