package io.github.trevarj.motd.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.systemPartner
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.FONT_SCALE_STEP_PERCENT
import io.github.trevarj.motd.data.prefs.MAX_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.MIN_FONT_SCALE_PERCENT
import io.github.trevarj.motd.ui.chat.ChatWallpaperPicker
import io.github.trevarj.motd.ui.components.IrcChannelBadge
import io.github.trevarj.motd.ui.components.IrcSpriteAvatar
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.fontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.rememberAppFontFamily
import java.io.File
import kotlin.math.roundToInt

/** Appearance category: theme, dynamic color, layout density, avatar style, nick colors, wallpaper. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNickColors: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importFailedMessage = stringResource(R.string.settings_font_custom_invalid)
    // Success is already visible in the picker row (it selects and shows the file name); only the
    // failure case needs a transient nudge, mirroring the audio-cache-clear Toast pattern.
    LaunchedEffect(viewModel, context, importFailedMessage) {
        viewModel.customFontImportEvents.collect { event ->
            if (event == CustomFontImportEvent.FAILED) {
                Toast.makeText(context, importFailedMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
    AppearanceSettingsContent(
        settings = state.settings,
        appearance = state.appearance,
        customFontFile = viewModel.customFontFile,
        onBack = onBack,
        onOpenNickColors = onOpenNickColors,
        onThemePreset = viewModel::setThemePreset,
        onTrueBlack = viewModel::setTrueBlack,
        onFollowSystem = viewModel::setFollowSystem,
        onDynamicColor = viewModel::setDynamicColor,
        onLayoutDensity = viewModel::setLayoutDensity,
        onAvatarStyle = viewModel::setAvatarStyle,
        onNickColorsEnabled = viewModel::setNickColorsEnabled,
        onNickColorPalette = viewModel::setNickColorPalette,
        onWallpaper = viewModel::setWallpaper,
        onUiFontScale = viewModel::setUiFontScale,
        onConversationFontScale = viewModel::setConversationFontScale,
        onFontChoice = viewModel::setFontChoice,
        onImportCustomFont = viewModel::importCustomFont,
        onShowTimestamps = viewModel::setShowTimestamps,
        onTimeFormat = viewModel::setTimeFormat,
        onMessageSpacing = viewModel::setMessageSpacing,
        onBubbleCornerStyle = viewModel::setBubbleCornerStyle,
    )
}

@Composable
fun AppearanceSettingsContent(
    settings: Settings,
    appearance: io.github.trevarj.motd.data.prefs.AppearanceConfig,
    onBack: () -> Unit,
    onOpenNickColors: () -> Unit,
    onThemePreset: (ColorThemePreset) -> Unit,
    onTrueBlack: (Boolean) -> Unit,
    onFollowSystem: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLayoutDensity: (LayoutDensity) -> Unit,
    onAvatarStyle: (AvatarStyle) -> Unit,
    onNickColorsEnabled: (Boolean) -> Unit,
    onNickColorPalette: (NickColorPalette) -> Unit,
    onWallpaper: (io.github.trevarj.motd.data.prefs.WallpaperSelection) -> Unit,
    onUiFontScale: (Int) -> Unit,
    onConversationFontScale: (Int) -> Unit,
    onFontChoice: (FontChoice) -> Unit,
    onShowTimestamps: (Boolean) -> Unit,
    onTimeFormat: (TimeFormat) -> Unit,
    onMessageSpacing: (io.github.trevarj.motd.data.prefs.MessageSpacing) -> Unit,
    onBubbleCornerStyle: (io.github.trevarj.motd.data.prefs.BubbleCornerStyle) -> Unit,
    customFontFile: File? = null,
    onImportCustomFont: (Uri) -> Unit = {},
) {
    var showThemeSheet by rememberSaveable { mutableStateOf(false) }
    var showFontSheet by rememberSaveable { mutableStateOf(false) }
    val followSystemAvailable = appearance.theme.systemPartner != null
    val trueBlackAvailable = appearance.theme == ColorThemePreset.SYSTEM ||
        appearance.theme.isDark || (appearance.followSystem && followSystemAvailable)
    val dynamicColorAvailable = appearance.theme == ColorThemePreset.SYSTEM
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_theme_section)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_theme),
                value = themePresetLabel(appearance.theme),
                onClick = { showThemeSheet = true },
                modifier = Modifier.testTag("settings_theme_picker"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_follow_system),
                subtitle = stringResource(
                    when {
                        appearance.theme == ColorThemePreset.SYSTEM -> R.string.settings_follow_system_system_desc
                        followSystemAvailable -> R.string.settings_follow_system_desc
                        else -> R.string.settings_follow_system_unavailable_desc
                    },
                ),
                checked = appearance.followSystem,
                onCheckedChange = onFollowSystem,
                switchTag = "settings_switch_follow_system",
                enabled = followSystemAvailable,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_true_black),
                subtitle = stringResource(
                    when {
                        appearance.theme == ColorThemePreset.SYSTEM -> R.string.settings_true_black_system_desc
                        trueBlackAvailable -> R.string.settings_true_black_desc
                        appearance.trueBlack -> R.string.settings_true_black_saved_desc
                        else -> R.string.settings_true_black_unavailable_desc
                    },
                ),
                checked = appearance.trueBlack,
                onCheckedChange = onTrueBlack,
                switchTag = "settings_switch_true_black",
                enabled = trueBlackAvailable,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (dynamicColorAvailable) R.string.settings_dynamic_color_desc
                    else R.string.settings_dynamic_color_unavailable,
                ),
                checked = settings.dynamicColor && dynamicColorAvailable,
                onCheckedChange = onDynamicColor,
                switchTag = "settings_switch_dynamic_color",
                enabled = dynamicColorAvailable,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_nick_colors),
                subtitle = stringResource(R.string.settings_nick_colors_desc),
                checked = settings.nickColorsEnabled,
                onCheckedChange = onNickColorsEnabled,
                switchTag = "settings_switch_nick_colors",
            )
            PaletteGroup(current = settings.nickColorPalette, enabled = settings.nickColorsEnabled, onSelect = onNickColorPalette)
            SettingsNavigationRow(
                icon = Icons.Outlined.ColorLens,
                title = stringResource(R.string.settings_nick_color_overrides),
                value = pluralStringResource(
                    R.plurals.settings_nick_count,
                    settings.nickColorOverrides.size,
                    settings.nickColorOverrides.size,
                ),
                modifier = Modifier.testTag("settings_nick_color_overrides"),
                onClick = onOpenNickColors,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_layout_section)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.TextFields,
                title = stringResource(R.string.settings_app_font),
                value = fontChoiceLabel(appearance.fontChoice),
                onClick = { showFontSheet = true },
                modifier = Modifier.testTag("settings_font_picker"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            FontScaleSlider(
                title = stringResource(R.string.settings_ui_font_size),
                description = stringResource(R.string.settings_ui_font_size_desc),
                value = appearance.uiFontScalePercent,
                tag = "settings_ui_font_scale",
                onValue = onUiFontScale,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            FontScaleSlider(
                title = stringResource(R.string.settings_conversation_font_size),
                description = stringResource(R.string.settings_conversation_font_size_desc),
                value = appearance.conversationFontScalePercent,
                tag = "settings_conversation_font_scale",
                onValue = onConversationFontScale,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_density))
            DensityGroup(current = settings.layoutDensity, onSelect = onLayoutDensity)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_avatar_style))
            AvatarStyleGroup(current = settings.avatarStyle, onSelect = onAvatarStyle)
        }
        SettingsGroup(title = stringResource(R.string.settings_appearance_messages_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_show_timestamps),
                subtitle = stringResource(R.string.settings_show_timestamps_desc),
                checked = appearance.showTimestamps,
                onCheckedChange = onShowTimestamps,
                switchTag = "settings_switch_show_timestamps",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_time_format))
            TimeFormatGroup(current = appearance.timeFormat, onSelect = onTimeFormat)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_message_spacing))
            MessageSpacingGroup(current = appearance.messageSpacing, onSelect = onMessageSpacing)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_bubble_corners))
            BubbleCornerStyleGroup(current = appearance.bubbleCornerStyle, onSelect = onBubbleCornerStyle)
        }
        SettingsGroup(title = stringResource(R.string.settings_wallpaper)) {
            ChatWallpaperPicker(current = appearance.wallpaper, onApply = onWallpaper)
        }
    }
    if (showThemeSheet) {
        ThemePickerSheet(
            current = appearance.theme,
            trueBlack = appearance.trueBlack,
            dynamicColor = settings.dynamicColor,
            onSelect = onThemePreset,
            onDismiss = { showThemeSheet = false },
        )
    }
    if (showFontSheet) {
        FontPickerSheet(
            current = appearance.fontChoice,
            customFontName = appearance.customFontName,
            customFontFile = customFontFile,
            onSelect = onFontChoice,
            onImportCustomFont = onImportCustomFont,
            onDismiss = { showFontSheet = false },
        )
    }
}

@Composable
private fun FontScaleSlider(
    title: String,
    description: String,
    value: Int,
    tag: String,
    onValue: (Int) -> Unit,
) {
    var pending by remember(value) { mutableStateOf(value.toFloat()) }
    val displayed = pending.toInt()
    val percent = stringResource(R.string.settings_font_size_percent, displayed)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(percent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pending,
            onValueChange = { raw ->
                pending = (raw / FONT_SCALE_STEP_PERCENT).roundToInt() * FONT_SCALE_STEP_PERCENT.toFloat()
            },
            onValueChangeFinished = { onValue(pending.toInt()) },
            valueRange = MIN_FONT_SCALE_PERCENT.toFloat()..MAX_FONT_SCALE_PERCENT.toFloat(),
            steps = (MAX_FONT_SCALE_PERCENT - MIN_FONT_SCALE_PERCENT) / FONT_SCALE_STEP_PERCENT - 1,
            modifier = Modifier
                .testTag(tag)
                .semantics {
                    contentDescription = title
                    stateDescription = percent
                },
        )
        // The threshold flips repeatedly while the slider is dragged; ease the reset button's row
        // in and out so the content below doesn't jump under the user's finger.
        AnimatedVisibility(
            visible = displayed != DEFAULT_FONT_SCALE_PERCENT,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) {
            TextButton(
                onClick = {
                    pending = DEFAULT_FONT_SCALE_PERCENT.toFloat()
                    onValue(DEFAULT_FONT_SCALE_PERCENT)
                },
            ) {
                Text(stringResource(R.string.settings_font_size_reset))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThemePickerSheet(
    current: ColorThemePreset,
    trueBlack: Boolean,
    dynamicColor: Boolean,
    onSelect: (ColorThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    fun filtered(items: List<ColorThemePreset>) = items.filter {
        themePresetLabelText(it).lowercase().contains(normalized)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_theme_sheet")) {
        SheetSystemBars()
        LazyColumn(
            Modifier.testTag("settings_theme_list").selectableGroup().heightIn(max = 680.dp).padding(bottom = 24.dp),
        ) {
            item {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.settings_theme_search)) },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_theme_search"),
                )
            }
            val groups = listOf(
                R.string.settings_theme_system_group to filtered(listOf(ColorThemePreset.SYSTEM)),
                R.string.settings_theme_light_group to filtered(LIGHT_THEME_PRESETS),
                R.string.settings_theme_dark_group to filtered(DARK_THEME_PRESETS),
            )
            groups.forEach { (title, modes) ->
                if (modes.isNotEmpty()) {
                    item { SubLabel(stringResource(title)) }
                    items(modes.size) { index ->
                        val mode = modes[index]
                        ThemeRadioRow(
                            mode,
                            current == mode,
                            trueBlack,
                            dynamicColor,
                            onSelect,
                        )
                    }
                }
            }
            if (groups.all { it.second.isEmpty() }) {
                item { Text(stringResource(R.string.settings_theme_no_results), modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}

@Composable
private fun ThemeRadioRow(
    mode: ColorThemePreset,
    selected: Boolean,
    trueBlack: Boolean,
    dynamicColor: Boolean,
    onSelect: (ColorThemePreset) -> Unit,
) {
    RadioRow(
        label = themePresetLabel(mode),
        selected = selected,
        enabled = true,
        onClick = { onSelect(mode) },
        modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}"),
        trailing = {
            MotdTheme(themePreset = mode, trueBlack = trueBlack, dynamicColor = dynamicColor) {
                val scheme = MaterialTheme.colorScheme
                Surface(
                    color = scheme.background,
                    shape = MotdShapes.tag,
                    border = BorderStroke(1.dp, scheme.outline),
                    modifier = Modifier
                        .width(100.dp)
                        .height(42.dp)
                        .testTag("settings_theme_preview_${mode.name.lowercase()}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Aa", color = scheme.onBackground, style = MaterialTheme.typography.labelSmall)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(0.72f).height(7.dp)
                                    .background(scheme.surfaceContainerHigh, MotdShapes.pill),
                            )
                            Box(
                                Modifier.fillMaxWidth(0.86f).height(7.dp)
                                    .background(scheme.primaryContainer, MotdShapes.pill),
                            )
                            Box(
                                Modifier.fillMaxWidth().height(7.dp)
                                    .background(scheme.secondaryContainer, MotdShapes.pill),
                            )
                        }
                        Box(Modifier.width(5.dp).height(24.dp).background(scheme.tertiary, MotdShapes.pill))
                    }
                }
            }
        },
    )
}

/** Mime types accepted by the custom-font document picker; broad because OEM providers vary. */
private val CUSTOM_FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/*",
    "application/x-font-ttf",
    "application/octet-stream",
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FontPickerSheet(
    current: FontChoice,
    customFontName: String,
    customFontFile: File?,
    onSelect: (FontChoice) -> Unit,
    onImportCustomFont: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportCustomFont)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_font_sheet")) {
        SheetSystemBars()
        Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_app_font), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            FontChoice.entries.forEach { choice ->
                if (choice == FontChoice.CUSTOM) {
                    CustomFontRow(
                        selected = current == choice,
                        customFontName = customFontName,
                        customFontFile = customFontFile,
                        onClick = {
                            if (customFontName.isEmpty()) {
                                launcher.launch(CUSTOM_FONT_MIME_TYPES)
                            } else {
                                onSelect(choice)
                            }
                        },
                        onChange = { launcher.launch(CUSTOM_FONT_MIME_TYPES) },
                    )
                } else {
                    RadioRow(
                        label = fontChoiceLabel(choice),
                        selected = current == choice,
                        enabled = true,
                        onClick = { onSelect(choice) },
                        modifier = Modifier.testTag("settings_font_${choice.name.lowercase()}"),
                        trailing = {
                            Text("Aa 0O1lI", fontFamily = choice.fontFamily())
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomFontRow(
    selected: Boolean,
    customFontName: String,
    customFontFile: File?,
    onClick: () -> Unit,
    onChange: () -> Unit,
) {
    val imported = customFontName.isNotEmpty()
    val previewFamily = rememberAppFontFamily(FontChoice.CUSTOM, customFontFile)
    RadioRow(
        label = stringResource(R.string.settings_font_custom),
        subtitle = if (imported) customFontName else stringResource(R.string.settings_font_custom_none),
        selected = selected,
        enabled = true,
        onClick = onClick,
        modifier = Modifier.testTag("settings_font_custom"),
        trailing = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Aa 0O1lI", fontFamily = previewFamily)
                if (imported) {
                    TextButton(onClick = onChange) {
                        Text(stringResource(R.string.settings_font_custom_change))
                    }
                }
            }
        },
    )
}

@Composable
private fun fontChoiceLabel(choice: FontChoice): String = stringResource(
    when (choice) {
        FontChoice.SYSTEM -> R.string.settings_font_system
        FontChoice.SANS -> R.string.settings_font_sans
        FontChoice.SERIF -> R.string.settings_font_serif
        FontChoice.MONOSPACE -> R.string.settings_font_mono
        FontChoice.JETBRAINS_MONO -> R.string.settings_font_jetbrains_mono
        FontChoice.CUSTOM -> R.string.settings_font_custom
    },
)

@Composable
private fun themePresetLabel(mode: ColorThemePreset): String = stringResource(themePresetLabelRes(mode))

internal fun themePresetLabelText(mode: ColorThemePreset): String = when (mode) {
    ColorThemePreset.SYSTEM -> "System default"
    ColorThemePreset.LIGHT -> "Light"
    ColorThemePreset.DARK -> "Dark"
    ColorThemePreset.AMOLED -> "AMOLED (true black)"
    ColorThemePreset.AYU_DARK -> "Ayu Dark"
    ColorThemePreset.AYU_LIGHT -> "Ayu Light"
    ColorThemePreset.AYU_MIRAGE -> "Ayu Mirage"
    ColorThemePreset.CATPPUCCIN_LATTE -> "Catppuccin Latte"
    ColorThemePreset.CATPPUCCIN_MOCHA -> "Catppuccin Mocha"
    ColorThemePreset.DRACULA -> "Dracula"
    ColorThemePreset.EVERFOREST_DARK -> "Everforest Dark"
    ColorThemePreset.EVERFOREST_LIGHT -> "Everforest Light"
    ColorThemePreset.GRUVBOX_DARK -> "Gruvbox Dark"
    ColorThemePreset.GRUVBOX_LIGHT -> "Gruvbox Light"
    ColorThemePreset.KANAGAWA_DRAGON -> "Kanagawa Dragon"
    ColorThemePreset.KANAGAWA_LOTUS -> "Kanagawa Lotus"
    ColorThemePreset.KANAGAWA_WAVE -> "Kanagawa Wave"
    ColorThemePreset.MODUS_OPERANDI -> "Modus Operandi"
    ColorThemePreset.MODUS_VIVENDI -> "Modus Vivendi"
    ColorThemePreset.MODUS_OPERANDI_TINTED -> "Modus Operandi Tinted"
    ColorThemePreset.MODUS_VIVENDI_TINTED -> "Modus Vivendi Tinted"
    ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> "Modus Operandi Deuteranopia"
    ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> "Modus Vivendi Deuteranopia"
    ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> "Modus Operandi Tritanopia"
    ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> "Modus Vivendi Tritanopia"
    ColorThemePreset.MONOKAI -> "Monokai"
    ColorThemePreset.NORD -> "Nord"
    ColorThemePreset.NORD_LIGHT -> "Nord Light"
    ColorThemePreset.ONE_DARK -> "One Dark"
    ColorThemePreset.ROSE_PINE -> "Rosé Pine"
    ColorThemePreset.ROSE_PINE_DAWN -> "Rosé Pine Dawn"
    ColorThemePreset.ROSE_PINE_MOON -> "Rosé Pine Moon"
    ColorThemePreset.SOLARIZED_DARK -> "Solarized Dark"
    ColorThemePreset.SOLARIZED_LIGHT -> "Solarized Light"
    ColorThemePreset.TOKYO_NIGHT -> "Tokyo Night"
    ColorThemePreset.ZENBURN -> "Zenburn"
}

private fun themePresetLabelRes(mode: ColorThemePreset): Int = when (mode) {
    ColorThemePreset.SYSTEM -> R.string.settings_theme_system
    ColorThemePreset.LIGHT -> R.string.settings_theme_light
    ColorThemePreset.DARK -> R.string.settings_theme_dark
    ColorThemePreset.AMOLED -> R.string.settings_theme_amoled
    ColorThemePreset.AYU_DARK -> R.string.settings_theme_ayu_dark
    ColorThemePreset.AYU_LIGHT -> R.string.settings_theme_ayu_light
    ColorThemePreset.AYU_MIRAGE -> R.string.settings_theme_ayu_mirage
    ColorThemePreset.CATPPUCCIN_LATTE -> R.string.settings_theme_catppuccin_latte
    ColorThemePreset.CATPPUCCIN_MOCHA -> R.string.settings_theme_catppuccin_mocha
    ColorThemePreset.DRACULA -> R.string.settings_theme_dracula
    ColorThemePreset.EVERFOREST_DARK -> R.string.settings_theme_everforest_dark
    ColorThemePreset.EVERFOREST_LIGHT -> R.string.settings_theme_everforest_light
    ColorThemePreset.GRUVBOX_DARK -> R.string.settings_theme_gruvbox_dark
    ColorThemePreset.GRUVBOX_LIGHT -> R.string.settings_theme_gruvbox_light
    ColorThemePreset.KANAGAWA_DRAGON -> R.string.settings_theme_kanagawa_dragon
    ColorThemePreset.KANAGAWA_LOTUS -> R.string.settings_theme_kanagawa_lotus
    ColorThemePreset.KANAGAWA_WAVE -> R.string.settings_theme_kanagawa_wave
    ColorThemePreset.MODUS_OPERANDI -> R.string.settings_theme_modus_operandi
    ColorThemePreset.MODUS_VIVENDI -> R.string.settings_theme_modus_vivendi
    ColorThemePreset.MODUS_OPERANDI_TINTED -> R.string.settings_theme_modus_operandi_tinted
    ColorThemePreset.MODUS_VIVENDI_TINTED -> R.string.settings_theme_modus_vivendi_tinted
    ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> R.string.settings_theme_modus_operandi_deuteranopia
    ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> R.string.settings_theme_modus_vivendi_deuteranopia
    ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> R.string.settings_theme_modus_operandi_tritanopia
    ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> R.string.settings_theme_modus_vivendi_tritanopia
    ColorThemePreset.MONOKAI -> R.string.settings_theme_monokai
    ColorThemePreset.NORD -> R.string.settings_theme_nord
    ColorThemePreset.NORD_LIGHT -> R.string.settings_theme_nord_light
    ColorThemePreset.ONE_DARK -> R.string.settings_theme_one_dark
    ColorThemePreset.ROSE_PINE -> R.string.settings_theme_rose_pine
    ColorThemePreset.ROSE_PINE_DAWN -> R.string.settings_theme_rose_pine_dawn
    ColorThemePreset.ROSE_PINE_MOON -> R.string.settings_theme_rose_pine_moon
    ColorThemePreset.SOLARIZED_DARK -> R.string.settings_theme_solarized_dark
    ColorThemePreset.SOLARIZED_LIGHT -> R.string.settings_theme_solarized_light
    ColorThemePreset.TOKYO_NIGHT -> R.string.settings_theme_tokyo_night
    ColorThemePreset.ZENBURN -> R.string.settings_theme_zenburn
}

internal val LIGHT_THEME_PRESETS = ColorThemePreset.entries
    .filter { !it.isDark && it != ColorThemePreset.SYSTEM }
    .sortedBy(::themePresetLabelText)
internal val DARK_THEME_PRESETS = ColorThemePreset.entries
    .filter { it.isDark && it != ColorThemePreset.AMOLED }
    .sortedBy(::themePresetLabelText)

@Composable
private fun AvatarStyleGroup(current: AvatarStyle, onSelect: (AvatarStyle) -> Unit) {
    val options: List<Triple<AvatarStyle, Int, Int?>> = listOf(
        Triple(AvatarStyle.MONOGRAM, R.string.settings_avatar_monogram, null),
        Triple(AvatarStyle.INITIALS, R.string.settings_avatar_initials, null),
        Triple(
            AvatarStyle.IRC_SPRITE,
            R.string.settings_avatar_irc_sprite,
            R.string.settings_avatar_irc_sprite_desc,
        ),
        Triple(AvatarStyle.NONE, R.string.settings_avatar_none, R.string.settings_avatar_none_desc),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (style, labelRes, subtitleRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = subtitleRes?.let { stringResource(it) },
                selected = current == style,
                enabled = true,
                onClick = { onSelect(style) },
                modifier = Modifier.testTag("settings_avatar_style_${style.name.lowercase()}"),
            )
            if (style == AvatarStyle.IRC_SPRITE) IrcSpriteSampleStrip()
        }
    }
}

/** A static, data-free sample shows both person sprites and contextual channel marks. */
@Composable
private fun IrcSpriteSampleStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp, bottom = 10.dp)
            .testTag("settings_avatar_sprite_preview"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IrcSpriteAvatar(name = "rustacean", size = 30.dp)
        IrcSpriteAvatar(name = "alice", size = 30.dp)
        IrcChannelBadge(name = "#guix", size = 30.dp)
        IrcChannelBadge(name = "#debian", size = 30.dp)
    }
}

@Composable
private fun DensityGroup(current: LayoutDensity, onSelect: (LayoutDensity) -> Unit) {
    // Density selects the message *render style*, not the font size: Compact is classic single-line
    // IRC, Comfortable is chat bubbles, Two-line is a compact avatar+nick+time header over the body.
    // Subtitles spell that out.
    val options = listOf(
        Triple(LayoutDensity.COMPACT, R.string.settings_density_compact, R.string.settings_density_compact_desc),
        Triple(LayoutDensity.COMFORTABLE, R.string.settings_density_comfortable, R.string.settings_density_comfortable_desc),
        Triple(LayoutDensity.TWO_LINE, R.string.settings_density_two_line, R.string.settings_density_two_line_desc),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (density, labelRes, descRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = stringResource(descRes),
                selected = current == density,
                enabled = true,
                onClick = { onSelect(density) },
                modifier = Modifier.testTag("settings_density_${density.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun TimeFormatGroup(current: TimeFormat, onSelect: (TimeFormat) -> Unit) {
    // Always enabled: the chat list keeps using the format even while message timestamps are hidden.
    val options = listOf(
        TimeFormat.AUTO to R.string.settings_time_format_auto,
        TimeFormat.H12 to R.string.settings_time_format_h12,
        TimeFormat.H24 to R.string.settings_time_format_h24,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (format, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == format,
                enabled = true,
                onClick = { onSelect(format) },
                modifier = Modifier.testTag("settings_time_format_${format.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun MessageSpacingGroup(
    current: io.github.trevarj.motd.data.prefs.MessageSpacing,
    onSelect: (io.github.trevarj.motd.data.prefs.MessageSpacing) -> Unit,
) {
    val options = listOf(
        io.github.trevarj.motd.data.prefs.MessageSpacing.COMPACT to R.string.settings_message_spacing_compact,
        io.github.trevarj.motd.data.prefs.MessageSpacing.DEFAULT to R.string.settings_message_spacing_default,
        io.github.trevarj.motd.data.prefs.MessageSpacing.RELAXED to R.string.settings_message_spacing_relaxed,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (spacing, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == spacing,
                enabled = true,
                onClick = { onSelect(spacing) },
                modifier = Modifier.testTag("settings_message_spacing_${spacing.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun BubbleCornerStyleGroup(
    current: io.github.trevarj.motd.data.prefs.BubbleCornerStyle,
    onSelect: (io.github.trevarj.motd.data.prefs.BubbleCornerStyle) -> Unit,
) {
    // Applies to the Comfortable bubble layout only; the note lives on the first (default) option,
    // mirroring the AvatarStyleGroup pattern where only the relevant option carries a subtitle.
    val options = listOf(
        Triple(
            io.github.trevarj.motd.data.prefs.BubbleCornerStyle.ROUNDED,
            R.string.settings_bubble_corner_rounded,
            R.string.settings_bubble_corners_desc,
        ),
        Triple(io.github.trevarj.motd.data.prefs.BubbleCornerStyle.SUBTLE, R.string.settings_bubble_corner_subtle, null),
        Triple(io.github.trevarj.motd.data.prefs.BubbleCornerStyle.SQUARE, R.string.settings_bubble_corner_square, null),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (style, labelRes, subtitleRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = subtitleRes?.let { stringResource(it) },
                selected = current == style,
                enabled = true,
                onClick = { onSelect(style) },
                modifier = Modifier.testTag("settings_bubble_corner_${style.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun PaletteGroup(
    current: NickColorPalette,
    enabled: Boolean,
    onSelect: (NickColorPalette) -> Unit,
) {
    val options = listOf(
        NickColorPalette.THEME to R.string.settings_palette_theme,
        NickColorPalette.CLASSIC to R.string.settings_palette_classic,
        NickColorPalette.VIVID to R.string.settings_palette_vivid,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (palette, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == palette,
                enabled = enabled,
                onClick = { onSelect(palette) },
                modifier = Modifier.testTag("settings_palette_${palette.name.lowercase()}"),
            )
        }
    }
}

@Preview
@Composable
private fun AppearanceSettingsPreview() {
    MotdTheme {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(theme = ColorThemePreset.DARK),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onFollowSystem = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
        )
    }
}

@Preview(name = "Interface 80%", fontScale = 1f)
@Composable
private fun AppearanceSettingsMinTextPreview() {
    MotdTheme(uiFontScalePercent = 80) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(uiFontScalePercent = 80),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onFollowSystem = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
        )
    }
}

@Preview(name = "Interface 140% + large system font", fontScale = 1.5f)
@Composable
private fun AppearanceSettingsMaxTextPreview() {
    MotdTheme(uiFontScalePercent = 140) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(uiFontScalePercent = 140),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onFollowSystem = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
        )
    }
}
