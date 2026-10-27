package com.volla.accessories.fw.updater.app.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volla.accessories.fw.updater.app.ui.theme.AccessoriesTheme

private const val DUMP_MIME = "application/octet-stream"

class MainActivity : ComponentActivity() {

    private val viewModel: AccessoryManagerViewModel by viewModels()

    private val unlock = SecretUnlock { viewModel.unlockService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AccessoriesTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                AccessoriesApp(state, viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        unlock.onTouch(event, window.decorView.width, window.decorView.height)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        unlock.onKey(event)
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun AccessoriesApp(state: ManagerUiState, viewModel: AccessoryManagerViewModel) {
    when (val screen = state.screen) {
        Screen.List -> AccessoryListScreen(
            state = state,
            onOpen = viewModel::open,
        )

        is Screen.Detail -> {
            val model = state.resolve(screen.accessory)
                ?: AccessoryUiModel(
                    accessory = screen.accessory,
                    capabilities = emptyList(),
                    firmware = null,
                    settings = null,
                )
            val firmware = model.firmware?.capability
            val settings = model.settings?.capability
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null && firmware != null) {
                    viewModel.installFile(model.accessory, firmware, uri)
                }
            }
            val dumpPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(DUMP_MIME),
            ) { uri ->
                if (uri != null && firmware != null) {
                    viewModel.dumpFirmware(model.accessory, firmware, uri)
                }
            }
            AccessoryDetailScreen(
                model = model,
                install = state.install?.takeIf { it.matches(screen.accessory, model.accessory) },
                action = state.action?.takeIf { it.matches(screen.accessory, model.accessory) },
                onBack = viewModel::back,
                onCheck = { firmware?.let { viewModel.check(model.accessory, it) } },
                onInstall = { release -> firmware?.let { viewModel.install(model.accessory, it, release) } },
                onSetting = { id, value ->
                    settings?.let { viewModel.setSetting(model.accessory, it, id, value) }
                },
                onRunAction = { action -> viewModel.runAction(model.accessory, action) },
                onDismissAction = viewModel::dismissAction,
                onDismissInstall = viewModel::dismissInstall,
                dump = state.dump?.takeIf { it.matches(screen.accessory, model.accessory) },
                onDump = {
                    firmware?.let { dumpPicker.launch(viewModel.dumpName(model.accessory, it)) }
                },
                onDismissDump = viewModel::dismissDump,
                serviceUnlocked = state.serviceUnlocked,
                onPickFile = { picker.launch(arrayOf("*/*")) },
            )
        }
    }
}
