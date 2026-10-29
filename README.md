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
| **ouvrir la main** | **appui** — le clic, par défaut |
| pincer et relâcher sans bouger | **appui** — si le pincement est choisi comme geste du clic |
| pincer et rester immobile | **appui long** — menus contextuels, sélection de texte |
| pincer et déplacer la main | **tracé continu** — défilement, balayage, glisser-déposer |
| viser et ne plus bouger | **appui**, sans pincer du tout |
| poser le curseur contre un bord | **défilement continu**, sans pincer du tout |
| « Défilement continu » dans le panneau | **défilement à vitesse commandée** — voir plus bas |
| amener le curseur au bord droit | **panneau de commandes** |

C'est exactement une souris à un bouton, et c'est voulu : personne n'a à apprendre une souris.

Les deux lignes « sans pincer du tout » ne sont pas des gestes de plus : ce sont des **seconds
chemins vers des actions qui existent déjà**, et il n'y a rien à y apprendre puisque c'est
l'immobilité qui les déclenche. Elles existent parce que tout ce qui passe par l'articulation des
doigts hérite de la fiabilité avec laquelle la caméra voit ces doigts — et cette fiabilité a fait
défaut longuement, pour une raison qui n'était pas un réglage. Voir
[Le clic était impossible, pas mal réglé](#le-clic-était-impossible-pas-mal-réglé).

Le tracé porte à lui seul ce que six poses portaient avant, et mérite une remarque : rien n'est
reconnu. On ouvre un tracé, il suit la main, et **c'est le système qui l'interprète** — en
défilement, en balayage, en glisser-déposer — exactement comme il le fait d'un doigt posé sur la
vitre. Il n'y a donc ni geste de défilement ni geste de balayage à distinguer : il y a un doigt, et
Android sait déjà quoi faire d'un doigt.

#### Le curseur a reculé à la base de l'index, et pourquoi il a fallu

Le clic par défaut est désormais **l'ouverture de la main**. Le choix vient de l'usage : le maintien
plaisait mais cliquait au mauvais endroit quand la chaîne était en retard, et le pincement reste
disponible en un réglage. Mais ce geste imposait une conséquence que la mesure a rendue
incontournable.

Le curseur venait du bout de l'index, pondéré avec la phalange — le point le plus fin pour viser,
puisque c'est avec lui qu'on pointe. Or **ouvrir la main étend l'index**, donc déplace son bout.
Mesuré en harnais, entre main détendue et main ouverte :

| source du curseur | déplacement en ouvrant la main |
|---|---|
| bout de l'index | **122 px** sur un écran de 1080 |
| base de l'index (le MCP) | **0 px** |
| poignet | 0 px |

Deux centimètres, soit plus qu'une cible tactile ordinaire — et le déplacement est **progressif et
simultané au signal** : à mi-signal, le curseur a déjà bougé de 50 px. Le geste de clic serait ainsi
devenu la première cause d'imprécision du clic. Aucune ancre antérieure n'y remédie, puisqu'il
faudrait la prendre avant le début de l'ouverture et que c'est l'ouverture qu'on détecte.

Le curseur suit donc la **base** de l'index, qui appartient à la paume et ne bouge pas quand les
doigts s'étendent. On pointe toujours dans la direction de l'index, mais le curseur ne suit plus
l'articulation du doigt. Ce qu'on y perd est réel : la finesse du bout du doigt, et la possibilité
de pointer sans déplacer la main. Ce qu'on y gagne est un clic qui part là où on visait.

#### Mesurer « main ouverte » sans retomber dans la classification de formes

C'était le risque, puisque trois vocabulaires de poses ont échoué ici. Reconstituer « main ouverte »
à partir de quatre extensions de doigts ramènerait le défaut exact qui les a fait retirer :
l'extension d'un doigt replié se déduit de phalanges invisibles que le modèle devine.

La mesure retenue n'interroge aucun doigt en particulier. C'est la **plus petite distance entre le
centre de la paume et un bout de doigt**, normalisée par la taille de la paume. Les points
utilisés sont tous parfaitement visibles dans le seul cas où la réponse doit être « oui », et quand
la main est refermée la mesure n'a besoin que d'être basse — ce que les erreurs d'estimation
produisent de toute façon.

Le **minimum** et non la moyenne, et c'est la mesure qui l'a décidé : « main ouverte » signifie
*tous* les doigts étendus, donc la contrainte qui lie est le doigt le moins étendu. La moyenne
sépare d'un facteur 1,36, le minimum de **1,61**. Le minimum est plus sensible à un doigt mal
suivi, et c'est assumé : il produit alors un clic **manqué**, là où la moyenne produirait un clic
**fantôme**. Des deux, le premier se recommence ; le second agit sur le téléphone.

| main | mesure |
|---|---|
| poing fermé | 0,44 |
| détendue | 0,67 |
| ouverte | 1,08 |

Sa faiblesse doit être écrite : le signal sépare trois fois moins bien que le pincement, et sa pente
s'écrase en fin de course — 0,84 au milieu de la plage, **0,06** sur les derniers dix pour cent
d'ouverture. « Grande ouverte » est donc presque indistinguable de « ouverte à 75 % ». C'est
pourquoi le seuil est appris et non constant, et pourquoi l'ouverture exige plus de profondeur que
le pincement.

#### Deux constantes tenues pour universelles, deux fois le même clic qui ne partait pas

Le détecteur juge un scalaire qui descend vers un seuil appris ; il ne sait rien du geste qui le
pilote. Il suffit donc de retourner l'étendue autour d'un pivot pour qu'il la traite sans une ligne
de modification — y compris la correction qui avait rendu le seuil atteignable.

Sauf que deux de ses constantes étaient, sans que rien ne le dise, **calibrées sur le pincement** :

- le **plancher supposé** avant tout geste observé. Un pincement descend à 30 % de l'écart au repos,
  une ouverture de main à 73 % du sien. Avec la supposition du pincement, le seuil se trouvait très
  au-dessous de ce que le geste peut atteindre : aucune ouverture ne déclenchait d'appui.
- le **plafond du seuil**, garde-fou contre une course quasi nulle. Sa valeur, 70 % de la ligne de
  base, se trouve juste *au* plancher de l'ouverture, qui est à 69 %. Il écrasait donc le seuil sur
  le plancher, avec le même résultat.

Chacune a été prise en flagrant délit par le même test de bout en bout, l'une après l'autre. Les
deux appartiennent maintenant au déclencheur, et non au détecteur : on ne peut plus construire un
détecteur pour un geste sans dire lequel.

#### Le clic était impossible, pas mal réglé

Le pincement a été réglé trois fois sans effet sur l'appareil. Le quatrième tour n'a pas cherché un
seuil mal placé mais une **impossibilité**, et il en a trouvé une.

Le seuil de contact était une fraction de l'écart au repos : 34 % de la course. Cela suppose de
savoir jusqu'où descend un pincement, et la supposition était fausse. Quand le pouce touche
l'index, les deux extrémités **se confondent dans l'image**, et le modèle de points de repère cesse
de les rapprocher : il conserve entre elles une distance plancher, non nulle, qui dépend de la main,
de l'angle et de la caméra. Mesuré en harnais, pour une course au repos de 0,75, le seuil tombait à
**0,255** — tout plancher réel à 0,25 ou au-dessus, valeur ordinaire, rendait l'appui inatteignable.
Aucun soin mis à pincer n'y changeait rien. Et l'application affichait alors « votre pouce
s'approche sans toucher l'index » à quelqu'un dont les doigts se touchaient parfaitement.

Le seuil dérive désormais de l'intervalle entre la course au repos et **le plus faible écart que
cette main a réellement produit**. Il ne peut donc plus être hors d'atteinte, puisqu'il est défini
par rapport à ce qui a été atteint. Une tentative qui échoue enseigne tout de même le plancher : la
calibration se fait en un ou deux essais au lieu de jamais.

| plancher réel de la mesure | avant | après |
|---|---|---|
| 0,20 | aboutit | aboutit |
| 0,25 à 0,40 | **jamais** | aboutit au 1er essai |
| 0,45 à 0,50 | **jamais** | aboutit au 2e essai |

Et aucune fermeture partielle jusqu'à 64 % de la course ne déclenche d'appui, main au repos comprise.

Apprendre le plancher demande une précaution qui n'est pas évidente. Il doit pouvoir **monter**,
sinon une main dont la mesure ne descend jamais sous 0,50 garderait un seuil hors d'atteinte. Mais
s'il monte librement, une fermeture délibérément partielle lui enseigne un plancher faussement haut,
le seuil se relâche d'autant, et une main à peine refermée déclenche un appui — des tests ont pris ce
second défaut en flagrant délit. Les deux cas sont indiscernables sur une image et se séparent sur
leur **persistance** : une butée de la mesure est tenue, un arrêt choisi est transitoire. Le plancher
ne monte donc que vers une valeur tenue plusieurs images.

Un second critère est tombé au passage : la fermeture devait durer moins de 240 ms. Le critère est
juste sur le geste — une impulsion volontaire est brève, un repositionnement de main est lent — et
intenable sur la mesure, parce qu'il est exprimé en millisecondes mais évalué en images, et que la
cadence réelle de la chaîne caméra-inférence sur un téléphone est de 12 à 20 images par seconde, non
30. Une fermeture volontaire mais posée de 300 ms était rejetée à **toute** cadence inférieure à 30.
Il ne discriminait d'ailleurs rien que la profondeur ne discrimine déjà : replacer sa main n'amène
pas le pouce sur l'index.

Le réglage de fermeté porte donc maintenant sur la **profondeur** exigée, qui ne dépend d'aucune
horloge, et non plus sur la vivacité.

#### La leçon, et les deux chemins qu'elle a ouverts

Le correctif ci-dessus traite une cause précise. Mais la leçon dépasse la cause : **tout appui
obtenu par l'articulation des doigts hérite de la fiabilité avec laquelle la caméra voit ces
doigts**, et rien ne garantit cette fiabilité. Une position, elle, est la grandeur la mieux mesurée
de toute la chaîne — celle qui marchait déjà quand rien d'autre ne marchait.

D'où deux chemins qui ne demandent aucune articulation :

- **Le clic par maintien.** Viser et ne plus bouger déclenche l'appui. C'est le mécanisme de tous
  les suivis oculaires et céphaliques, pour cette raison exactement. Il faut avoir **quitté** le
  dernier point cliqué pour en viser un autre, ce qui rend la rafale impossible ; l'anneau du
  curseur montre le compte à rebours, donc bouger l'annule. **Et il ne clique que sur une cible
  réellement cliquable** — voir ci-dessous.
- **Le défilement par les bords.** Poser le curseur contre un bord y fait défiler en continu,
  d'autant plus vite qu'on s'en approche. Il passe par les actions d'accessibilité du nœud
  défilable, et non par un doigt synthétisé : aucune trajectoire n'est transmise, donc le bruit de
  suivi ne peut rien perturber, et le pas est celui que l'application a choisi pour son contenu. Un
  délai d'amorçage de 320 ms sépare « se poser dans la bande » de « la traverser », et le bord droit
  reste au panneau de commandes.

Ni l'un ni l'autre ne remplace le pincement, plus rapide et plus intentionnel, ni le défilement au
tracé, seul à être fluide et à amplitude libre.

Le maintien a été raccourci de 900 à 520 ms sur retour d'usage — « la méthode me plaît mais c'est
trop lent ». La durée n'était d'ailleurs que la moitié du problème : le rayon de tolérance était
**constant**, si bien qu'une dérive tardive remettait le compte à zéro. Or une main tenue en l'air
dérive d'autant plus qu'on la tient longtemps : le rayon constant exigeait le plus de stabilité au
moment où la main en a le moins, et la durée nominale n'était jamais la durée vécue. Le rayon
s'élargit maintenant à mesure que l'attente avance — l'intention est déjà démontrée à ce stade.

#### Le défilement à vitesse commandée, et pourquoi il fallait changer de nature

Les deux méthodes ci-dessus sont en **contrôle de position** : la main se déplace, le contenu se
déplace d'autant. C'est le modèle du doigt sur la vitre, et il porte une limite qu'aucun réglage ne
lève — la distance défilable est bornée par la distance que le bras peut parcourir. Un article long
demande donc de répéter le geste, et répéter un geste aérien fatigue bien plus vite que répéter un
geste appuyé sur du verre. Le défilement par les bords contourne la borne par une répétition
automatique, mais au prix de garder le curseur dans une bande étroite, loin de ce qu'on lit.

La commande « Défilement continu » du panneau change de **nature** : le décalage de la main commande
une **vitesse**, pas une distance. Tenir la main dix pour cent plus bas que son point neutre fait
défiler lentement et sans fin ; trente pour cent plus bas, vite. Trois propriétés en découlent, dont
aucune n'est atteignable en contrôle de position :

1. la distance défilée est **sans rapport** avec la distance parcourue par la main — un livre entier
   se lit sans déplacer le bras de plus de quelques centimètres ;
2. la vitesse se règle **pendant** le défilement, comme on relâche un accélérateur, là où un geste de
   position est joué puis constaté ;
3. le point neutre est là où la main se pose, donc devant ce qu'on lit — rien à viser, aucun bord à
   atteindre.

Son coût propre est d'être un **mode**, et c'est pourquoi il est lié à une commande et non à un
geste : un mode dans lequel on tombe par accident est bien pire qu'un mode qu'on choisit. Tout
pincement en sort, de sorte qu'il existe toujours une sortie qui ne demande pas de retrouver le
panneau, et l'anneau du curseur porte la vitesse commandée — un mode sans retour visible est un
piège.

Le point neutre n'est **pas** pris à l'activation, et c'est un défaut qu'il a fallu voir avant qu'il
n'existe : la commande se choisit dans le panneau, au bord droit de l'écran. L'y fixer aurait fait
commander un défilement vers la gauche dès que la main revient vers le contenu — le mode se serait
mis à défiler tout seul, sur le mauvais axe, au moment de son activation. Il s'arme donc, puis
attend que la main se pose : la première position tenue 300 ms devient le zéro.

#### « Si on est en retard, ça clique au mauvais endroit » : deux causes

La première est un **bug**, et il explique le mot « en retard » à la lettre. Le maintien mesure
l'immobilité du *curseur*, et une chaîne qui décroche produit un curseur parfaitement immobile — la
dernière position connue, qui n'est pas celle de la main. Une pause de 400 ms dans l'arrivée des
images était donc comptée comme 400 ms de visée stable, et l'appui partait là où la main n'était
plus. L'attente est désormais relancée dès qu'un écart de plus de 140 ms sépare deux images : elle
ne compte que du temps pendant lequel elle a réellement observé la main.

La seconde n'est pas un bug mais une propriété du mécanisme : **le maintien ne peut pas distinguer
« je réfléchis » de « je veux cliquer ».** C'est le problème du toucher de Midas, commun à tous les
systèmes à temporisation, et le raccourcir ne fait que l'aggraver. On ne peut donc pas supprimer
l'erreur — mais on peut la rendre inoffensive : le maintien n'agit plus que si le curseur se pose
sur quelque chose de **réellement cliquable**, ce que l'arbre d'accessibilité sait dire. Rester
immobile au-dessus d'un paragraphe, d'une image ou d'une zone vide ne produit plus rien, et l'anneau
se vide au lieu de se remplir en vain, ce qui enseigne où le maintien fonctionne.

Ce qui subsiste — un appui involontaire sur un bouton effectivement visé — est le cas où l'intention
est de toute façon la plus probable, et le seul où un clic a un sens. La vérification coûte un
parcours de l'arbre d'accessibilité, soit un appel interprocessus par nœud : elle a donc lieu **une
fois par attente**, pas à chaque image, la seconde option ayant déjà causé une des saccades
corrigées plus bas.

#### Deux ajouts corrects, un défaut à leur rencontre

Le défilement par les bords demande de poser le curseur contre un bord et de ne plus bouger ; le clic
par maintien se déclenche précisément quand on ne bouge plus. Poser le curseur pour défiler appuyait
donc aussi sur ce qui se trouvait sous lui — au bord de l'écran, soit souvent une barre d'onglets ou
d'état. Le défilement n'était pas seulement gêné, il était dangereux.

Le maintien est désormais inhibé dans les bandes de défilement et pendant le mode à vitesse
commandée. Il est **désarmé** et pas seulement suspendu : sans cela, le curseur qui quitte la zone
libérerait l'appui resté en attente, à l'endroit où il arrive, que personne n'a visé. Ils garantissent que l'appareil reste utilisable
quand le geste ne part pas, et que parcourir un article long ne coûte pas dix pincements tenus.

#### La précision du curseur : ce que la mesure a contredit

Le soupçon naturel était le filtrage, ou l'amplification de la zone active. Les deux étaient faux.
La mesure en harnais — tremblement physiologique à 10 Hz plus bruit d'estimation, bruit résiduel
exprimé en pixels d'écran — a désigné la **prédiction de latence**.

Prédire, c'est extrapoler le long de la vitesse, et la vitesse est une **dérivée** du signal.
Dériver amplifie le bruit, et le résultat est réinjecté dans la position. Au repos, la « vitesse »
n'est donc que du bruit dérivé, et la prédiction le recopiait dans le curseur : elle **doublait**
le bruit résiduel en visée.

Or la prédiction ne sert qu'à compenser une latence perçue, laquelle n'existe qu'en mouvement : un
curseur immobile n'est jamais en retard. Elle est donc **coupée en visée** et pleine en mouvement.
Ce n'est pas un compromis mais une correction — les deux grandeurs s'améliorent ensemble :

| Réglage | Bruit en visée | Réponse à un départ |
|---|---|---|
| avant | 1,34 px | 99 ms |
| prédiction coupée en visée | 0,62 px | 99 ms |
| **+ filtre relissé (0,4 Hz, beta 3,5)** | **0,22 px** | **66 ms** |

Soit **six fois moins de bruit** et un tiers de réponse en moins. Le relissage mérite un mot : une
coupure plus basse *et* un `beta` plus fort donnent les deux à la fois, alors qu'à `beta` constant
les deux s'opposent. C'est en relevant `beta` que le compromis se dissout, puisque la coupure
remonte alors d'autant plus vite que la main part.

Les valeurs absolues dépendent du modèle de bruit injecté ; c'est le rapport entre les lignes qui
est le résultat.

Reste une limite qu'aucun filtre ne franchit : le tremblement physiologique de la main tendue
couvre plusieurs millimètres à l'écran. C'est pourquoi l'aimantation vers les objets existe — viser
des objets plutôt que des pixels est la seule façon de dépasser la physiologie.

#### Pourquoi le défilement s'appuie sur la paume, et pas sur l'index

Le curseur vient du bout de l'index, et c'est juste pour **viser** — c'est avec lui qu'on pointe.
Mais pendant un pincement, le pouce vient se poser précisément sur ce bout d'index : les deux
extrémités se confondent dans l'image, et l'estimation devient bruitée **exactement pendant qu'un
pincement est tenu**, donc exactement pendant un défilement. Le gain multipliait alors ce bruit avec
l'intention.

Le **centre de la paume** — barycentre du poignet et des quatre articulations métacarpiennes — ne
bouge pas quand les doigts se ferment, et c'est le groupe de points le mieux suivi de la main. Le
défilement s'appuie donc sur lui : il n'y a plus à maintenir une géométrie de doigts stable en
déplaçant le bras, seulement à déplacer le bras.

Mesuré en harnais sur un balayage : un bruit de ±2 % sur le signal pilote rendait les pas du
défilement **2,6 fois plus irréguliers**, et un curseur agité de ±6 % déplace désormais le doigt de
zéro tant que la paume reste immobile.

Le déplacement qui départage un appui d'un tracé se mesure lui aussi sur la paume : c'est la main
qui décide, et un curseur qui tremble parce que le pouce s'y est posé ne doit pas transformer un
appui en glisser.

Deux corrections s'y ajoutent, du même ordre :

- **Le relâchement demande deux images consécutives.** Une seule image de suivi aberrante — un flou
  de mouvement pendant un balayage — suffisait à faire croire que le pouce s'était rouvert : le
  tracé se terminait, le défilement s'arrêtait en pleine course, et il fallait repincer alors que
  le pouce touchait toujours l'index.
- **L'appui est ancré avant la fermeture.** L'ancre était figée à l'instant du contact, où le pouce
  est déjà arrivé sur le bout d'index et en déplace l'estimation : l'appui partait quelques
  millimètres à côté, systématiquement dans la direction d'où venait le pouce. La position retenue
  est celle d'avant la fermeture — mais seulement si la main était stable, car viser c'est garder
  la main immobile. Si elle se déplaçait, c'est la position du contact qui sert, pour ne pas
  introduire de retard.

#### L'appui : ce qui le rendait imprécis et intermittent

Deux défauts distincts, tous deux dans le code et non dans le réglage.

**Imprécis** — l'aimantation ramenait l'appui au **centre** de la cible, même lorsque le point était
déjà dedans. Dans un champ de texte le curseur partait donc toujours au milieu ; sur un réglage de
volume, seule la position médiane était atteignable ; dans une carte ou une image, on visait le
centre quoi qu'on fasse. Être dedans, c'est avoir réussi son geste : il n'y a rien à corriger, et
corriger quand même revient à perdre volontairement l'information que l'utilisateur venait de
fournir. L'aimantation ne déplace plus l'appui que lorsqu'il tombe **à côté** d'une cible proche —
le seul cas où elle sert à quelque chose.

**Intermittent** — le verrou qui empêche de juger deux fois la même fermeture ne se levait qu'une
fois la main revenue au-dessus de 72 % de sa ligne de base, une valeur **absolue**. Une main dont la
position de repos tombait sous cette bande ne pouvait donc plus jamais le lever : un seul rejet, et
plus aucun appui ne partait tant que la main n'était pas grande ouverte. Le défilement s'arrêtait
avec lui, puisqu'il a besoin du même enfoncement. Le seuil est désormais **relatif à la course** de
l'utilisateur, comme tout le reste du détecteur.

S'y ajoutait une adaptation trop lente : la ligne de base ne descendait qu'à 1,2 % par image, soit
près de trois secondes pour rejoindre une nouvelle position de repos — pendant lesquelles la course
mesurée était celle d'une main qui n'existait plus. Elle rejoint maintenant une main **posée** en
quelques images, tout en refusant toujours de suivre une main qui se **ferme** : les deux cas se
distinguent au déplacement net sur quelques images, là où un écart par image ne peut pas les
séparer.

#### Le défilement : pourquoi le doigt est décollé du curseur

Le doigt synthétique a d'abord suivi la **position absolue** du curseur. Trois symptômes en
découlaient, et ils n'en faisaient qu'un :

- **Capacité** — ce qu'on pouvait défiler d'un pincement était borné par la distance entre le point
  de départ et le bord de l'écran. Pincer au milieu donnait une demi-page, d'où des
  pincer-tirer-relâcher à répétition.
- **Élan** — une fois le curseur buté sur le bord, le déplacement devenait nul et plus aucun segment
  n'était émis. Android mesurait donc une vitesse nulle au relâchement : **aucune inertie**.
- **Effort** — le rapport était de 1:1 : défiler une page demandait de déplacer la main d'une page.

Le doigt est désormais **décollé** du curseur ([`StrokeKinematics`]). Il avance par incréments
amplifiés — 1:1 sur les 9 premiers pour cent de déplacement, puis un gain progressif jusqu'à 3,4 —
dans une zone bornée dont il ne sort jamais : au bord, il est relevé et reposé de l'autre côté pour
continuer dans le même sens.

La conséquence la plus importante n'est pas la capacité, c'est l'**élan**. Comme le doigt ne se fige
plus jamais, le dernier segment porte toujours une vraie vitesse, et Android lance l'inertie comme
pour un doigt sur la vitre. La capacité vient donc du mouvement, pas de la course du bras : un
balayage franc fait défiler plusieurs pages, puis le contenu continue seul.

Le doigt part du **centre de la zone défilable**, et non du point pincé : c'est ce qui donne la même
course dans les quatre directions, là où pincer en bas de l'écran ne laissait presque rien pour
défiler vers le bas.

La vivacité se règle dans l'écran, en trois positions — « Précis », « Standard », « Rapide » —
parce que c'est la seule part du geste qui ne s'apprend pas toute seule : elle n'est pas mesurable
mais préférentielle. Le début du geste reste au rapport 1:1 dans les trois positions.

L'amplification ne s'applique **pas à tout** — amplifier un glisser d'icône ou le curseur d'un
réglage le rendrait faux. Le mode est choisi par ce qui se trouve sous le doigt, demandé au système
lui-même : sur une zone défilable, amplification et repositionnement ; partout ailleurs, 1:1 strict.
Quand l'application ne déclare pas ses zones défilables, on retombe sur le 1:1, donc sur le
comportement précédent : jamais pire. Les axes viennent des actions que le nœud déclare, de sorte
qu'un déplacement horizontal sur une liste verticale n'y écarte pas l'élément sous le doigt.

Un repositionnement suivi d'un relâchement immédiat serait un contact posé et relevé presque au
même endroit — **un appui**, qui ouvrirait l'élément sous le doigt au lieu de défiler. Un
dégagement minimal est donc garanti avant toute levée.

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

### Les cinq secondes avant que le service démarre

`onStartCommand` tourne sur le **thread principal**, et y construisait l'analyseur, dont le
constructeur enchaînait jusqu'à **trois** créations de `HandLandmarker` — GPU, puis CPU par asset,
puis CPU en mémoire — chacune chargeant un modèle de 7,8 Mo. La caméra ne démarrait qu'après. Rien
ne s'affichait pendant ce temps, et un service de premier plan qui bloque son thread principal aussi
longtemps frôle l'ANR.

Deux corrections, qui se multiplient plutôt que de s'ajouter :

- **Le chargement sort du thread principal et court en parallèle de la caméra.** Les deux
  initialisations sont indépendantes et duraient l'une après l'autre ; le coût est maintenant celui
  de la plus lente, non leur somme. Les images qui arrivent avant que le modèle soit prêt sont
  ignorées sans frais — quelques images au tout début, rien ensuite.
- **Le chemin d'initialisation qui a marché est mémorisé.** La cascade existe parce qu'on ne peut
  pas savoir *à l'avance* lequel des trois fonctionne sur un appareil donné — mais on peut le savoir
  **après**, et cela ne change pas. Trois chargements de modèle deviennent un seul à chaque
  démarrage suivant. Le gain est le plus net précisément sur les appareils dont le delegate GPU ne
  tient pas : c'était l'essai le plus coûteux qui était rejoué à chaque fois.

Rien n'est invalidé explicitement, et ce n'est pas un oubli : une valeur devenue fausse — une mise à
jour système qui casse le GPU — ne coûte qu'un essai raté avant que la cascade reprenne son cours et
réécrive la préférence. Un cache dont l'erreur se corrige d'elle-même n'a pas besoin d'être invalidé.

## Consommation

La caméra et l'inférence en continu sont, ensemble, l'un des profils les plus gourmands qu'une
application Android puisse avoir. Trois mécanismes réduisent la facture, du plus fort au plus fin :

| Mécanisme | Effet |
|---|---|
| Caméra déliée quand l'écran s'éteint | éteint capteur et processeur d'image, pas seulement l'analyse |
| Régulation de cadence (`FrameGate`) | analyse à 2,5 img/s au repos au lieu de 30 |
| Images de caméra réutilisées | supprime ~36 Mo/s d'allocation, donc les pauses de ramasse-miettes |
| Parcours d'accessibilité élagués | quelques nœuds au lieu de 400, au moment précis de l'appui |
| Superposition redessinée sur changement seul | n'invalide plus quand la main vise, donc immobile |

### Les trois saccades, et leur cause

Chacune avait une cause mesurable, et aucune n'était un problème de réglage.

**L'allocation d'une image par frame.** `Bitmap.createBitmap(source, …, matrix, true)` allouait une
image entière à chaque frame — 640 × 480 × 4 octets, trente fois par seconde, soit environ
**36 Mo/s** à ramasser. La pause de ramasse-miettes qui s'ensuit est exactement ce qu'on ressent
comme « ça rame », et elle est provoquée par le flux de la caméra lui-même. Les images de
destination sont désormais réutilisées à tour de rôle et dessinées au canevas. Trois suffisent :
le mode LIVE_STREAM ne traite qu'une inférence à la fois, de sorte qu'au moment de réécrire une
image on en a publié deux autres depuis.

**Les parcours de l'arbre d'accessibilité.** Les deux parcours énuméraient tout l'écran — jusqu'à
400 nœuds — pour que l'appelant n'en retienne ensuite que le voisinage du point visé. Or chaque
nœud se lit par un **appel interprocessus**, et le parcours a lieu sur le **thread principal**, à
l'instant où l'utilisateur appuie : la saccade frappait donc au moment où l'on attend une réponse.
L'élagage est géométrique — on ne descend que dans les nœuds dont les limites rencontrent la zone
cherchée — et sûr, à une marge près qui absorbe les limites approximatives.

**Le redessin perpétuel de la superposition.** Elle invalidait à chaque image même main immobile,
ce qui fait recomposer tout l'écran, application au premier plan comprise — pour un anneau
décoratif qui tourne. Elle ne redessine plus que sur un changement perceptible : un demi-pixel de
déplacement, ou un demi-degré d'arc. Conséquence visible : l'anneau se **fige** quand la main est
parfaitement stable. C'est voulu, et c'est même un retour utile — il signale que la visée est
verrouillée.

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
par 166 tests JVM (exécution en quelques dizaines de
millisecondes, sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

`SyntheticHand` génère des poses de main paramétrables (courbure de chaque doigt, écart du
pincement, échelle, position), ce qui rend les scénarios gestuels parfaitement reproductibles :
absence de rebond au seuil, unicité de l'appui, arbitrage entre appui, appui long et tracé,
cinématique du défilement — course symétrique, doigt jamais figé, jamais confondu avec un appui —
invariance d'échelle, bruit et latence du filtre.

## Intégration continue

Le workflow `.github/workflows/build-release-apk.yml` exécute les tests JVM puis assemble la
variante installable, publiée dans la release `latest` :

| Fichier | Minifiée par R8 | Usage |
|---|---|---|
| `a-release.apk` | oui | l'artefact installable |

La signature dépend des secrets du dépôt : avec les quatre secrets décrits plus bas, l'APK est
signée de la clé de diffusion ; sans eux, le workflow retombe sur la clé de debug pour rester
assemblable, et le dit dans les notes de release. Le workflow vérifie au passage que
`hand_landmarker.task` est bien présent dans
les assets **et** qu'il contient ses deux modèles internes : un modèle manquant ne casse pas la
compilation, il casse la détection à l'exécution — c'est-à-dire au seul endroit où le défaut ne
serait plus détectable automatiquement. Il vérifie aussi que `gesture_recognizer.task` a bien
disparu de l'APK, pour que le modèle du classifieur retiré ne continue pas d'y peser.

Le tag est glissant : il est recréé sur le commit courant à chaque build, ce qui donne une
adresse de téléchargement permanente pour la dernière version. Les assets de release sont aussi
préférés aux artefacts Actions parce qu'ils ne consomment pas le quota de stockage des
artefacts, commun à tout le compte GitHub.

Le `versionCode` est dérivé de `GITHUB_RUN_NUMBER`. Ce n'est pas cosmétique : Android refuse
d'installer par-dessus une application de même signature si le `versionCode` n'a pas augmenté.
Avec une valeur figée, chaque mise à jour imposait une désinstallation manuelle, qui efface les
réglages **et** l'autorisation d'accessibilité. Le numéro d'exécution croît strictement, ce qui
est exactement la garantie demandée.

## Signature

### Sans clé déposée, chaque build a une signature différente

À connaître avant toute chose, parce que le symptôme ne dit pas sa cause. Faute de clé de release,
le build retombe sur `signingConfigs.getByName("debug")` — la configuration de debug **interne à
Gradle**, adossée à `~/.android/debug.keystore`. Ce fichier n'existe pas sur un runner
d'intégration continue, qui est neuf à chaque exécution : Gradle en génère un, avec une clé
**aléatoire**, à chaque build.

Deux APK produites ici n'ont donc jamais la même signature, et Android refuse d'installer par-dessus
une signature différente. Le message affiché est « Application non installée », qui ne distingue pas
ce cas d'une dizaine d'autres. Il faut désinstaller l'application avant chaque installation, ce qui
efface les réglages **et** l'autorisation d'accessibilité.

Déposer les quatre secrets ci-dessous supprime la contrainte pour de bon : la clé devient stable, et
chaque build s'installe par-dessus le précédent en conservant les données.

### Créer la clé, une seule fois

```bash
keytool -genkeypair -v \
  -keystore ma-cle.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

**Conservez ce fichier et ses mots de passe hors du dépôt, et sauvegardez-les.** Android
identifie une application par son couple nom de paquet / clé de signature : perdre la clé rend
toute mise à jour future impossible — les utilisateurs devraient désinstaller puis réinstaller,
en perdant leurs données. `*.jks` et `*.keystore` sont dans `.gitignore`, et une clé privée qui
entre dans l'historique Git y reste même après suppression du fichier.

### Déclarer la clé à l'intégration continue

Encodez le keystore en base64 — un secret GitHub ne transporte que du texte :

```bash
base64 -w0 ma-cle.jks > ma-cle.b64
```

Puis, dans *Settings → Secrets and variables → Actions → New repository secret*, créez les
quatre secrets suivants :

| Secret | Contenu |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | le contenu de `ma-cle.b64` |
| `RELEASE_STORE_PASSWORD` | le mot de passe du keystore |
| `RELEASE_KEY_ALIAS` | l'alias, `upload` ci-dessus |
| `RELEASE_KEY_PASSWORD` | le mot de passe de la clé |

Le workflow décode le keystore dans `$RUNNER_TEMP`, hors de l'arborescence du dépôt : il ne peut
donc être ni committé par mégarde ni téléversé parmi les artefacts, et il est effacé en fin de
job même si celui-ci échoue. Le build affiche ensuite le certificat de l'APK produite et
**échoue** si la signature attendue était celle de diffusion mais que le certificat porte
`CN=Android Debug` — une APK silencieusement mal signée est le défaut qu'on ne remarque qu'au
moment où la mise à jour est refusée sur l'appareil.

### Signer en local

```bash
KEYSTORE_PATH=/chemin/vers/ma-cle.jks \
KEY_ALIAS=upload STORE_PASSWORD=... KEY_PASSWORD=... \
  ./gradlew assembleRelease
```

Les configurations de signature ne sont créées que si le fichier de keystore existe réellement :
sans cela, toute machine dépourvue de clé échouerait à assembler la variante release. Sans
keystore et sans `-PstarkDebugSigning`, AGP produit `app-release-unsigned.apk`, qu'Android
refuse d'installer.

## Configuration requise

- Caméra frontale (l'application reste fonctionnelle en mode tactile/simulation sans caméra)
- Le service d'accessibilité doit être activé manuellement dans les réglages Android pour que
  les gestes agissent hors de l'application (appuis, défilement, panneau de commandes)
- Aucune connexion réseau nécessaire : l'inférence est entièrement locale
