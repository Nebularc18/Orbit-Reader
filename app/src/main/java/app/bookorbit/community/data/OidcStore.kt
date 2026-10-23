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
import kotlinx.serialization.encodeToString

class OidcStore(context: Context) {
    companion object {
        private val lock = Any()
    }

    private val preferences = context.getSharedPreferences("oidc-login", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return store.getKey("orbit-oidc", null) as? SecretKey
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                    "orbit-oidc",
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                    }
                    .generateKey()
        }

    fun save(value: PendingOidc) =
        synchronized(lock) {
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val encrypted = cipher.doFinal(wireJson.encodeToString(value).toByteArray())
            check(
                preferences
                    .edit()
                    .putString(
                        "pending",
                        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                            ":" +
                            Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    )
                    .commit()
            )
        }

    fun clear() = synchronized(lock) { check(preferences.edit().clear().commit()) }

    fun consume(callback: String, now: Long): Pair<PendingOidc, String> =
        synchronized(lock) {
            val stored =
                preferences.getString("pending", null) ?: error("No pending sign-in. Try again.")
            val parts = stored.split(':')
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
                    )
                }
            val pending =
                wireJson.decodeFromString<PendingOidc>(
                    String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
                )
            if (now < pending.startedAt || now - pending.startedAt > OIDC_MAX_AGE_MS) clear()
            val code = pending.code(callback, now)
            clear()
            pending to code
        }
}
