# Square Fusion

Square Fusion est Square (https://github.com/Lelonio/Square) avec une partie des
fonctionnalités de BitChord (https://github.com/kushagrasinghx/BitChord). Elle
s'installe **à côté** de Square : identifiant `dev.lelonio.square.fusion`, nom
« Square Fusion ». Square n'est ni remplacé, ni désinstallé, ni modifié, et ses
données ne sont pas touchées.

## Installer

1. Sur le téléphone, ouvrir
   https://github.com/httpsMrAbdelhak/Square_fusion/releases/latest/download/square-fusion.apk
   (APK universel). Les APK par architecture (`arm64-v8a`, `armeabi-v7a`) sont
   dans chaque release `fusion-N`.
2. Autoriser le navigateur (ou le gestionnaire de fichiers) à installer des
   applications inconnues quand Android le demande.
3. Installer. Square reste installé ; les deux icônes coexistent.

Mise à jour : installer l'APK d'un build plus récent par-dessus. Cela fonctionne
tant que les APK sont signés avec la même clé (voir « Signature »). Le système
de mise à jour automatique de Square est **désactivé** dans Square Fusion : il
téléchargerait les versions officielles de Square, qui portent l'identifiant de
Square et remplaceraient l'application originale.

## Signature

Le workflow signe avec la clé fournie par quatre secrets du dépôt s'ils
existent (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Sinon il utilise une clé de
secours générée sur GitHub Actions et conservée dans le cache Actions, pour que
les builds successifs s'installent l'un sur l'autre. Ce cache expire après 7
jours sans build : un build ultérieur aurait alors une autre clé et Android
refuserait la mise à jour (il faudrait désinstaller **Square Fusion**, pas
Square). Pour une clé définitive, voir « Étapes facultatives » plus bas. Aucune
clé n'est jamais écrite dans le dépôt.

## Ce qui a été ajouté

### Sources (addons) — Paramètres › Lecture › Son › Sources
- Protocole addon de BitChord : un addon est un serveur HTTP qui répond en JSON
  à `/manifest.json`, `/search?q=` et `/stream/{id}`. Rien n'est exécuté.
- Ajouter une URL, la tester, activer/désactiver, réordonner, supprimer.
- Pour un morceau YouTube Music **écouté** (pas en mode vidéo), les sources
  activées sont interrogées dans l'ordre, avec la qualité demandée (Wi-Fi ou
  réseau mobile). La première qui a le même enregistrement (titre, artiste,
  durée à ±8 s, sans « live/remix/… » d'un seul côté) et un flux acceptable
  est lue. Sinon : YouTube, exactement comme dans Square.
- Délais : 8 s par source, 12 s au total, puis YouTube.
- Refusés : flux chiffrés (DRM), URL non http(s) ou mal formées, manifestes
  HLS/DASH (le lecteur de Square ne lit que des fichiers progressifs), flux au-
  dessus du plafond demandé, et pour « Sans perte » tout flux dont la source ne
  déclare pas elle-même un codec sans perte (rien n'est jamais ré-étiqueté ni
  converti en FLAC).
- `allowDownloads: 0` : la source n'est jamais utilisée pour un téléchargement.
- `checkValidLossless` : la source n'est interrogée que si une sortie filaire,
  USB, HDMI ou Bluetooth LDAC/LHDC/aptX Lossless est branchée.
- Le traitement spécial YouTube de Square (`ChunkedDataSource`) ne s'applique
  qu'aux hôtes `googlevideo.com`, et `SignedInStreams` qu'aux échecs de
  résolution YouTube : une URL d'addon passe par la source HTTP normale.
- Le système MODULE de BitChord (JavaScript tiers exécuté dans QuickJS) n'est
  **pas** porté ; une URL d'index MODULE est reconnue et refusée.

### JioSaavn
- Ajouté uniquement à la demande, et ajouté **désactivé**.
- API **non officielle** : l'API privée du site JioSaavn. Elle peut changer ou
  disparaître à tout moment ; une panne donne une liste vide et n'affecte pas
  les autres sources.
- Différence avec BitChord : les en-têtes `X-Forwarded-For`/`X-Real-IP` avec
  une adresse indienne ne sont **pas** envoyés (ils simulent une connexion
  depuis l'Inde). Hors des régions servies par JioSaavn, la source répondra
  probablement vide.
- L'URL du média est chiffrée en DES avec une clé fixe présente dans le client
  web de JioSaavn ; elle est déchiffrée comme le font BitChord et les autres
  clients tiers. Cette utilisation n'est pas autorisée par JioSaavn et peut
  contrevenir à ses conditions d'utilisation ; c'est la raison pour laquelle la
  source n'est jamais activée par défaut. Téléchargement interdit, jamais sans
  perte (AAC 320 kbps au mieux).

### Qualité audio — Paramètres › Lecture › Son
- Le réglage YouTube de Square (auto / haute / basse) est inchangé.
- Nouveau : qualité demandée aux sources en Wi-Fi et sur réseau mobile
  (Basse ≤128 kbps, Moyenne ≤160 kbps, Haute, Sans perte). « Sans perte » n'est
  sélectionnable que si un addon est activé.
- Téléchargement sans perte : seulement si la qualité Wi-Fi est « Sans perte »,
  et seulement depuis une source qui déclare un codec sans perte et autorise le
  téléchargement ; sinon le fichier YouTube habituel.

### Informations du flux — Paramètres › Lecture › Son › Informations du flux
Trois niveaux, chacun lu là où il est connaissable :
- **Annoncé** par la source (codec, fréquence, profondeur, débit) ; une valeur
  « déduite » vient d'un libellé ou d'une extension, pas d'une déclaration.
- **Reçu** par ExoPlayer (`Format` du flux : type MIME, fréquence, canaux,
  profondeur si le codec l'expose, débit, décodeur choisi).
- **Transmis au mixeur** de Square (format PCM lu là où le rééchantillonneur
  est configuré), et la fréquence fixe du mixeur.
- Comparaison annoncé/reçu : Conforme, Différent ou **Inconnu**.

Chaîne audio de Square, vérifiée dans le code : tous les morceaux YouTube Music
passent par `PcmMixer` (fondu enchaîné), qui fonctionne en flottant stéréo à
**48 kHz fixe** ; `MixerRateProcessor` (Sonic) rééchantillonne tout flux à
48 kHz, et le moteur de rendu ne demande pas la sortie flottante, donc le
décodeur fournit du **PCM 16 bits**. Un FLAC 24 bits / 96 ou 192 kHz est donc
entendu en 16 bits / 48 kHz. C'est une dégradation propre (rééchantillonnage
Sonic, pas de troncature brutale), et c'est ce que la page affiche. Les effets
vitesse/hauteur (Bungee), réverbération et karaoké de Square sont dans cette
même chaîne. Le moteur et le mixeur de Square n'ont pas été modifiés pour
monter en résolution : ce serait une refonte de la lecture, hors du périmètre
demandé.

### Dolby Atmos
- Aucun interrupteur. Une section Atmos n'apparaît que si le flux reçu est
  réellement `audio/eac3-joc`.
- Elle distingue « présent dans le flux » de « rendu » : le mixeur de Square
  est stéréo, donc même avec un décodeur Dolby, Atmos n'est **pas** restitué en
  Atmos ; la page le dit. La liste des décodeurs E-AC-3 JOC du téléphone est
  lue dans la liste des codecs, jamais supposée.

### Normalisation du volume — un seul réglage, désactivé par défaut
- YouTube Music : `loudnessDb` lu dans la réponse player d'InnerTube (requête
  anonyme en arrière-plan) ; le morceau est atténué d'autant, jamais amplifié
  (comme le lecteur YouTube), au plus −20 dB. Appliqué comme niveau du canal du
  morceau dans le mixeur, donc compatible avec les fondus.
- Morceaux venant d'un addon/JioSaavn, ou fichier sans perte téléchargé depuis
  une source : non normalisés (le loudnessDb décrit le fichier YouTube).
- Spotify (moteur librespot de Square) : sa normalisation intégrée est déjà
  toujours active dans Square et n'est pas modifiée ; Spotify et YouTube sont
  deux lecteurs distincts, donc jamais de double normalisation.
- Désactivé : niveau exactement 1, comportement de Square inchangé.

### Spotify (compte gratuit ou Premium) — Paramètres › Compte
- Mécanisme de BitChord : connexion sur la page officielle de Spotify dans une
  WebView (le mot de passe n'est jamais vu par l'application), conservation du
  cookie de session `sp_dc` **chiffré** sur le téléphone, jeton du lecteur web
  lu dans une WebView cachée, puis appels à l'API GraphQL privée du lecteur
  web (playlists, titres likés, titres, artistes, pochettes).
- Lecture : un morceau Spotify ouvre la recherche YouTube Music sur « titre
  artiste ». L'onglet Bibliothèque de Square et son moteur Spotify (Premium,
  librespot) ne sont pas modifiés.
- Erreurs isolées : session expirée/refusée (401/403 → « reconnectez-vous »),
  réseau, réponse inattendue (API privée modifiée). Aucune ne quitte la page.
- Limites : API privée non documentée, susceptible de changer sans préavis ;
  les conditions de Spotify n'autorisent pas les clients non officiels. Aucune
  protection technique n'est contournée (pas de DRM, l'authentification est
  celle de Spotify), mais l'usage reste hors du cadre autorisé par Spotify.
- Différence avec BitChord : BitChord efface **tout** le stockage WebView à
  chaque obtention de jeton ; Square garde ses connexions YouTube et Spotify
  Web SDK dans des WebView, donc cet effacement est retiré.
- Non vérifié ici : connexion réelle, compte gratuit, contenu réel (pas de
  compte disponible dans l'environnement de compilation).

### Icônes — Paramètres › Application › Icône de l'application
- Icône Square par défaut ; 13 variantes BitChord copiées octet pour octet
  (BitChord, Liquid Glass ×4, Samsung Glass ×4, Pixel Glass ×4).
- Mise en œuvre par `activity-alias` ; `MainActivity` n'est pas modifiée, donc
  les intents explicites, notifications, widgets, boutons média, liens
  `spotify:` et `open.spotify.com` continuent d'y arriver.
- Limites des lanceurs : un raccourci épinglé ou une icône d'écran d'accueil
  créé avec un alias désactivé peut disparaître ou devenir gris ; certains
  lanceurs mettent quelques secondes (ou un redémarrage du lanceur) à afficher
  la nouvelle icône. Revenir à « Square » rétablit l'alias d'origine.

### Performance — Paramètres › Application › Performance
- Désactivé par défaut : comportement de Square (mode le plus rapide demandé
  tant que l'application est à l'écran).
- « Choisir la fréquence » : 60 ou 120 Hz, proposés seulement si l'écran a un
  mode à cette fréquence à sa résolution actuelle (sinon grisé avec la
  raison). C'est une préférence de fenêtre ; Android peut décider autrement.
- La fréquence affichée comme appliquée est relue dans `Display.getMode()`.
  Si elle diffère de la demande : « demandée mais non confirmée par le système ».

### Autorisations
- `BLUETOOTH_CONNECT` (Android 12+) et `BLUETOOTH` (≤ Android 11) : pour lire
  le codec Bluetooth. Demandée uniquement depuis la page Informations du flux,
  et seulement si une sortie Bluetooth est connectée. Refusée : codec
  « Inconnu », tout le reste fonctionne.

## Compilation

`.github/workflows/fusion.yml` : à chaque push sur `main` (et à la demande),
installe JDK 21, SDK/NDK Android et Rust, lance les tests unitaires, compile
`assembleRelease` (R8 comme les versions officielles de Square), vérifie l'APK
(identifiant, version, signature, bibliothèque native), le publie comme
artefact et comme release `fusion-N` + `latest`. La version est
`2.6.3-fusion.N` (N = numéro du run) et chaque release indique le commit exact.

## Étapes facultatives

Clé de signature définitive (recommandé si vous gardez l'application) : créer
une clé avec `keytool`, puis l'ajouter en base64 et ses mots de passe dans
Settings › Secrets and variables › Actions du dépôt, sous les quatre noms
ci-dessus. Le changement de clé impose de désinstaller Square Fusion une fois.
