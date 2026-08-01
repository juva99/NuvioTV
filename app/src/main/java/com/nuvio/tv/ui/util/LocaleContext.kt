package com.nuvio.tv.ui.util

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import java.util.Locale

fun Context.localizedForAppLocale(): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return this
    val locale = Locale.forLanguageTag(tag)
    if (locale == Locale.ROOT) return this

    return createConfigurationContext(
        Configuration(resources.configuration).apply {
            setLocale(locale)
        }
    )
}
