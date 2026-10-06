package com.argun.mapper

import android.app.Application
import android.util.Log
import com.argun.mapper.data.database.AppDatabase

class ArgunMapperApp : Application() {
    companion object {
        private const val TAG = "ArgunMapperApp"
        var database: AppDatabase? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        try {
            database = AppDatabase.getDatabase(this)
            Log.d(TAG, "Database initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        database?.close()
        database = null
    }
}