package dev.flipper.zerokit.sample.presentation.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.flipper.ui.FlipperScreen
import dev.flipper.zerokit.sample.R
import dev.flipper.zerokit.sample.presentation.theme.FlipperTheme
import dev.flipper.zerokit.sample.presentation.theme.ThemeViewModel

private const val FLIPPER_ROUTE = "flipper"

/** Demonstration host. Other product destinations belong alongside this one in the host graph. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onThemeResolved: (Boolean) -> Unit,
    themeViewModel: ThemeViewModel = hiltViewModel(),
    hostViewModel: FlipperHostViewModel = hiltViewModel(),
) {
    val preference by themeViewModel.theme.collectAsStateWithLifecycle()
    FlipperTheme(preference = preference) { isDark ->
        SideEffect { onThemeResolved(isDark) }
        val navController = rememberNavController()
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.tab_flipper)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        IconButton(onClick = { themeViewModel.toggle(isDark) }) {
                            Icon(if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                                contentDescription = stringResource(if (isDark) R.string.switch_to_light_theme else R.string.switch_to_dark_theme))
                        }
                    })
            },
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
                    FlipperScreen(flipper = hostViewModel.flipper, connectionController = hostViewModel.connectionController)
                }
            }
        }
    }
}
