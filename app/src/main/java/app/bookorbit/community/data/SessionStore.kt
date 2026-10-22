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

    fun connections(): List<Session> = listOfNotNull(read())

    fun activate(scope: String): Boolean = read()?.scope == scope
}

@kotlinx.serialization.Serializable
data class SessionConnections(
    val activeScope: String? = null,
    val saved: List<Session> = emptyList(),
) {
    val active: Session?
        get() = saved.firstOrNull { it.scope == activeScope }

    fun save(session: Session?): SessionConnections =
        if (session == null) {
            val remaining = saved.filterNot { it.scope == activeScope }
            SessionConnections(remaining.firstOrNull()?.scope, remaining)
        } else
            SessionConnections(
                session.scope,
                saved.filterNot { it.scope == session.scope } + session,
            )

    fun activate(scope: String): SessionConnections {
        require(saved.any { it.scope == scope })
        return copy(activeScope = scope)
    }
}

fun decodeSessionConnections(value: String): SessionConnections {
    val objectValue = wireJson.parseToJsonElement(value)
    return if (objectValue is kotlinx.serialization.json.JsonObject && "saved" in objectValue)
        wireJson.decodeFromString<SessionConnections>(value).also {
            require(it.activeScope == null || it.active != null)
        }
    else wireJson.decodeFromString<Session>(value).let { SessionConnections(it.scope, listOf(it)) }
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

    @Synchronized override fun read(): Session? = readConnections().active

    @Synchronized override fun connections(): List<Session> = readConnections().saved

    @Synchronized
    override fun activate(scope: String): Boolean {
        val registry = readConnections()
        if (registry.saved.none { it.scope == scope }) return false
        writeConnections(registry.activate(scope))
        return true
    }

    private fun readConnections(): SessionConnections {
        val stored = preferences.getString("encrypted", null) ?: return SessionConnections()
        return try {
            val parts = stored.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            decodeSessionConnections(
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
            )
        } catch (_: Exception) {
            preferences.edit().clear().commit()
            SessionConnections()
        }
    }

    @Synchronized
    override fun save(session: Session?) = writeConnections(readConnections().save(session))

    private fun writeConnections(registry: SessionConnections) {
        if (registry.saved.isEmpty()) {
            check(preferences.edit().clear().commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encoded =
            cipher.doFinal(
                wireJson.encodeToString(SessionConnections.serializer(), registry).toByteArray()
            )
        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encoded, Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted", value).commit())
    }
}

fun SessionStore.hasConnection(scope: String): Boolean = connections().any { it.scope == scope }
