package app.bookorbit.community.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    fun read(): Session?

    fun save(session: Session?)
}

class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("orbit-session", null) as? SecretKey)?.let {
                return it
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "orbit-session",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        }

    @Synchronized
    override fun read(): Session? {
        val stored = preferences.getString("encrypted", null) ?: return null
        return try {
            val parts = stored.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            wireJson.decodeFromString<Session>(
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
            )
        } catch (_: Exception) {
            preferences.edit().clear().commit()
            null
        }
    }

    @Synchronized
    override fun save(session: Session?) {
        if (session == null) {
            check(preferences.edit().clear().commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encoded =
            cipher.doFinal(wireJson.encodeToString(Session.serializer(), session).toByteArray())
        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encoded, Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted", value).commit())
    }
}
