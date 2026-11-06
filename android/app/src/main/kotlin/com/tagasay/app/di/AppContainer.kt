package com.tagasay.app.di

import android.content.Context
import com.tagasay.app.data.AppRepository
import com.tagasay.app.data.ServerMonitor
import com.tagasay.app.data.local.DeviceKeyStore
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.local.VaultStore
import com.tagasay.app.security.FileInspector
import com.tagasay.core.crypto.CryptoEngine

/**
 * Ручной DI-контейнер приложения. Сознательно без рефлексии и кодогенерации:
 * меньше зависимостей — меньше поверхности атаки и времени сборки.
 */
class AppContainer(context: Context) {
    val crypto: CryptoEngine = CryptoEngine()
    val deviceKeyStore: DeviceKeyStore = DeviceKeyStore(context.applicationContext, crypto)
    val vaultStore: VaultStore = VaultStore(context.applicationContext, deviceKeyStore)
    val repository: AppRepository = AppRepository(vaultStore)
    val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
    val serverMonitor: ServerMonitor = ServerMonitor()
    val fileInspector: FileInspector = FileInspector(context.applicationContext, crypto)
}
