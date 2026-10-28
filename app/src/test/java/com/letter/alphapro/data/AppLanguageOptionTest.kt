package com.letter.alphapro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageOptionTest {
    @Test fun systemUsesEmptyLanguageOverride() {
        assertEquals("", AppLanguageOption.SYSTEM.languageTag)
        assertEquals(AppLanguageOption.SYSTEM, AppLanguageOption.forTag(""))
    }
    @Test fun supportedRegionalAliasesResolveWithoutChangingIdentifiers() {
        for (tag in listOf("en", "en-US", "en-GB")) assertEquals(AppLanguageOption.ENGLISH, AppLanguageOption.forTag(tag))
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-TW")) assertEquals(AppLanguageOption.SIMPLIFIED_CHINESE, AppLanguageOption.forTag(tag))
    }
    @Test fun unsupportedLanguagesDoNotPretendToHaveTranslations() {
        for (tag in listOf("fr", "ja", "zh-Hant", "zh-Latn-CN", "zh-TW", "zh-HK", "und", " "))
            assertEquals(AppLanguageOption.SYSTEM, AppLanguageOption.forTag(tag))
    }
    @Test fun supportedChoicesHaveUniqueResourceLabelsAndStableTags() {
        assertEquals(3, AppLanguageOption.entries.map { it.labelRes }.distinct().size)
        assertEquals(setOf("", "en", "zh-Hans"), AppLanguageOption.entries.map { it.languageTag }.toSet())
        assertTrue(AppLanguageOption.entries.all { it.labelRes != 0 })
    }
}
