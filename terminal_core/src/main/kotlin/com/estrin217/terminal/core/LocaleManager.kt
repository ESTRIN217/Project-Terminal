package com.estrin217.terminal.core

import android.content.Context
import com.estrin217.terminal.core.logger.DebugLogger

object LocaleManager {
    private const val TAG = "LocaleManager"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        DebugLogger.i(TAG, "LocaleManager initialized with native Android resource system")
    }

    fun getString(key: String, vararg formatArgs: Any?): String {
        val context = appContext ?: return key
        val resId = context.resources.getIdentifier(key, "string", context.packageName)
        if (resId == 0) {
            DebugLogger.w(TAG, "String resource not found for key: $key")
            return key
        }
        return if (formatArgs.isNotEmpty()) {
            try {
                context.getString(resId, *formatArgs)
            } catch (e: Exception) {
                context.getString(resId)
            }
        } else {
            context.getString(resId)
        }
    }
}
