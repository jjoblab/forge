# Démo Forge — application Compose

Application Android **Jetpack Compose** compilée entièrement sur l'appareil par
le moteur Forge : le plugin compilateur Compose (embarqué dans le composant
`kotlin-compiler`, version verrouillée sur le compilateur Kotlin 2.0.21) est
appliqué automatiquement dès que le classpath du module porte
`androidx.compose.runtime` — aucune option dans `forge.json`.

Ce que la chaîne fait pour ce projet :

- **Résolution** : les dépendances `androidx.*` viennent de Google Maven
  (`https://dl.google.com/dl/android/maven2`, déclaré dans `forge.json`) ;
  le résolveur lit leurs métadonnées de module Gradle pour sélectionner les
  variantes `-android` (les POMs passerelle d'androidx ne sont pas consommables
  par un résolveur Maven pur) ;
- **AAR** : chaque AAR est explosé dans le cache — ses classes compilent avec
  le module et sont dexées dans l'APK, ses `res/` sont fusionnés par aapt2,
  son manifeste est fusionné (providers, services) et son paquet reçoit son
  `R` par `--extra-packages` ;
- **Compilation Kotlin** : `compileKotlin` applique le plugin Compose
  (`-Xplugin lib/compose-compiler.jar` sur l'appareil) puis ECJ compile le
  `R.java` généré ; `d8` dexe le module ET la fermeture runtime (~60 Mo de
  classes Compose) ; l'APK est signé (v1+v2).

## Requis

- moteur ≥ 0.2.15 (composant `kotlin-compiler` avec plugin Compose,
  plateforme 34 installée) ;
- **premier build** : ~100 Mo de téléchargement (fermeture Compose) et
  plusieurs minutes de dexage sur un appareil d'entrée de gamme — le cache
  `.forge/deps` rend les builds suivants immédiats ;
- appareil ≥ 3 Go de RAM pour le tas par défaut du moteur (sinon la coquille
  bascule en mode basse mémoire, et un projet Compose peut manquer de place
  au dexage).

## Construire

Copiez ce répertoire dans le projet Forge de l'appareil (bouton de partage de
la coquille) et lancez le build : `build/app/outputs/app-debug.apk` est
l'application installable.
