package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    assertEquals("SubTranslate", appName)
  }

  @Test
  fun `verify audio capture service intent actions and resolution`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("com.example.subtranslate.ACTION_START_AUDIO", com.example.service.AudioCaptureService.ACTION_START)
    assertEquals("com.example.subtranslate.ACTION_STOP_AUDIO", com.example.service.AudioCaptureService.ACTION_STOP)
    assertEquals("com.example.subtranslate.ACTION_PAUSE_RESUME_AUDIO", com.example.service.AudioCaptureService.ACTION_PAUSE_RESUME)

    val intent = android.content.Intent(context, com.example.service.AudioCaptureService::class.java).apply {
      action = com.example.service.AudioCaptureService.ACTION_START
    }
    org.junit.Assert.assertNotNull(intent.component)
    assertEquals(com.example.service.AudioCaptureService::class.java.name, intent.component?.className)
  }
}
