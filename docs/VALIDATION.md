# Rapport de validation — Square Fusion

Ce rapport distingue ce qui a été **vérifié automatiquement** (et où), ce qui
a été **vérifié sur émulateur**, et ce qui **reste à vérifier sur votre
téléphone**. Rien n'a été testé sur un appareil physique.

Chaque build publié joint ses preuves à sa release `fusion-N`
(`test-results.zip` : journaux, captures d'écran, vidages d'écran) ; les
lignes OK / WARN / FAIL apparaissent aussi en annotations sur la page du run.

## 1. Compilation et livraison (GitHub Actions, `fusion.yml`)

| Vérification | Résultat |
|---|---|
| Tests unitaires de Square et de Fusion (`testDevUnitTest`) | OK |
| `assembleRelease` (R8, comme les versions officielles de Square) | OK |
| APK : identifiant `dev.lelonio.square.fusion`, version `<Square>-fusion.N`, signature vérifiée par `apksigner`, `libsquarecore.so` présent | OK |
| Signature : même certificat d'un build à l'autre (comparé sur deux runs) | OK |
| Publication seulement si les tests sur émulateur passent et que la signature est continue | en place |

## 2. Tests unitaires (JVM, à chaque build)

`FusionLogicTest` :
- qualité : chaque niveau choisit un flux différent sur une liste type
  (139 / 250 / 140 / 251) ; les formats ~256 kbps gagnent en Maximale quand
  YouTube les liste ; un niveau absent retombe sur un voisin ; liste vide → rien ;
- correspondance Spotify → YouTube Music : même enregistrement accepté (y
  compris « Official Audio ») ; live, remix, autre artiste, durée trop
  différente, autre titre refusés ; l'original préféré à une cover ; artistes
  invités et accents ;
- liens Spotify sous toutes leurs formes ; page publique (embed) lue : nom,
  propriétaire, pochette, titres dans l'ordre, épisodes ignorés, page vide →
  indisponible ; réponses de l'API (playlist, playlist introuvable, artistes
  suivis) ;
- comparaison des versions du système de mise à jour (`2.6.3-fusion.14` >
  `…13`, `2.6.4-fusion.20` > `2.6.3-fusion.25`).

`SpotifyFreeLibraryTest` (repris de BitChord) : playlists, titres, titres
likés, en-têtes de jeton, 401 → session expirée, 503 → pas une expiration,
réponse inattendue, erreur GraphQL.

## 3. Émulateur Android 11 (x86_64 avec traduction ARM), à chaque build

### Lancement et intégration (APK release)
Installation ; une seule entrée de lanceur ; lancement ; intent explicite ;
liens `spotify:` et `open.spotify.com` ; choix de YouTube Music au premier
lancement ; arrière-plan puis reprise ; écran éteint puis rallumé ; aucun
plantage.

### Tests instrumentés (build debug)
`FusionDeviceTest` : icône Square par défaut ; chacune des 14 icônes
appliquée, toujours exactement une entrée de lanceur et `MainActivity`
joignable ; valeurs par défaut (fréquence désactivée, moteur forcé sur YouTube
Music) ; fréquences lues sur le vrai écran.
`FusionScreensTest` : sélecteur d'icônes, Performance (désactivé par défaut,
60 Hz proposé une fois activé), section Spotify (« Connecter Spotify »).

### Spotify et lecture directe (build debug, sans compte)
| Étape | Résultat |
|---|---|
| Premier lancement : pas de choix Spotify | OK |
| Accueil : plus de sélecteur de source | OK |
| Bibliothèque › + › Importer une playlist › lien public › Importer | OK : « Today's Top Hits », 50 titres |
| Lecture : le premier titre demandé est le premier de la playlist (ordre conservé) | OK |
| Correspondance sur YouTube Music | OK (ex. « Manchild », Sabrina Carpenter → `DntZ3-yCaFs`) |
| Son réellement joué | **non vérifiable ici** : YouTube refuse les flux anonymes au réseau du runner ; l'application ne plante pas |

### Ajouts de la version « podcasts, téléchargements, Apple Music » (build debug)
| Étape | Résultat |
|---|---|
| Accueil : puce « Podcasts » présente, page podcasts de YouTube Music chargée | OK |
| Recherche « Flippe Go Sleep » › filtre Podcasts sélectionné | OK |
| Ouverture du podcast trouvé : épisodes affichés, pas de « Aucun titre » | OK |
| Retour à la recherche : filtre Podcasts toujours sélectionné | OK |
| Bibliothèque › + › Importer › lien Apple Music public (« Today's Hits ») | OK : 50 titres |
| Import Spotify, ordre de lecture, correspondance YouTube Music | OK (inchangé) |

Diagnostic des podcasts vides, fait contre YouTube Music depuis GitHub
Actions avant la correction : pour « Flippe & Go Sleep », « SIP & GOSSIP »
(Maghla) et « Les Grosses Têtes », la lecture de l'identifiant `MPSP…` comme
playlist renvoyait 0 titre sans erreur ; la liste de l'émission (même
identifiant sans `MPSP`) renvoie 22, 35 et 100 épisodes.

Unitaires ajoutés : lecture d'une page Apple Music (titre, curateur, pochette,
titres dans l'ordre, vidéos ignorées, page vide → indisponible) et de ses liens.

### Ajouts « nom et pochette, qualité affichée, profil, salutation »
| Étape | Résultat |
|---|---|
| Playlist importée › More › Modifier › nouveau nom › Enregistrer : pas d'erreur, nom affiché | OK |
| Après arrêt de l'application et nouvelle lecture depuis Spotify : nom conservé | OK |
| Pochette personnelle (test instrumenté) : affichée, image de la source intacte, nouvelle pochette de synchro restée dessous, retour à l'originale | OK |
| Photo de profil (test instrumenté) : image choisie gardée carrée, puis retirée | OK |
| Accueil : salutation selon l'heure (« Good afternoon » à 14 h, sans compte) à la place de « Square » | OK |
| Paramètres : plus de case « Soutenir le développement » | OK |
| À propos : plus de Ko-fi ; dépôt Square Fusion affiché (la mention du développeur d'origine est revenue dans la version suivante) | OK |
| Unitaires : heures de la salutation, codecs de la ligne de qualité, durées Spotify (`trackDuration`) | OK |


### Ajouts « recadrage, ajout aux playlists, icône de notification, notes de version »
| Étape | Résultat |
|---|---|
| Menu d'une playlist importée : ni « Retirer de la bibliothèque » ni YTDLnis ; « Supprimer » présent | OK |
| Menu d'un titre : pas de YTDLnis | OK |
| Fenêtre de modification : plus de « Changer la pochette » | OK |
| Titre « Bibliothèque » et salutation : une seule ligne, dans l'écran (320 px de large) | OK |
| Titre › Ajouter à une playlist, sans compte : playlists importées Spotify et Apple Music proposées | OK |
| Titre ajouté à la playlist Apple Music, retrouvé dans celle-ci après arrêt de l'application | OK |
| À propos : « Développé par » Lelonio, github.com/Lelonio ; pas de Ko-fi | OK |
| Menus sur Android 11 : ouverture en ~1,6 s sur l'émulateur, 0 recalcul du flou menu ouvert | OK |
| Recadrage (test instrumenté), carré et cercle : image carrée, gauche et droite d'une image 2:1 conservées sans étirement ; annuler ne renvoie rien | OK |
| Icône de notification (test instrumenté) : pour les 14 icônes, l'icône choisie est appliquée, le changement est annoncé, l'icône de notification se charge | OK |
| Notes de version (`scripts/release-notes.sh`, testé localement) : fichier modifié depuis la version précédente → ce fichier ; inchangé → commits depuis la version précédente ; aucun commit → « Nouvelle compilation… » ; version précédente inconnue → le fichier | OK |

Non vérifiable ici : la pastille de qualité pendant une vraie lecture
(YouTube refuse la lecture sur l'émulateur de GitHub), l'icône réellement
dessinée par la barre d'état et l'écran de verrouillage d'un téléphone, le
geste de recadrage au doigt (le test valide le cadrage de départ), la
fenêtre de mise à jour sur votre téléphone avec les nouvelles notes.

## 4. À vérifier sur votre téléphone
- Lecture réelle d'un titre Spotify via YouTube Music, suivant / précédent /
  aléatoire / répétition dans une playlist importée.
- Qualité : changement de niveau entendu, « Dernier flux » affiché ; ~256 kbps
  seulement avec un compte YouTube Music Premium connecté (non testé).
- Connexion Spotify réelle (compte gratuit), playlists, titres likés, artistes
  suivis, playlist privée importée avec le compte.
- Téléchargement d'une playlist depuis son menu, avec chaque qualité de
  téléchargement (le débit du fichier est visible dans les téléchargements).
- Ligne de qualité sous la barre de lecture pendant une vraie lecture (YouTube
  refuse la lecture sur l'émulateur de GitHub), en streaming et sur un titre
  téléchargé.
- Choix d'une pochette et d'une photo avec le sélecteur de photos d'Android.
- Photo et nom du compte YouTube (aucun compte ici).
- Renommage d'une playlist YouTube du compte (envoyé à YouTube).
- Lecture des épisodes de podcast (YouTube refuse la lecture anonyme ici).
- Mise à jour depuis l'application (Paramètres › À propos), avec
  l'installateur d'Android.
- 120 Hz (l'émulateur n'a que 60 Hz), lanceur de votre téléphone pour les
  icônes, autonomie.

## 5. Ce qui n'a pas été modifié

Moteur ExoPlayer, recherche, file d'attente, paroles, design. Dans
`PlaybackService`, seule la petite icône de la notification a changé (elle
suit l'icône d'application choisie). Les points d'accroche ajoutés dans le code de
Square sont listés dans NOTICE.
