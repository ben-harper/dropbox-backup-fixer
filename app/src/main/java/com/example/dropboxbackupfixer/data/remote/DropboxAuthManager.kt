package com.example.dropboxbackupfixer.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2

object DropboxAuthManager {
    const val APP_KEY = "iv7vj5f47lrn0jo"
    private const val PREFS_NAME = "dropbox_prefs"
    
    private var dbxClient: DbxClientV2? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun startAuth(context: Context) {
        Auth.startOAuth2PKCE(context, APP_KEY, DbxRequestConfig("DropboxBackupFixer/1.0"))
    }

    fun handleAuthCallback(context: Context): Boolean {
        val credential = Auth.getDbxCredential() // will be non-null if auth succeeded
        return if (credential != null) {
            saveCredential(context, credential)
            initClient(credential)
            true
        } else {
            false
        }
    }

    private fun saveCredential(context: Context, credential: DbxCredential) {
        getPrefs(context).edit().apply {
            putString("access_token", credential.accessToken)
            putLong("expires_at", credential.expiresAt ?: -1L)
            putString("refresh_token", credential.refreshToken)
            putString("app_key", credential.appKey)
            apply()
        }
    }

    private fun loadCredential(context: Context): DbxCredential? {
        val prefs = getPrefs(context)
        val accessToken = prefs.getString("access_token", null)
        val refreshToken = prefs.getString("refresh_token", null)
        val expiresAt = prefs.getLong("expires_at", -1L)
        val appKey = prefs.getString("app_key", APP_KEY)
        
        return if (accessToken != null) {
            DbxCredential(accessToken, if (expiresAt == -1L) null else expiresAt, refreshToken, appKey)
        } else null
    }

    fun getClient(context: Context): DbxClientV2? {
        if (dbxClient != null) return dbxClient
        
        val credential = loadCredential(context)
        if (credential != null) {
            initClient(credential)
            return dbxClient
        }
        return null
    }
    
    fun hasToken(context: Context): Boolean {
        return loadCredential(context) != null
    }

    fun logout(context: Context) {
        getPrefs(context).edit().clear().apply()
        dbxClient = null
    }

    private fun initClient(credential: DbxCredential) {
        val requestConfig = DbxRequestConfig("DropboxBackupFixer/1.0")
        dbxClient = DbxClientV2(requestConfig, credential)
    }
}
