package com.letter.alphapro.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.letter.alphapro.R
import java.util.Locale

enum class AppLanguageOption(val languageTag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.language_system),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_chinese),
    ENGLISH("en", R.string.language_english);

    companion object {
        fun forTag(tag: String): AppLanguageOption {
            if (tag.isBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            return when {
                locale.language == "en" -> ENGLISH
                locale.language == "zh" && (locale.script == "Hans" ||
                    (locale.script.isEmpty() && locale.country !in listOf("TW", "HK", "MO"))) -> SIMPLIFIED_CHINESE
                else -> SYSTEM
            }
        }
    }
}

/** AppCompat owns Activity refresh/system integration; explicit tag also covers cold-start services. */
object AppLanguage {
    const val ACTION_CHANGED = "com.letter.alphapro.APP_LANGUAGE_CHANGED"
    private const val PREFS = "alphapro_language"
    private const val KEY = "language_tag"

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && AppCompatDelegate.getApplicationLocales().isEmpty) {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            if (stored.isNotEmpty()) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored))
        }
    }

    fun currentOption(context: Context): AppLanguageOption = AppLanguageOption.forTag(currentTag(context))

    private fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.toLanguageTag().orEmpty()
        }
        val declared = AppCompatDelegate.getApplicationLocales()
        return if (!declared.isEmpty) declared.get(0)?.toLanguageTag().orEmpty()
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(context: Context, option: AppLanguageOption) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, option.languageTag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(option.languageTag))
        context.sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    fun localizedContext(context: Context): Context {
        val tag = currentTag(context)
        if (tag.isEmpty()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration)
    }

    fun getString(context: Context, @StringRes id: Int, vararg args: Any): String =
        localizedContext(context).getString(id, *args)
}
