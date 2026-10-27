package com.volla.accessories.fw.updater.app.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.volla.accessories.fw.updater.R
import com.volla.accessories.fw.updater.core.AccessoryAction
import com.volla.accessories.fw.updater.core.ChoiceSetting
import com.volla.accessories.fw.updater.core.ColorSetting
import com.volla.accessories.fw.updater.core.FirmwareRelease
import com.volla.accessories.fw.updater.core.InfoCapability
import com.volla.accessories.fw.updater.core.RangeSetting
import com.volla.accessories.fw.updater.core.UpdateProgress
import com.volla.accessories.fw.updater.core.actions
import com.volla.accessories.fw.updater.core.info

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessoryDetailScreen(
    model: AccessoryUiModel?,
    install: InstallUiState?,
    action: ActionUiState?,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onInstall: (FirmwareRelease) -> Unit,
    onSetting: (String, Int) -> Unit,
    onRunAction: (AccessoryAction) -> Unit,
    onDismissAction: () -> Unit,
    onDismissInstall: () -> Unit,
    dump: DumpUiState?,
    onDump: () -> Unit,
    onDismissDump: () -> Unit,
    serviceUnlocked: Boolean,
    onPickFile: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(model?.accessory?.displayName ?: stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (model == null) return@Scaffold
        val locked = (install != null && !install.finished) || dump?.running == true
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val firmware = model.firmware
            val firmwareSection: @Composable () -> Unit = {
                if (firmware != null) {
                    FirmwareSection(
                        firmware = firmware,
                        install = install,
                        onCheck = onCheck,
                        onInstall = onInstall,
                        serviceUnlocked = serviceUnlocked,
                        onPickFile = onPickFile,
                        onDismissInstall = onDismissInstall,
                        dump = dump,
                        onDump = onDump,
                        onDismissDump = onDismissDump,
                    )
                } else if (install != null) {
                    Section(stringResource(R.string.section_firmware)) {
                        InstallStatus(install, onDismissInstall)
                    }
                }
            }

            if (install != null) firmwareSection()

            if (model.capabilities.isEmpty() && !locked) DisconnectedNotice()

            model.capabilities.info()?.let { InfoSection(it, locked) }

            if (install == null) firmwareSection()

            model.settings?.let { SettingsSection(it, serviceUnlocked, locked, onSetting) }

            model.capabilities.actions()?.let { capability ->
                ActionsSection(
                    actions = capability.actions,
                    state = action,
                    locked = locked,
                    onRun = onRunAction,
                    onDismiss = onDismissAction,
                )
            }
        }
    }
}

@Composable
private fun DisconnectedNotice() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.disconnected_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.disconnected_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, dimmed: Boolean = false, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().alpha(if (dimmed) DIMMED else 1f)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoSection(capability: InfoCapability, dimmed: Boolean) {
    Section(stringResource(R.string.section_info), dimmed) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            capability.rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        row.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(row.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    state: SettingsUiState,
    serviceUnlocked: Boolean,
    locked: Boolean,
    onSetting: (String, Int) -> Unit,
) {
    Section(stringResource(R.string.section_settings), locked) {
        if (state.loading && state.values.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Section
        }
        val error = state.error
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        state.capability.settings.filter { serviceUnlocked || !it.advanced }.forEach { setting ->
            val value = state.values[setting.id]
            if (value != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(setting.title, style = MaterialTheme.typography.bodyLarge)
                    if (setting.description.isNotEmpty()) {
                        Text(
                            setting.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val enabled = !state.busy && !locked
                    when (setting) {
                        is ChoiceSetting -> ChoiceControl(setting, value, enabled) {
                            onSetting(setting.id, it)
                        }

                        is RangeSetting -> RangeControl(setting, value, enabled) {
                            onSetting(setting.id, it)
                        }

                        is ColorSetting -> ColorControl(value, enabled) {
                            onSetting(setting.id, it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceControl(
    setting: ChoiceSetting,
    value: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        setting.options.forEachIndexed { index, label ->
            FilterChip(
                selected = index == value,
                onClick = { onSelect(index) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun RangeControl(
    setting: RangeSetting,
    value: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var position by remember(value) { mutableStateOf(value.toFloat()) }
    val snapped = setting.min + ((position.toInt() - setting.min) / setting.step) * setting.step
    Column {
        Text(
            "$snapped${setting.unit}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            valueRange = setting.min.toFloat()..setting.max.toFloat(),
            enabled = enabled,
            onValueChangeFinished = { onCommit(snapped) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColorControl(color: Int, enabled: Boolean, onCommit: (Int) -> Unit) {
    val hsv = remember { FloatArray(3) }
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var brightness by remember { mutableStateOf(0f) }

    LaunchedEffect(color) {
        if (AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xffffff != color) {
            AndroidColor.colorToHSV(color or (0xff shl 24), hsv)
            hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }

    val current = Color.hsv(hue, saturation, brightness)
    val commit = { onCommit(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xffffff) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .pick(enabled, commit) { x, y ->
                    saturation = x
                    brightness = 1f - y
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                marker(saturation * size.width, (1f - brightness) * size.height)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .pick(enabled, commit) { x, _ -> hue = x * 360f },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(HUES))
                marker(hue / 360f * size.width, size.height / 2f)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(current),
            )
            Text(
                "#%06X".format(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xffffff),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.marker(x: Float, y: Float) {
    drawCircle(Color.White, radius = 9f, center = Offset(x, y), style = Stroke(width = 4f))
    drawCircle(Color.Black, radius = 13f, center = Offset(x, y), style = Stroke(width = 2f))
}

private fun Modifier.pick(
    enabled: Boolean,
    onCommit: () -> Unit,
    onMove: (Float, Float) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown()
            onMove(
                (down.position.x / size.width).coerceIn(0f, 1f),
                (down.position.y / size.height).coerceIn(0f, 1f),
            )
            down.consume()
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                onMove(
                    (change.position.x / size.width).coerceIn(0f, 1f),
                    (change.position.y / size.height).coerceIn(0f, 1f),
                )
                change.consume()
            }
            onCommit()
        }
    }
}

private const val DIMMED = 0.38f

private val HUES = listOf(
    Color.hsv(0f, 1f, 1f),
    Color.hsv(60f, 1f, 1f),
    Color.hsv(120f, 1f, 1f),
    Color.hsv(180f, 1f, 1f),
    Color.hsv(240f, 1f, 1f),
    Color.hsv(300f, 1f, 1f),
    Color.hsv(360f, 1f, 1f),
)

@Composable
private fun FirmwareSection(
    firmware: FirmwareUiState,
    install: InstallUiState?,
    onCheck: () -> Unit,
    onInstall: (FirmwareRelease) -> Unit,
    serviceUnlocked: Boolean,
    onPickFile: () -> Unit,
    onDismissInstall: () -> Unit,
    dump: DumpUiState?,
    onDump: () -> Unit,
    onDismissDump: () -> Unit,
) {
    Section(stringResource(R.string.section_firmware)) {
        Text(
            stringResource(R.string.label_current_version, firmware.capability.currentVersion.value),
            style = MaterialTheme.typography.bodyLarge,
        )

        val error = firmware.error
        val update = firmware.available
        when {
            install != null -> InstallStatus(install, onDismissInstall)
            firmware.checking -> Text(stringResource(R.string.label_checking))
            error != null -> Text(
                stringResource(R.string.label_check_failed, error),
                color = MaterialTheme.colorScheme.error,
            )
            update != null -> UpdateAvailable(update, onInstall)
            else -> Text(stringResource(R.string.label_up_to_date))
        }

        if (install == null && !firmware.checking) {
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_check))
            }
            if (serviceUnlocked) {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_pick_file))
                }
                if (firmware.capability.dump != null) {
                    OutlinedButton(
                        onClick = onDump,
                        enabled = dump?.running != true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_dump))
                    }
                    DumpStatus(dump, onDismissDump)
                }
            }
        }
    }
}

@Composable
private fun DumpStatus(dump: DumpUiState?, onDismiss: () -> Unit) {
    if (dump == null) return
    if (dump.running) {
        Text(stringResource(R.string.label_dumping), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val message = dump.message ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dump.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

@Composable
private fun ActionsSection(
    actions: List<AccessoryAction>,
    state: ActionUiState?,
    locked: Boolean,
    onRun: (AccessoryAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf<AccessoryAction?>(null) }

    Section(stringResource(R.string.section_actions), locked) {
        actions.forEach { action ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { if (action.confirmation != null) pending = action else onRun(action) },
                    enabled = !locked && state?.running != true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (action.destructive) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(action.title)
                }
                if (action.description.isNotEmpty()) {
                    Text(
                        action.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val message = state?.message
        val failed = state?.failed == true
        when {
            state?.running == true -> LinearProgressIndicator(Modifier.fillMaxWidth())
            message != null -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.title) },
            text = { Text(action.confirmation.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onRun(action)
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun UpdateAvailable(release: FirmwareRelease, onInstall: (FirmwareRelease) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.label_update_available, release.version.value),
            style = MaterialTheme.typography.titleMedium,
        )
        if (release.notes.isNotEmpty()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onInstall(release) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_install))
        }
    }
}

@Composable
private fun InstallStatus(install: InstallUiState, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = install.progress
        Text(
            progress.label(),
            style = MaterialTheme.typography.titleMedium,
            color = if (progress is UpdateProgress.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (progress is UpdateProgress.Transferring) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (!install.finished) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (progress is UpdateProgress.Rebooting) {
            Text(
                stringResource(R.string.label_rebooting_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (install.finished) {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

private fun UpdateProgress.label(): String = when (this) {
    UpdateProgress.Preparing -> "Preparing…"
    is UpdateProgress.Transferring -> "Installing…"
    UpdateProgress.Verifying -> "Verifying…"
    UpdateProgress.Rebooting -> "Restarting the accessory…"
    UpdateProgress.Done -> "Update complete"
    is UpdateProgress.Failed -> reason
}
