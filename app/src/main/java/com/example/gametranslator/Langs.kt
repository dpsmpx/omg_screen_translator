package com.example.gametranslator

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

/** Один язык: код ML Kit, название для списка и короткая метка для плавающей панели. */
data class Lang(val code: String, val title: String, val label: String)

object Langs {
    /** Языки игр, которые приложение умеет читать с экрана и переводить. */
    val sources = listOf(
        Lang(TranslateLanguage.JAPANESE, "Японский", "JA"),
        Lang(TranslateLanguage.CHINESE, "Китайский", "ZH"),
        Lang(TranslateLanguage.ENGLISH, "Английский", "EN"),
        Lang(TranslateLanguage.SPANISH, "Испанский", "ES")
    )

    /** Языки, НА которые переводим. */
    val targets = listOf(
        Lang(TranslateLanguage.RUSSIAN, "Русский", "RU"),
        Lang(TranslateLanguage.ENGLISH, "Английский", "EN"),
        Lang(TranslateLanguage.SPANISH, "Испанский", "ES")
    )

    /** Модели, которые надо скачать один раз, чтобы потом всё работало без интернета. */
    val allModels = listOf(
        TranslateLanguage.ENGLISH,
        TranslateLanguage.RUSSIAN,
        TranslateLanguage.SPANISH,
        TranslateLanguage.CHINESE,
        TranslateLanguage.JAPANESE
    )

    fun source(code: String): Lang = sources.firstOrNull { it.code == code } ?: sources[0]

    fun target(code: String): Lang = targets.firstOrNull { it.code == code } ?: targets[0]
}

/** Сохранённые настройки приложения. */
object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun source(c: Context): String =
        prefs(c).getString("source", TranslateLanguage.JAPANESE) ?: TranslateLanguage.JAPANESE

    fun setSource(c: Context, code: String) {
        prefs(c).edit().putString("source", code).apply()
    }

    fun target(c: Context): String =
        prefs(c).getString("target", TranslateLanguage.RUSSIAN) ?: TranslateLanguage.RUSSIAN

    fun setTarget(c: Context, code: String) {
        prefs(c).edit().putString("target", code).apply()
    }

    fun root(c: Context): Boolean = prefs(c).getBoolean("root", false)

    fun setRoot(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("root", value).apply()
    }
}
