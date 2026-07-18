package com.oa.automation

import android.app.Application
import android.content.Context
import com.oa.automation.di.appModule
import com.oa.automation.locale.withSimplifiedChineseLocale
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class MeetingNotesApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withSimplifiedChineseLocale())
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@MeetingNotesApp)
            modules(appModule)
        }
    }
}
