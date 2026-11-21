# Exemple Kotlin simple (expérimental)

Premier projet Kotlin du moteur Forge (feuille de route langages, v0.2.9) :

- `kalc` — bibliothèque Kotlin et Java mélangées : `object Calculs` en Kotlin
  (`src/main/kotlin`), `Principal` en Java (`src/main/java`) qui l'appelle.

## Construire avec le moteur

Depuis une coquille Forge installée : pointer un build sur ce répertoire (le moteur y
lit `forge.json`). La chaîne du module est `resolveDependencies` → `compileKotlin` →
`compileJava` → `jar` — le Java compile contre les classes Kotlin fraîches, le jar
porte les deux.

## Prérequis composant

La tâche `compileKotlin` exige le composant `kotlin-compiler` du moteur (assistant
d'installation). **État v0.2.9** : la compilation est validée sur JVM ; sur ART elle
est EXPÉRIMENTALE (l'audit dédié reste à mener, voir `docs/limites-et-suite.md` —
le moteur de référence a dû instrumenter le compilateur en profondeur pour
l'exécuter sur appareil).

Le module déclare `org.jetbrains.kotlin:kotlin-stdlib:2.0.21` en dépendance Maven :
c'est la bibliothèque standard de la COMPILATION sur appareil (le classloader du
moteur ne sert pas les entrées `.class` d'un jar dexé — sur la JVM de test, la
stdlib vient du classpath du moteur) et celle de l'EXÉCUTION partout (le jar produit
référence `kotlin.jvm.internal.*`).

## Exécuter le résultat (JVM)

```
java -cp build/kalc/classes:.forge/deps/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar demo.Principal
```
