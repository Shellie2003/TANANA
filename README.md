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

Les poses sont discriminables par le seul décompte des doigts tendus — le signal le plus robuste
que fournisse un suivi monoculaire. Deux poses ne différant que par un angle subtil seraient
confondues dès que la main pivote.

| Geste | Action |
|---|---|
| Index seul | Déplace le curseur |
| Pouce + index (pincement) | Clic ; glisser si maintenu |
| Index + majeur | Défilement vertical |
| Trois doigts | Applications récentes ; un balayage y navigue |
| Main ouverte | Lecture / pause du média |
| Poing | Retour |
| Pouce levé | Accueil |

## Contrôle à l'échelle du système

Les gestes agissent sur tout le téléphone, pas seulement dans cette application. Trois briques
le permettent, et chacune exige une autorisation que l'utilisateur accorde explicitement :

| Brique | Rôle | Autorisation |
|---|---|---|
| `StarkVisionService` | garde la caméra active en arrière-plan | service de premier plan `camera` + notification |
| `CursorOverlayView` | dessine le curseur au-dessus des applications | affichage par-dessus les applications |
| `StarkAccessibilityService` | injecte appuis, glissers et défilements | service d'accessibilité |

Le service d'accessibilité est le seul moyen pour une application d'agir en dehors d'elle-même :
`dispatchGesture` synthétise des appuis n'importe où à l'écran, `performGlobalAction` déclenche
Retour, Accueil et Récents. La commutation rapide entre applications est un balayage synthétisé
le long du bord inférieur — le geste de navigation système —, avec l'écran des récents en repli.

La décision (`GesturePolicy`) est séparée de son exécution : la politique est du Kotlin pur, donc
vérifiable sans appareil, sans caméra et sans service.

## Tests

Les composants de précision et la politique de commande sont couverts par 67 tests JVM (exécution en quelques dizaines de
millisecondes, sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

`SyntheticHand` génère des poses de main paramétrables (courbure de chaque doigt, écart du
pincement, échelle, position), ce qui rend les scénarios gestuels parfaitement reproductibles :
absence de rebond au seuil, unicité du clic, rejet des faux balayages, invariance d'échelle,
bruit et latence du filtre.

## Intégration continue

Le workflow `.github/workflows/build-release-apk.yml` exécute les tests JVM puis assemble deux
variantes installables, publiées dans la release `unsigned-latest` :

| Fichier | Minifiée par R8 | Usage |
|---|---|---|
| `stark-hud-launcher-release.apk` | oui | l'artefact réel |
| `stark-hud-launcher-debug.apk` | non | témoin de diagnostic |

Les deux sont signées avec la clé de debug (`-PstarkDebugSigning=true`) : installables pour
tester, jamais publiables. Comparer les deux isole immédiatement un problème de minification —
si le suivi de main marche en debug mais pas en release, il manque une règle de conservation R8.

Le tag est glissant : il est recréé sur le commit courant à chaque build, ce qui donne une
adresse de téléchargement permanente pour la dernière version. Les assets de release sont aussi
préférés aux artefacts Actions parce qu'ils ne consomment pas le quota de stockage des
artefacts, commun à tout le compte GitHub.

Les keystores n'étant pas versionnés, les configurations de signature ne sont créées que si le
fichier correspondant existe réellement. Sans keystore et sans `-PstarkDebugSigning`, AGP
produit `app-release-unsigned.apk`, qu'Android refuse d'installer. Pour une vraie diffusion :

```bash
KEYSTORE_PATH=/chemin/vers/ma-cle.jks \
KEY_ALIAS=upload STORE_PASSWORD=... KEY_PASSWORD=... \
  ./gradlew assembleRelease
```

## Configuration requise

- Caméra frontale (l'application reste fonctionnelle en mode tactile/simulation sans caméra)
- Le service d'accessibilité doit être activé manuellement dans les réglages Android pour que
  le geste « poing » déclenche le retour système
- Aucune connexion réseau nécessaire : l'inférence est entièrement locale
