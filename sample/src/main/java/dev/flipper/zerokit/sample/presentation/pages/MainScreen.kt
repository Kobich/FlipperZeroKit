package dev.flipper.zerokit.sample.presentation.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.flipper.ui.FlipperScreen
import dev.flipper.zerokit.sample.R
import dev.flipper.zerokit.sample.presentation.theme.FlipperTheme

private const val FLIPPER_ROUTE = "flipper"

/** Demonstration host. Other product destinations belong alongside this one in the host graph. */
@Composable
fun MainScreen(hostViewModel: FlipperHostViewModel = hiltViewModel()) {
    FlipperTheme {
        val navController = rememberNavController()
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = true,
                        onClick = { navController.navigate(FLIPPER_ROUTE) { launchSingleTop = true } },
                        icon = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_flipper)) })
                }
            },
        ) { insets ->
            NavHost(navController = navController, startDestination = FLIPPER_ROUTE, modifier = Modifier.padding(insets)) {
                composable(FLIPPER_ROUTE) {
                    FlipperScreen(flipper = hostViewModel.flipper)
                }
            }
        }
    }
}
