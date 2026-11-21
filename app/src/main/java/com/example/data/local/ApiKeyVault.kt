package com.example.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val VAULT_TAG = "ApiKeyVault"

/**
 * Hardware-backed Android Keystore AES-256-GCM credential vault for protecting user BYOK API keys
 * before persisting them to Room SQLite.
 *
 * Ciphertext format: `enc_v1:<base64(iv_12_bytes)>.<base64(ciphertext_and_tag)>`
 * Gracefully migrates any pre-existing legacy plaintext keys on read.
 */
object ApiKeyVault {
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "paper_critic_api_key_aes_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val PREFIX = "enc_v1:"

  fun isEncrypted(storedValue: String): Boolean = storedValue.startsWith(PREFIX)

  fun encrypt(plaintext: String): String {
    val clean = plaintext.trim()
    if (clean.isEmpty()) return ""
    if (isEncrypted(clean)) return clean

    return runCatching {
      val key = getOrCreateKey() ?: return@runCatching clean
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
      val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
      val ctB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
      "$PREFIX$ivB64.$ctB64"
    }.onFailure { err ->
      Log.w(VAULT_TAG, "AndroidKeyStore encryption unavailable in current runtime", err)
    }.getOrDefault(clean)
  }

  fun decrypt(storedValue: String): String {
    if (storedValue.isBlank()) return ""
    if (!isEncrypted(storedValue)) {
      // Transparent read compatibility for legacy unencrypted rows prior to v4 migration
      return storedValue
    }
    return runCatching {
      val payload = storedValue.removePrefix(PREFIX)
      val dotIdx = payload.indexOf('.')
      if (dotIdx <= 0) return@runCatching ""
      val iv = Base64.decode(payload.substring(0, dotIdx), Base64.NO_WRAP)
      val cipherBytes = Base64.decode(payload.substring(dotIdx + 1), Base64.NO_WRAP)
      val key = getOrCreateKey() ?: return@runCatching ""
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.onFailure { err ->
      Log.w(VAULT_TAG, "Failed to decrypt stored API key", err)
    }.getOrDefault("")
  }

  private fun getOrCreateKey(): SecretKey? {
    return runCatching {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
      if (existing != null) return@runCatching existing

      val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
      val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build()
      keyGenerator.init(spec)
      keyGenerator.generateKey()
    }.getOrNull()
  }
}
