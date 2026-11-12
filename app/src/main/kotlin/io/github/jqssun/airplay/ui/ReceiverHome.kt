package io.github.jqssun.airplay.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ReceiverHome(
    viewModel: MainViewModel,
    preview: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit
) {
    val state by viewModel.serverState.collectAsState()
    val name by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val access by viewModel.localNetwork.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val secondaryMirroring by viewModel.secondaryMirroringActive.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audio by viewModel.audioOnly.collectAsState()
    val diagnostics by viewModel.floatingDiagnostics.collectAsState()
    val grant = state != ServerState.RUNNING && access != LocalNetworkAccess.GRANTED
    val context = LocalContext.current
    val startFocus = remember { FocusRequester() }
    var addresses by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        startFocus.requestFocusUntilLanded()
        while (true) {
            addresses = withContext(Dispatchers.IO) {
                runCatching {
                    NetworkInterface.getNetworkInterfaces().toList()
                        .filter { it.isUp && !it.isLoopback }
                        .flatMap { it.inetAddresses.toList() }
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .mapNotNull { it.hostAddress }.distinct().joinToString(" · ")
                }.getOrDefault("")
            }
            delay(5000)
        }
    }
    val panelShape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF080C18), Color(0xFF172743), Color(0xFF241E3E))))
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Airplay, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.08f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(
                        if (state == ServerState.RUNNING) Color(0xFF67E3AD) else MaterialTheme.colorScheme.error,
                        RoundedCornerShape(50)))
                    Text(stringResource(when {
                        grant -> R.string.local_network_label
                        state == ServerState.RUNNING -> R.string.home_ready
                        state == ServerState.ERROR -> R.string.error_label
                        else -> R.string.stopped_label
                    }), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(Modifier.weight(1f), shape = panelShape, color = Color.White.copy(alpha = 0.06f)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.home_connect_to), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(addresses.ifEmpty { stringResource(R.string.home_no_network) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    ConnectionStep("1", stringResource(R.string.home_step_network))
                    ConnectionStep("2", stringResource(R.string.home_step_mirroring))
                    ConnectionStep("3", stringResource(R.string.home_step_select, name))
                    Button(
                        onClick = {
                            when {
                                grant -> onLocalNetwork()
                                state == ServerState.RUNNING -> viewModel.stopServer()
                                else -> viewModel.startServer()
                            }
                        },
                        modifier = Modifier.focusRequester(startFocus).dpadFocus().fillMaxWidth()
                    ) {
                        Text(stringResource(when {
                            grant -> R.string.btn_allow
                            state == ServerState.RUNNING -> R.string.home_stop_receiver
                            else -> R.string.home_start_receiver
                        }))
                    }
                }
            }
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(if (audio && connections > 0) 320.dp else 190.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), panelShape)
                        .background(Color.Black.copy(alpha = 0.5f), panelShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (mirroring || secondaryMirroring || (audio && connections > 0)) preview()
                    else Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ScreenShare, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (connections > 0) R.string.waiting_for_playback else R.string.home_preview),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onFullscreen, enabled = mirroring || secondaryMirroring, modifier = Modifier.dpadFocus().weight(1f)) {
                        Icon(Icons.Default.Fullscreen, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.cd_fullscreen))
                    }
                    FilledTonalButton(onClick = onPip, enabled = mirroring || secondaryMirroring, modifier = Modifier.dpadFocus().weight(1f)) {
                        Icon(Icons.Default.PictureInPictureAlt, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.pip_short))
                    }
                }
                Text(stringResource(R.string.home_modes_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (Settings.canDrawOverlays(context)) R.string.home_overlay_ready else R.string.home_overlay_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (diagnostics.isNotBlank()) {
                    Text(stringResource(R.string.floating_last_diagnostic, diagnostics),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStep(number: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f)) {
            Text(number, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}
