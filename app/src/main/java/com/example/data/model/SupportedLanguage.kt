package com.example.data.model

import com.google.mlkit.nl.translate.TranslateLanguage

data class SupportedLanguage(
    val mlKitCode: String,
    val localeTag: String,
    val shortCode: String,
    val displayName: String,
    val nativeName: String
)

object LanguageCatalog {
    val allLanguages: List<SupportedLanguage> = listOf(
        SupportedLanguage(TranslateLanguage.INDONESIAN, "id-ID", "ID", "Indonesia", "Bahasa Indonesia"),
        SupportedLanguage(TranslateLanguage.ENGLISH, "en-US", "EN", "Inggris", "English"),
        SupportedLanguage(TranslateLanguage.JAPANESE, "ja-JP", "JA", "Jepang", "日本語"),
        SupportedLanguage(TranslateLanguage.KOREAN, "ko-KR", "KO", "Korea", "한국어"),
        SupportedLanguage(TranslateLanguage.ARABIC, "ar-SA", "AR", "Arab", "العربية"),
        SupportedLanguage(TranslateLanguage.CHINESE, "zh-CN", "ZH", "Mandarin", "中文"),
        SupportedLanguage(TranslateLanguage.MALAY, "ms-MY", "MS", "Melayu", "Bahasa Melayu"),
        SupportedLanguage(TranslateLanguage.THAI, "th-TH", "TH", "Thailand", "ไทย"),
        SupportedLanguage(TranslateLanguage.VIETNAMESE, "vi-VN", "VI", "Vietnam", "Tiếng Việt"),
        SupportedLanguage(TranslateLanguage.SPANISH, "es-ES", "ES", "Spanyol", "Español"),
        SupportedLanguage(TranslateLanguage.FRENCH, "fr-FR", "FR", "Prancis", "Français"),
        SupportedLanguage(TranslateLanguage.GERMAN, "de-DE", "DE", "Jerman", "Deutsch"),
        SupportedLanguage(TranslateLanguage.ITALIAN, "it-IT", "IT", "Italia", "Italiano"),
        SupportedLanguage(TranslateLanguage.PORTUGUESE, "pt-PT", "PT", "Portugis", "Português"),
        SupportedLanguage(TranslateLanguage.DUTCH, "nl-NL", "NL", "Belanda", "Nederlands"),
        SupportedLanguage(TranslateLanguage.RUSSIAN, "ru-RU", "RU", "Rusia", "Русский"),
        SupportedLanguage(TranslateLanguage.TURKISH, "tr-TR", "TR", "Turki", "Türkçe"),
        SupportedLanguage(TranslateLanguage.HINDI, "hi-IN", "HI", "Hindi", "हिन्दी")
    )

    fun findByCode(code: String): SupportedLanguage {
        return allLanguages.firstOrNull { it.mlKitCode == code } ?: allLanguages.first()
    }
}
