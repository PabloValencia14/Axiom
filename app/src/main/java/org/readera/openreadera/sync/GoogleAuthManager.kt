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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class GoogleUserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val accessToken: String? = null
)

class GoogleAuthManager(
    private val context: Context,
    private val signOutRequest: ((() -> Unit) -> Unit)? = null,
    private val tokenProvider: ((String) -> String)? = null
) {

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
        getSelectedProfile()?.let { return it }
        val session = DriveAccountGuard.capture()
        if (!prefs.getBoolean("explicitly_signed_out", false)) {
            getDeviceGoogleAccounts().firstOrNull()?.let {
                return selectAccountByEmail(it, launchSession = session).first
            }
        }
        return null
    }

    internal fun getSelectedProfile(): GoogleUserProfile? = DriveAccountGuard.locked {
        val email = prefs.getString("google_email", "") ?: ""
        if (isSignedIn && email.isNotBlank()) {
            return@locked GoogleUserProfile(
                id = prefs.getString("google_id", "") ?: "",
                email = email,
                displayName = prefs.getString("google_name", email.substringBefore('@')) ?: email.substringBefore('@'),
                photoUrl = prefs.getString("google_photo", null),
                accessToken = prefs.getString("google_token", null).takeIf {
                    prefs.getString("google_token_account", null) == email
                }
            )
        }
        null
    }

    fun getDeviceGoogleAccounts(): List<String> {
        return try {
            val am = android.accounts.AccountManager.get(context)
            am.getAccountsByType("com.google").map { it.name }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccountByEmail(email: String, displayName: String? = null, token: String? = null): GoogleUserProfile =
        saveAccount(email, displayName, token, null).first

    internal fun beginAccountSelection(): DriveAccountGuard.Session = DriveAccountGuard.capture()

    internal fun selectAccountByEmail(
        email: String,
        displayName: String? = null,
        launchSession: DriveAccountGuard.Session
    ): Pair<GoogleUserProfile, DriveAccountGuard.Session> =
        saveAccount(email, displayName, null, launchSession)

    private fun saveAccount(
        email: String,
        displayName: String?,
        token: String?,
        expected: DriveAccountGuard.Session?
    ): Pair<GoogleUserProfile, DriveAccountGuard.Session> {
        val cleanEmail = email.trim().lowercase(java.util.Locale.ROOT)
        require(cleanEmail.isNotBlank())
        return DriveAccountGuard.updateIdentity(expected, invalidateWhen = {
            val changed = prefs.getString("google_email", null) != cleanEmail
            // OAuth callbacks may only authorize the still-selected identity.
            require(token == null || !changed) { "Google account changed during authorization" }
            require(token == null || (prefs.getString("google_token_account", null) == cleanEmail &&
                prefs.getString("google_token", null) == token)) { "Stale Google authorization" }
            changed
        }) { session ->
            val changed = prefs.getString("google_email", null) != cleanEmail
            if (changed) {
                context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit()
                    .remove("target_folder_id").remove("target_folder_account")
                    .remove("last_sync_timestamp").apply()
            }
            val finalToken = token ?: prefs.getString("google_token", null).takeIf {
                !changed && prefs.getString("google_token_account", null) == cleanEmail
            }
            val profile = GoogleUserProfile(
                id = "gid_${cleanEmail.hashCode()}", email = cleanEmail,
                displayName = displayName?.takeIf(String::isNotBlank) ?: cleanEmail.substringBefore('@'),
                accessToken = finalToken
            )
            prefs.edit().putBoolean("is_signed_in", true).putBoolean("explicitly_signed_out", false)
                .putString("google_id", profile.id).putString("google_email", cleanEmail)
                .putString("google_name", profile.displayName).remove("google_photo")
                .putString("google_token", finalToken)
                .putString("google_token_account", if (finalToken != null) cleanEmail else null).apply()
            profile to session
        }
    }

    suspend fun fetchOAuthToken(email: String): Result<String> =
        fetchOAuthToken(email, DriveAccountGuard.capture())

    internal suspend fun fetchOAuthToken(email: String, session: DriveAccountGuard.Session): Result<String> =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim().lowercase(java.util.Locale.ROOT)
            val coroutineContext = currentCoroutineContext()
            val job = checkNotNull(coroutineContext[Job])
            try {
                session.attach(job)
                coroutineContext.ensureActive()
                session.commit {
                    require(prefs.getString("google_email", null) == cleanEmail) { "Google account changed" }
                    prefs.edit().remove("google_token").remove("google_token_account").apply()
                }
                val token = (tokenProvider?.invoke(cleanEmail) ?: GoogleAuthUtil.getToken(
                    context, android.accounts.Account(cleanEmail, "com.google"),
                    "oauth2:$driveScopeAppData $driveScopeFile"
                )).takeIf { !it.isNullOrBlank() } ?: error("No se recibió token OAuth")
                session.commit {
                    coroutineContext.ensureActive()
                    require(prefs.getString("google_email", null) == cleanEmail) { "Google account changed" }
                    prefs.edit().putString("google_token", token).putString("google_token_account", cleanEmail).apply()
                }
                Result.success(token)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            } finally {
                session.detach(job)
            }
        }

    suspend fun refreshToken(email: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val oldToken = getUserProfile()?.takeIf { it.email.equals(email.trim(), true) }?.accessToken
            if (!oldToken.isNullOrBlank()) {
                try {
                    GoogleAuthUtil.clearToken(context, oldToken)
                } catch (_: Exception) {}
            }
            fetchOAuthToken(email)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun handleSignInAccount(
        account: GoogleSignInAccount,
        launchSession: DriveAccountGuard.Session
    ): Result<GoogleUserProfile> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val email = account.email?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure(IllegalStateException("No se pudo obtener el correo de la cuenta Google"))
        val (selected, session) = selectAccountByEmail(email, account.displayName, launchSession)
        fetchOAuthToken(email, session).map { token ->
            session.commit {
                selected.copy(id = account.id ?: selected.id, photoUrl = account.photoUrl?.toString(), accessToken = token).also { profile ->
                    prefs.edit().putString("google_id", profile.id).putString("google_photo", profile.photoUrl).apply()
                }
            }
        }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        DriveAccountGuard.updateIdentity {
            prefs.edit().clear().putBoolean("explicitly_signed_out", true).apply()
            context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit()
                .remove("target_folder_id").remove("target_folder_account").remove("last_sync_timestamp")
                .remove("saf_folder_uri").remove("saf_folder_name").apply()
        }
        try {
            val request = signOutRequest
            if (request != null) request(onComplete)
            else googleSignInClient.signOut().addOnCompleteListener { onComplete() }
        } catch (_: Exception) {
            onComplete()
        }
    }
}
