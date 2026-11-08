package com.tagasay.app.di

import android.content.Context
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.ServerMonitor
import com.tagasay.app.data.SyncManager
import com.tagasay.app.data.crypto.SignalStore
import com.tagasay.app.data.local.DeviceKeyStore
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.local.VaultStore
import com.tagasay.app.data.network.AccountManager
import com.tagasay.app.data.network.StateSync
import com.tagasay.app.data.network.StoryTransfer
import com.tagasay.app.data.update.UpdateManager
import com.tagasay.app.security.FileInspector
import com.tagasay.core.crypto.CryptoEngine

/**
 * Ручной DI-контейнер приложения. Сознательно без рефлексии и кодогенерации:
 * меньше зависимостей — меньше поверхности атаки и времени сборки.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val crypto: CryptoEngine = CryptoEngine()
    val deviceKeyStore: DeviceKeyStore = DeviceKeyStore(appContext, crypto)
    val vaultStore: VaultStore = VaultStore(appContext, deviceKeyStore)
    val repository: AppRepository = AppRepository(vaultStore)
    val settingsStore: SettingsStore = SettingsStore(appContext)
    val serverMonitor: ServerMonitor = ServerMonitor()
    val fileInspector: FileInspector = FileInspector(appContext, crypto)
    val updateManager: UpdateManager by lazy { UpdateManager(appContext, settingsStore) }

    val signalStore: SignalStore by lazy { SignalStore(appContext, deviceKeyStore, crypto) }
    val accountManager: AccountManager by lazy {
        AccountManager(appContext, deviceKeyStore, signalStore, crypto)
    }
    val stateSync: StateSync by lazy {
        StateSync(appContext, repository, settingsStore, crypto, storyTransfer)
    }
    val storyTransfer: StoryTransfer by lazy { StoryTransfer(appContext, crypto) }
    val syncManager: SyncManager by lazy {
        SyncManager(
            appContext,
            repository,
            settingsStore,
            accountManager,
            signalStore,
            crypto,
            stateSync,
        )
    }
}
