# Exemple application Android

Projet minimal à deux modules construit jusqu'à l'**APK signé** par le moteur Forge :

- `core` — bibliothèque Java (message de salutation) ;
- `app` — application Android (manifeste, ressources, activité) dépendant de `core`.

Chaîne exécutée par le moteur : `resolveDependencies` → `processResources` (aapt2 :
APK de base + sources R) → `compileJava` (ECJ, R générées, `android.jar` au classpath)
→ `dex` (d8) → `packageApk` → `signApk` (v1+v2, clé de débogage du projet).

Sortie : `build/app/outputs/app-debug.apk` (vérifiable avec apksig, installable sur
appareil — l'installation depuis la coquille arrive avec l'étape 11).
