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
HandGeometry ................ 21 points -> deux mesures : position et écart pouce-index,
    │                          invariantes à l'échelle (pur Kotlin)
    ├──▶ PinchDetector ....... le bouton unique : ligne de base adaptative, hystérésis,
    │                          anti-rebond, causes de rejet nommées (pur Kotlin)
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

**Il n'y en a qu'un : pincer.** Le pouce touche l'index, et c'est tout le vocabulaire.

### Pourquoi un seul

Trois vocabulaires de poses ont été essayés — huit signes, puis six, puis trois, chaque fois mieux
mesurés et mieux départagés — et les trois se sont confondus à l'usage. Le défaut n'était donc ni
le nombre de poses ni la qualité du classement : c'était de **reconnaître une forme de main**.

Cette tâche est une classification sur un continuum, et elle hérite de trois défauts qu'aucun
réglage ne corrige :

- les doigts repliés, qui portent toute l'information d'une forme, sont ceux que la caméra ne voit
  pas — occulter le pouce fait chuter le rappel de MediaPipe d'environ 20 % ;
- passer d'une forme à une autre traverse mécaniquement toutes les formes intermédiaires ;
- tenir une forme en l'air fatigue la main, donc dégrade le geste à mesure qu'on s'en sert. La
  littérature sur l'interaction aérienne est explicite : une posture statique maintenue est ce qui
  mène le plus vite au « bras de gorille ».

Les trois plateformes qui ont le plus investi sur le sujet ont convergé sur la même réponse, avec
des capteurs bien meilleurs qu'une caméra frontale de téléphone : **Vision Pro, HoloLens et Quest
n'ont pas de vocabulaire de poses.** Elles ont le pincement, et elles en dérivent tout le reste —
l'appui, l'appui long, le glisser, le défilement. Si un vocabulaire de poses était tenable, elles
l'auraient.

Le pincement, lui, se mesure comme une **distance entre deux points** — bout du pouce, bout de
l'index — les mieux vus quand la main fait face à la caméra. C'est une grandeur continue avec
hystérésis, pas un classement à huit issues ; il se sent au doigt, donc se confirme sans regarder
l'écran ; il s'arrête sur une butée anatomique, pas sur un seuil arbitraire ; et **il ne demande de
tenir aucune forme** : le reste de la main fait ce qu'il veut, détendu, bras baissé.

Son défaut connu disparaît du même coup. La littérature note qu'un pincement est indistinguable des
autres gestes rapprochant le pouce de l'index — un signe « OK » déclenche un pincement. C'est vrai,
et sans conséquence ici : ce défaut n'existe que s'il y a autre chose à confondre avec.

### Tout le vocabulaire

| Ce qu'on fait | Ce que ça donne |
|---|---|
| déplacer la main | le curseur suit, quelle que soit la forme de la main |
| pincer et relâcher sans bouger | **appui** — le clic |
| pincer et rester immobile | **appui long** — menus contextuels, sélection de texte |
| pincer et déplacer la main | **tracé continu** — défilement, balayage, glisser-déposer |
| amener le curseur au bord droit | **panneau de commandes** |

C'est exactement une souris à un bouton, et c'est voulu : personne n'a à apprendre une souris.

Le tracé porte à lui seul ce que six poses portaient avant, et mérite une remarque : rien n'est
reconnu. On ouvre un tracé, il suit la main, et **c'est le système qui l'interprète** — en
défilement, en balayage, en glisser-déposer — exactement comme il le fait d'un doigt posé sur la
vitre. Il n'y a donc ni geste de défilement ni geste de balayage à distinguer : il y a un doigt, et
Android sait déjà quoi faire d'un doigt.

L'arbitrage entre les trois issues se fait sur un **déplacement** (2,2 % de l'écran) et une
**durée** (550 ms), jamais sur une vitesse. C'est le point sur lequel une tentative précédente a
échoué : un défilement distingué par sa vitesse partait tout seul, parce qu'une traversée d'écran
rapide satisfait « assez loin et assez vite » sur n'importe lequel de ses segments. Déplacement et
durée sont monotones et ne dépendent d'aucune dérivée du signal — ce sont les deux mêmes grandeurs
qu'Android applique à un doigt sur l'écran.

### Le panneau de commandes

Toutes les commandes du téléphone vivent dans une liste, ouverte en amenant le curseur au bord droit
de l'écran. On choisit une ligne, on pince. **Aucun geste nouveau** : le curseur se déplace comme
toujours, et le pincement valide comme partout ailleurs.

Trois propriétés en découlent :

- **le nombre de commandes ne coûte plus rien** — elles sont écrites les unes sous les autres ; en
  ajouter une n'ajoute ni forme à distinguer ni secteur à rétrécir ;
- **il n'y a rien à mémoriser** — la liste se lit, là où un vocabulaire de gestes doit se retenir
  avant de servir ;
- **la visée est à une seule dimension** — choisir une ligne ne demande que de monter ou descendre,
  ce qui est sans commune mesure avec viser un angle ou tenir une forme de main.

Dix-huit commandes sont disponibles ; dix sont dans le panneau par défaut, « Retour » en premier
puisque c'est de loin l'action la plus utilisée d'Android. La liste est librement composable.

Le panneau suit la position de la main : il s'ouvre quand on reste au bord, se referme dès qu'on
s'en éloigne. Pas de bascule, donc pas d'état où rester bloqué, et s'éloigner sans pincer n'exécute
rien — c'est la façon d'annuler, et elle ne demande aucun geste supplémentaire. Deux garde-fous
empêchent l'ouverture accidentelle : la bande est étroite (5 % de l'écran) et il faut y rester
220 ms ; et le panneau ne s'ouvre **jamais pendant un pincement**, de sorte qu'un défilement longeant
le bord droit ne peut pas le déclencher.

### Ce qui a disparu, et pourquoi

- **Toute la classification de poses** — `GestureClassifier`, `PoseScorer`, le classifieur entraîné
  de MediaPipe, et avec eux `gesture_recognizer.task` au profit de `hand_landmarker.task` : 554 Ko
  et une inférence par image en moins.
- **Les mesures d'extension de doigts.** Elles se déduisaient de points de repère que le modèle
  devine, puisqu'il ne les voit pas : on raisonnait sur une inférence d'une inférence.
- **Les balayages**, qui demandaient un mouvement ample indiscernable d'un déplacement de main
  pressé.
- **La roue de commandes**, où la précision exigée croissait avec le nombre de commandes.
- **Le défilement et le glisser comme actions** : le tracé continu les rend tous les deux.

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
par 134 tests JVM (exécution en quelques dizaines de
millisecondes, sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

`SyntheticHand` génère des poses de main paramétrables (courbure de chaque doigt, écart du
pincement, échelle, position), ce qui rend les scénarios gestuels parfaitement reproductibles :
absence de rebond au seuil, unicité de l'appui, arbitrage entre appui, appui long et tracé,
invariance d'échelle, bruit et latence du filtre.

## Intégration continue

Le workflow `.github/workflows/build-release-apk.yml` exécute les tests JVM puis assemble la
variante installable, publiée dans la release `unsigned-latest` :

| Fichier | Minifiée par R8 | Usage |
|---|---|---|
| `nexus-release.apk` | oui | l'artefact installable |

Elle est signée avec la clé de debug (`-PstarkDebugSigning=true`) : installable pour tester,
jamais publiable. Le workflow vérifie au passage que `hand_landmarker.task` est bien présent dans
les assets **et** qu'il contient ses deux modèles internes : un modèle manquant ne casse pas la
compilation, il casse la détection à l'exécution — c'est-à-dire au seul endroit où le défaut ne
serait plus détectable automatiquement. Il vérifie aussi que `gesture_recognizer.task` a bien
disparu de l'APK, pour que le modèle du classifieur retiré ne continue pas d'y peser.

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
  les gestes agissent hors de l'application (appuis, défilement, panneau de commandes)
- Aucune connexion réseau nécessaire : l'inférence est entièrement locale
