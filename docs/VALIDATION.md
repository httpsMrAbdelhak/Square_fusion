# Rapport de validation — Square Fusion

Ce rapport distingue ce qui a été **vérifié automatiquement** (et où), ce qui
a été **vérifié sur émulateur**, et ce qui **reste à vérifier sur votre
téléphone**. Rien n'a été testé sur un appareil physique.

Chaque build publie ses preuves dans sa release `fusion-N` :
`smoke-log.txt`, `instrumented-log.txt`, `playback-log.txt` et
`smoke-results.zip` (captures d'écran, journaux, vidages d'écran).

## 1. Compilation et livraison (GitHub Actions)

| Vérification | Résultat |
|---|---|
| JDK 21, SDK 37, NDK, Rust installés par le workflow | OK |
| Tests unitaires de Square **et** de Fusion (`testDevUnitTest`) | OK |
| `assembleRelease` (R8, comme les versions officielles de Square) | OK |
| APK : identifiant `dev.lelonio.square.fusion`, version `2.6.3-fusion.N`, signature vérifiée par `apksigner`, `libsquarecore.so` présent | OK |
| Publication : artefact du run + release `fusion-N` + release `latest` | OK |
| Commit exact indiqué dans la release, le résumé du run et `BuildConfig.SOURCE_COMMIT` | OK |

## 2. Tests unitaires (JVM, à chaque build)

`AddonSourceTest` — 24 tests, serveur HTTP local réel :
réponses valides (manifest, recherche, flux FLAC 24 bits/96 kHz) ;
négociation de qualité avec les options déclarées ; JSON invalide ; manifest
sans `id` ; lignes incomplètes ignorées ; flux sans URL (repli sur l'URL de la
ligne) ; flux chiffrés / URL non http(s) / URL dupliquée refusés ; 5xx
(injoignable) contre 4xx (refusé) ; délai dépassé ; hôte injoignable ; source
qui lève une exception ; source qui ne répond jamais ; « Sans perte » refuse
un MP3 et un FLAC seulement déduit d'un libellé ; plafond de débit ;
manifestes DASH refusés ; aucune correspondance → YouTube ; `allowDownloads: 0`
→ refusé pour le téléchargement seulement ; `checkValidLossless` sans sortie
compatible → source muette ; registre (ordre, désactivation, suppression,
persistance) ; détection d'URL (addon, index MODULE refusé) ; addon sans
manifest détecté par `/search` ; masquage des jetons dans les journaux ;
correspondance de titres (versions live/remix refusées, durée).

`FusionAudioTest` — normalisation (atténuation seule, plancher −20 dB, niveau 1
si désactivée) ; classement des sorties (filaire, USB, haut-parleur, Bluetooth
codec inconnu → non compté, LDAC, AAC, aptX HD) ; comparaison annoncé/reçu
(conforme, différent, inconnu) ; JioSaavn : 320 kbps, déchiffrement, absence
d'en-tête `X-Forwarded-For`, résistance à une API modifiée, à un 500 et à du HTML.

`SpotifyFreeLibraryTest` — analyse des playlists, titres et titres likés
(repris de BitChord) ; envoi des jetons ; 401 → session expirée ; 503 → pas une
expiration ; absence de jeton ; réponse de forme inattendue ; erreur GraphQL.

## 3. Émulateur Android 11 (x86_64 avec traduction ARM), à chaque build

### Lancement et intégration (`smoke-log.txt`)
Installation de l'APK release ; une seule entrée de lanceur (Square) ;
lancement ; intent explicite vers `MainActivity` ; lien `spotify:` ; lien
`open.spotify.com` ; choix de YouTube Music à l'accueil et affichage de la page
d'accueil (catalogue chargé) ; retour en arrière-plan puis reprise ; écran
éteint puis rallumé ; aucun plantage enregistré.

### Tests instrumentés (`instrumented-log.txt`, build debug)
`FusionDeviceTest` : icône Square par défaut ; **chacune des 14 icônes**
appliquée par `AppIcon.apply`, avec à chaque fois exactement une entrée de
lanceur, la bonne cible `MainActivity`, `MainActivity` toujours joignable par
intent explicite et les liens `spotify:` toujours reçus ; retour à Square ;
valeurs par défaut de Square conservées (normalisation et fréquence
désactivées, aucune source) ; persistance du registre ; fréquences lues sur le
vrai écran ; sorties audio et liste des codecs lues sans autorisation.
`FusionScreensTest` : page Sources (ajout de JioSaavn **désactivé**, suppression),
page Informations du flux (« Inconnu » sans lecture), section qualité
(« Sans perte » verrouillé sans addon), sélecteur d'icônes (Pixel Glass puis
retour à Square), Performance (désactivé par défaut, 60 Hz proposé une fois
activé), section Spotify (« Se connecter »).

### Lecture réelle (`playback-log.txt`)
RÉSULTATS_LECTURE

## 4. À vérifier sur votre téléphone

Impossible à vérifier sans vous ou sans votre matériel :
- **Spotify** : connexion réelle, compte gratuit, contenu de vos playlists, et
  expiration de session après plusieurs jours (aucun compte disponible ici).
- **Addons réels** : il faut l'URL d'un addon que vous utilisez ; le protocole
  est testé contre un serveur local, pas contre votre addon.
- **JioSaavn** : dépend de votre région ; non testé contre le vrai service.
- **Sans perte / 24 bits / 96–192 kHz** : nécessite un addon qui les fournit ;
  rappel : la chaîne de Square sort en 16 bits / 48 kHz (voir docs/FUSION.md).
- **Bluetooth LDAC/LHDC**, sorties USB/HDMI, autorisation « Appareils à
  proximité » : l'émulateur n'a pas de Bluetooth.
- **Dolby Atmos** : nécessite un flux E-AC-3 JOC et un téléphone avec décodeur.
- **120 Hz** : l'émulateur n'a qu'un mode 60 Hz.
- **Écran de verrouillage** : les commandes y utilisent la même session média
  que les touches média testées, mais l'affichage n'a pas été vérifié à l'œil.
- **Lanceur de votre téléphone** : délai d'affichage d'une nouvelle icône,
  raccourcis épinglés.
- **Autonomie, chauffe, fluidité** : non mesurées.

## 5. Ce qui n'a pas été modifié

Moteur ExoPlayer, `PlaybackService`, notifications, moteur Spotify (librespot),
Web SDK, recherche, bibliothèque, file d'attente, paroles, design. Les points
d'accroche ajoutés dans le code de Square sont listés dans NOTICE ; chacun ne
fait rien tant que la fonction correspondante n'est pas utilisée.
