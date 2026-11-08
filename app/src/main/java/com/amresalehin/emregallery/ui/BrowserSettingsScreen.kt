package com.amresalehin.emregallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.BrowserAddressBarPosition
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.BrowserSettingsRepository
import com.amresalehin.emregallery.data.BrowserStartupBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsScreen(
    repository: BrowserSettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by repository.settings.collectAsState()
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("browser_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Browser settings", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Configure your browsing experience",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("browser_settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 18.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { BrowserSettingsSection("1. General", "Startup, search and address bar") }
            item {
                BrowserChoiceRow(
                    title = "Startup behaviour",
                    summary = "Choose what opens when the browser starts",
                    selected = settings.startupBehavior,
                    options = BrowserStartupBehavior.values().map { it to it.label },
                    onSelected = repository::setStartupBehavior,
                    testTag = "browser_startup_behavior"
                )
            }
            item {
                BrowserChoiceRow(
                    title = "Default search engine",
                    summary = "Used for searches entered in the address bar",
                    selected = settings.searchEngine,
                    options = listOf(
                        BrowserSearchEngine.GOOGLE to "Google",
                        BrowserSearchEngine.BING to "Bing",
                        BrowserSearchEngine.DUCKDUCKGO to "DuckDuckGo",
                        BrowserSearchEngine.CUSTOM to "Custom"
                    ),
                    onSelected = repository::setSearchEngine,
                    testTag = "browser_search_engine"
                )
            }
            if (settings.searchEngine == BrowserSearchEngine.CUSTOM) {
                item {
                    OutlinedTextField(
                        value = settings.customSearchUrl,
                        onValueChange = repository::setCustomSearchUrl,
                        modifier = Modifier.fillMaxWidth().testTag("browser_custom_search_url"),
                        singleLine = true,
                        label = { Text("Search URL template") },
                        supportingText = { Text("Use %s where the search terms should be inserted.") }
                    )
                }
            }
            item {
                BrowserChoiceRow(
                    title = "Address bar position",
                    summary = "Place the browser toolbar at the top or bottom",
                    selected = settings.addressBarPosition,
                    options = BrowserAddressBarPosition.values().map { it to it.label },
                    onSelected = repository::setAddressBarPosition,
                    testTag = "browser_address_bar_position"
                )
            }
            item {
                BrowserInfoRow(
                    title = "Open links",
                    value = "Current tab",
                    detail = "New-tab and background-tab opening require the multi-tab browser session, which is not implemented yet."
                )
            }
            item {
                BrowserInfoRow(
                    title = "External links",
                    value = "Android app links",
                    detail = "Supported intent, telephone, email and other app links are handed to Android automatically."
                )
            }

            item { BrowserSettingsSection("2. Tabs & Navigation", "Tab controls and navigation gestures") }
            item {
                BrowserInfoRow(
                    title = "Tab management",
                    value = "Single-tab mode",
                    detail = "Background tabs, restore closed tabs, tab grouping, per-tab desktop mode, pull-to-refresh and swipe-between-tabs are not supported by the current browser yet."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Close confirmation",
                    value = "Not applicable",
                    detail = "The current browser keeps one active tab, so there is no close-all-tabs action."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Inactive tab discard",
                    value = "Not applicable",
                    detail = "Automatic tab discarding will be configurable when multiple browser tabs are supported."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Back navigation",
                    value = "Web history",
                    detail = "Android back navigates through the current page history; a separate edge-swipe gesture is not enabled."
                )
            }

            item { BrowserSettingsSection("3. Privacy & Security", "Website behaviour and browser protection") }
            item {
                BrowserSwitchRow(
                    title = "Safe browsing",
                    summary = "Use Android WebView Safe Browsing protection",
                    checked = settings.safeBrowsingEnabled,
                    onCheckedChange = repository::setSafeBrowsingEnabled,
                    testTag = "browser_safe_browsing"
                )
            }
            item {
                BrowserSwitchRow(
                    title = "Block third-party cookies",
                    summary = "Restrict cookies set by embedded third-party sites",
                    checked = settings.blockThirdPartyCookies,
                    onCheckedChange = repository::setBlockThirdPartyCookies,
                    testTag = "browser_block_third_party_cookies"
                )
            }

            item { BrowserSettingsSection("4. Content & Media", "Control website rendering and media discovery") }
            item {
                BrowserSwitchRow(
                    title = "JavaScript",
                    summary = "Required by many modern websites",
                    checked = settings.javaScriptEnabled,
                    onCheckedChange = repository::setJavaScriptEnabled,
                    testTag = "browser_javascript"
                )
            }
            item {
                BrowserSwitchRow(
                    title = "Load images automatically",
                    summary = "Allow websites to load image resources",
                    checked = settings.loadImagesAutomatically,
                    onCheckedChange = repository::setLoadImagesAutomatically,
                    testTag = "browser_load_images"
                )
            }
            item {
                BrowserSwitchRow(
                    title = "Allow media autoplay",
                    summary = "Allow audio and video to play without a tap",
                    checked = settings.allowAutoplay,
                    onCheckedChange = repository::setAllowAutoplay,
                    testTag = "browser_allow_autoplay"
                )
            }
            item {
                BrowserSwitchRow(
                    title = "Media discovery",
                    summary = "Detect media resources while browsing; this never starts downloads",
                    checked = settings.mediaDiscoveryEnabled,
                    onCheckedChange = repository::setMediaDiscoveryEnabled,
                    testTag = "browser_media_discovery"
                )
            }

            item { BrowserSettingsSection("5. Appearance", "Adjust browser controls and website readability") }
            item {
                BrowserSwitchRow(
                    title = "Show loading progress",
                    summary = "Display the page-load progress line",
                    checked = settings.showLoadingProgress,
                    onCheckedChange = repository::setShowLoadingProgress,
                    testTag = "browser_show_loading_progress"
                )
            }
            item {
                BrowserSwitchRow(
                    title = "Show security indicator",
                    summary = "Display the lock icon for HTTPS pages",
                    checked = settings.showSecurityIndicator,
                    onCheckedChange = repository::setShowSecurityIndicator,
                    testTag = "browser_show_security_indicator"
                )
            }
            item {
                BrowserChoiceRow(
                    title = "Website text size",
                    summary = "Change the default text scale for websites",
                    selected = settings.websiteTextZoom,
                    options = listOf(75, 85, 100, 110, 125, 150).map { it to "$it%" },
                    onSelected = repository::setWebsiteTextZoom,
                    testTag = "browser_website_text_zoom"
                )
            }
            item {
                BrowserChoiceRow(
                    title = "Website zoom",
                    summary = "Set the initial scale applied to pages",
                    selected = settings.websiteInitialScale,
                    options = listOf(50, 75, 90, 100, 110, 125, 150).map { it to "$it%" },
                    onSelected = repository::setWebsiteInitialScale,
                    testTag = "browser_website_zoom"
                )
            }
            item {
                BrowserInfoRow(
                    title = "Theme",
                    value = "Follows Emre",
                    detail = "The browser inherits Emre's Light, Dark or System appearance setting."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Website favicon and page title",
                    value = "Not shown",
                    detail = "The current compact toolbar does not expose these page-identity elements yet."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Full-screen browsing",
                    value = "Not available",
                    detail = "Immersive browsing and hiding Android system bars are not implemented yet."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Toolbar layout",
                    value = "Position",
                    detail = "The top/bottom selector moves the entire navigation toolbar with the page content occupying the space between."
                )
            }
            item {
                BrowserInfoRow(
                    title = "Website dark mode",
                    value = "Not yet available",
                    detail = "Force-dark rendering is not exposed until it can be applied consistently across supported Android WebView versions."
                )
            }

            item { BrowserSettingsSection("6. About", "Browser engine information") }
            item {
                BrowserInfoRow(
                    title = "Rendering engine",
                    value = "Android System WebView",
                    detail = "The engine is supplied and updated by Android. Emre does not bundle a second browser engine."
                )
            }
        }
    }
}

@Composable
private fun BrowserSettingsSection(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T : Any> BrowserChoiceRow(
    title: String,
    summary: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    testTag: String
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()

    Column {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 13.dp).testTag(testTag),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Text(selectedLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ExpandMore, contentDescription = "Choose $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        },
                        trailingIcon = if (value == selected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected") }
                        } else null
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun BrowserSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 11.dp).testTag(testTag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun BrowserInfoRow(title: String, value: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}
