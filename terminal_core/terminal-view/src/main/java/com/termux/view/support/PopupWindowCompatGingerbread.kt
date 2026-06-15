package com.termux.view.support

import android.widget.PopupWindow
import java.lang.reflect.Method

object PopupWindowCompatGingerbread {

    private var sSetWindowLayoutTypeMethod: Method? = null
    private var sSetWindowLayoutTypeMethodAttempted = false
    private var sGetWindowLayoutTypeMethod: Method? = null
    private var sGetWindowLayoutTypeMethodAttempted = false

    @JvmStatic
    fun setWindowLayoutType(popupWindow: PopupWindow, layoutType: Int) {
        if (!sSetWindowLayoutTypeMethodAttempted) {
            try {
                sSetWindowLayoutTypeMethod = PopupWindow::class.java.getDeclaredMethod("setWindowLayoutType", Int::class.javaPrimitiveType!!)
                sSetWindowLayoutTypeMethod?.isAccessible = true
            } catch (_: Exception) {
            }
            sSetWindowLayoutTypeMethodAttempted = true
        }
        if (sSetWindowLayoutTypeMethod != null) {
            try {
                sSetWindowLayoutTypeMethod!!.invoke(popupWindow, layoutType)
            } catch (_: Exception) {
            }
        }
    }

    @JvmStatic
    fun getWindowLayoutType(popupWindow: PopupWindow): Int {
        if (!sGetWindowLayoutTypeMethodAttempted) {
            try {
                sGetWindowLayoutTypeMethod = PopupWindow::class.java.getDeclaredMethod("getWindowLayoutType")
                sGetWindowLayoutTypeMethod?.isAccessible = true
            } catch (_: Exception) {
            }
            sGetWindowLayoutTypeMethodAttempted = true
        }
        if (sGetWindowLayoutTypeMethod != null) {
            try {
                return sGetWindowLayoutTypeMethod!!.invoke(popupWindow) as Int
            } catch (_: Exception) {
            }
        }
        return 0
    }
}
