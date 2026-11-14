package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AppCheckProviderMode
import com.example.ai.configureAppCheck
import com.example.ai.ensureFirebaseInitialized
import com.example.ai.isAppCheckConfigured
import com.example.ai.resolveAppCheckMode
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Moo Moo", appName)
  }

  @Test
  fun `firebase initialization and app check setup succeed safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "test-debug-secret-123")
    configureAppCheck(context, intent)
    assertTrue(ensureFirebaseInitialized(context))
    assertTrue(isAppCheckConfigured())

    val persistenceKey = FirebaseApp.getInstance().persistenceKey
    val debugPrefs = context.getSharedPreferences(
      "com.google.firebase.appcheck.debug.store.$persistenceKey",
      Context.MODE_PRIVATE
    )
    assertEquals(
      "test-debug-secret-123",
      debugPrefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
    )
  }

  @Test
  fun `resolveAppCheckMode selects recaptcha on real device debug apk without harness token`() {
    assertEquals(
      AppCheckProviderMode.RECAPTCHA,
      resolveAppCheckMode(
        isDebugBuild = true,
        intentDebugToken = null,
        savedHarnessToken = null,
        recaptchaSiteKey = "6LeOYx0uAAAAAPTm5_ZtWp2Ix8lNma_2al0AuAVp",
        isRobolectric = false
      )
    )
    assertEquals(
      AppCheckProviderMode.DEBUG,
      resolveAppCheckMode(
        isDebugBuild = true,
        intentDebugToken = "harness-token",
        savedHarnessToken = null,
        recaptchaSiteKey = "6LeOYx0uAAAAAPTm5_ZtWp2Ix8lNma_2al0AuAVp",
        isRobolectric = false
      )
    )
    assertEquals(
      AppCheckProviderMode.DEBUG,
      resolveAppCheckMode(
        isDebugBuild = true,
        intentDebugToken = null,
        savedHarnessToken = "saved-harness-token",
        recaptchaSiteKey = "6LeOYx0uAAAAAPTm5_ZtWp2Ix8lNma_2al0AuAVp",
        isRobolectric = false
      )
    )
    assertEquals(
      AppCheckProviderMode.RECAPTCHA,
      resolveAppCheckMode(
        isDebugBuild = false,
        intentDebugToken = "ignored-in-release",
        savedHarnessToken = "ignored-in-release",
        recaptchaSiteKey = "6LeOYx0uAAAAAPTm5_ZtWp2Ix8lNma_2al0AuAVp",
        isRobolectric = false
      )
    )
  }
}
