package org.jellyfin.mobile.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedProfileStore(context: Context, filename: String, private val keyAlias: String) {
    private val store = AtomicFile(File(context.noBackupFilesDir, filename))

    fun read(): String {
        val bytes = store.readFully()
        val key = loadKey() ?: throw GeneralSecurityException("Profile key is unavailable")
        require(bytes.size > IV_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        }.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    fun write(value: String) {
        val key = loadKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        val encryptor = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        if (encryptor.iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected profile cipher IV")
        val payload = encryptor.iv + encryptor.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = store.startWrite()
        try {
            out.write(payload)
            store.finishWrite(out)
        } catch (e: Exception) {
            store.failWrite(out)
            throw e
        }
    }

    fun delete() = store.delete()

    private fun loadKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyAlias, null) as? SecretKey

    private companion object {
        const val IV_BYTES = 12
    }
}
