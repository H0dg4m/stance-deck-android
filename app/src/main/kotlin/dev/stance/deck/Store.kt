package dev.stance.deck

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.stance.deck.core.KeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps secrets so the stored device key is useless outside this app's Keystore entry. */
interface Wrapper {
    fun wrap(plain: ByteArray): String
    fun unwrap(text: String): ByteArray
}

object KeystoreWrapper : Wrapper {
    private const val ALIAS = "stance-deck-wrap"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    override fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    override fun unwrap(text: String): ByteArray {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12)
    }
}

/** One paired PC. [pcPublic] (base64url) identifies it; the phone key is per PC. */
@Serializable
data class PcRecord(
    val pcPublic: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val ownPrivateWrapped: String,
) {
    fun ownKey(w: Wrapper): KeyPair = KeyPair.fromPrivate(w.unwrap(ownPrivateWrapped))
}

class Store(context: Context, private val wrapper: Wrapper = KeystoreWrapper) {
    private val prefs = context.getSharedPreferences("stance-deck", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<PcRecord> = try {
        json.decodeFromString(prefs.getString("pcs", "[]") ?: "[]")
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(l: List<PcRecord>) = prefs.edit().putString("pcs", json.encodeToString(l)).apply()

    fun upsert(r: PcRecord) = save(list().filter { it.pcPublic != r.pcPublic } + r)

    fun remove(pcPublic: String) = save(list().filter { it.pcPublic != pcPublic })

    fun wrap(own: KeyPair): String = wrapper.wrap(own.private)
    fun ownKey(r: PcRecord): KeyPair = r.ownKey(wrapper)
}
