# Exemple application Android en Kotlin

Projet minimal à deux modules, le module d'application écrit en **Kotlin**, construit
jusqu'à l'**APK signé** par le moteur Forge (Android+Kotlin admis depuis `v0.2.11`) :

- `core` — bibliothèque Java (message de salutation), référencée PAR le Kotlin de
  l'application (compilation jointe) ;
- `app` — application Android **Kotlin** (manifeste, ressources, activité Kotlin,
  classe Java visitant l'objet Kotlin), dépendant de `core` et de
  `org.jetbrains.kotlin:kotlin-stdlib:2.0.21` par Maven.

Chaîne exécutée par le moteur : `resolveDependencies` (télécharge la stdlib Kotlin
depuis Maven) → `processResources` (aapt2 : APK de base + sources R) →
`compileKotlin` (kotlinc embarqué, compilation JOINTE — le `R.java` généré et le
Java du module sont parsés pour la résolution de symboles) → `compileJava` (ECJ
contre les classes Kotlin) → `dex` (d8, stdlib comprise) → `packageApk` →
`signApk` (v1+v2, clé de débogage du projet).

Sortie : `build/app/outputs/app-debug.apk` (vérifiable avec apksig, installable
sur appareil — l'installation depuis la coquille arrive avec l'étape 11).

## Sur l'appareil

Exige le composant `kotlin-compiler` (assistant du moteur, ~19 Mo — contenu
identique depuis `v0.2.9`, aucune re-téléchargement pour une mise à jour moteur).
La stdlib doit rester en dépendance Maven du module : sur ART, le chargeur ne
sert pas les entrées `.class` du jar dexé du compilateur — c'est le résolveur
Maven qui la fournit, à la compilation comme à l'exécution (elle rejoint le dex).
