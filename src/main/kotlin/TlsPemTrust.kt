package com.example

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Утилита для загрузки CA-сертификатов из PEM и создания TrustManager.
 *
 * Нужна, если внешний API использует специфическую цепочку сертификатов.
 */
object TlsPemTrust {

    fun trustManagerFromPemFile(pemPath: String): X509TrustManager {
        val pemBytes = File(pemPath).readBytes()

        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(pemBytes.inputStream())
        require(certs.isNotEmpty()) { "No certificates found in PEM: $pemPath" }

        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        certs.forEachIndexed { idx, cert ->
            ks.setCertificateEntry("gigachat-$idx", cert)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)

        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        return tm ?: error("No X509TrustManager created from PEM: $pemPath")
    }
}
