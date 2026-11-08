package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.BrowserAddressBarPosition
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.BrowserSettingsRepository
import com.amresalehin.emregallery.data.BrowserStartupBehavior
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSettingsRepositoryTest {
    @Test
    fun defaultsToGoogleAndTopToolbar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("emre_browser_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val settings = BrowserSettingsRepository(context).settings.value

        assertEquals(BrowserSearchEngine.GOOGLE, settings.searchEngine)
        assertEquals(BrowserAddressBarPosition.TOP, settings.addressBarPosition)
        assertEquals(BrowserStartupBehavior.HOMEPAGE, settings.startupBehavior)
    }


    @Test
    fun customSearchUrlReplacesTheSearchPlaceholder() {
        val actual = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl(
            "hello world",
            BrowserSearchEngine.CUSTOM,
            "https://search.example/find?term=%s"
        )

        assertEquals("https://search.example/find?term=hello+world", actual)
    }

    @Test
    fun persistsSearchEngineAndToolbarPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("emre_browser_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()

        BrowserSettingsRepository(context).apply {
            setSearchEngine(BrowserSearchEngine.BING)
            setAddressBarPosition(BrowserAddressBarPosition.BOTTOM)
        }

        val restored = BrowserSettingsRepository(context).settings.value
        assertEquals(BrowserSearchEngine.BING, restored.searchEngine)
        assertEquals(BrowserAddressBarPosition.BOTTOM, restored.addressBarPosition)
    }
}
