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
- Pochette personnelle pour chaque playlist (sélecteur de photos d'Android),
  et « Pochette d'origine » pour revenir à celle de la source. Elle reste sur
  le téléphone : la pochette Spotify ou YouTube n'est jamais modifiée.
- Les playlists Spotify et Apple Music importées se modifient aussi.
- Nom et pochette personnels sont posés par-dessus les données de la source :
  la synchronisation continue de mettre à jour titres et pochette d'origine
  en dessous, sans jamais écraser le choix fait dans Square.

### Qualité affichée pendant la lecture
Sous la barre de progression, entre les deux temps : le codec réellement
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
  Mise à jour chaque minute.
- Photo de profil : celle du compte YouTube connecté, ou une photo choisie
  dans Paramètres › Compte › Photo de profil (« Utiliser la photo du compte »
  pour revenir). Elle reste sur le téléphone ; le profil YouTube n'est pas
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
- À propos : le lien GitHub et le Ko-fi du développeur d'origine sont
  remplacés par le dépôt de Square Fusion ; « Signaler un problème » ouvre
  ses issues. La communauté Telegram et les discussions du projet d'origine ne
  sont plus listées.

### Recherche
Le filtre choisi (Titres, Vidéos, Artistes, Albums, Podcasts…) reste
sélectionné au retour d'un résultat, avec la liste et sa position. Une
nouvelle recherche revient à « Tout ».

### Icônes — Paramètres › Application › Icône de l'application
Icône Square par défaut ; 13 variantes BitChord (BitChord, Liquid Glass,
Samsung Glass, Pixel Glass). Certains lanceurs mettent un moment à afficher le
changement ; un raccourci épinglé avec l'ancienne icône peut disparaître.

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
confirmation. Rien n'est installé en silence. Une version plus récente
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
