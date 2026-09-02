package com.example.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.control.BindableAction
import com.example.control.ClickTuning
import com.example.control.GestureBindings
import com.example.control.GesturePolicy
import com.example.gesture.GestureType
import com.example.power.PowerMode
import com.example.ui.theme.StarkBlue
import com.example.ui.theme.StarkCyan
import com.example.ui.theme.StarkDarkBg
import com.example.ui.theme.StarkGold
import com.example.ui.theme.StarkRed

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
    val visionError: String?,
    /** Régime de cadence : indique si le moteur économise ou tourne à plein. */
    val powerMode: PowerMode = PowerMode.IDLE,
    /** Proportion d'images économisées par la régulation, entre 0 et 1. */
    val savingsRatio: Float = 0f,
    /** État du geste de clic : course apprise, réussites, cause du dernier échec. */
    val click: GesturePolicy.ClickTelemetry = GesturePolicy.ClickTelemetry()
)

/**
 * Écran principal.
 *
 * Cet écran ne lance rien. Il a comporté une grille d'applications, retirée : NEXUS est un
 * contrôleur, et lui faire tenir aussi le rôle de lanceur le mettait en concurrence avec l'écran
 * d'accueil de l'utilisateur.
 *
 * L'ordre reflète la progression de l'utilisateur : d'abord ce qui bloque (la configuration),
 * puis l'interrupteur principal, puis le réglage du clic, enfin l'aide-mémoire des gestes.
 */
@Composable
fun StarkControlCenter(
    status: EngineStatus,
    setupSteps: List<SetupStep>,
    bindings: GestureBindings,
    clickTuning: ClickTuning,
    onToggleEngine: (Boolean) -> Unit,
    onClickTuningChange: (ClickTuning) -> Unit,
    onRebind: (GestureType, BindableAction) -> Unit,
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

        // La carte du clic vient avant l'aide-mémoire : c'est le geste le plus fréquent, le seul
        // dont l'échec soit silencieux, et donc celui dont l'utilisateur a besoin d'un retour.
        if (status.isRunning) {
            item { SectionTitle("Clic", "Contact bref : clic. Contact maintenu : le doigt se pose.") }
            item { ClickCard(status.click, clickTuning, onClickTuningChange) }
        }

        item {
            SectionTitle(
                "Poses",
                "Fonctionnent partout. Touchez une ligne pour changer son action."
            )
        }
        item {
            GestureLegend(
                docs = POSE_DOCS,
                activeGesture = status.gesture,
                bindings = bindings,
                onRebind = onRebind
            )
        }

        item { SectionTitle("Balayages", "En pose « V », d'un mouvement franc") }
        item {
            GestureLegend(
                docs = SWIPE_DOCS,
                activeGesture = status.gesture,
                bindings = bindings,
                onRebind = onRebind
            )
        }
    }
}

@Composable
private fun Header(status: EngineStatus) {
    Column {
        Text(
            text = "NEXUS",
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
                Spacer(Modifier.height(10.dp))
                PowerRow(status)
            }
        }
    }
}

/**
 * Régime énergétique et économie réalisée.
 *
 * Une consommation ne se pilote pas à l'aveugle : afficher la proportion d'images effectivement
 * évitées est le seul moyen pour l'utilisateur de constater que la régulation opère, et pour
 * nous de détecter qu'elle a cessé de fonctionner.
 */
@Composable
private fun PowerRow(status: EngineStatus) {
    val (label, color) = when (status.powerMode) {
        PowerMode.ACTIVE -> "Pleine cadence — main suivie" to StarkCyan
        PowerMode.SEARCHING -> "Cadence réduite — recherche" to StarkGold
        PowerMode.IDLE -> "Balayage lent — économie" to StarkBlue
        PowerMode.SUSPENDED -> "Suspendu — écran éteint" to Color.White.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            text = "${(status.savingsRatio * 100).toInt()} % d'images évitées",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
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

/**
 * Retour sur le geste de clic.
 *
 * Le clic gestuel a une propriété qu'aucun autre geste ne partage : **son échec est silencieux**.
 * Un défilement qui ne part pas se voit ; un clic qui ne part pas est indiscernable d'une cible
 * manquée ou d'une application qui ne réagit pas. L'utilisateur ne peut donc ni corriger son geste
 * ni savoir s'il peut se fier au système — ce qui est exactement le contraire de la confiance
 * recherchée.
 *
 * Cette carte rend le geste observable : la course apprise pour *cette* main, la progression en
 * temps réel, la part de gestes aboutis, et la cause nommée du dernier échec.
 */
@Composable
private fun ClickCard(
    click: GesturePolicy.ClickTelemetry,
    tuning: ClickTuning,
    onTuningChange: (ClickTuning) -> Unit
) {
    val rate = click.successRate

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, StarkGold.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (!click.isCalibrated) {
            Text(
                text = "Calibration en cours — gardez la main dans le champ quelques secondes.",
                color = StarkGold.copy(alpha = 0.9f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        } else {
            // Jauge de progression : la même grandeur que celle qui déclenche l'appui. Ce que
            // l'utilisateur voit se remplir ici est littéralement ce qui décide sur l'appareil.
            Text("Approche du contact", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(click.progress.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(StarkGold)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Clics", "${click.clicks}", Modifier.weight(1f))
                Metric(
                    "Réussite",
                    rate?.let { "${(it * 100).toInt()} %" } ?: "—",
                    Modifier.weight(1f)
                )
                Metric("Course", "%.2f".format(click.baseline), Modifier.weight(1f))
            }

            val advice = click.advice
            if (advice != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = advice,
                    color = StarkGold.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        // Le réglage reste accessible pendant la calibration : « Souple » est précisément ce dont
        // a besoin quelqu'un dont les clics ne partent pas, et donc dont la main est mal suivie.
        Spacer(Modifier.height(14.dp))
        Text("Fermeté du geste", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in ClickTuning.entries) {
                val selected = option == tuning
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) StarkGold.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            1.dp,
                            if (selected) StarkGold else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onTuningChange(option) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        color = if (selected) StarkGold else Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tuning.description,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

/** Description d'un geste dans l'aide-mémoire. */
private data class GestureDoc(
    val gesture: GestureType,
    val icon: ImageVector,
    val name: String,
    /** Comment former le geste. Sans cette phrase, le nom seul se prête à plusieurs lectures. */
    val hint: String,
    val color: Color
)

private val POSE_DOCS = listOf(
    GestureDoc(
        GestureType.POINT, Icons.Default.TouchApp, "Index seul",
        "Déplace le curseur. Pouce contre index : clic. Contact maintenu : le doigt se pose " +
            "et suit la main — c'est ce qui fait défiler, glisser et balayer.", StarkCyan
    ),
    GestureDoc(
        GestureType.TWO_FINGER, Icons.Default.SwapHoriz, "Index + majeur (V)",
        "Pose de navigation : c'est d'elle que partent les quatre balayages", StarkBlue
    ),
    GestureDoc(
        GestureType.OPEN_PALM, Icons.Default.PlayArrow, "Main ouverte",
        "Les cinq doigts écartés, paume vers la caméra", StarkCyan
    ),
    GestureDoc(
        GestureType.FIST, Icons.Default.ArrowBack, "Poing",
        "Main fermée, pouce rentré", StarkRed
    ),
    GestureDoc(
        GestureType.THUMB_UP, Icons.Default.Home, "Pouce levé",
        "Poing fermé, pouce sorti vers le haut", StarkRed
    ),
    GestureDoc(
        GestureType.THUMB_DOWN, Icons.Default.Wifi, "Pouce baissé",
        "Poing fermé, pouce sorti vers le bas", StarkGold
    )
)

private val SWIPE_DOCS = listOf(
    GestureDoc(
        GestureType.SWIPE_LEFT, Icons.Default.ArrowBack, "Balayage à gauche",
        "En pose « V », d'un geste franc", StarkBlue
    ),
    GestureDoc(
        GestureType.SWIPE_RIGHT, Icons.Default.SwapHoriz, "Balayage à droite",
        "En pose « V », d'un geste franc", StarkBlue
    ),
    GestureDoc(
        GestureType.SWIPE_UP, Icons.Default.UnfoldMore, "Balayage vers le haut",
        "En pose « V », main qui remonte", StarkBlue
    ),
    GestureDoc(
        GestureType.SWIPE_DOWN, Icons.Default.Notifications, "Balayage vers le bas",
        "En pose « V », main qui descend", StarkBlue
    )
)

/**
 * Aide-mémoire des gestes, doublé de l'écran de personnalisation.
 *
 * Les deux sont confondus à dessein : la liste qui apprend le vocabulaire est celle qui permet de
 * le modifier. Séparer « aide » et « réglages » obligerait à mémoriser la correspondance avant de
 * pouvoir la changer.
 *
 * La ligne du geste en cours s'illumine, ce qui donne un retour immédiat pendant l'apprentissage.
 */
@Composable
private fun GestureLegend(
    docs: List<GestureDoc>,
    activeGesture: GestureType,
    bindings: GestureBindings,
    onRebind: (GestureType, BindableAction) -> Unit
) {
    var editing by remember { mutableStateOf<GestureType?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (doc in docs) {
            val isActive = doc.gesture == activeGesture
            val bound = bindings[doc.gesture]

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isActive) doc.color.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f)
                    )
                    .border(
                        1.dp,
                        if (isActive) doc.color else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        editing = if (editing == doc.gesture) null else doc.gesture
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(doc.icon, null, tint = doc.color, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = doc.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            text = bound.label,
                            color = doc.color.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = "Changer l'action",
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (editing == doc.gesture) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = doc.hint,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = bound.description,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    ActionPicker(
                        // Un balayage ne dure qu'une image : les rôles continus ne lui sont pas
                        // proposés, plutôt que d'être proposés puis ignorés silencieusement.
                        options = BindableAction.assignableTo(doc.gesture),
                        current = bound,
                        onPick = {
                            onRebind(doc.gesture, it)
                            editing = null
                        }
                    )
                }
            }
        }
    }
}

/** Liste des actions assignables. */
@Composable
private fun ActionPicker(
    options: List<BindableAction>,
    current: BindableAction,
    onPick: (BindableAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (action in options) {
            val selected = action == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) StarkCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f)
                    )
                    .clickable { onPick(action) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = action.label,
                    color = if (selected) StarkCyan else Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(Icons.Default.CheckCircle, null, tint = StarkCyan, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
