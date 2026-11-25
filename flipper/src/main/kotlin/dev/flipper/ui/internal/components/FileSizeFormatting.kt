package dev.flipper.ui.internal.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal fun rememberFileSizeContext(): Context {
    val context = LocalContext.current
    return remember(context) {
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        context.createConfigurationContext(english)
    }
}
