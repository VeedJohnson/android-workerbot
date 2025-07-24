package com.veedjohnson.workerbot

import android.app.Application
import com.veedjohnson.workerbot.data.ObjectBoxStore
import com.veedjohnson.workerbot.di.AppModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module

class WorkerBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WorkerBotApplication)
            modules(AppModule().module)
        }
        ObjectBoxStore.init(this)
    }
}
