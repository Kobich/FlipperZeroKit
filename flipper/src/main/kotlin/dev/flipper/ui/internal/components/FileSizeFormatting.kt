package dev.flipper.ui.internal.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal fun rememberFileSizeContext(): Context {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        val english = Configuration(configuration).apply { setLocale(Locale.ENGLISH) }
        context.createConfigurationContext(english)
    }
}
