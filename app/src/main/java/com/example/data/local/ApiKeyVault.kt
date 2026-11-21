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
 * Thrown when encrypted credential storage (Android Keystore) cannot be initialized or invoked.
 * Paper Critic fails closed and refuses to persist API keys in plaintext.
 */
class CredentialStorageUnavailableException(
  message: String,
  cause: Throwable? = null
) : IllegalStateException(message, cause)

enum class VaultStatus {
  AVAILABLE,
  UNAVAILABLE
}

sealed interface KeyDecryptOutcome {
  data object Empty : KeyDecryptOutcome
  data class Decrypted(val plaintext: String, val wasLegacyPlaintext: Boolean) : KeyDecryptOutcome
  data class Unavailable(val reason: String) : KeyDecryptOutcome
}

/**
 * Hardware-backed Android Keystore AES-256-GCM credential vault for protecting user BYOK API keys
 * before persisting them to Room SQLite.
 *
 * Security invariant: **FAIL CLOSED**. If Android Keystore is unavailable or encryption fails,
 * [encrypt] throws [CredentialStorageUnavailableException] rather than falling back to plaintext.
 *
 * Ciphertext format: `enc_v1:<base64(iv_12_bytes)>.<base64(ciphertext_and_tag)>`
 */
object ApiKeyVault {
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "paper_critic_api_key_aes_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val PREFIX = "enc_v1:"

  /**
   * Optional key-provider hook strictly for deterministic JVM/Robolectric unit tests
   * (e.g., simulating Keystore availability or Keystore failure without mocking Android internals).
   */
  internal var keyProviderOverride: (() -> SecretKey?)? = null

  fun isEncrypted(storedValue: String): Boolean = storedValue.startsWith(PREFIX)

  fun status(): VaultStatus = if (getOrCreateKey() != null) VaultStatus.AVAILABLE else VaultStatus.UNAVAILABLE

  fun isHardwareVaultAvailable(): Boolean = status() == VaultStatus.AVAILABLE

  /**
   * Encrypts [plaintext] with AES-256-GCM. Fails closed by throwing
   * [CredentialStorageUnavailableException] if the Keystore is unavailable.
   */
  @Throws(CredentialStorageUnavailableException::class)
  fun encrypt(plaintext: String): String {
    val clean = plaintext.trim()
    if (clean.isEmpty()) return ""
    if (isEncrypted(clean)) return clean

    val key = getOrCreateKey()
      ?: throw CredentialStorageUnavailableException(
        "Encrypted credential storage (Android Keystore) is unavailable on this device. Plaintext API key storage is blocked."
      )

    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
      val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
      val ctB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
      "$PREFIX$ivB64.$ctB64"
    } catch (err: Exception) {
      Log.e(VAULT_TAG, "AES-256-GCM encryption failed; refusing plaintext fallback", err)
      throw CredentialStorageUnavailableException(
        "Failed to encrypt API key via Android Keystore (${err.javaClass.simpleName}). Key was not stored.",
        err
      )
    }
  }

  /**
   * Decrypts a stored value and signals whether a legacy unencrypted row was encountered
   * so callers can immediately re-encrypt it at rest.
   */
  fun decryptOutcome(storedValue: String): KeyDecryptOutcome {
    if (storedValue.isBlank()) return KeyDecryptOutcome.Empty
    if (!isEncrypted(storedValue)) {
      // Legacy unencrypted row detected — caller must migrate immediately when Keystore is available.
      return KeyDecryptOutcome.Decrypted(plaintext = storedValue, wasLegacyPlaintext = true)
    }
    val key = getOrCreateKey()
      ?: return KeyDecryptOutcome.Unavailable(
        "Encrypted credential storage is unavailable; stored API key cannot be unlocked."
      )
    return try {
      val payload = storedValue.removePrefix(PREFIX)
      val dotIdx = payload.indexOf('.')
      if (dotIdx <= 0) {
        return KeyDecryptOutcome.Unavailable("Stored API key ciphertext is malformed.")
      }
      val iv = Base64.decode(payload.substring(0, dotIdx), Base64.NO_WRAP)
      val cipherBytes = Base64.decode(payload.substring(dotIdx + 1), Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      KeyDecryptOutcome.Decrypted(
        plaintext = String(cipher.doFinal(cipherBytes), Charsets.UTF_8),
        wasLegacyPlaintext = false
      )
    } catch (err: Exception) {
      Log.w(VAULT_TAG, "Failed to decrypt stored API key", err)
      KeyDecryptOutcome.Unavailable("Stored API key could not be decrypted (${err.javaClass.simpleName}).")
    }
  }

  /**
   * Returns decrypted plaintext for an encrypted key, or empty string if unavailable.
   * Refuses to silently serve legacy plaintext unless the caller explicitly migrates it via [decryptOutcome].
   */
  fun decrypt(storedValue: String): String {
    return when (val outcome = decryptOutcome(storedValue)) {
      is KeyDecryptOutcome.Decrypted -> {
        if (outcome.wasLegacyPlaintext) {
          // Do not silently accept legacy plaintext if Keystore is unavailable
          if (isHardwareVaultAvailable()) outcome.plaintext else ""
        } else {
          outcome.plaintext
        }
      }
      KeyDecryptOutcome.Empty,
      is KeyDecryptOutcome.Unavailable -> ""
    }
  }

  private fun getOrCreateKey(): SecretKey? {
    keyProviderOverride?.let { overrideProvider ->
      return runCatching { overrideProvider() }.getOrNull()
    }
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
