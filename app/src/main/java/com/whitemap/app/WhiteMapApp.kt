package com.whitemap.app

import android.app.Application
import com.whitemap.app.data.AppDatabase
import com.whitemap.app.data.MapPrefs

class WhiteMapApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val prefs: MapPrefs by lazy { MapPrefs(this) }
}
