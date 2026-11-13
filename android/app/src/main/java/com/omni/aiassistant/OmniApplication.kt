package com.omni.aiassistant

import android.app.Application
import com.omni.aiassistant.data.db.AppDatabase
import com.omni.aiassistant.di.appModule
import com.omni.aiassistant.di.toolModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class OmniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@OmniApplication)
            modules(appModule, toolModule)
        }
        // A message left in SENDING/STREAMING by a killed process would spin
        // forever in the transcript. No generation survives a process restart.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                GlobalContext.get().get<AppDatabase>().messageDao().failInterrupted()
            }
        }
    }
}
