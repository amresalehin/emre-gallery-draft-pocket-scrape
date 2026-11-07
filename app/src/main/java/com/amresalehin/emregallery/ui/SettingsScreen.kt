package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.data.PreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    total: Int,
    theme: String,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onTheme: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: (String) -> Unit,
    onPermissionSettings: () -> Unit,
    onMenuClick: () -> Unit,
    prefs: PreferencesRepository? = null,
    onShowSnackbar: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val actualPrefs = prefs ?: remember {
        PreferencesRepository(context.applicationContext)
    }
    var query by remember { mutableStateOf("") }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val matchesTheme = query.isBlank() ||
        "theme".contains(query, ignoreCase = true) ||
        "dark".contains(query, ignoreCase = true) ||
        "light".contains(query, ignoreCase = true) ||
        "mode".contains(query, ignoreCase = true)

    val matchesAi = query.isBlank() ||
        "ai configuration".contains(query, ignoreCase = true) ||
        "cloud".contains(query, ignoreCase = true) ||
        "local".contains(query, ignoreCase = true) ||
        "florence".contains(query, ignoreCase = true) ||
        "model".contains(query, ignoreCase = true) ||
        "provider".contains(query, ignoreCase = true)

    val otherEntries = listOf(
        "Indexing Status" to Icons.Default.Storage,
        "About" to Icons.Default.Info
    ).filter { it.first.contains(query, ignoreCase = true) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                top = topContentPadding + 64.dp,
                bottom = bottomContentPadding
            )
        ) {
            // 1. THEME CARD (Moved to the very top with sleek Welcome Screen aesthetics)
            if (matchesTheme) {
                item(key = "theme_card") {
                    AestheticThemeCard(
                        theme = theme,
                        isDark = isDark,
                        onTheme = onTheme
                    )
                }
            }

            // 2. AI CONFIGURATION CARD
            if (matchesAi) {
                item(key = "ai_configuration_card") {
                    AiConfigurationCard(
                        prefs = actualPrefs,
                        onShowSnackbar = onShowSnackbar
                    )
                }
            }

            // 3. OTHER SETTINGS TILES ("Indexing Status", "About")
            items(otherEntries, key = { it.first }) { (label, icon) ->
                AestheticSettingsTile(
                    label = label,
                    icon = icon,
                    isDark = isDark,
                    onClick = {
                        if (label == "Indexing Status") onRefresh()
                        onSettings(label)
                    }
                )
            }

            // Empty search state
            if (!matchesTheme && !matchesAi && otherEntries.isEmpty() && query.isNotBlank()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.SearchOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No settings found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "No settings match \"$query\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Floating Search Bar at Top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open navigation menu",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search settings",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "Search settings…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ultra-aesthetic Theme card matching the Welcome Screen cards design in a single sleek row.
 * Features 26.dp rounded corners, frosted/theme-adaptive surface, circular viewfinder halo avatar,
 * and a compact 3-option capsule switch on the right (System | Light | Dark).
 */
@Composable
private fun AestheticThemeCard(
    theme: String,
    isDark: Boolean,
    onTheme: (String) -> Unit
) {
    val cardBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        Color(0x75F5F7FA)
    }

    val cardBorder = BorderStroke(
        1.dp,
        if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        else Color.White.copy(alpha = 0.65f)
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cardBackground,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_theme_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Circular Halo Avatar + Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Circular Halo Avatar with Signature Viewfinder Corners
                Surface(
                    shape = CircleShape,
                    color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else Color.White.copy(alpha = 0.60f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ThemeSignatureIcon(tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            // Right: Compact 3-way Segmented Capsule (System | Light | Dark)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.height(38.dp)
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val isSystemSelected = theme != "light" && theme != "dark"
                    val isLightSelected = theme == "light"
                    val isDarkSelected = theme == "dark"

                    SingleRowThemeOption(
                        icon = Icons.Default.BrightnessAuto,
                        label = "System",
                        isSelected = isSystemSelected,
                        isDark = isDark,
                        onClick = { onTheme("system") },
                        testTag = "settings_theme_system"
                    )

                    SingleRowThemeOption(
                        icon = Icons.Default.LightMode,
                        label = "Light",
                        isSelected = isLightSelected,
                        isDark = isDark,
                        onClick = { onTheme("light") },
                        testTag = "settings_theme_light"
                    )

                    SingleRowThemeOption(
                        icon = Icons.Default.DarkMode,
                        label = "Dark",
                        isSelected = isDarkSelected,
                        isDark = isDark,
                        onClick = { onTheme("dark") },
                        testTag = "settings_theme_dark"
                    )
                }
            }
        }
    }
}

/**
 * Signature Theme Icon with Emre's iconic 4 viewfinder corners and central palette.
 */
@Composable
private fun ThemeSignatureIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF181C22)
) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.5.dp.toPx()
            val w = size.width
            val h = size.height

            // Top-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, cornerLength), strokeWidth)

            // Top-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, cornerLength), strokeWidth)

            // Bottom-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - cornerLength), strokeWidth)

            // Bottom-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - cornerLength), strokeWidth)
        }
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
    }
}

/**
 * Compact circular theme option button for the single-row Theme card.
 */
@Composable
private fun SingleRowThemeOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isSelected) {
            if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .size(32.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) {
                    if (isDark) MaterialTheme.colorScheme.onPrimary else Color.White
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Aesthetic Settings Tile for Indexing Status, About, etc. matching the 26.dp rounded card style.
 */
@Composable
private fun AestheticSettingsTile(
    label: String,
    icon: ImageVector,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val cardBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        Color(0x75F5F7FA)
    }

    val cardBorder = BorderStroke(
        1.dp,
        if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        else Color.White.copy(alpha = 0.65f)
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = cardBackground,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_tile_${label.lowercase().replace(" ", "_")}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.60f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                val subtitle = when (label) {
                    "Indexing Status" -> "Local MediaStore scan & OCR status"
                    "About" -> "Emre Gallery · 0.1.0"
                    else -> ""
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
