package com.osogold.tv

import android.app.Application

/** Application class: makes the application context available to the network layer. */
class OsoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NetConfig.init(this)
    }
}
