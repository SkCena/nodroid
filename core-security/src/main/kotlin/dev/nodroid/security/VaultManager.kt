package dev.nodroid.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local credential vault: Google Tink AES-256-GCM, keys held in the Android Keystore.
 * Plaintext secrets never touch disk — only Tink ciphertext (Base64) is stored in SharedPreferences.
 * Referenced from workflows as {{vault.KEY}}.
 */
class VaultManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("nodroid_vault_values", Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, "nodroid_vault_keyset", "nodroid_vault_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://nodroid_master_key")
            .build()
        keysetManager.keysetHandle.getPrimitive(Aead::class.java)
    }

    suspend fun put(key: String, plaintext: String): Unit = withContext(Dispatchers.IO) {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(key, null) ?: return@withContext null
        try {
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            String(aead.decrypt(ciphertext, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    suspend fun listKeys(): Set<String> = withContext(Dispatchers.IO) {
        prefs.all.keys.toSet()
    }

    suspend fun has(key: String): Boolean = withContext(Dispatchers.IO) { prefs.contains(key) }
}
