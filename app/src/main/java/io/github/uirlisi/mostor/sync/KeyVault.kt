package io.github.uirlisi.mostor.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the household key at rest: wrapped by a key that never leaves this phone (SPEC §9.4). */
interface KeyVault {
    fun wrap(key: ByteArray): String

    /** Null when the wrapped key can't be opened here (a database moved from another phone). */
    fun unwrap(wrapped: String): ByteArray?
}

/** An AES-GCM key in the Android Keystore wraps the household key (DECISIONS, "… crypto"). */
class AndroidKeyVault : KeyVault {
    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun wrap(key: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = c.doFinal(key)
        return Base64.encodeToString(c.iv + sealed, Base64.NO_WRAP)
    }

    override fun unwrap(wrapped: String): ByteArray? = runCatching {
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.doFinal(bytes, 12, bytes.size - 12)
    }.getOrNull()

    private companion object {
        const val ALIAS = "mostor-household-wrap"
    }
}
