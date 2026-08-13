package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.gesture.GestureType
import com.example.ui.theme.StarkBlue
import com.example.ui.theme.StarkCyan
import com.example.ui.theme.StarkDarkBg
import com.example.ui.theme.StarkGold
import com.example.ui.theme.StarkRed

/** Une application lançable depuis la grille. */
data class LauncherAppItem(
    val label: String,
    val packageName: String,
    val iconBitmap: Bitmap? = null,
    val fallbackIcon: ImageVector = Icons.Default.Android
)

/** Une étape de configuration, avec son état et son action de résolution. */
data class SetupStep(
    val title: String,
    val explanation: String,
    val isDone: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit
)

/** État affiché du moteur gestuel. */
data class EngineStatus(
    val isRunning: Boolean,
    val gesture: GestureType,
    val confidence: Float,
    val fps: Int,
    val latencyMs: Int,
    val visionError: String?
)

/**
 * Écran principal.
 *
 * L'ancienne interface plaçait les applications sur un anneau en rotation : spectaculaire, mais
 * contraire à ce qu'on attend d'un launcher — cibles mouvantes, étiquettes qui se chevauchent,
 * profondeur simulée qui réduit certaines icônes à quelques pixels. Elle est remplacée par une
 * disposition verticale ordinaire, où chaque élément a une position stable et une taille de
 * cible confortable.
 *
 * L'ordre reflète la progression de l'utilisateur : d'abord ce qui bloque (la configuration),
 * puis l'interrupteur principal, puis l'aide-mémoire des gestes, enfin les applications.
 */
@Composable
fun StarkControlCenter(
    status: EngineStatus,
    setupSteps: List<SetupStep>,
    apps: List<LauncherAppItem>,
    onToggleEngine: (Boolean) -> Unit,
    onLaunchApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingSteps = setupSteps.filterNot { it.isDone }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(StarkDarkBg, Color(0xFF071026), StarkDarkBg))
            ),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { Header(status) }

        // La configuration passe avant tout : tant qu'elle est incomplète, activer le moteur ne
        // donnerait qu'un résultat partiel, ce qui est la pire des expériences.
        if (pendingSteps.isNotEmpty()) {
            item { SectionTitle("Configuration requise", "${pendingSteps.size} étape(s) restante(s)") }
            items(pendingSteps, key = { it.title }) { SetupCard(it) }
        }

        item {
            EngineCard(
                status = status,
                isReady = pendingSteps.isEmpty(),
                onToggle = onToggleEngine
            )
        }

        item { SectionTitle("Gestes", "Fonctionnent partout, y compris hors de cette application") }
        item { GestureLegend(activeGesture = status.gesture) }

        if (apps.isNotEmpty()) {
            item { SectionTitle("Applications", "${apps.size} installées") }
            item { AppGrid(apps = apps, onLaunch = onLaunchApp) }
        }
    }
}

@Composable
private fun Header(status: EngineStatus) {
    Column {
        Text(
            text = "STARK HUD",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(active = status.isRunning)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (status.isRunning) "Contrôle gestuel actif" else "En veille",
                color = if (status.isRunning) StarkCyan else Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            // Identité du build : permet de vérifier d'un coup d'œil quelle version est installée.
            text = "build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (active) StarkCyan else Color.White.copy(alpha = 0.3f))
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(
            text = title.uppercase(),
            color = StarkCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

/** Carte d'une étape de configuration : ce qui manque, pourquoi, et comment le résoudre. */
@Composable
private fun SetupCard(step: SetupStep) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StarkGold.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = StarkGold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = step.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Expliquer la raison évite que l'utilisateur refuse une permission qui lui
                    // paraîtrait arbitraire — en particulier la superposition et l'accessibilité.
                    text = step.explanation,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = step.onAction,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(step.actionLabel, color = StarkGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Interrupteur principal, avec la télémétrie du moteur quand il tourne. */
@Composable
private fun EngineCard(status: EngineStatus, isReady: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status.isRunning) StarkCyan.copy(alpha = 0.10f)
            else Color.White.copy(alpha = 0.04f)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (status.isRunning) StarkCyan.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Contrôle gestuel",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            !isReady -> "Terminez la configuration pour l'activer"
                            status.isRunning -> "Pilotez tout le téléphone à la main"
                            else -> "Inactif — la caméra n'est pas utilisée"
                        },
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = status.isRunning,
                    enabled = isReady,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StarkDarkBg,
                        checkedTrackColor = StarkCyan,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                    )
                )
            }

            if (status.visionError != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StarkRed.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, StarkRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "Moteur de vision indisponible",
                            color = StarkRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = status.visionError,
                            color = StarkRed.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else if (status.isRunning) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Cadence", "${status.fps}/s", Modifier.weight(1f))
                    Metric("Latence", "${status.latencyMs} ms", Modifier.weight(1f))
                    Metric("Précision", "${(status.confidence * 100).toInt()} %", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = StarkCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
    }
}

/** Description d'un geste dans l'aide-mémoire. */
private data class GestureDoc(
    val gesture: GestureType,
    val icon: ImageVector,
    val name: String,
    val action: String,
    val color: Color
)

private val GESTURE_DOCS = listOf(
    GestureDoc(GestureType.POINT, Icons.Default.TouchApp, "Index seul", "Déplace le curseur", StarkCyan),
    GestureDoc(GestureType.PINCH, Icons.Default.CheckCircle, "Pouce + index", "Clic, ou glisser si maintenu", StarkGold),
    GestureDoc(GestureType.TWO_FINGER, Icons.Default.UnfoldMore, "Index + majeur", "Défilement vertical", StarkBlue),
    GestureDoc(GestureType.THREE_FINGER, Icons.Default.SwapHoriz, "Trois doigts", "Applications récentes ; balayez pour naviguer", StarkBlue),
    GestureDoc(GestureType.OPEN_PALM, Icons.Default.PlayArrow, "Main ouverte", "Lecture / pause du média", StarkCyan),
    GestureDoc(GestureType.FIST, Icons.Default.ArrowBack, "Poing", "Retour", StarkRed),
    GestureDoc(GestureType.THUMB_UP, Icons.Default.Home, "Pouce levé", "Accueil", StarkRed)
)

/**
 * Aide-mémoire des gestes. La ligne correspondant au geste en cours s'illumine : l'utilisateur
 * apprend le vocabulaire en le pratiquant, sans avoir à mémoriser un tableau.
 */
@Composable
private fun GestureLegend(activeGesture: GestureType) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (doc in GESTURE_DOCS) {
            val isActive = doc.gesture == activeGesture
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isActive) doc.color.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        1.dp,
                        if (isActive) doc.color else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = doc.icon,
                    contentDescription = null,
                    tint = doc.color,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = doc.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = doc.action,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Grille d'applications. Quatre colonnes, cibles de 56 dp : au-dessus du minimum de 48 dp
 * recommandé pour un appui au doigt, et atteignable au curseur gestuel sans viser au pixel.
 */
@Composable
private fun AppGrid(apps: List<LauncherAppItem>, onLaunch: (String) -> Unit) {
    // Hauteur bornée : la grille vit dans une LazyColumn, qui ne peut pas mesurer un enfant
    // de hauteur infinie.
    val rows = (apps.size + 3) / 4
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height((rows * 92).dp.coerceAtMost(560.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(apps, key = { it.packageName }) { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onLaunch(app.packageName) }
                    .padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = app.iconBitmap
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(app.fallbackIcon, null, tint = StarkCyan, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
