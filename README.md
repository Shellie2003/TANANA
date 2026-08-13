# Stark HUD Launcher

Launcher Android futuriste piloté par gestes de la main, sans contact avec l'écran.
CameraX alimente MediaPipe Hand Landmarker ; les 21 points de repère de la main sont traduits
en curseur holographique et en gestes (pincer, poing, pointer, balayer) au sein d'une interface
Jetpack Compose.

## Architecture du moteur gestuel

Le pipeline est découpé en couches, dont trois sont du **Kotlin pur sans dépendance Android**,
donc testables sur JVM sans émulateur :

```
CameraX (RGBA_8888, 640x480, KEEP_ONLY_LATEST)
    │
    ▼
HandGestureAnalyzer ......... redressement/miroir de la frame, inférence MediaPipe
    │                          (GPU avec repli CPU à chaud), horodatage monotone
    ▼
HandGeometry ................ 21 points -> mesures normalisées, invariantes à l'échelle
    │                          (pur Kotlin)
    ├──▶ GestureClassifier ... machine à états : hystérésis, confirmation temporelle,
    │                          filtre de qualité, détection de balayage (pur Kotlin)
    └──▶ CursorMapper ........ zone active, correction de gain (pur Kotlin)
              │
              ▼
        OneEuroFilter3D ...... lissage adaptatif + prédiction de latence amortie (pur Kotlin)
              │
              ▼
        Compose UI ........... lecture de l'état en phase de dessin (zéro recomposition)
```

## Ce qui fait la précision

| Mécanisme | Rôle |
|---|---|
| Modèle MediaPipe embarqué (`app/src/main/assets/hand_landmarker.task`) | Suivi réel des 21 articulations, hors ligne, sans réseau |
| Normalisation par la taille de paume | Les seuils restent valables à 20 cm comme à 80 cm de la caméra |
| Extension des doigts par fusion angle + portée | Résiste à la rotation de la main et à la vue de face |
| Hystérésis (seuils d'entrée/sortie distincts) | Supprime les clics parasites au voisinage du seuil |
| Confirmation sur N images | Absorbe les sursauts d'inférence isolés (~30 ms de coût) |
| Filtre 1-Euro + prédiction amortie | Curseur immobile au repos, sans traînage en mouvement |
| Zone morte au repos | Curseur parfaitement figé pendant la visée |
| Zone active recadrée | Toute la surface de l'écran atteignable sans sortir du champ |
| Filtre de qualité géométrique | Aucune action déclenchée sur une détection douteuse |

Calibration du filtre, mesurée par balayage paramétrique (30 img/s, coordonnées normalisées) :

| Paramètre | Valeur | Effet mesuré |
|---|---|---|
| `minCutoff` | 1.6 Hz | bruit résiduel < 0.7 % pour ±1 % en entrée |
| `beta` | 1.2 | traînage 1.2 % à 0.3 u/s |
| prédiction | 40 ms | traînage résiduel divisé par deux |
| amortissement | actif | dépassement à l'arrêt réduit d'environ 40 % |

## Gestes

| Geste | Action |
|---|---|
| Main ouverte / index tendu | Déplacement du curseur holographique |
| Pincer (pouce + index) | Clic sur l'application survolée |
| Poing | Retour système (via le service d'accessibilité) |
| Balayage gauche / droite | Rotation de l'anneau d'applications |

## Tests

Les composants de précision sont couverts par 40 tests JVM (exécution en quelques dizaines de
millisecondes, sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

`SyntheticHand` génère des poses de main paramétrables (courbure de chaque doigt, écart du
pincement, échelle, position), ce qui rend les scénarios gestuels parfaitement reproductibles :
absence de rebond au seuil, unicité du clic, rejet des faux balayages, invariance d'échelle,
bruit et latence du filtre.

## Configuration requise

- Caméra frontale (l'application reste fonctionnelle en mode tactile/simulation sans caméra)
- Le service d'accessibilité doit être activé manuellement dans les réglages Android pour que
  le geste « poing » déclenche le retour système
- Aucune connexion réseau nécessaire : l'inférence est entièrement locale
