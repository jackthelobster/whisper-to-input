package com.example.whispertoinput

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CredentialStore {
    fun contains(): Boolean
    fun read(): String
    fun write(value: String)
    fun boundEndpoint(): String? = null
    fun writeBound(value: String, endpoint: String) = write(value)
}

/** No exportable key material, plaintext preferences, trust-all TLS, or logging. */
internal class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.transcription.credentials.v1"
    private val aad = "${context.packageName}.transcription.api-key.v1".toByteArray(Charsets.UTF_8)

    override fun contains(): Boolean = preferences.contains(ENTRY)

    override fun read(): String {
        val encoded = preferences.getString(ENTRY, null) ?: return ""
        check(encoded.length <= 16384)
        val parts = encoded.split(':')
        check(parts.size == 3 && parts[0] == "v1")
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        check(iv.size == 12 && encrypted.size >= 16)
        val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Missing credential key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    override fun boundEndpoint(): String? = if (contains()) preferences.getString(ENDPOINT_BINDING, null) else null

    override fun write(value: String) = persist(value, null)

    override fun writeBound(value: String, endpoint: String) = persist(value, endpoint)

    private fun persist(value: String, endpoint: String?) {
        val editor = preferences.edit()
        if (value.isEmpty()) {
            editor.remove(ENTRY)
            editor.remove(ENDPOINT_BINDING)
        } else {
            val store = keyStore()
            val key = store.getKey(alias, null) as? SecretKey ?: generateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Keystore supplies a fresh random IV for each encryption.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            editor.putString(ENTRY, "v1:$iv:$ciphertext")
            if (endpoint == null) editor.remove(ENDPOINT_BINDING)
            else editor.putString(ENDPOINT_BINDING, endpoint)
        }
        check(editor.commit()) { "Credential storage failed" }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
    }.generateKey()

    companion object {
        private const val ENTRY = "api-key-ciphertext"
        private const val ENDPOINT_BINDING = "api-key-endpoint"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
