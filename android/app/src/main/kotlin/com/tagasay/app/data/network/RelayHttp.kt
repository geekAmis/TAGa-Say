package com.tagasay.app.data.network

import com.tagasay.app.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Один общий OkHttpClient на приложение: общий пул соединений и dispatcher.
 *
 * Пиннинг задаётся при сборке (-PcertPin) и добавляется производным клиентом
 * только когда он реально настроен, чтобы не плодить клиентов впустую.
 */
object RelayHttp {

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Клиент с увеличенными таймаутами для передачи файлов. */
    val transfer: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    fun forBase(baseUrl: String): OkHttpClient = withPin(base, baseUrl)

    fun transferForBase(baseUrl: String): OkHttpClient = withPin(transfer, baseUrl)

    private fun withPin(client: OkHttpClient, baseUrl: String): OkHttpClient {
        val pin = BuildConfig.CERT_PIN
        if (pin.isBlank()) return client
        val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return client
        return client.newBuilder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(host, if (pin.startsWith("sha256/")) pin else "sha256/$pin")
                    .build(),
            )
            .build()
    }
}
