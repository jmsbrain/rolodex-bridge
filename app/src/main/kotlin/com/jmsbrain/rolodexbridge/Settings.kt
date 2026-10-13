package com.jmsbrain.rolodexbridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A phone contacts account (LightOS's address book is one). */
data class PhoneBook(val name: String, val type: String)

/** Sign-in and the two address books. The password is encrypted with an Android Keystore key. */
object Settings {
    const val DEFAULT_SERVER = "https://dav.mailbox.org/"

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun user(ctx: Context): String? = prefs(ctx).getString("user", null)
    fun server(ctx: Context): String? = prefs(ctx).getString("server", null)
    fun home(ctx: Context): String? = prefs(ctx).getString("home", null)
    fun signedIn(ctx: Context): Boolean = user(ctx) != null && password(ctx) != null

    fun saveSignIn(ctx: Context, server: String, user: String, home: String, password: String) {
        prefs(ctx).edit().putString("server", server).putString("user", user).putString("home", home).apply()
        savePassword(ctx, password)
    }

    /** The mailbox.org address book, and its name for the screen. */
    fun serverBook(ctx: Context): String? = prefs(ctx).getString("serverBook", null)
    fun serverBookName(ctx: Context): String = prefs(ctx).getString("serverBookName", null).orEmpty()
    fun setServerBook(ctx: Context, url: String, name: String) =
        prefs(ctx).edit().putString("serverBook", url).putString("serverBookName", name).apply()

    /** LightOS's address book on the phone. */
    fun phoneBook(ctx: Context): PhoneBook? {
        val name = prefs(ctx).getString("phoneBookName", null) ?: return null
        val type = prefs(ctx).getString("phoneBookType", null) ?: return null
        return PhoneBook(name, type)
    }

    fun setPhoneBook(ctx: Context, book: PhoneBook) =
        prefs(ctx).edit().putString("phoneBookName", book.name).putString("phoneBookType", book.type).apply()

    /** On once the starting point is recorded; from then on only new contacts cross. */
    fun running(ctx: Context): Boolean = prefs(ctx).getBoolean("running", false)
    fun setRunning(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("running", on).apply()

    fun signOut(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    // ---- password ----

    private const val KEY_ALIAS = "rolodex-bridge-password"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun savePassword(ctx: Context, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString("pwIv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("pw", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }

    fun password(ctx: Context): String? = runCatching {
        val iv = Base64.decode(prefs(ctx).getString("pwIv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(prefs(ctx).getString("pw", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()
}
