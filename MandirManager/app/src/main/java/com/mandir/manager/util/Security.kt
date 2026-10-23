package com.mandir.manager.util

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mandir.manager.data.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.resume

object Passwords {
    private const val ITERATIONS = 60_000

    private fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** Returns (hash, salt) for a new password. */
    suspend fun create(password: String): Pair<String, String> = withContext(Dispatchers.Default) {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(bytes, Base64.NO_WRAP)
        hash(password, salt) to salt
    }

    suspend fun verify(password: String, user: UserEntity): Boolean = withContext(Dispatchers.Default) {
        MessageDigest.isEqual(hash(password, user.salt).toByteArray(), user.passwordHash.toByteArray())
    }

    fun validUsername(u: String) = Regex("^[a-z0-9._]{3,20}$").matches(u)
}

/** The logged-in user. Locks automatically after 10 minutes in the background. */
object Session {
    var user: UserEntity? = null
        private set
    private var lastActive = 0L

    val isAdmin: Boolean get() = user?.role == Constants.ROLE_ADMIN
    val name: String get() = user?.fullName.orEmpty()
    val username: String get() = user?.username.orEmpty()

    fun login(u: UserEntity) { user = u; touch() }
    fun logout() { user = null }
    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun isExpired() = SystemClock.elapsedRealtime() - lastActive > Constants.SESSION_TIMEOUT_MS
}

/**
 * Wraps Android's BiometricPrompt. Android only reports "a fingerprint enrolled on this phone
 * matched" — it never reveals which finger or whose finger. That is why approvals pair the
 * fingerprint with the approver's own password.
 */
object Biometric {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun confirm(activity: FragmentActivity, title: String, subtitle: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                    // onAuthenticationFailed = wrong finger; the prompt stays open for another try.
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(AUTH)
                    .build()
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
