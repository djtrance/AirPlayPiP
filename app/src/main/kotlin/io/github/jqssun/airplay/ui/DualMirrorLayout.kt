package io.github.jqssun.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import io.github.jqssun.airplay.R

@Composable
fun DualMirrorLayout(
    layout: String,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
    primaryDiagnostic: String,
    secondaryDiagnostic: String,
    selectedScreen: String,
    onSelectScreen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (layout == "pip") {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            primary()
            ScreenSelectButton("primary", selectedScreen, onSelectScreen,
                Modifier.align(Alignment.TopStart).padding(18.dp))
            DecoderBadge(primaryDiagnostic, Modifier.align(Alignment.BottomStart).padding(12.dp))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = maxWidth * 0.30f
                Box(
                    Modifier.align(Alignment.TopEnd).padding(18.dp).width(width).aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    secondary()
                    ScreenSelectButton("secondary", selectedScreen, onSelectScreen,
                        Modifier.align(Alignment.TopStart).padding(4.dp))
                    DecoderBadge(secondaryDiagnostic, Modifier.align(Alignment.BottomStart).padding(4.dp))
                }
            }
        }
    } else {
        Row(modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(end = 1.dp)) {
                primary()
                ScreenSelectButton("primary", selectedScreen, onSelectScreen,
                    Modifier.align(Alignment.TopStart).padding(10.dp))
                DecoderBadge(primaryDiagnostic, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 1.dp)) {
                secondary()
                ScreenSelectButton("secondary", selectedScreen, onSelectScreen,
                    Modifier.align(Alignment.TopStart).padding(10.dp))
                DecoderBadge(secondaryDiagnostic, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
    }
}

@Composable
private fun DecoderBadge(details: String, modifier: Modifier = Modifier) {
    val lines = details.lineSequence().take(2).joinToString(" · ")
    if (lines.isNotBlank()) Text(lines, modifier.background(Color.Black.copy(alpha = .8f))
        .padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White,
        style = MaterialTheme.typography.labelSmall, maxLines = 2)
}

@Composable
private fun ScreenSelectButton(screen: String, selectedScreen: String,
                               onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val selected = selectedScreen == screen
    val label = stringResource(if (screen == "primary") R.string.dual_screen_primary else R.string.dual_screen_secondary)
    TextButton(onClick = { onSelect(screen) }, modifier = modifier
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .92f) else Color.Black.copy(alpha = .72f), CircleShape)
        .dpadFocus(CircleShape)) {
        Text(label, color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
