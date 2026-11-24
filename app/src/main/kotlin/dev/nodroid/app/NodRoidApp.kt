package dev.nodroid.app

import android.app.Application
import dev.nodroid.database.AppDatabase
import dev.nodroid.database.RoomKvStore
import dev.nodroid.nodes.NodeRegistry
import dev.nodroid.nodes.RoomKvAdapter
import dev.nodroid.runtime.RuntimeHolder
import dev.nodroid.security.VaultManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class NodRoidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeHolder.init(this)
        val appModule = module {
            single { AppDatabase.get(androidContext()) }
            single { VaultManager(androidContext()) }
            single { RoomKvStore(get<AppDatabase>().keyValueDao()) }
            single { RoomKvAdapter(get()) }
            single { NodeRegistry.create(androidContext(), get()) }
        }
        startKoin {
            androidContext(this@NodRoidApp)
            modules(appModule)
        }
    }
}
