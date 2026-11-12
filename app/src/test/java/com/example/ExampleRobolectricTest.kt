package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.configureAppCheck
import com.example.ai.ensureFirebaseInitialized
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
    configureAppCheck(context, null)
    assertTrue(ensureFirebaseInitialized(context))
  }
}
