package com.example.ai_assistant.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date

/**
 * AdbIdentity gerencia e persiste o par de chaves RSA e o certificado X.509
 * necessários para a autenticação e pairing ADB (Wireless Debugging / TLS).
 */
class AdbIdentity(private val context: Context) {

    companion object {
        private const val TAG = "AICallADB"
        private const val PRIVATE_KEY_FILE = "adb_private.key"
        private const val CERT_FILE = "adb_cert.der"
        private const val PUBLIC_KEY_FILE = "adb_public.key"
        const val DEFAULT_DEVICE_NAME = "AI_Assistant"
    }

    data class KeyStoreData(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    @Volatile
    private var cachedData: KeyStoreData? = null

    /**
     * Obtém o par de chave privada e certificado salvo em disco ou gera um novo.
     */
    fun getKeyStoreData(): KeyStoreData {
        cachedData?.let { return it }

        synchronized(this) {
            cachedData?.let { return it }

            val privateFile = File(context.filesDir, PRIVATE_KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)

            val loaded = if (privateFile.exists() && certFile.exists()) {
                try {
                    val privateKeyBytes = privateFile.readBytes()
                    val certBytes = certFile.readBytes()

                    val keyFactory = KeyFactory.getInstance("RSA")
                    val privateKey: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))

                    val certFactory = CertificateFactory.getInstance("X.509")
                    val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

                    KeyStoreData(privateKey, cert)
                } catch (e: Exception) {
                    Log.w(TAG, "[AICALL ADB] Falha ao carregar chaves salvas, gerando novas chaves: ${e.message}")
                    generateAndSaveKeys(privateFile, certFile)
                }
            } else {
                generateAndSaveKeys(privateFile, certFile)
            }

            cachedData = loaded
            return loaded
        }
    }

    /**
     * Obtém a chave privada para conexão ADB.
     */
    fun getPrivateKey(): PrivateKey = getKeyStoreData().privateKey

    /**
     * Obtém o certificado X.509 para conexão TLS/pairing ADB.
     */
    fun getCertificate(): X509Certificate = getKeyStoreData().certificate

    /**
     * Obtém a chave pública associada ao certificado.
     */
    fun getPublicKey(): PublicKey = getKeyStoreData().certificate.publicKey

    /**
     * Retorna o nome padrão do dispositivo anunciado nas conexões ADB.
     */
    fun getDeviceName(): String = DEFAULT_DEVICE_NAME

    /**
     * Retorna o certificado X.509 codificado em Base64.
     */
    fun getCertificateBase64(): String {
        return Base64.encodeToString(getCertificate().encoded, Base64.NO_WRAP)
    }

    private fun generateAndSaveKeys(privateFile: File, certFile: File): KeyStoreData {
        Log.i(TAG, "[AICALL ADB] Gerando novo par de chaves RSA e certificado para autenticação ADB...")
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val javaKeyPair = keyGen.generateKeyPair()

        val certificate = generateSelfSignedCertificate(javaKeyPair)

        privateFile.writeBytes(javaKeyPair.private.encoded)
        certFile.writeBytes(certificate.encoded)

        val publicFile = File(context.filesDir, PUBLIC_KEY_FILE)
        try {
            publicFile.writeBytes(javaKeyPair.public.encoded)
        } catch (e: Exception) {
            Log.w(TAG, "[AICALL ADB] Não foi possível salvar $PUBLIC_KEY_FILE: ${e.message}")
        }

        return KeyStoreData(javaKeyPair.private, certificate)
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000L) // ontem
        val endDate = Date(now + 25L * 365 * 24 * 60 * 60 * 1000L) // ~25 anos
        val dnName = X500Name("CN=$DEFAULT_DEVICE_NAME")

        val certBuilder = JcaX509v3CertificateBuilder(
            dnName,
            BigInteger.valueOf(now),
            startDate,
            endDate,
            dnName,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        return JcaX509CertificateConverter().getCertificate(certHolder)
    }
}
