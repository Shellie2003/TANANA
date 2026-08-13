package com.example.power

/**
 * Régime de fonctionnement du moteur de vision.
 *
 * L'énergie consommée est presque proportionnelle au nombre d'images analysées : chacune coûte
 * une acquisition capteur, une conversion et une inférence. Or dans l'usage réel, la très grande
 * majorité des images ne contient aucune main. Analyser à pleine cadence en permanence revient à
 * payer le prix fort pour ne rien détecter.
 */
enum class PowerMode {
    /** Une main est suivie : pleine cadence, la latence prime. */
    ACTIVE,

    /** Une main vient de disparaître : cadence réduite, reprise quasi instantanée. */
    SEARCHING,

    /** Aucune main depuis longtemps : balayage lent, l'énergie prime. */
    IDLE,

    /** Écran éteint, batterie critique ou arrêt demandé : plus aucune image n'est analysée. */
    SUSPENDED
}

/**
 * Décide, image par image, s'il faut analyser ou ignorer.
 *
 * Le principe est un **compromis latence/énergie asymétrique** : on accepte de réagir plus
 * lentement quand rien ne se passe, jamais quand quelque chose se passe. Perdre 400 ms pour
 * détecter l'arrivée d'une main est imperceptible ; perdre 400 ms pendant qu'on pilote le
 * curseur serait rédhibitoire. Le retour en pleine cadence est donc immédiat dès la première
 * main détectée.
 *
 * Ordre de grandeur : à 30 img/s en continu, l'analyse tourne 108 000 fois par heure. Avec ce
 * régulateur et une main présente 10 % du temps, elle tourne environ 16 000 fois — sept fois
 * moins de travail d'inférence.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class FrameGate(private val config: Config = Config()) {

    data class Config(
        /** Intervalle minimal entre deux analyses lorsqu'une main est suivie. */
        val activeIntervalMs: Long = 0L,
        /** Intervalle en recherche rapprochée, juste après la perte d'une main. */
        val searchingIntervalMs: Long = 100L,
        /** Intervalle de balayage lent, en l'absence prolongée de main. */
        val idleIntervalMs: Long = 400L,
        /** Délai sans main avant de passer en recherche rapprochée. */
        val activeToSearchingMs: Long = 1_500L,
        /** Délai sans main avant de passer en balayage lent. */
        val searchingToIdleMs: Long = 12_000L,
        /**
         * Multiplicateur d'intervalle en mode économie d'énergie. Le système signale ainsi que
         * l'utilisateur privilégie explicitement l'autonomie.
         */
        val powerSaveMultiplier: Float = 2.5f,
        /** Niveau de batterie sous lequel les intervalles sont également allongés. */
        val lowBatteryPercent: Int = 15,
        val lowBatteryMultiplier: Float = 2f
    )

    private var lastProcessedAt = Long.MIN_VALUE
    private var lastHandSeenAt = Long.MIN_VALUE

    /** Régime courant, à des fins d'affichage et de diagnostic. */
    var mode: PowerMode = PowerMode.IDLE
        private set

    /** Nombre d'images ignorées depuis la dernière remise à zéro, pour mesurer le gain. */
    var skippedFrames: Long = 0L
        private set

    /** Nombre d'images analysées depuis la dernière remise à zéro. */
    var processedFrames: Long = 0L
        private set

    fun reset() {
        lastProcessedAt = Long.MIN_VALUE
        lastHandSeenAt = Long.MIN_VALUE
        mode = PowerMode.IDLE
        skippedFrames = 0
        processedFrames = 0
    }

    /** À appeler dès qu'une main est détectée : réarme immédiatement la pleine cadence. */
    fun onHandDetected(nowMs: Long) {
        lastHandSeenAt = nowMs
    }

    /**
     * Décide du sort d'une image.
     *
     * @param screenOn faux quand l'écran est éteint : plus rien n'est analysé, l'utilisateur ne
     *   pourrait de toute façon pas voir le curseur
     * @param powerSaveMode mode économie d'énergie du système
     * @param batteryPercent niveau de batterie, ou -1 s'il est inconnu
     */
    fun shouldProcess(
        nowMs: Long,
        screenOn: Boolean,
        powerSaveMode: Boolean = false,
        batteryPercent: Int = -1
    ): Boolean {
        if (!screenOn) {
            mode = PowerMode.SUSPENDED
            skippedFrames++
            return false
        }

        mode = resolveMode(nowMs)

        var interval = when (mode) {
            PowerMode.ACTIVE -> config.activeIntervalMs
            PowerMode.SEARCHING -> config.searchingIntervalMs
            PowerMode.IDLE -> config.idleIntervalMs
            PowerMode.SUSPENDED -> Long.MAX_VALUE
        }

        // Les majorations ne s'appliquent pas au régime actif : une main est suivie, donc
        // l'utilisateur est en train de piloter. Dégrader à cet instant rendrait le contrôle
        // inutilisable pour une économie marginale, puisque ce régime est déjà rare.
        if (mode != PowerMode.ACTIVE) {
            if (powerSaveMode) interval = (interval * config.powerSaveMultiplier).toLong()
            if (batteryPercent in 0 until config.lowBatteryPercent) {
                interval = (interval * config.lowBatteryMultiplier).toLong()
            }
        }

        if (lastProcessedAt != Long.MIN_VALUE && nowMs - lastProcessedAt < interval) {
            skippedFrames++
            return false
        }

        lastProcessedAt = nowMs
        processedFrames++
        return true
    }

    private fun resolveMode(nowMs: Long): PowerMode {
        if (lastHandSeenAt == Long.MIN_VALUE) return PowerMode.IDLE
        val since = nowMs - lastHandSeenAt
        return when {
            since <= config.activeToSearchingMs -> PowerMode.ACTIVE
            since <= config.searchingToIdleMs -> PowerMode.SEARCHING
            else -> PowerMode.IDLE
        }
    }

    /** Proportion d'images ignorées, entre 0 et 1. Sert à afficher le gain réellement obtenu. */
    fun savingsRatio(): Float {
        val total = skippedFrames + processedFrames
        return if (total == 0L) 0f else skippedFrames.toFloat() / total
    }
}
