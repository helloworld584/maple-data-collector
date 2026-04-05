package com.helloworld584.mapledatacollector

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "maple_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var supabaseUrl: String
        get() = prefs.getString(KEY_SUPABASE_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SUPABASE_URL, value).apply() }

    var supabaseKey: String
        get() = prefs.getString(KEY_SUPABASE_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SUPABASE_KEY, value).apply() }

    var visionApiKey: String
        get() = prefs.getString(KEY_VISION_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_VISION_API_KEY, value).apply() }

    companion object {
        private const val KEY_SUPABASE_URL   = "supabase_url"
        private const val KEY_SUPABASE_KEY   = "supabase_key"
        private const val KEY_VISION_API_KEY = "vision_api_key"
    }
}
