package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.16.1 ne fournit pas d'image système pour l'API 36 (UnsupportedOperationException
// dans DefaultSdkProvider). L'API 34 est la plus récente réellement supportée ici ; la valeur ne
// change rien à ce que le test vérifie.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppResourcesTest {

  @Test
  fun `nom de l application expose par les ressources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("NEXUS", appName)
  }
}
