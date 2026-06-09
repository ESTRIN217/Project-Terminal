package com.estrin217.terminal.core

import android.content.Context
import com.estrin217.terminal.core.logger.DebugLogger
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

object LocaleManager {
    private const val TAG = "LocaleManager"
    private var translations: JSONObject? = null
    private var fallbackTranslations: JSONObject? = null

    fun init(context: Context) {
        val locale = Locale.getDefault()
        val language = locale.language
        val country = locale.country
        val localeTag = if (country.isNotEmpty()) "${language}_$country" else language

        DebugLogger.i(TAG, "Initializing locale system. Target locale: $localeTag")

        // Load targeted translations
        translations = loadJsonFromAsset(context, "locales/$localeTag.json")
            ?: loadJsonFromAsset(context, "locales/$language.json")
            ?: loadJsonFromAsset(context, "locales/es_VE.json") // Default target locale in GEMINI.md

        // Load fallback translations (default is es_VE, then en)
        fallbackTranslations = loadJsonFromAsset(context, "locales/es_VE.json")
            ?: loadJsonFromAsset(context, "locales/en.json")
    }

    private fun loadJsonFromAsset(context: Context, fileName: String): JSONObject? {
        return try {
            val inputStream: InputStream = context.assets.open(fileName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val jsonString = String(buffer, Charsets.UTF_8)
            JSONObject(jsonString)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Locale file not found or invalid: $fileName")
            null
        }
    }

    fun getString(key: String, vararg formatArgs: Any?): String {
        val rawString = if (translations?.has(key) == true) {
            translations?.optString(key) ?: key
        } else {
            fallbackTranslations?.optString(key) ?: key
        }

        return if (formatArgs.isNotEmpty()) {
            try {
                String.format(rawString, *formatArgs)
            } catch (e: Exception) {
                rawString
            }
        } else {
            rawString
        }
    }
}
