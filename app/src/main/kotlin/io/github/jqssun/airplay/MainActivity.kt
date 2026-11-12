package io.github.jqssun.airplay

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.SecondaryAirPlayService
import io.github.jqssun.airplay.update.UpdateAuthMode
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    private var secondaryService: SecondaryAirPlayService? = null
    val isInPip = mutableStateOf(false)
    private val showPipPicker = mutableStateOf(false)
    private val showFloatingTools = mutableStateOf(false)
    private val showExitConfirmation = mutableStateOf(false)
    private val showApkUpdateDialog = mutableStateOf(false)
    private val pendingDecoderProfile = mutableStateOf<String?>(null)
    private var closing = false
    private val fullscreenRequest = mutableStateOf(0)
    private var suppressAutoPip = false
    private val nativePipSupported by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }
    private val apkInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val success = result.resultCode == RESULT_OK
        viewModel.completePendingApkInstall(success)
        if (success) Toast.makeText(this, R.string.update_install_complete, Toast.LENGTH_LONG).show()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) restoreFloating(svc)
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _refreshForeground()
            _autoStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            viewModel.unbindService()
        }
    }

    private val secondaryConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service as? SecondaryAirPlayService ?: return
            secondaryService = svc
            viewModel.bindSecondaryService(svc)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _autoStartSecondary()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            secondaryService = null
            viewModel.unbindSecondaryService()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.isNotEmpty()) _syncLocalNetwork(requested = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !_granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasLocalNetwork()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
        bindService(Intent(this, SecondaryAirPlayService::class.java), secondaryConnection, BIND_AUTO_CREATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.updateFromService()
                    delay(200)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.pref(Prefs.KEEP_SCREEN_ON), viewModel.connectionCount,
                    viewModel.secondaryConnectionCount) { keep, first, second ->
                    keep && first + second > 0
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // auto-enter pre-declared in pip params
        if (nativePipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.serverState, viewModel.connectionCount,
                        viewModel.videoPlaybackActive, viewModel.videoPlaybackAspect,
                        viewModel.videoAspect
                    ) { _, _, _, _, _ -> }.collect { updatePipParams() }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(viewModel.secondaryServerState, viewModel.secondaryConnectionCount,
                        viewModel.secondaryMirroringActive) { _, _, _ -> }
                        .collect { updatePipParams() }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(viewModel.mirroringActive, viewModel.secondaryMirroringActive,
                        viewModel.pref(Prefs.AUTO_PIP)) { _, _, _ -> }
                        .collect { updatePipParams() }
                }
            }
        }

        setContent {
            AirPlayTheme {
                MainScreen(
                    viewModel = viewModel,
                    isInPip = isInPip.value,
                    fullscreenRequest = fullscreenRequest.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork,
                    onTools = { showFloatingTools.value = true; updatePipParams() },
                    onExitApp = { showExitConfirmation.value = true; updatePipParams() },
                    onApkUpdate = { showApkUpdateDialog.value = true; updatePipParams() },
                    onSecondaryReceiverChanged = { enabled ->
                        if (enabled) viewModel.startSecondaryServer() else viewModel.stopSecondaryServer()
                    }
                )
                val updateState = viewModel.apkUpdateState.collectAsState().value
                if (showApkUpdateDialog.value) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!updateState.running) showApkUpdateDialog.value = false
                            updatePipParams()
                        },
                        title = { Text(stringResource(R.string.update_over_network_title)) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(stringResource(R.string.update_over_network_security))
                                if (updateState.running) {
                                    updateState.urls.forEach { url ->
                                        TextButton(onClick = { copyToClipboard(url, R.string.update_url_copied) }) {
                                            Text(stringResource(R.string.update_copy_url, url))
                                        }
                                    }
                                    if (updateState.authMode == UpdateAuthMode.FOUR_DIGIT && updateState.token.isNotBlank()) {
                                        Text(stringResource(R.string.update_code_label), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                                        Text(updateState.token, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                                        TextButton(onClick = { copyToClipboard(updateState.token, R.string.update_code_copied) }) {
                                            Text(stringResource(R.string.update_copy_code))
                                        }
                                    } else if (updateState.authMode == UpdateAuthMode.NO_CODE) {
                                        Text(stringResource(R.string.update_no_code_warning), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                                    }
                                    updateState.progress?.let { Text(stringResource(R.string.update_progress, it)) }
                                }
                                if (!updateState.running && !updateState.installReady) {
                                    TextButton(onClick = { viewModel.startApkUpdateServer(UpdateAuthMode.FOUR_DIGIT) }) {
                                        Text(stringResource(R.string.update_start_four_digit))
                                    }
                                    TextButton(onClick = { viewModel.startApkUpdateServer(UpdateAuthMode.NO_CODE) }) {
                                        Text(stringResource(R.string.update_start_no_code))
                                    }
                                }
                                if (updateState.installReady) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                                        TextButton(onClick = { openInstallSourcePermission() }) {
                                            Text(stringResource(R.string.update_open_install_permission))
                                        }
                                    } else {
                                        TextButton(onClick = {
                                            viewModel.pendingApkInstallerIntent()?.let(apkInstaller::launch)
                                        }) {
                                            Text(stringResource(R.string.update_install_now))
                                        }
                                    }
                                }
                                if (updateState.status.isNotBlank()) Text(updateState.status)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (updateState.running) viewModel.stopApkUpdateServer()
                                else showApkUpdateDialog.value = false
                                updatePipParams()
                            }) {
                                Text(stringResource(when {
                                    updateState.running -> R.string.update_server_stop
                                    updateState.installReady -> R.string.update_close_session
                                    else -> R.string.update_close_session
                                }))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                if (updateState.running) viewModel.stopApkUpdateServer()
                                showApkUpdateDialog.value = false
                                updatePipParams()
                            }) { Text(stringResource(R.string.update_close_session)) }
                        }
                    )
                }
                if (showPipPicker.value) {
                    AlertDialog(
                        onDismissRequest = { showPipPicker.value = false; updatePipParams() },
                        title = { Text(stringResource(R.string.pip_picker_title)) },
                        text = { Text(stringResource(R.string.pip_picker_description)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showPipPicker.value = false
                                enterFloatingMirror()
                            }) { Text(stringResource(R.string.pip_floating)) }
                        },
                        dismissButton = {
                            if (nativePipSupported) {
                                TextButton(onClick = {
                                    showPipPicker.value = false
                                    enterNativePip()
                                }) { Text(stringResource(R.string.pip_system)) }
                            }
                        }
                    )
                }
                if (showFloatingTools.value) {
                    AlertDialog(
                        onDismissRequest = { showFloatingTools.value = false; updatePipParams() },
                        title = { Text(stringResource(R.string.floating_controls_launcher)) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(stringResource(R.string.floating_tools_description))
                                TextButton(onClick = {
                                    showFloatingTools.value = false
                                    pendingDecoderProfile.value = "hardware_compat"
                                    updatePipParams()
                                }) { Text(stringResource(R.string.hardware_decoder_profile_action)) }
                                TextButton(onClick = {
                                    showFloatingTools.value = false
                                    pendingDecoderProfile.value = "software"
                                    updatePipParams()
                                }) { Text(stringResource(R.string.software_decoder_profile_action)) }
                                TextButton(onClick = { enterFloatingMirror(controlsDelay = 0) }) {
                                    Text(stringResource(R.string.floating_controls_now))
                                }
                                TextButton(onClick = { enterFloatingMirror(controlsDelay = 10) }) {
                                    Text(stringResource(R.string.floating_controls_delayed))
                                }
                                TextButton(onClick = { enterFloatingMirror(controlsDelay = 10, cpuCompatibility = true) }) {
                                    Text(stringResource(R.string.floating_cpu_action))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showFloatingTools.value = false; updatePipParams() }) {
                                Text(stringResource(R.string.btn_ok))
                            }
                        }
                    )
                }
                if (pendingDecoderProfile.value != null) {
                    val hardware = pendingDecoderProfile.value == "hardware_compat"
                    AlertDialog(
                        onDismissRequest = { pendingDecoderProfile.value = null; updatePipParams() },
                        title = { Text(stringResource(if (hardware) R.string.hardware_decoder_profile_title else R.string.software_decoder_profile_title)) },
                        text = { Text(stringResource(if (hardware) R.string.hardware_decoder_profile_description else R.string.software_decoder_profile_description)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val success = if (hardware) service?.applyHardwareDecoderProfile() == true
                                    else service?.applySoftwareDecoderProfile() == true
                                if (success) {
                                    pendingDecoderProfile.value = null
                                    updatePipParams()
                                }
                            }) { Text(stringResource(R.string.software_decoder_apply)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDecoderProfile.value = null; updatePipParams() }) {
                                Text(stringResource(R.string.cancel_changes))
                            }
                        }
                    )
                }
                if (showExitConfirmation.value) {
                    AlertDialog(
                        onDismissRequest = { showExitConfirmation.value = false; updatePipParams() },
                        title = { Text(stringResource(R.string.close_application)) },
                        text = { Text(stringResource(R.string.close_application_description)) },
                        confirmButton = {
                            TextButton(onClick = {
                                closing = true
                                suppressAutoPip = true
                                updatePipParams()
                                service?.closeForUser()
                                finishAndRemoveTask()
                            }) { Text(stringResource(R.string.close_application)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitConfirmation.value = false; updatePipParams() }) {
                                Text(stringResource(R.string.keep_application_open))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun _granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun copyToClipboard(text: String, message: Int) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AirPlay PiP", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openInstallSourcePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                startActivity(appSettingsIntent())
            }
        }
    }

    private fun _autoStart() {
        if (closing) return
        val svc = service ?: return
        if (viewModel.pref(Prefs.AUTO_START).value && svc.serverState.value == AirPlayService.ServerState.STOPPED && hasLocalNetwork()) {
            viewModel.startServer()
        }
    }

    private fun _autoStartSecondary() {
        val svc = secondaryService ?: return
        if (viewModel.pref(Prefs.DUAL_RECEIVER).value && svc.serverState.value == AirPlayService.ServerState.STOPPED && hasLocalNetwork()) {
            viewModel.startSecondaryServer()
        }
    }
    
    private fun _refreshForeground() {
        if (service?.serverState?.value == AirPlayService.ServerState.RUNNING) {
            startService(Intent(this, AirPlayService::class.java))
        }
    }

    private fun _syncLocalNetwork(requested: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val p = Manifest.permission.ACCESS_LOCAL_NETWORK
        val prev = viewModel.localNetwork.value
        val now = when {
            hasLocalNetwork() -> LocalNetworkAccess.GRANTED
            shouldShowRequestPermissionRationale(p) -> LocalNetworkAccess.ASK
            requested || prev == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
            else -> LocalNetworkAccess.ASK
        }
        viewModel.setLocalNetwork(now)
        if (now == LocalNetworkAccess.GRANTED && prev != LocalNetworkAccess.GRANTED) _autoStart()
    }

    private fun _requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (viewModel.localNetwork.value == LocalNetworkAccess.SETTINGS) {
            startActivity(appSettingsIntent())
        } else {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    override fun onStart() {
        super.onStart()
        suppressAutoPip = false
        service?.let(::restoreFloating)
        _syncLocalNetwork()
        _refreshForeground()
        _autoStartSecondary()
    }

    fun enterPip() {
        if (viewModel.mirroringActive.value) {
            showPipPicker.value = true
            updatePipParams()
        }
        else if (viewModel.videoPlaybackActive.value) enterNativePip()
    }

    private fun restoreFloating(svc: AirPlayService) {
        if (svc.isFloating) {
            svc.hideFloatingMirror()
            fullscreenRequest.value++
        }
    }

    private fun enterFloatingMirror(controlsDelay: Int? = null, cpuCompatibility: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            try {
                suppressAutoPip = true
                updatePipParams()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, R.string.overlay_permission_hint, Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                suppressAutoPip = false
                updatePipParams()
                Toast.makeText(this, R.string.overlay_settings_unavailable, Toast.LENGTH_LONG).show()
            }
            return
        }
        val success = if (controlsDelay == null) service?.showFloatingMirror() == true
            else service?.openFloatingControls(controlsDelay, cpuCompatibility) == true
        if (success) {
            showFloatingTools.value = false
            updatePipParams() // Disable auto PiP before hiding the activity; the overlay owns the surface.
            moveTaskToBack(true)
        } else Toast.makeText(this, R.string.floating_failed, Toast.LENGTH_LONG).show()
    }

    private fun enterNativePip() {
        if (!nativePipSupported) {
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (!enterPictureInPictureMode(_pipParams())) {
                Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
            }
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePipParams() {
        if (nativePipSupported) runCatching { setPictureInPictureParams(_pipParams()) }
    }

    private fun _pipParams(): PictureInPictureParams {
        val aspect = if (viewModel.videoPlaybackActive.value) viewModel.videoPlaybackAspect.value
            else viewModel.videoAspect.value
        val rational = Rational((PresentationPolicy.pipAspect(aspect) * 1000).roundToInt().coerceIn(419, 2390), 1000)
        val builder = PictureInPictureParams.Builder().setAspectRatio(rational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(_shouldAutoPip())
        }
        return builder.build()
    }

    private fun _shouldAutoPip(): Boolean = nativePipSupported && PresentationPolicy.canAutoPip(
        enabled = viewModel.pref(Prefs.AUTO_PIP).value && !showPipPicker.value && !suppressAutoPip &&
            !showFloatingTools.value && !showExitConfirmation.value && pendingDecoderProfile.value == null && !closing,
        running = viewModel.serverState.value == AirPlayService.ServerState.RUNNING ||
            viewModel.secondaryServerState.value == AirPlayService.ServerState.RUNNING,
        visualSession = viewModel.mirroringActive.value || viewModel.secondaryMirroringActive.value || viewModel.videoPlaybackActive.value,
        floating = service?.isFloating == true
    )

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && _shouldAutoPip()) enterNativePip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        isInPip.value = inPip
        service?.setMirrorPipDecoderMode(inPip)
    }

    override fun onStop() {
        super.onStop()
        // never stop mid-session
        if (!viewModel.pref(Prefs.RUN_IN_BACKGROUND).value && !isChangingConfigurations &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value == 0 && viewModel.secondaryConnectionCount.value == 0) {
            viewModel.stopServer()
        }
    }

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        unbindService(secondaryConnection)
        super.onDestroy()
    }
}
