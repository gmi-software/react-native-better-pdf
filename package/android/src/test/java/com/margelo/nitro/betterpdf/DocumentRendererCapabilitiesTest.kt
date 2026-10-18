package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.DocumentRenderer.Companion.hasNewerRendererApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRendererCapabilitiesTest {
  @Test
  fun `Android 15 and later have it whatever the extension`() {
    assertTrue(hasNewerRendererApi(sdk = 35, sExtension = 13))
    assertTrue(hasNewerRendererApi(sdk = 37, sExtension = 0))
  }

  @Test
  fun `Android 12 to 14 have it from SDK extension 13`() {
    assertTrue(hasNewerRendererApi(sdk = 31, sExtension = 13))
    assertTrue(hasNewerRendererApi(sdk = 33, sExtension = 13))
    assertTrue(hasNewerRendererApi(sdk = 34, sExtension = 15))
    assertFalse(hasNewerRendererApi(sdk = 33, sExtension = 7))
    assertFalse(hasNewerRendererApi(sdk = 34, sExtension = 12))
  }

  @Test
  fun `Android 11 and earlier never have it`() {
    assertFalse(hasNewerRendererApi(sdk = 30, sExtension = 13))
    assertFalse(hasNewerRendererApi(sdk = 24, sExtension = 0))
  }
}
