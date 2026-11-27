package dev.flipper.zerokit.sample.presentation.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.flipper.ui.FlipperScreen
import dev.flipper.zerokit.sample.R
import dev.flipper.zerokit.sample.presentation.theme.FlipperTheme

private const val FLIPPER_ROUTE = "flipper"
private const val HOME_ROUTE = "home"

/** Demonstration host. Other product destinations belong alongside this one in the host graph. */
@Composable
fun MainScreen(hostViewModel: FlipperHostViewModel = hiltViewModel()) {
    FlipperTheme {
        val navController = rememberNavController()
        val entry by navController.currentBackStackEntryAsState()
        val navigate: (String) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = entry?.destination?.route == HOME_ROUTE,
                        onClick = { navigate(HOME_ROUTE) },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) })
                    NavigationBarItem(selected = entry?.destination?.route == FLIPPER_ROUTE,
                        onClick = { navigate(FLIPPER_ROUTE) },
                        icon = { Icon(painterResource(R.drawable.ic_flipper_zero), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_flipper)) })
                }
            },
        ) { insets ->
            NavHost(navController = navController, startDestination = FLIPPER_ROUTE, modifier = Modifier.padding(insets)) {
                composable(HOME_ROUTE) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.tab_home)) }
                }
                composable(FLIPPER_ROUTE) {
                    FlipperScreen(flipper = hostViewModel.flipper)
                }
            }
        }
    }
}
