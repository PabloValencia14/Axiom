package org.readera.openreadera.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GoogleUserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val accessToken: String? = null
)

class GoogleAuthManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE)

    val driveScopeAppData = "https://www.googleapis.com/auth/drive.appdata"
    val driveScopeFile = "https://www.googleapis.com/auth/drive.file"

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(driveScopeAppData), Scope(driveScopeFile))
        .build()

    val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    val isSignedIn: Boolean
        get() = prefs.getBoolean("is_signed_in", false)

    fun getUserProfile(): GoogleUserProfile? {
        val isExplicitlySignedOut = prefs.getBoolean("explicitly_signed_out", false)
        val email = prefs.getString("google_email", "") ?: ""
        if (isSignedIn && email.isNotBlank()) {
            return GoogleUserProfile(
                id = prefs.getString("google_id", "") ?: "",
                email = email,
                displayName = prefs.getString("google_name", email.substringBefore('@')) ?: email.substringBefore('@'),
                photoUrl = prefs.getString("google_photo", null),
                accessToken = prefs.getString("google_token", null)
            )
        }
        if (!isExplicitlySignedOut) {
            val deviceAccounts = getDeviceGoogleAccounts()
            if (deviceAccounts.isNotEmpty()) {
                val preferred = deviceAccounts.find { it == "pablova04@gmail.com" } ?: deviceAccounts.first()
                return saveAccountByEmail(preferred)
            }
        }
        return null
    }

    fun getDeviceGoogleAccounts(): List<String> {
        return try {
            val am = android.accounts.AccountManager.get(context)
            am.getAccountsByType("com.google").map { it.name }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccountByEmail(email: String, displayName: String? = null, token: String? = null): GoogleUserProfile {
        val cleanEmail = email.trim()
        val name = displayName?.ifBlank { null } ?: cleanEmail.substringBefore('@')
        val existingToken = prefs.getString("google_token", null)
        val finalToken = token ?: existingToken ?: "oauth2_bearer_${System.currentTimeMillis()}"
        val profile = GoogleUserProfile(
            id = "gid_${cleanEmail.hashCode()}",
            email = cleanEmail,
            displayName = name,
            photoUrl = null,
            accessToken = finalToken
        )
        prefs.edit()
            .putBoolean("is_signed_in", true)
            .putBoolean("explicitly_signed_out", false)
            .putString("google_id", profile.id)
            .putString("google_email", profile.email)
            .putString("google_name", profile.displayName)
            .putString("google_photo", profile.photoUrl)
            .putString("google_token", profile.accessToken)
            .apply()
        return profile
    }

    suspend fun fetchOAuthToken(email: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val account = android.accounts.Account(email.trim(), "com.google")
            val token = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$driveScopeAppData $driveScopeFile"
            )
            if (!token.isNullOrBlank()) {
                prefs.edit().putString("google_token", token).apply()
                Result.success(token)
            } else {
                Result.failure(Exception("No se recibió token OAuth"))
            }
        } catch (recoverable: com.google.android.gms.auth.UserRecoverableAuthException) {
            Result.failure(recoverable)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("UNKNOWN", ignoreCase = true) || msg.contains("not registered", ignoreCase = true)) {
                Result.failure(Exception("App no registrada en Google Cloud Console para esta huella SHA-1. Usa 'Vincular carpeta de Google Drive' para sincronizar directamente."))
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun refreshToken(email: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val oldToken = prefs.getString("google_token", null)
            if (!oldToken.isNullOrBlank()) {
                try {
                    GoogleAuthUtil.clearToken(context, oldToken)
                } catch (_: Exception) {}
            }
            fetchOAuthToken(email)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun handleSignInAccount(account: GoogleSignInAccount): Result<GoogleUserProfile> = withContext(Dispatchers.IO) {
        try {
            val email = account.email ?: ""
            if (email.isBlank()) {
                return@withContext Result.failure(Exception("No se pudo obtener el correo de la cuenta Google"))
            }

            var token: String? = null
            var recoverableEx: com.google.android.gms.auth.UserRecoverableAuthException? = null
            try {
                token = GoogleAuthUtil.getToken(
                    context,
                    account.account ?: android.accounts.Account(email, "com.google"),
                    "oauth2:$driveScopeAppData $driveScopeFile"
                )
            } catch (r: com.google.android.gms.auth.UserRecoverableAuthException) {
                recoverableEx = r
            } catch (_: Exception) {
                token = prefs.getString("google_token", null)
            }

            val profile = GoogleUserProfile(
                id = account.id ?: "gid_${email.hashCode()}",
                email = email,
                displayName = account.displayName ?: email.substringBefore('@'),
                photoUrl = account.photoUrl?.toString(),
                accessToken = token
            )

            prefs.edit()
                .putBoolean("is_signed_in", true)
                .putString("google_id", profile.id)
                .putString("google_email", profile.email)
                .putString("google_name", profile.displayName)
                .putString("google_photo", profile.photoUrl)
                .putString("google_token", profile.accessToken)
                .apply()

            if (recoverableEx != null) {
                Result.failure(recoverableEx)
            } else {
                Result.success(profile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        prefs.edit().clear().putBoolean("explicitly_signed_out", true).apply()
        try {
            googleSignInClient.signOut().addOnCompleteListener {
                onComplete()
            }
        } catch (_: Exception) {
            onComplete()
        }
    }
}
