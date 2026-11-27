package com.aixiaohuadb

import android.app.Application
import com.aixiaohuadb.di.AppContainer

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
