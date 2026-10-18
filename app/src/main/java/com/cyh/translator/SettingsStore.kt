package com.cyh.translator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun load(): AppSettings {
        // Legacy audio/clone preferences are deliberately ignored. Every Android session is text-only.
        fun channel(id: String, fallback: String) = ChannelConfig(id,
            preferences.getString("$id.target", fallback) ?: fallback)
        return AppSettings(
            preferences.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
            preferences.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
            runCatching { InputMode.valueOf(preferences.getString("mode", "MICROPHONE")!!) }.getOrDefault(InputMode.MICROPHONE),
            channel("listen", "zh"), channel("speak", "en"))
    }
    fun hasKey() = preferences.contains("encryptedKey")
    fun apiKey(): String {
        val encoded = preferences.getString("encryptedKey", null) ?: return ""
        try {
            val parts = encoded.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { error("密钥无法解密，请在设置中重新填写 API Key") }
    }
    fun save(settings: AppSettings, newKey: String = "") {
        val encrypted = if (newKey.isNotBlank()) {
            require(!newKey.any { it.isWhitespace() }) { "API Key 不能包含空格或换行" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(newKey.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } else null
        val editor = preferences.edit().putString("endpoint", settings.endpoint).putString("model", settings.model)
            .putString("mode", settings.mode.name)
        listOf(settings.listen, settings.speak).forEach {
            editor.putString("${it.id}.target", it.target).remove("${it.id}.audio").remove("${it.id}.clone")
        }
        if (encrypted != null) editor.putString("encryptedKey", encrypted)
        check(editor.commit()) { "设置保存失败，请检查存储空间" }
    }
    fun clearKey() { check(preferences.edit().remove("encryptedKey").commit()) }
    fun overlayEnabled() = preferences.getBoolean("overlay.enabled", true)
    fun setOverlayEnabled(enabled: Boolean) { preferences.edit().putBoolean("overlay.enabled", enabled).apply() }
    fun overlayTextSize() = preferences.getInt("overlay.textSize", 20).coerceIn(16, 30)
    fun setOverlayTextSize(size: Int) { preferences.edit().putInt("overlay.textSize", size.coerceIn(16, 30)).apply() }
    fun overlayPosition() = OverlayPosition(preferences.getFloat("overlay.x", 0.5f), preferences.getFloat("overlay.y", 0.72f))
    fun setOverlayPosition(position: OverlayPosition) {
        preferences.edit().putFloat("overlay.x", position.x).putFloat("overlay.y", position.y).apply()
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("translation_api_key", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("translation_api_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
