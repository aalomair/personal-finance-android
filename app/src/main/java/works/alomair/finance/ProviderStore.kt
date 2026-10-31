package works.alomair.finance

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderId(val endpoint: String, val defaultModel: String) {
    GO("https://opencode.ai/inference/go/openai/v1/chat/completions", "glm-5.3-flash"),
    ROUTER("https://openrouter.ai/api/v1/chat/completions", ""),
    CUSTOM("", ""),
}

// Deliberately not a data class: credentials must never appear in generated toString().
class ProviderConfig(val id: ProviderId, val endpoint: String, val model: String, val apiKey: String) {
    override fun toString() = "ProviderConfig(id=$id, key=[masked])"
    fun validate(requireKey: Boolean = false) {
        val url = endpoint.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null) { "ENDPOINT" }
        require(url.isHttps || (url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2"))) { "HTTPS_REQUIRED" }
        require(id == ProviderId.CUSTOM || endpoint == id.endpoint) { "FIXED_ENDPOINT" }
        require(model.isNotBlank() && model.length <= 200 && !model.contains('\n')) { "MODEL" }
        require(apiKey.length <= 8192 && !apiKey.contains('\n') && !apiKey.contains('\r')) { "KEY" }
        require(!requireKey || apiKey.isNotBlank()) { "MISSING_KEY" }
    }
}

class KeyUnavailable : IllegalStateException("KEY_UNAVAILABLE")

class AesGcmCipher(private val key: () -> SecretKey) {
    fun encrypt(plain: String, scope: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    fun decrypt(encoded: String, scope: String): String {
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw KeyUnavailable()
        }
    }
    companion object {
        fun androidKeystore() = AesGcmCipher {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "finance-provider-keys-v1"
            (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}

class ProviderStore(val preferences: SharedPreferences, val cipher: AesGcmCipher) {
    // Shared between store instances so selection changes cannot race a request dispatch.
    companion object {
        private val lock = Any()
        fun open(context: Context) = ProviderStore(context.getSharedPreferences("providers", Context.MODE_PRIVATE), AesGcmCipher.androidKeystore())
    }
    // SharedPreferences reads are thread-safe and each selection commit is atomic.
    // Ingress must not wait on withActive's network-duration lock: goAsync is bounded.
    // Selection WRITES still take that lock, preserving dispatch/provider-consent gating.
    fun selected(): ProviderId {
        val stored = preferences.getString("selected", null)
        return ProviderId.values().firstOrNull { it.name == stored } ?: ProviderId.GO
    }
    fun select(id: ProviderId) = synchronized(lock) {
        check(preferences.edit().putString("selected", id.name).commit()) { "SETTINGS_WRITE" }
    }
    fun load(id: ProviderId): ProviderConfig = synchronized(lock) {
        val prefix = id.name
        val ciphertext = preferences.getString("$prefix.key", null)
        ProviderConfig(id, if (id == ProviderId.CUSTOM) preferences.getString("$prefix.url", "")!! else id.endpoint,
            preferences.getString("$prefix.model", id.defaultModel)!!,
            if (ciphertext == null) "" else cipher.decrypt(ciphertext, prefix))
    }
    fun active(): ProviderConfig = load(selected())
    fun save(config: ProviderConfig) = synchronized(lock) {
        config.validate()
        // Encrypt before writing any field, then commit all fields atomically.
        val encrypted = cipher.encrypt(config.apiKey, config.id.name)
        check(preferences.edit().putString("${config.id.name}.key", encrypted)
            .putString("${config.id.name}.url", config.endpoint)
            .putString("${config.id.name}.model", config.model).commit()) { "SETTINGS_WRITE" }
    }
    fun sessionId(): String = synchronized(lock) {
        preferences.getString("session", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("session", it).commit()) { "SETTINGS_WRITE" }
        }
    }
    fun <T> withActive(block: (ProviderConfig) -> T): T = synchronized(lock) { block(active()) }
}
