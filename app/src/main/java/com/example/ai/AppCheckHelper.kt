package com.example.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val TOKEN_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val META_PREFS_NAME = "com.example.ai.APPCHECK_META"
private const val META_HARNESS_TOKEN_PREFIX = "harness_debug_token_"
private const val META_RECAPTCHA_MIGRATED_PREFIX = "recaptcha_migrated_"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

internal enum class AppCheckProviderMode {
    DEBUG,
    RECAPTCHA
}

private val initLock = Any()

@Volatile
private var installedMode: AppCheckProviderMode? = null

@Volatile
private var installedDebugSecret: String? = null

fun isAppCheckConfigured(): Boolean = installedMode != null

internal fun resolveAppCheckMode(
    isDebugBuild: Boolean,
    intentDebugToken: String?,
    savedHarnessToken: String?,
    recaptchaSiteKey: String?,
    isRobolectric: Boolean
): AppCheckProviderMode {
    if (!isDebugBuild) {
        return AppCheckProviderMode.RECAPTCHA
    }
    if (!intentDebugToken.isNullOrBlank() || !savedHarnessToken.isNullOrBlank()) {
        return AppCheckProviderMode.DEBUG
    }
    if (!isRobolectric && !recaptchaSiteKey.isNullOrBlank()) {
        return AppCheckProviderMode.RECAPTCHA
    }
    return AppCheckProviderMode.DEBUG
}

private fun resolveRecaptchaSiteKey(context: Context): String? {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) return null
    return context.getString(resId).trim().takeIf { it.isNotEmpty() }
}

/**
 * Ensures [FirebaseApp] is initialized in this process and returns true if ready.
 * Never throws if Firebase resources (from google-services.json) are unavailable.
 */
fun ensureFirebaseInitialized(context: Context): Boolean {
    val appContext = context.applicationContext ?: context
    synchronized(initLock) {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }
        val options = FirebaseOptions.fromResource(appContext)
        if (options == null) {
            Log.e(
                TAG,
                "FirebaseOptions.fromResource returned null. Ensure app/google-services.json is present in the build."
            )
            return false
        }
        val app = FirebaseApp.initializeApp(appContext, options)
        return app != null || FirebaseApp.getApps(appContext).isNotEmpty()
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    try {
        if (!ensureFirebaseInitialized(appContext)) {
            Log.e(TAG, "Skipping App Check setup because default FirebaseApp could not be initialized.")
            return
        }

        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val metaPrefs = appContext.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
        val debugPrefs = appContext.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        val tokenPrefs = appContext.getSharedPreferences(
            "$TOKEN_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )

        val intentDebugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()?.takeIf { it.isNotEmpty() }
        if (BuildConfig.DEBUG && intentDebugToken != null) {
            debugPrefs.edit()
                .putString(DEBUG_SECRET_KEY, intentDebugToken)
                .commit()
            metaPrefs.edit()
                .putString("$META_HARNESS_TOKEN_PREFIX$persistenceKey", intentDebugToken)
                .putBoolean("$META_RECAPTCHA_MIGRATED_PREFIX$persistenceKey", false)
                .commit()
            Log.d(TAG, "App Check harness debug secret configured synchronously.")
        }

        val savedHarnessToken = metaPrefs
            .getString("$META_HARNESS_TOKEN_PREFIX$persistenceKey", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val recaptchaSiteKey = resolveRecaptchaSiteKey(appContext)
        val isRobolectric = "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)

        val targetMode = resolveAppCheckMode(
            isDebugBuild = BuildConfig.DEBUG,
            intentDebugToken = intentDebugToken,
            savedHarnessToken = savedHarnessToken,
            recaptchaSiteKey = recaptchaSiteKey,
            isRobolectric = isRobolectric
        )

        val appCheck = FirebaseAppCheck.getInstance()
        when (targetMode) {
            AppCheckProviderMode.DEBUG -> {
                val effectiveSecret = intentDebugToken ?: savedHarnessToken
                if (effectiveSecret != null && debugPrefs.getString(DEBUG_SECRET_KEY, null) != effectiveSecret) {
                    debugPrefs.edit()
                        .putString(DEBUG_SECRET_KEY, effectiveSecret)
                        .commit()
                }
                val secretChanged = effectiveSecret != null && installedDebugSecret != null && installedDebugSecret != effectiveSecret
                val modeChanged = installedMode != null && installedMode != AppCheckProviderMode.DEBUG
                if (installedMode != AppCheckProviderMode.DEBUG || (intentDebugToken != null && installedDebugSecret != intentDebugToken)) {
                    if (secretChanged || modeChanged) {
                        tokenPrefs.edit().clear().commit()
                    }
                    appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
                    installedMode = AppCheckProviderMode.DEBUG
                    installedDebugSecret = effectiveSecret
                    if (secretChanged || modeChanged) {
                        appCheck.getAppCheckToken(true)
                    } else if (!isRobolectric) {
                        appCheck.getAppCheckToken(false)
                    }
                    Log.d(TAG, "DebugAppCheckProviderFactory installed.")
                }
            }

            AppCheckProviderMode.RECAPTCHA -> {
                if (recaptchaSiteKey == null) {
                    Log.e(TAG, "recaptcha_site_key string resource not found or blank in package.")
                    return
                }
                val migratedKey = "$META_RECAPTCHA_MIGRATED_PREFIX$persistenceKey"
                if (!metaPrefs.getBoolean(migratedKey, false)) {
                    // Remove any unregistered random UUID / invalid token left behind by an earlier debug launch.
                    debugPrefs.edit().clear().commit()
                    tokenPrefs.edit().clear().commit()
                    metaPrefs.edit().putBoolean(migratedKey, true).commit()
                }
                if (installedMode != AppCheckProviderMode.RECAPTCHA) {
                    appCheck.installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(recaptchaSiteKey)
                    )
                    installedMode = AppCheckProviderMode.RECAPTCHA
                    installedDebugSecret = null
                    if (!isRobolectric) {
                        appCheck.getAppCheckToken(false)
                    }
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
