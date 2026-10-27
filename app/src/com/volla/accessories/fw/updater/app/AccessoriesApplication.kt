package com.volla.accessories.fw.updater.app

import android.app.Application

class AccessoriesApplication : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}
