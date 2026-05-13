package com.oa.automation

import android.app.Application
import com.oa.automation.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class MeetingNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@MeetingNotesApp)
            modules(appModule)
        }
    }
}
