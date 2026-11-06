package com.tagasay.app

import android.app.Application
import com.tagasay.app.di.AppContainer

class TAGaSayApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
