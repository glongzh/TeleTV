package com.teletv.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted-hash PIN storage. This is a privacy gate against casual access from
 * the couch — not cryptographic protection of the (unencrypted) TDLib database.
 * The plaintext PIN never touches disk: only a random salt and
 * sha256(salt + pin) are persisted.
 */
class PinRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pin_lock", Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(KEY_HASH)

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        return hash(Base64.decode(salt, Base64.NO_WRAP), pin) == stored
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, hash(salt, pin))
            .apply()
    }

    fun clearPin() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    private fun hash(salt: ByteArray, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
    }
}
