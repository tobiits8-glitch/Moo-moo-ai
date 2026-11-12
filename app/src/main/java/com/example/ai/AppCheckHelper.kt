package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

private val initLock = Any()
private val appCheckInstalled = AtomicBoolean(false)

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

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else if (!appCheckInstalled.get()) {
                Log.d(TAG, "No debug token found in Intent extra; using standard debug App Check provider.")
            }

            if (appCheckInstalled.compareAndSet(false, true) || !debugToken.isNullOrEmpty()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed.")
            }
        } else {
            if (appCheckInstalled.compareAndSet(false, true)) {
                val resId = appContext.resources.getIdentifier(
                    RECAPTCHA_SITE_KEY_RES,
                    "string",
                    appContext.packageName
                )
                if (resId != 0) {
                    val siteKey = appContext.getString(resId)
                    if (siteKey.isNotBlank()) {
                        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                        )
                        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                    } else {
                        Log.e(TAG, "recaptcha_site_key resource string is blank.")
                    }
                } else {
                    Log.e(TAG, "recaptcha_site_key string resource not found in package.")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
