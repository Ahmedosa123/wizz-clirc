package com.example.swapbot

import android.content.Context
import android.content.SharedPreferences

/** تخزين الإعدادات والنقاط المحفوظة. */
object Prefs {
    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("swapbot", Context.MODE_PRIVATE)
}
