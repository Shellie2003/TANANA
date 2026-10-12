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
    ├──▶ GestureClassifier ... machine à états : trois poses, score avec marge de rejet,
    │                          confirmation temporelle, filtre de qualité (pur Kotlin)
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

**Trois poses, et une roue.** Le vocabulaire a compté jusqu'à huit poses, puis six, et restait
instable. Réduire le nombre ne suffisait pas : le défaut n'était pas la quantité mais le principe
— distinguer des poses par le nombre de doigts tendus.

Deux faits condamnent ce principe. Le mode d'échec dominant du suivi monoculaire est l'occlusion
des doigts par la paume, or les doigts repliés — qui portent toute l'information d'un décompte —
sont précisément ceux qu'on ne voit pas. Et la main est un continuum : passer d'une pose à une
autre traverse mécaniquement tous les décomptes intermédiaires, si bien que chaque pose ajoutée
crée autant d'occasions d'en verrouiller une autre au passage. Les mesures le confirmaient : avec
six poses, index, poing et pouce levé se tenaient tous à 0,200 du seuil de rejet, le pouce levé
étant la rivale la plus proche *des deux autres*.

| Pose | Rôle |
|---|---|
| **Index seul** (1 doigt) | Déplace le curseur. Pouce contre index : clic. Contact maintenu : le doigt se pose et suit la main — défilement, glisser, balayage |
| **Poing** (0 doigt) | Retour. Pose à tenir : l'anneau du curseur se remplit, changer de pose annule |
| **Main ouverte** (5 doigts) | Ouvre la roue de commandes |

Les trois formes sont maximalement éloignées, et ce qui compte n'est pas leur nombre mais la
nature de ce qui les sépare : chaque paire diffère par au moins un **doigt long** passant de
replié à franchement tendu — jamais par le seul pouce, le plus mal suivi des cinq, ni par une
extension partielle, du même ordre que le bruit de suivi. Les décomptes intermédiaires — deux,
trois doigts — ne correspondent plus à aucune pose, donc les traverser ne déclenche rien.

### La roue de commandes

Toutes les autres commandes passent par une roue de huit directions, ouverte par la main ouverte
et validée par le même clic que partout ailleurs. La tolérance d'une direction est **angulaire** :
elle ne dépend ni de la taille de l'écran, ni de la distance de la main, ni de sa stabilité.

Par défaut, dans le sens horaire depuis le haut : Accueil, Applications récentes, Application
suivante, Lecture/pause, Notifications, Réglages rapides, Application précédente, Clavier
gestuel. Les huit sont réaffectables, comme les trois poses.

Deux conséquences qui ne viennent pas du hasard. **Plus rien à mémoriser** : les commandes sont
écrites à l'écran, là où un vocabulaire de poses exige de retenir un signe par commande. Et **le
nombre de commandes cesse de peser sur la fiabilité** : en ajouter une n'ajoute plus une pose à
distinguer. La roue n'est pas un mode — elle est visible tant que la pose est tenue et disparaît
dès qu'on la quitte, donc on ne peut pas y rester coincé, et quitter la pose sans valider est la
façon d'annuler.

### Ce qui a disparu, et pourquoi

- Les **balayages** demandaient un mouvement ample et rapide, indiscernable d'un déplacement de
  main pressé. La roue fait le même travail en étant plus sûre et visible.
- Le **pincement comme pose** entrait en conflit direct avec le clic, qui est ce même contact :
  selon que le contact durait plus ou moins que le verrou temporel, le même geste partait en clic
  ou en glisser. Le contact est désormais lu uniquement par le détecteur de clic.
- Le **pouce levé**, le **pouce baissé** et le **« V »** se distinguaient de nos poses par un seul
  doigt — le pouce, ou un doigt occulté dans le pointage.

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
Le contact maintenu, lui, n'est pas traduit en geste reconnu : il ouvre un tracé continu
(`continueStroke`) que le système interprète lui-même, exactement comme un doigt sur la vitre.

La décision (`GesturePolicy`) est séparée de son exécution : la politique est du Kotlin pur, donc
vérifiable sans appareil, sans caméra et sans service.

## Consommation

La caméra et l'inférence en continu sont, ensemble, l'un des profils les plus gourmands qu'une
application Android puisse avoir. Trois mécanismes réduisent la facture, du plus fort au plus fin :

| Mécanisme | Effet |
|---|---|
| Caméra déliée quand l'écran s'éteint | éteint capteur et processeur d'image, pas seulement l'analyse |
| Régulation de cadence (`FrameGate`) | analyse à 2,5 img/s au repos au lieu de 30 |
| Superposition non redessinée à vide | évite de faire recomposer l'écran entier par le compositeur |

Le compromis est **asymétrique et délibéré** : on accepte de réagir plus lentement quand rien ne
se passe, jamais quand quelque chose se passe. Détecter l'arrivée d'une main avec 400 ms de retard
est imperceptible ; 400 ms de retard en pilotant le curseur serait rédhibitoire. La pleine cadence
revient donc dès la première image contenant une main, et n'est jamais dégradée — même en mode
économie d'énergie ou batterie faible.

À 30 img/s en continu, l'analyse tournerait 108 000 fois par heure. Avec une main présente 10 %
du temps, elle tourne environ 16 000 fois : sept fois moins de travail d'inférence. La proportion
réellement économisée est affichée dans l'application.

## Tests

Les composants de précision, la politique de commande et la régulation de cadence sont couverts
par 162 tests JVM (exécution en quelques dizaines de
millisecondes, sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

`SyntheticHand` génère des poses de main paramétrables (courbure de chaque doigt, écart du
pincement, échelle, position), ce qui rend les scénarios gestuels parfaitement reproductibles :
absence de rebond au seuil, unicité du clic, rejet des poses ambiguës et des décomptes
intermédiaires, invariance d'échelle, bruit et latence du filtre.

## Intégration continue

Le workflow `.github/workflows/build-release-apk.yml` exécute les tests JVM puis assemble la
variante installable, publiée dans la release `unsigned-latest` :

| Fichier | Minifiée par R8 | Usage |
|---|---|---|
| `nexus-release.apk` | oui | l'artefact installable |

Elle est signée avec la clé de debug (`-PstarkDebugSigning=true`) : installable pour tester,
jamais publiable. Le workflow vérifie au passage que `gesture_recognizer.task` est bien présent
dans les assets **et** qu'il contient ses deux modèles internes : un modèle manquant ne casse pas
la compilation, il casse la reconnaissance à l'exécution — c'est-à-dire au seul endroit où le
défaut ne serait plus détectable automatiquement.

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
  les gestes agissent hors de l'application (retour, appuis, défilement, roue de commandes)
- Aucune connexion réseau nécessaire : l'inférence est entièrement locale
