# Square Fusion

Square Fusion est Square (https://github.com/Lelonio/Square) avec quelques
ajouts. Elle s'installe **à côté** de Square : identifiant
`dev.lelonio.square.fusion`, nom « Square Fusion ». Square n'est ni remplacé,
ni désinstallé, ni modifié, et ses données ne sont pas touchées.

## Installer

1. Sur le téléphone, ouvrir
   https://github.com/httpsMrAbdelhak/Square_fusion/releases/latest/download/square-fusion.apk
   (APK universel). Les APK par architecture (`arm64-v8a`, `armeabi-v7a`) sont
   dans la même release.
2. Autoriser le navigateur (ou le gestionnaire de fichiers) à installer des
   applications inconnues quand Android le demande.
3. Installer. Une version précédente de Square Fusion est mise à jour, ses
   données conservées, tant que la signature est la même (voir « Signature »).

## Ce qui diffère de Square

### YouTube Music uniquement
- Le premier lancement ne propose que YouTube Music.
- Le sélecteur YouTube Music / Spotify en haut de l'accueil est retiré, sans
  remplacement. Un ancien choix « Spotify » enregistré est réécrit en
  YouTube Music au démarrage ; rien d'autre n'est effacé.
- Le moteur Spotify Premium de Square (librespot) reste dans le code mais n'est
  plus jamais sélectionné.

### Qualité audio — Paramètres › Lecture › Son › Qualité YouTube Music
Automatique (Haute en Wi-Fi, Basse sur réseau limité), Basse, Moyenne, Haute,
Maximale. Chaque niveau change réellement le flux choisi parmi ceux que
YouTube liste pour le morceau :

| Niveau | Flux choisi | Débit annoncé par YouTube |
| --- | --- | --- |
| Basse | le plus petit | ~48–50 kbps (AAC-HE 139 ou Opus 249) |
| Moyenne | le plus proche de 80 kbps | ~70 kbps (Opus 250) |
| Haute | le meilleur AAC (choix d'origine de Square) | ~128 kbps (AAC 140) |
| Maximale | le plus haut débit | ~130–160 kbps (Opus 251) ; ~256 kbps (141/774) seulement si YouTube le fournit |

- Les ~256 kbps ne sont fournis par YouTube qu'à un compte **YouTube Music
  Premium connecté** (Paramètres › Compte › YouTube). En Maximale avec un compte
  connecté, l'application les demande d'abord ; sinon elle prend le meilleur
  flux disponible. Ce n'est pas garanti et ce n'a pas pu être vérifié ici
  (aucun compte Premium disponible).
- Un niveau absent pour un morceau retombe sur le plus proche : aucun morceau
  ne cesse d'être lisible à cause de la qualité.
- « Dernier flux » sous le réglage : codec et débit **annoncés** par YouTube
  pour le dernier morceau. Ce n'est pas une mesure du fichier.
- L'Opus est décodé par le FFmpeg intégré à l'application.

### Spotify — Paramètres › Compte › Spotify
- « Connecter Spotify » ouvre la page de connexion officielle de Spotify dans
  une WebView (mécanisme de BitChord). Le mot de passe n'est jamais vu par
  l'application ; le cookie de session est conservé **chiffré** sur le
  téléphone. Comptes gratuits acceptés. « Déconnecter » l'efface.
- Une fois connecté, l'onglet Bibliothèque de Square montre, mélangés aux
  playlists YouTube : les playlists du compte et celles qu'il suit, « Titres
  likés », et les artistes suivis. Les données viennent de Spotify ; rien
  n'est inventé. Elles sont gardées sur le téléphone pour l'affichage hors
  ligne.
- Un artiste Spotify ouvre la page de l'artiste du même nom sur YouTube Music.

### Importer une playlist — Bibliothèque › + › Importer une playlist
- Coller un lien `open.spotify.com/playlist/…` (ou `spotify:playlist:…`). Nom,
  propriétaire, pochette et tous les titres sont récupérés dans l'ordre, et la
  playlist apparaît dans la Bibliothèque. Une playlist publique s'importe sans
  compte ; une playlist privée demande le compte connecté qui y a accès.
- Un lien de playlist YouTube Music (`…list=…`) ouvre cette playlist.
- Un lien Apple Music (`music.apple.com/<pays>/playlist/…/pl.…`) : titre,
  curateur, pochette et titres (titre, artiste, album, durée) lus sur la page
  publique d'Apple, dans l'ordre, sans compte. Une playlist personnelle
  (`pl.u-…`) non partagée publiquement ou retirée est signalée indisponible.
  Si la page publique ne liste qu'une partie des titres, seule cette partie
  est importée (le journal l'indique).
- Erreurs : lien invalide, playlist privée ou introuvable, réseau, session
  expirée ; un message court, rien ne bloque l'application.
- Appui long sur une playlist importée › « Retirer de la bibliothèque ».

### Lecture directe des titres Spotify
- Un titre Spotify se lit directement : l'application cherche le même
  enregistrement sur YouTube Music (titre, artiste, album, durée) au moment où
  il est chargé, puis le lit. Les versions live, remix, acoustique, cover,
  accélérée… ne sont retenues que si le titre Spotify l'est aussi ; une durée
  qui diffère de plus de 15 s est refusée.
- Trouvé : la correspondance est mémorisée sur le téléphone. Introuvable :
  message « introuvable sur YouTube Music », le lecteur passe au suivant ; une
  nouvelle tentative est possible après deux minutes.
- La file garde l'ordre de la playlist ; suivant, précédent, aléatoire et
  répétition sont ceux de Square.
- Aucune lecture Spotify Premium n'est utilisée.

### Téléchargement des playlists
- Menu d'une playlist, d'un album ou d'un artiste ouvert › « Télécharger » :
  la même ligne, la même icône et le même système de téléchargement que
  Square (file d'attente, progression, reprise, fichiers partagés entre
  listes sans doublon, ordre de la liste conservé).
- Les playlists Spotify et Apple Music importées se téléchargent aussi :
  chaque titre est téléchargé depuis sa correspondance YouTube Music. Un titre
  sans correspondance fiable est marqué en échec, sans réessais inutiles.
- Un podcast YouTube Music ouvert se télécharge de la même façon (ses épisodes).

### Qualité des téléchargements — Paramètres › Lecture › Son
Basse, Moyenne, Haute, Maximale (par défaut, comme avant), indépendante de la
qualité d'écoute. Le flux est réellement choisi selon ce réglage au moment du
téléchargement, y compris pour les playlists ; ~256 kbps seulement avec un
compte YouTube Music Premium connecté. Le débit enregistré pour le fichier est
celui annoncé par YouTube pour le flux choisi. Les fichiers déjà téléchargés
gardent leur qualité.

### Podcasts
- Accueil : puce « Podcasts » à côté de celles de YouTube Music (ajoutée
  seulement si YouTube n'en propose pas déjà une). Elle affiche la page
  podcasts de YouTube Music (émissions, épisodes, sélections), personnalisée
  par YouTube quand un compte est connecté, précédée des nouveaux épisodes des
  podcasts suivis.
- Correction : un podcast trouvé par la recherche ou enregistré s'ouvrait sur
  « Aucun titre ». Square lisait la page de l'émission (`MPSP…`) comme une
  playlist, ce à quoi YouTube répond par une liste vide sans erreur. Les
  épisodes sont maintenant lus dans la liste dont l'émission est faite.
  Vérifié sur « Flippe & Go Sleep » et « SIP & GOSSIP » (Maghla) et « Les
  Grosses Têtes » : 0 épisode avant, 22, 35 et 100 après.

### Nom et pochette des playlists — Bibliothèque › appui long › Modifier
- Correction : Square proposait « Modifier » sur toutes les playlists YouTube
  de la bibliothèque et envoyait le nouveau nom à YouTube, qui refuse pour une
  playlist simplement enregistrée (pas créée par le compte) ou pour « J'aime » ;
  l'erreur s'affichait comme un problème de connexion et le nom était perdu.
- Maintenant le nom n'est envoyé à YouTube que pour les playlists du compte,
  comme YouTube l'indique. Ailleurs, ou si YouTube ne répond pas, le nom est
  gardé dans Square et affiché partout, sans erreur, après redémarrage aussi.
- Pochette personnelle pour chaque playlist : touchez la pochette dans la
  fenêtre de modification, choisissez une photo, puis cadrez-la (glisser pour
  déplacer, pincer pour zoomer, double-tap pour revenir au cadrage de départ ;
  ✓ pour valider, ✕ pour annuler sans rien changer). L'image enregistrée est
  carrée, sans déformation. « Pochette d'origine » revient à celle de la
  source. Elle reste sur
  le téléphone : la pochette Spotify ou YouTube n'est jamais modifiée.
- Les playlists Spotify et Apple Music importées se modifient aussi.
- Nom et pochette personnels sont posés par-dessus les données de la source :
  la synchronisation continue de mettre à jour titres et pochette d'origine
  en dessous, sans jamais écraser le choix fait dans Square.

### Qualité affichée pendant la lecture
Sous la barre de progression, entre les deux temps, dans une petite pastille
Liquid Glass : le codec réellement
entré dans le décodeur pour le titre en cours (AAC, Opus…) et le débit, qui
est :
- pour un titre téléchargé, celui du fichier lu (taille ÷ durée), mesuré ;
- en streaming, celui que YouTube indique pour le flux réellement choisi pour
  ce titre, affiché seulement si ce flux est dans le codec décodé.
Sans débit connu, seul le codec est affiché ; rien avant que le décodeur ait
reçu le flux.

### Accueil et profil
- En haut à gauche, à côté du logo : « Bonjour », « Bon après-midi » ou
  « Bonsoir » selon l'heure du téléphone (5 h–12 h, 12 h–18 h, ensuite),
  suivi du nom du compte YouTube connecté ; sans compte, la salutation seule.
  Mise à jour chaque minute. Le texte rapetisse si besoin pour tenir en
  entier sur une ligne, sans points de suspension ; de même pour le titre
  « Bibliothèque ».
- Photo de profil : celle du compte YouTube connecté, ou une photo choisie
  dans Paramètres › Compte › Photo de profil, puis cadrée dans un cercle
  (« Utiliser la photo du compte » pour revenir). Elle reste sur le téléphone ; le profil YouTube n'est pas
  modifié.

### Durées des titres Spotify
Les titres des playlists Spotify affichaient des tirets : Spotify renvoie
désormais la durée sous `trackDuration`, que Square ne lisait pas. Elle est
lue, une durée connue n'est jamais remplacée par une valeur vide, et à défaut
la durée du titre YouTube Music correspondant (même enregistrement vérifié)
est utilisée. Les playlists déjà importées sont corrigées à leur prochaine
ouverture.

### Paramètres et À propos
- La case « Soutenir le développement » (Ko-fi du développeur d'origine) est
  retirée ; les six cases restantes sont sur trois rangées.
- À propos : « Développé par » et le lien GitHub du développeur d'origine
  de Square (Lelonio), comme dans Square ; son Ko-fi est retiré.
  « Signaler un problème » ouvre les issues de Square Fusion. La communauté Telegram et les discussions du projet d'origine ne
  sont plus listées.

### Recherche
Le filtre choisi (Titres, Vidéos, Artistes, Albums, Podcasts…) reste
sélectionné au retour d'un résultat, avec la liste et sa position. Une
nouvelle recherche revient à « Tout ».

### Icônes — Paramètres › Application › Icône de l'application
Icône Square par défaut ; 13 variantes BitChord (BitChord, Liquid Glass,
Samsung Glass, Pixel Glass). Certains lanceurs mettent un moment à afficher le
changement ; un raccourci épinglé avec l'ancienne icône peut disparaître.

### Ajouter à une playlist
Le menu d'un titre et le bouton « + » du lecteur proposent toutes les
playlists où l'ajout est possible :
- playlists YouTube Music du compte (créées par le compte, ou que YouTube
  marque modifiables) : le titre est envoyé à YouTube ;
- playlists importées par lien (Spotify, Apple Music) et playlists Spotify
  synchronisées : le titre est ajouté dans Square Fusion uniquement, à la fin
  de la liste, et gardé après redémarrage. Rien n'est envoyé à Spotify ou à
  Apple Music, et une nouvelle synchronisation ne l'efface pas.
Un titre déjà présent n'est pas ajouté une deuxième fois. Le titre est
enregistré complet (titre, artistes, album, pochette, durée). Depuis le
lecteur, seuls son adresse et son nom l'étaient : il s'affichait vide et ne se
lisait pas. Ceux enregistrés ainsi sont complétés depuis YouTube Music à
l'ouverture de la playlist ; un titre Spotify pas encore trouvé sur YouTube
Music garde de quoi être cherché et lu.

Cause de l'ancien comportement (seul « J'aime » proposé) : YouTube n'envoie
plus l'icône « EDIT » dans le menu des playlists de la bibliothèque, seul
indice que Square utilisait pour savoir qu'une playlist est modifiable ; et
les playlists Spotify ou importées n'étaient jamais proposées.

### Menus
Les menus des titres et des playlists ne proposent plus YTDLnis (les
téléchargements de Square restent). Les playlists importées par lien n'ont
plus « Retirer de la bibliothèque » ; « Supprimer » les retire.

### Icône de la notification de lecture
La petite icône de la notification (barre d'état, en-tête de la notification
et, selon le téléphone, écran de verrouillage) suit l'icône d'application
choisie : la silhouette monochrome de cette icône, comme Android l'exige pour
une petite icône. Elle change dès qu'une autre icône est choisie, sans
redémarrer, et reste la bonne après pause, reprise, changement de titre ou
redémarrage de l'application. La pochette du titre reste l'image de la
notification.

Limites d'Android : la petite icône est toujours dessinée en une couleur.
Sur Android 13 et plus, les commandes multimédias du système et le lecteur
de l'écran de verrouillage affichent l'icône de l'application déclarée dans
le manifeste, que l'application ne peut pas changer : elles gardent l'icône
Square. Certains fabricants dessinent ces emplacements à leur façon.

### Lire ensuite et appui long
- Le menu d'un titre propose « Lire ensuite » : le titre est placé juste
  après celui en cours, avant les titres déjà en file d'attente (« Ajouter à
  la file d'attente » les place après eux). Avec la lecture aléatoire, il est
  aussi joué juste après. La lecture en cours continue.
- Un appui long sur un titre de l'accueil, de Nouveautés ou de Radio ouvre ce
  même menu (Lire ensuite, file d'attente, ajout à une playlist,
  téléchargement, artiste, album, radio, lien). La recherche et les
  playlists l'avaient déjà.

### Pays du contenu musical — Paramètres › Application
Sous « Langue ». « Automatique » garde le comportement d'avant (pays du
téléphone, ou du réseau). Un pays choisi est envoyé à YouTube Music comme
région (`gl`) de toutes ses requêtes : accueil, Nouveautés, Radio,
classements, recommandations, recherche. L'accueil, Nouveautés et Radio sont
rechargés au changement, sans redémarrer ; le choix est gardé après
redémarrage. La langue de l'application, celle du téléphone et le compte
Google ne changent pas.

Limites : seul YouTube Music accepte une région. Les playlists Spotify et
Apple Music importées ne dépendent d'aucun pays. Si YouTube Music ne renvoie
aucun contenu pour le pays choisi, l'application affiche celui des
États-Unis (comportement déjà présent pour le pays du téléphone).
YouTube décide lui-même de ce qu'il propose pour un pays ; Square Fusion ne
filtre rien et ne bloque aucun titre.

### Sections de l'accueil — Paramètres › Application › Apparence
Chaque section que YouTube Music a envoyée sur l'accueil (sous son titre :
« Sélection rapide », « Reprises et remixes »…) et « Écouter à nouveau »
peuvent être masquées. Les titres de section viennent de YouTube et changent
avec le compte, la langue et le pays : la liste montre ceux déjà reçus sur ce
téléphone. Tout est affiché par défaut.

### Accueil hors connexion
Si l'accueil n'a pas pu être chargé, un bouton « Réessayer » le recharge. Au
retour de la connexion, il est rechargé automatiquement s'il était vide ou en
erreur ; un accueil déjà affiché n'est pas rechargé.

### Réduction du lecteur
Le glissement ne part que de la pochette, au-dessus du titre, quand la pochette
est la vue affichée. Depuis le titre, les commandes et les onglets, ou avec les
paroles, la file d'attente, les effets ou les crédits ouverts, il est laissé à
ce qui est sous le doigt ; la flèche en haut réduit le lecteur.

Glisser vers le bas réduit le lecteur au même seuil
qu'avant les changements de Square Fusion : 110 dp de déplacement du doigt,
avec une vibration (retour haptique « appui long ») au moment où il est
franchi. Le lecteur suit le doigt avec une résistance : rien sur les 12
premiers dp, puis un tiers du déplacement jusqu'au seuil, puis un pour un.
Relâché avant le seuil, il revient ouvert ; après, il se range dans le
mini-lecteur avec la vitesse du doigt. Remonter avant de lâcher rend la
distance. Un jet très rapide (1 500 dp/s) ferme dès 60 % du seuil. Un geste
qui part plus de côté que vers le bas (la barre des sections, la pochette)
n'est jamais pris pour une réduction. La flèche utilise la même animation.

### Ouverture et fermeture du lecteur (d'après BitChord)
Repris du code de BitChord (`PlayerSheetMotion`, `PlayerDock`) : le lecteur
glisse comme une feuille sur un ressort amorti sans rebond (raideur 195,
environ 0,45 s), le reste du lecteur s'efface avec 15 % d'avance sur la
pochette, et une seule pochette voyage entre la couverture du mini-lecteur et
l'image en haut du lecteur. C'est l'image même que le lecteur a décodée,
jamais une copie chargée pendant le trajet. Elle est remise en page à chaque
image, de sorte que le carré du mini-lecteur devienne le 3:4 du lecteur sans
être étiré, et prend le bas fondu du lecteur en arrivant.

Pendant le trajet :
- les commandes du lecteur (flèche, ligne du haut, bouton du clip, titre,
  transport, barre des sections) sont redessinées au-dessus de la pochette,
  comme lorsque le lecteur est ouvert, et s'effacent avec lui ;
- le mini-lecteur reste en place sous le lecteur ; seule sa pochette est
  masquée ;
- le verre du lecteur garde le reflet pris avant le trajet
  (`LocalBackdropHeldWhole`) : l'image du haut étant partie voyager, un
  rafraîchissement aurait changé le flou sous les boutons au relâchement ;
- le bas flouté de la grande image part avec elle, et la pochette qui voyage
  porte ce bas flouté et le voile sombre du haut du lecteur, à pleine force
  en place et de moins en moins vers le mini-lecteur ;
- le lecteur s'efface sur une courbe adoucie : opaque tant que le doigt le
  tient près de sa place, effacé au même point qu'avant.

À l'ouverture depuis le mini-lecteur, le lecteur est d'abord mis en page hors
de l'écran avec son image (300 ms au plus, comme BitChord), puis glisse.

### Barre des sections du lecteur
Elle était décalée par une couche graphique à elle, qui s'affichait vide à
l'ouverture du lecteur jusqu'à ce qu'un doigt la touche : seule la pastille
de la section choisie restait visible. Le décalage passe maintenant par le
placement (`offset`), sans couche. Constaté et vérifié sur l'émulateur Android
11 (barre entière sur cinq ouvertures de suite).

### Pochettes
- La source choisie dans Paramètres › Lecture › Pochettes décide d'où viennent les images. Spotify (Canvas) : la pochette du
  titre lui-même (YouTube Music ou Spotify), aucune image d'Apple Music, et le
  Canvas de Spotify pour les titres venus de Spotify, lu avec la connexion
  Spotify. Apple Music : la grande image, la pochette et la pochette animée
  d'Apple, pour l'édition exacte du disque seulement.
- Causes corrigées : la recherche d'album d'Apple ignorait ce qui est entre
  parenthèses et classait « a une grande image » avant « nom exact », d'où la
  pochette de « The Middle (Remixes) » pour « The Middle » ; sans disque connu,
  la recherche par titre préférait la ligne d'une autre sortie (souvent une
  compilation) à celle du single. Les réponses gardées un mois avec ces règles
  ne sont plus lues.
- Clip YouTube Music : la pochette du titre est cherchée parmi les titres de
  YouTube Music (même titre une fois « (Official Music Video) » retiré, même
  version, même artiste) ; deux disques possibles que la durée ne départage pas
  sont un doute, et le clip garde sa miniature. Réponse gardée.
- Une seule pochette de référence par titre : mini-lecteur, lecteur, fond des
  paroles, couleurs.

### Nouveautés
Rangée « Nouveautés de vos artistes » : les disques de l'année (et de l'année
précédente en janvier-février) des six artistes les plus écoutés d'après
l'historique de lecture du téléphone, lus sur leurs pages YouTube Music. Absente
sans historique ou sans nouveauté.

### Durées des téléchargements
Un titre est gardé comme la liste d'où il est téléchargé le décrit ; les
rangées de l'accueil de YouTube Music n'ont souvent pas de durée. À la fin du
téléchargement (et au démarrage pour ceux déjà téléchargés), la durée lue dans
le fichier est enregistrée si elle manquait.

### Android 11
Les menus des titres et des playlists et la fenêtre « Ajouter à une
playlist » s'ouvrent sans le flou fait à la main (Android 11 n'a pas le flou
du système) : fond assombri et panneau opaque aux couleurs de l'application.
Le flou de ce fond, recalculé sur tout l'écran, retardait l'ouverture. Dans
le lecteur, le fond derrière les paroles, la file d'attente et l'ajout à une
playlist est flouté par le flou de secours de Liquid Glass (le flou de
Compose ne fait rien avant Android 12). Android 12 et plus : inchangé.

### Performance — Paramètres › Application › Performance
Désactivé par défaut (comportement de Square). « Choisir la fréquence » :
60 ou 120 Hz si l'écran les propose. La fréquence demandée et celle relue dans
le système sont affichées ; Android peut en appliquer une autre. Une fréquence
plus haute peut consommer davantage de batterie.

### Retirés par rapport à la version précédente de Square Fusion
Sources addons, JioSaavn, réglages sans perte / Hi-Res / compatibilité de
sortie, page Informations du flux, normalisation du volume, autorisations
Bluetooth.

## Mises à jour

### Dans l'application
Paramètres › À propos › Mises à jour : l'application lit la release `latest`
de **ce** dépôt (jamais celles de Square), télécharge l'APK de l'architecture
du téléphone et le remet à l'installateur d'Android, qui demande la
confirmation. Rien n'est installé en silence. Les notes affichées sont
celles de la version proposée : `.github/release-notes.md` quand il a été
modifié depuis la version précédente, sinon la liste des changements
(commits) depuis celle-ci (`scripts/release-notes.sh`). Une version plus récente
s'installe par-dessus en gardant les données, si elle a la même signature.

### Suivi de Square
`.github/workflows/upstream-sync.yml` s'exécute chaque jour (et à la demande) :
1. récupère `master` de Lelonio/Square ;
2. s'il y a du nouveau, le fusionne dans `main` ;
3. en cas de **conflit**, annule la fusion, ouvre (ou complète) une issue
   « Upstream sync: merge conflict » avec la liste des fichiers, et **ne publie
   rien** ;
4. sinon lance les tests unitaires ; s'ils échouent, issue et rien de publié ;
5. sinon pousse `main` et lance `fusion.yml`, qui compile, teste sur émulateur
   et publie seulement si tout passe.

Une modification des workflows par Square ne peut pas être poussée par le
jeton du workflow (règle de GitHub) : elle est signalée par une issue et doit
être fusionnée à la main. Les conflits restent possibles, surtout dans
`SettingsScreen.kt`, `MainViewModel.kt` et `SquareApp.kt`, où vivent les
modifications de Square Fusion. Rien n'est jamais poussé vers Lelonio/Square.

## Signature

Le workflow signe avec la clé fournie par quatre secrets du dépôt s'ils
existent (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Sinon il utilise une clé de
secours générée sur GitHub Actions et conservée dans le cache Actions. La
synchronisation quotidienne relit ce cache pour qu'il n'expire pas (GitHub
efface une entrée inutilisée pendant 7 jours). Aucune clé n'est écrite dans le
dépôt.

**Builds 1 à 13 :** chacun a été signé avec une clé jetable différente (le
plugin Android utilisait sa propre clé de debug, pas la clé mise en cache).
Aucun ne peut donc être mis à jour sur place : passer au build actuel demande
de désinstaller **Square Fusion** une fois (pas Square), ce qui efface ses
réglages et sa connexion YouTube. Les builds suivants sont signés avec la clé
mise en cache et s'installent l'un sur l'autre.

Avant de publier, le workflow compare le certificat du nouvel APK à celui de
l'APK publié ; s'ils diffèrent, la publication est bloquée (une mise à jour
serait refusée par Android), sauf lancement manuel avec `allow_new_signature`
ou si l'ancien certificat est listé dans `.github/retired-signing-certs.txt`.

La clé de secours est lisible par tout workflow du dépôt, y compris celui
d'une pull request venue d'un fork, si vous l'approuvez. Pour une clé que vous
seul détenez, utilisez les secrets (voir « Étape facultative »).

L'identifiant reste `dev.lelonio.square.fusion` : Square officiel est signé par
la clé de son auteur, une autre clé ne peut donc pas le mettre à jour sans le
désinstaller. Square Fusion reste une application séparée.

## Compilation

`.github/workflows/fusion.yml` : à chaque push sur `main`, tests unitaires,
`assembleRelease` (R8), vérification de l'APK (identifiant, version,
signature, bibliothèque native), tests sur émulateur Android 11 (lancement,
liens, icônes, tests instrumentés, import Spotify et lecture directe), puis
publication `fusion-N` et `latest`. Version : `<version de Square>-fusion.N`.
Un échec des tests sur émulateur bloque la publication. Les refus réseau de
YouTube ou de Spotify sur les serveurs de GitHub sont notés WARN dans le
journal, pas masqués.

## Limites

- Spotify : API privée du lecteur web, non documentée, qui peut changer sans
  préavis ; les conditions de Spotify n'autorisent pas les clients non
  officiels. Aucune protection n'est contournée (pas de DRM ; l'audio vient de
  YouTube Music), mais l'usage reste hors du cadre autorisé par Spotify.
- La correspondance Spotify → YouTube Music peut manquer un titre ou, plus
  rarement, en choisir un proche ; elle préfère « introuvable » à un mauvais
  titre.
- YouTube refuse souvent la lecture anonyme depuis les serveurs de GitHub ; la
  lecture réelle d'un morceau n'y est donc pas toujours vérifiable.

## Étape facultative

Clé de signature définitive : créer une clé avec `keytool`, puis l'ajouter en
base64 et ses mots de passe dans Settings › Secrets and variables › Actions,
sous les quatre noms ci-dessus. Le changement de clé impose de désinstaller
Square Fusion une fois (pas Square).
