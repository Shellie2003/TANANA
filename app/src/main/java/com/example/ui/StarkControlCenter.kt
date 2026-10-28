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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldMore
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.BuildConfig
import com.example.R
import com.example.control.ClickTuning
import com.example.control.Command
import com.example.control.CommandList
import com.example.control.PinchTelemetry
import com.example.control.ScrollTuning
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
    /** Vrai quand le bouton est enfoncé : le pouce touche l'index. */
    val isPressed: Boolean = false,
    val confidence: Float,
    val fps: Int,
    val latencyMs: Int,
    val visionError: String?,
    /** Régime de cadence : indique si le moteur économise ou tourne à plein. */
    val powerMode: PowerMode = PowerMode.IDLE,
    /** Proportion d'images économisées par la régulation, entre 0 et 1. */
    val savingsRatio: Float = 0f,
    /** État du pincement : course apprise, réussites, cause du dernier échec. */
    val pinch: PinchTelemetry = PinchTelemetry()
)

/**
 * Écran principal.
 *
 * Cet écran ne lance rien. Il a comporté une grille d'applications, retirée : l'application est un
 * contrôleur, et lui faire tenir aussi le rôle de lanceur la mettait en concurrence avec l'écran
 * d'accueil de l'utilisateur.
 *
 * L'ordre reflète la progression de l'utilisateur : d'abord ce qui bloque (la configuration),
 * puis l'interrupteur principal, puis le geste — il n'y en a qu'un — enfin les commandes du
 * panneau.
 */
@Composable
fun StarkControlCenter(
    status: EngineStatus,
    setupSteps: List<SetupStep>,
    commands: CommandList,
    clickTuning: ClickTuning,
    scrollTuning: ScrollTuning,
    onToggleEngine: (Boolean) -> Unit,
    onClickTuningChange: (ClickTuning) -> Unit,
    onScrollTuningChange: (ScrollTuning) -> Unit,
    onToggleCommand: (Command) -> Unit,
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

        item { SectionTitle("Le geste", "Il n'y en a qu'un : pincer.") }
        item { GestureGuide() }

        // La carte du pincement vient ensuite : c'est le seul geste, le seul dont l'échec soit
        // silencieux, et donc celui dont l'utilisateur a besoin d'un retour chiffré.
        if (status.isRunning) {
            item { SectionTitle("Réglage du pincement", "La course de votre main s'apprend seule.") }
            item { PinchCard(status.pinch, clickTuning, onClickTuningChange) }
        }

        item { SectionTitle("Défilement", "Pincez et déplacez la main ; relâchez en mouvement pour lancer l'inertie.") }
        item { ScrollCard(scrollTuning, onScrollTuningChange) }

        item {
            SectionTitle(
                "Commandes du panneau",
                "${commands.size} commandes. Amenez le curseur au bord droit pour l'ouvrir."
            )
        }
        item { CommandEditor(commands = commands, onToggle = onToggleCommand) }
    }
}

@Composable
private fun Header(status: EngineStatus) {
    Column {
        Text(
            // Lu dans les ressources, et non écrit en dur : c'est le même nom que celui affiché
            // par le système, donc il ne peut pas en diverger au prochain renommage.
            text = stringResource(R.string.app_name),
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
private fun PinchCard(
    click: PinchTelemetry,
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
                Metric("Appuis", "${click.presses}", Modifier.weight(1f))
                Metric(
                    "Réussite",
                    rate?.let { "${(it * 100).toInt()} %" } ?: "—",
                    Modifier.weight(1f)
                )
                Metric("Course", "%.2f".format(click.baseline), Modifier.weight(1f))
            }

            // Les trois nombres qui disent si le geste peut aboutir, et dont l'absence a coûté
            // trois tours de réglage à l'aveugle. « Atteint » est le plus faible écart que cette
            // main a réellement produit ; « Seuil » celui qu'il faut franchir. Si le premier reste
            // au-dessus du second, aucun pincement ne peut déclencher d'appui — et ce n'est alors
            // pas le geste qu'il faut corriger.
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(
                    "Atteint",
                    click.deepest.takeIf { it > 0f }?.let { "%.2f".format(it) } ?: "—",
                    Modifier.weight(1f)
                )
                Metric("Seuil", "%.2f".format(click.contactThreshold), Modifier.weight(1f))
                Metric(
                    "Fermeture",
                    "${(click.progress * 100).toInt()} %",
                    Modifier.weight(1f)
                )
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
        // a besoin quelqu'un dont les appuis ne partent pas, et donc dont la main est mal suivie.
        Spacer(Modifier.height(14.dp))
        ThreeWayPicker(
            title = "Fermeté du geste",
            options = ClickTuning.entries,
            current = tuning,
            label = { it.label },
            description = { it.description },
            onPick = onTuningChange
        )
    }
}

/**
 * Carte du défilement.
 *
 * Le gain était une constante du code, et donc une valeur qu'aucun usage ne pouvait corriger sans
 * un aller-retour. C'est la seule part du geste qui ne s'apprend pas toute seule, parce qu'elle
 * n'est pas mesurable mais préférentielle : de combien le contenu doit avancer pour un déplacement
 * de main donné.
 */
@Composable
private fun ScrollCard(tuning: ScrollTuning, onTuningChange: (ScrollTuning) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, StarkCyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        ThreeWayPicker(
            title = "Vivacité du défilement",
            options = ScrollTuning.entries,
            current = tuning,
            label = { it.label },
            description = { it.description },
            onPick = onTuningChange
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Le début du geste reste toujours au rapport 1:1 : un petit ajustement est " +
                "exact quelle que soit la position choisie.",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

/** Sélecteur à trois positions, partagé par les réglages de fermeté et de vivacité. */
@Composable
private fun <T> ThreeWayPicker(
    title: String,
    options: List<T>,
    current: T,
    label: (T) -> String,
    description: (T) -> String,
    onPick: (T) -> Unit
) {
    Column {
        Text(title, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                val selected = option == current
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
                        .clickable { onPick(option) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(option),
                        color = if (selected) StarkGold else Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = description(current),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

/**
 * Ce qu'il y a à apprendre, en entier.
 *
 * Le tableau tient en trois lignes, et c'est l'essentiel de ce qui a changé. Il y avait avant une
 * ligne par pose — huit, puis six, puis trois — plus une roue de huit directions, soit onze signes
 * à retenir avant de pouvoir se servir du téléphone. Le vocabulaire est désormais celui d'une
 * souris à un bouton : personne n'a à apprendre une souris.
 */
@Composable
private fun GestureGuide() {
    val steps = listOf(
        Triple(
            Icons.Default.TouchApp,
            "Pincer et relâcher",
            "Appui. C'est le clic. Le pouce touche l'index, et le doigt sent le geste partir."
        ),
        Triple(
            Icons.Default.UnfoldMore,
            "Pincer et déplacer la main",
            "Le doigt se pose et suit la main : défilement, balayage, glisser-déposer. " +
                "Rien n'est reconnu — c'est Android qui interprète, comme un doigt sur la vitre."
        ),
        Triple(
            Icons.Default.RadioButtonUnchecked,
            "Pincer et rester immobile",
            "Appui long. L'anneau rouge se remplit autour du curseur ; bouger la main le " +
                "transforme en défilement."
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((icon, title, detail) in steps) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(icon, null, tint = StarkCyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Text(
            text = "La forme de la main n'a aucune importance : gardez-la détendue, bras baissé. " +
                "Aucune pose n'est reconnue, donc aucune ne peut être confondue.",
            color = StarkCyan.copy(alpha = 0.75f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/**
 * Choix des commandes du panneau.
 *
 * Toucher une ligne la fait entrer ou sortir du panneau. Les commandes retenues y apparaissent
 * dans l'ordre où elles ont été ajoutées, ce qui rend l'ordre prévisible sans demander de
 * réordonner quoi que ce soit.
 *
 * Il n'y a plus rien à associer : aucun réglage ne peut rendre un geste impossible, puisqu'il n'y
 * a qu'un geste et qu'il n'est pas configurable. C'est la différence de fond avec l'écran
 * précédent, où choisir une association demandait de deviner quelles formes de main on
 * parviendrait à produire de façon fiable.
 */
@Composable
private fun CommandEditor(commands: CommandList, onToggle: (Command) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (command in Command.entries) {
            val position = commands.commands.indexOf(command)
            val selected = position >= 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) StarkGold.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f)
                    )
                    .border(
                        1.dp,
                        if (selected) StarkGold.copy(alpha = 0.5f) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onToggle(command) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selected) "${position + 1}" else "·",
                    color = if (selected) StarkGold else Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = command.label,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                    Text(
                        text = command.description,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        tint = StarkGold, modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Text(
            text = "Plus il y a de lignes, plus elles sont étroites : c'est le seul arbitrage, " +
                "et il se voit tout de suite.",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
