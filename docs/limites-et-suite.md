# Limites et suite

Suivi des manques, hypothèses et travaux restants. Règle du projet : aucun TODO silencieux —
tout écart est consigné ici.

## État des phases

| Phase | État | Livrable |
|---|---|---|
| 0 — Exploration | **Fait** | Dépôt de référence cloné hors du dépôt de travail, SHA épinglé, licence vérifiée et consignée, hooks anti-référence en place. |
| 1 — Analyse | **Fait** | [`analyse-moteur-build.md`](analyse-moteur-build.md) (13 sections, cartographie avec REF-xxx). |
| 2 — Revue critique | **Fait** | [`revue-moteur-build.md`](revue-moteur-build.md) (4 axes, 46 constats numérotés, compatibilité Android, comparaison Gradle). |
| 3 — Conception | **Fait** (2026-11-20) | [`conception.md`](conception.md) (12 décisions D-01..D-12 justifiées, registre des améliorations priorisées, ordre d'implémentation) et [`distribution.md`](distribution.md) (Play exclu, GitHub Releases + F-Droid, clés, licences, guidance appareil). |
| 4 — Implémentation | **Fait** (étapes 1-13, 2026-11-20) | Détail ci-dessous ; moteur Java ET Android jusqu'à l'APK signé, chaîne de publication complète. |
| Post-4 — Assistant, jonction, exemples | **Fait** (2026-11-20) | Étape 14 du tableau ci-dessous : assistant d'installation câblé, jonction des outils, exemples embarqués, publications `v0.2.0` puis `v0.2.1` (clé d'essai stable). |
| Post-4 — Diagnostic sur appareil | **Fait** (2026-11-20) | Étape 15 du tableau ci-dessous : stderr du moteur capturé, journaux copiables, moteurs successifs arrêtés, publication `v0.2.2`. |
| Post-4 — Correctif d'ouverture | **Fait** (2026-11-21) | La `v0.2.2` plantait à l'ouverture (boutons de journaux non branchés à leurs vues) ; correctif + journal d'écroulements de la coquille dans le rapport + garde de câblage des vues dans les tests + appels limités à l'API 28 (`List.of`/`String.lines`/`Files.readString` écartés), publication `v0.2.3`. |
| Post-4 — Correctif ECJ sur ART | **Fait** (2026-11-21) | Constat `v0.2.3` (rapport de diagnostic copié depuis l'appareil) : `core:compileJava` échoue par `Can't find bundle for base name …batch.messages, locale fr_HT` — d8 ne convertit que les classes, les ressources d'ECJ (bundles de messages, tables `.rsc` du parseur et du scanner, ~1,04 Mo) étaient perdues au dexage, et sur JVM les tests ne le voyaient pas (jar complet au classpath). Correctif : les jars dexés conservent toute entrée non-classe des jars sources (manifeste et signatures du jar d'origine écartés — ils ne signent plus rien une fois fusionnés ; `about.html` d'ECJ conservé, notice EPL-2.0 exigée à la redistribution §7), car le classloader d'ART sert les entrées zip d'un jar du classpath (`DexPathList`/`ClassPathURLStreamHandler`, API 28+). Doublons identiques dédupliqués, conflits de contenu refusés, ordre et horodatages fixes (déterminisme des composants). Publication `v0.2.4`. |
| Post-4 — Code de sortie honnête | **Fait** (2026-11-21) | Constat `v0.2.3` : le rapport disait « code de sortie : inconnu (processus encore vivant ou code non encore publié) » d'un moteur pourtant à l'arrêt. Deux causes : (1) `codeSortie()` ne publiait que ce que la sentinelle rapportait — une sentinelle muette (interrompue) laissait le code à `null` pour toujours ; il se guérit désormais seul (processus fini sans publication = `waitFor` immédiat, testé avec connexion sans aucun fil) ; (2) le sondage de mort (2,5 s) tournait sur la file mono-fil de la coquille et retardait l'arrêt du moteur mis en file derrière lui — le moteur restait vivant pendant qu'on l'attendait. La mort est publiée sur son propre fil, et un canal fermé sans code s'affiche désormais comme tel (« canal du moteur fermé, processus encore vivant ») au lieu de prétendre à un arrêt. Publication `v0.2.5`. |
| Post-4 — API `java.compiler` sur ART | **Fait** (2026-11-21) | Constat `v0.2.5` (rapport de diagnostic depuis l'appareil) : `core:compileJava` échoue par `Failed resolution of: Ljavax/lang/model/SourceVersion;`. Audit des pools de constantes d'ECJ 3.36 : 63 classes référencées dans `javax.lang.model`/`javax.annotation.processing`/`javax.tools` — exactement le module `java.compiler` qu'Android n'embarque pas (`batch/FileSystem` appelle `SourceVersion.valueOf` à CHAQUE compilation, hors processeurs d'annotations) ; aucune référence `sun.*`/`jdk.*`/`Runtime$Version` (le relogement de la référence, REF-155, n'est pas nécessaire à cette version d'ECJ). Correctif : le composant `java-compiler` embarque l'API du module, extraite de l'image `jrt` de la JVM d'assemblage à chaque assemblage (JDK épinglé en CI), MOINS `javax.lang.model.SourceVersion`, réécrit pour ART — l'original lit `Runtime.version()` dans son initialiseur, classe absente d'ART et non redéfinissable (`java.*` interdit dans un dex) — et compilé par ECJ lui-même avec `android.jar` en chemin d'amorçage ; garde-fou d'assemblage : toute classe de l'API référencée par ECJ doit être servie par le jar produit (échec nommé sinon, jamais un composant incomplet en production). Audit des autres composants : apksig propre (0 référence absente), r8 en porte 4 (voir « Manques »). Même correctif de franchise : un `LinkageError` ne s'affiche plus « probablement un manque de mémoire » — classification par famille (`MessagesErreursGraves`) et diagnostic structuré dans la trace (elle était vide). Publication `v0.2.6`. |
| Post-4 — Outils Android distribués, chaîne Android sur ART | **Fait — APK signé validé** (2026-11-20/21, `v0.2.7`-`v0.2.9`) | `v0.2.7` publie `platform-34` (android.jar, ~24 Mo — sans lui ECJ ne résout pas `java.lang.Object` sur ART) et le chemin de compilation sans image (plate-forme au classpath de tout module, tranches lambda, correctif varargs) ; `v0.2.8` publie `aapt2-arm64-v8a` (~4,7 Mo, prébuilt Android de ReVanced/aapt2, segments alignés 16 Ko — pages 4 Ko ET 16 Ko) et fait porter son VERDICT final au journal du build (le rapport « jamais terminé » du 2026-11-20 était un faux négatif : la réimportation des exemples effaçait les traces). Rapports appareil du 2026-11-21 : l'exemple `multi-modules-java` construit de bout en bout (6 tâches, 801 ms), et `application-android` valide sur ART `processResources` (PREMIER `exec` d'un binaire téléchargé depuis le stockage privé — W^X, D-04 : le bit exécutable est réparé avant la première exécution), `compileJava` avec R générées, `dex` et `packageApk` (APK non signé produit) ; `signApk` échouait sur `Cannot run program keytool` (binaire du JDK, absent d'ART) — corrigé en `v0.2.9` (ligne suivante). **Rapport `v0.2.9` du 2026-11-21 : la chaîne COMPLETE est validée sur l'appareil** — 9 tâches en 3155 ms, `signApk` compris (APK signé v1+v2 : la clé de débogage en pur Java tient sur ART). |
| Post-4 — Économie de données mobiles | **Fait** (2026-11-21, `v0.2.9`) | Constat utilisateur `v0.2.8` : chaque publication re-téléchargeait TOUT (platform-34 ~24 Mo et aapt2 compris) même quand le contenu n'avait pas changé — la version du composant était celle du moteur. Le manifeste porte désormais une EMPREINTE DE CONTENU par composant (SHA-256 canonique des fichiers extraits) ; l'installateur conserve ces fichiers sous `forge/cache/composants/<empreinte>` et les LIE par lien dur dans le nouveau moteur quand le manifeste réclame la même empreinte : zéro octet réseau pour un contenu identique. Le cache est amorcé depuis le moteur en place avant remplacement (la mise à jour `v0.2.8` → `v0.2.9` économise déjà platform-34 et aapt2), survit aux mises à jour et désinstallations, se vérifie par sommes et se purge explicitement. La publication s'aligne : versions des composants d'OUTILS dérivées de leur contenu (`<base>.<somme8>`, ex. `34.0.0.dba22cde`), release GitHub pérenne « outils » (un manifeste futur pointe les archives existantes sans les re-téléverser), l'assembleur accepte plusieurs `classes*.dex` (préalable au composant Kotlin), et la coquille annonce le volume réel (« déjà téléchargé — aucun octet », Mo évités). |
| Post-4 — Clé de signature de débogage en pur Java | **Fait** (2026-11-21, `v0.2.9`) | Constat `v0.2.8` (rapport appareil, `application-android`) : `app:signApk` mourait par `Cannot run program keytool: error=2` — keytool est un binaire du JDK, ART n'en a pas. La paire RSA et le certificat X.509 auto-signé sont générés EN PROCESSUS (`CertificatAutoSigné`) : encodage DER minimal écrit à la main, signature SHA256withRSA par les seules API publiques de `java.security` (présentes sur JVM comme sur ART), certificat revalidé par `CertificateFactory` et `X509Certificate.verify` à la sortie. Stockage `.forge/debug-key.forge` (conteneur maison : certificat DER + clé PKCS#8) — le lecteur PKCS12 d'ART ne déchiffre pas les clés PBES2/PBKDF2 qu'écrit un keytool moderne (constat d'interopérabilité pris au moteur de référence) ; le p12 hérité de la chaîne `v0.2.8` reste LU là où la plate-forme l'accepte (un projet démarré sur JVM garde sa signature). Chaque projet garde SA clé (un APK de débogage ne doit pas être remplaçable par un APK construit ailleurs). Tests : certificat relu et vérifié (y compris GeneralizedTime au-delà de 2050), conteneur identique relu, p12 repris, APK signé v1+v2 vérifié par ApkVerifier, deux projets deux clés. |
| Post-4 — Kotlin sur JVM (feuille de route langages) | **Fait sur JVM, EXPÉRIMENTAL sur ART** (2026-11-21, `v0.2.9`) | Composant `kotlin-compiler` publié (marqué expérimental) : `kotlin-compiler-embeddable` 2.0.21 + ses dépendances runtime du POM (stdlib, reflect, script-runtime, daemon, coroutines, trove4j — l'embarquable SEUL meurt à l'exécution : `NoClassDefFoundError kotlinx/coroutines/CoroutineScope` constaté), 8 jars dexés en 6 `classes*.dex` (~19 Mo zippé) ; la fusion des ressources du dexage gagne les conflits `META-INF/*.kotlin_module` par PREMIER jar (la distribution Kotlin livre les mêmes modules dans plusieurs jars — 11 chemins en conflit mesurés, même gagnant qu'un classpath de bureau), le tas de d8 est borné à 2 Go (3 Go tuait le processus par tueur OOM sur les machines de 4 Go — code 137 muet, désormais nommé dans le message d'échec). Moteur : champ `language` du module (`java` par défaut, `kotlin` → tâche `compileKotlin` AVANT `compileJava`, même répertoire de classes — le Java du module compile CONTRE les classes Kotlin ; une dépendance de projet dépend de la tâche du LANGAGE de son frère ; Android+Kotlin refusé explicitement, message actionnable — Compose suivra), conventions `src/main/kotlin` puis `src/main/java`, compilateur appelé EN PROCESSUS par réflexion sur les seules API publiques stables (`K2JVMCompiler`, `MessageCollector` proxyfié — diagnostics structurés fichier/ligne/colonne), stdlib de compilation posée depuis le classpath du moteur (JVM) ou en dépendance Maven du module (appareil — le classloader d'ART ne sert pas les entrées `.class` d'un jar dexé). Exemple `examples/kotlin-simple` (mixte Kotlin+Java, stdlib en dépendance Maven). Validé sur JVM : câblage du graphe, build complet avec EXÉCUTION du résultat, diagnostic structuré d'une erreur, module sans source `.kt`. Restait à mener : l'audit ART dédié et la compilation jointe Kotlin→Java du même module — **tous deux LIVRÉS en `v0.2.11`** (ligne suivante). |
| Post-4 — POM sûrs sur ART | **Fait** (2026-11-21, `v0.2.10`) | Constat `v0.2.9` (rapport appareil, `kotlin-simple`) : `kalc:resolveDependencies` mourait par `POM invalide … http://apache.org/xml/features/disallow-doctype-decl` — le parseur POM exigeait des fonctionnalités de durcissement Xerces que le parseur DOM d'ART (KXml) ne reconnaît pas (`setFeature` y lève `ParserConfigurationException(URI)`) ; jamais vu en tests JVM (Xerces les connaît toutes) ni avant sur l'appareil (AUCUN build appareil n'avait de dépendance Maven — kotlin-stdlib fut la première). Correctif, plus fort que le motif du moteur de référence (`runCatching` autour de `setFeature` + résolveur d'entités vide) : DOCTYPE rejeté par SCAN DU PROLOGUE en octets bruts (absolu et portable — même position que Maven, bombe à entités comprise, BOM traversé), garde-fous Xerces/JAXP posés UN PAR UN au mieux (un absent ne tue plus la résolution), résolveur d'entités vide (interface standard des deux runtimes) et franchise du diagnostic : un POM téléchargé mais rejeté est dit INVALIDE (et effacé du cache), plus « introuvable ». Le POM réel de kotlin-stdlib 2.0.21 est re-validé par le nouveau parseur (1 dépendance, `org.jetbrains:annotations:13.0`). |
| Post-4 — Premier `compileKotlin` sur ART + Android+Kotlin | **Corrigé (JVM et ART simulé), validation appareil attendue** (2026-11-21, `v0.2.11`) | Constat `v0.2.10` (rapport appareil, `kotlin-simple`) : `kalc:compileKotlin` meurt en 221 ms par `IllegalStateException: Resource not found: /org/jetbrains/kotlin/utils/PathUtil.class` — le compilateur s'auto-localise par `ClassLoader.getResource("….class")`, or le jar dexé du composant ne porte PLUS d'entrées `.class` (remplacées par les dex) ; la résolution Maven, elle, avait réussi (correctif `v0.2.10` : 1 dépendance au classpath, 2 à l'exécution). AUDIT par lecture du bytecode 2.0.21 : DEUX auto-localisations distinctes tuent l'exécution sur ART — `computeKotlinPaths` (chemins du « Kotlin home ») et le démarrage de l'environnement (`KotlinCoreEnvironment`, configuration des points d'extension). Correctif par ARGUMENTS PUBLICS uniquement, aucune passe bytecode (divergence assumée d'avec la référence REF-133, qui RECOMPILE le compilateur : son jar dexé — vérifié sur l'assemblage réel, 134 métadonnées conservées — GARDE `META-INF/extensions/compiler.xml`, les `.kotlin_builtins` et les `.kotlin_module`, lus par `java.util.zip.ZipFile`, disponible sur ART) : `-kotlin-home <racine du composant>` (répertoire EXISTANT exigé par le bytecode) court-circuite la première, `-Xintellij-plugin-root <lib/kotlin-compiler.jar>` (argument caché documenté du compilateur) désigne le jar réel qui porte la configuration d'extensions. Politique consolidée : `-no-stdlib` TOUJOURS (la stdlib vient du classpath — jar localisé sur la JVM de test, dépendance Maven du module sur l'appareil ; l'absence est un AVERTISSEMENT actionnable, le Kotlin builtins-only compile mais l'exécution exigera la stdlib), `-no-jdk` dès qu'une plate-forme est fournie (`android.jar` au classpath — motif du moteur de référence : pas de JDK hôte à emprunter sur ART), `kotlin.environment.keepalive` posée avant la première compilation (l'environnement du compilateur survit aux builds — miroir de `KotlinEnvironmentKeepAlive`). DEUXIÈME bug corrigé au passage, trouvé par sonde du worker de test : le décodage d'URL jar de `jarDeLaClasse` gardait le schéma interne (`file:/…`) et renvoyait donc null PARTOUT, même sur JVM — la stdlib n'était JAMAIS posée au classpath de compilation, seuls les builtins (servis par le chargeur, pas par un « home ») permettaient de compiler : les tests `v0.2.9` passaient par accident. COMPILATION JOINTE (v0.2.11) : les sources `.java` du module accompagnent les `.kt` en arguments libres — kotlinc les PARSE pour la résolution de symboles (le Kotlin voit le Java du MÊME module, le `R.java` généré d'une application Android compris) sans jamais les émettre ; ECJ compile ensuite le Java CONTRE les classes Kotlin. ANDROID+KOTLIN (v0.2.11) : le refus du modèle est levé — `application` et `android-library` enchaînent `compileKotlin` (après `processResources`, R joint) avant `compileJava`, même répertoire de classes (le dexeur n'y voit qu'un module) ; l'exemple `examples/application-kotlin` (activité Kotlin + objet Kotlin + classe Java visiteuse + module `core` Java + stdlib Maven) est VALIDÉ sur JVM de bout en bout : 10 tâches jusqu'à l'APK signé, résolution Maven RÉELLE de kotlin-stdlib (téléchargée puis dexée). Tests : ART simulé avec composant installé (`SimulationArtTest`), compilation jointe avec exécution du résultat, maison/racine explicites, message d'absence de stdlib, chaîne Android+Kotlin jusqu'à l'APK signé vérifié par ApkVerifier. |

### Phase 4 : détail par étape

| Étape | État | Contenu livré |
|---|---|---|
| 1. Squelette | **Fait** | Build Gradle multi-modules (`api`, `client`, `setup`, `engine`, `app`), wrapper 8.10.2, AGP 8.5.2, CI build+tests+APK, coquille minimale (cible API 34, `targetSdk` 28 assumé). |
| 2. Contrat + moteur factice | **Fait** | Trames stdio, négociation de version, messages et événements scellés ; moteur factice (ping, état, builds scriptés, annulation) ; client (lanceurs `app_process`/`java`, connexion, corrélation) ; test d'intégration avec VRAI processus séparé. |
| 3. Installateur | **Fait** | JSON strict maison, manifeste signé ECDSA P-256 (octets exacts), HTTPS exigé, téléchargeur reprenable (Range, rejet des 206 non sollicités), extracteur anti-zip-slip plafonné, installation atomique (staging + renommage + lecture seule), mise à jour/réparation/désinstallation/hors ligne. Tests avec serveurs HTTP locaux réels (coupure à mi-corps puis reprise vérifiée). |
| 4. Chargement réel | **Fait** | `EngineSession` (description de VM depuis l'état installé, refus clair si incomplet), `ForgeHostService` (service premier plan), interface branchée ; test de bout en bout : le VRAI moteur installé comme composant puis lancé et interrogé. |
| 5. Moteur de tâches | **Fait** | Graphe (Kahn par niveaux, cycle cité, ordre déterministe, dépendances explicites), contrat `Task` avec entrées/sorties déclarées. |
| 6. Exécuteur | **Fait** | File de prêtes, parallélisme plafonné calculé, échec bloquant transitif, branches saines menées à terme, annulation par interruption immédiate des threads, événements en flux. |
| 7. Empreintes et magasin | **Fait** | Empreintes par contenu (fichiers, répertoires en flux, propriétés triées, version de code), magasin atomique versionné à somme de contrôle, noms injectifs, corruption = périmé, sorties revérifiées ; incrémentalité prouvée par test (toucher un fichier ne relance que sa tâche). |
| 8. Pipeline Java | **Fait** | Modèle déclaratif `forge.json` strict (codec testé), ECJ en processus avec diagnostics par l'API (fichier/ligne/colonne/code), tâches `compileJava`/`jar` câblées par portées (fermeture `api`), jar déterministe (testé octet par octet), **vrai serveur de build** câblé dans `EngineMain` (défaut ; `--fake` garde le moteur scripté pour les tests de contrat), exemple `examples/multi-modules-java`, benchmark complet vs incrémental (première mesure : 6/6 → 4/6 tâches). Parseur JSON extrait dans le module `json/` partagé. |
| 9. Résolveur | **Fait** | Résolution Maven complète : POM effectifs (parents, propriétés, gestion, nomenclatures importées, relogements), exclusions, optionnelles, médiation trois politiques (échec-sur-conflit réel, chemins cités), comparateur aligné Maven, cache réplique à écritures atomiques + cache négatif TTL, intégrité sha1, HTTPS strict (boucle locale pour les tests), bornes avec diagnostic, hors ligne, convergence en deux passes ; tâche `resolveDependencies` câblée dans le pipeline (le classpath résolu est une entrée d'empreinte de la compilation) ; 16 tests sur un vrai dépôt HTTP local. **Bonus : correction d'une course réelle dans l'exécuteur** (double soumission d'une tâche par la boucle des racines, détectée par le benchmark incrémental, corrigée et re-vérifiée 5 fois). |
| 10. Pipeline Android | **Fait** | Chaîne `application` complète jusqu'à l'**APK signé v1+v2 vérifié par apksig**, testée avec de VRAIS outils (aapt2 officiel extrait du dépôt Maven, android.jar API 34, d8 et apksig en processus) : `processResources` (aapt2 borné 5 min, annulation par destruction, autotest, diagnostics localisés), `compileJava` (R générées au chemin des sources, `android.jar` au classpath), `dex` (d8 en processus, diagnostics par l'API, classes emballées en jar déterministe), `packageApk` (déterministe, arsc STORED, noms traversants refusés), `signApk` (clé de débogage générée par keytool borné, aucun secret en ligne de commande) ; `android-library` v1 = classes+jar contre android.jar (ressources/AAR non livrés, consigné) ; incrémentalité testée (source modifiée → ressources à jour ; ressource modifiée → chaîne aval relancée) ; exemple `examples/application-android`. |
| 11. Diagnostics et console | **Fait** | Rédaction de secrets au point de sortie (D-09, heuristique consignée), parseur générique de sortie d'outils (repli sans API), journal circulaire borné par build (P-7), trace JSON exportable par build sous `.forge/traces/` (A-13, atomique, testée), écran de build complet dans la coquille (chemin du projet, progression, journal + diagnostics en flux, annulation, installation de l'APK avec confirmation système). |
| 12. Packaging et publication | **Fait** | Chaîne complète : `PackagerComposants` (setup) assemble les six composants dexés (engine-core 93 Ko, java-compiler 1,2 Mo, dexeur 3,2 Mo, signeur 165 Ko, aapt2-arm64-v8a 4,7 Mo — prébuilt ReVanced depuis v0.2.8, binaire d'essai de 2,2 Mo avant —, platform-34 24 Mo) en zips déterministes + manifeste JSON via le codec de l'installateur ; signature ECDSA P-256 du manifeste (outil `OutilManifeste` : génération, signature, vérification — round-trip testé avec le vérificateur de la coquille) ; **clé de production générée et épinglée** (publique : `ClesForge` dans la coquille ; privée : jeton CI `FORGE_MANIFEST_KEY`, posé par l'API GitHub, jamais versionnée) ; tâche `:engine:composants` ; job CI `release` sur étiquette `vX.Y.Z` (assemblage, signature, vérification contre la clé épinglée, version GitHub). La chaîne a été exécutée en entier dans le bac à sable : signature vérifiée VALIDE. |
| 13. Import Gradle statique (optionnel) | **Fait** | Lecteur STATIQUE des scripts Groovy (aucun code évalué — même limite que la référence, assumée) : `include` du settings, plugins, dépôts (`google()`, `mavenCentral()`), dépendances (portées Gradle, notation `g:a:v` et `project(':x')`), bloc `android` (namespace, applicationId, min/target/compileSdk) ; blocs sur une ligne normalisés ; constructions inconnues ignorées AVEC avertissement ; le modèle produit passe le codec officiel (testé) ; application sans namespace refusée avec message actionnable. |
| 14. Assistant, jonction, exemples (post-4) | **Fait** (2026-11-20) | Canal signé (`CanalMoteur` : manifeste + signature vérifiés contre la clé épinglée, HTTPS strict, plafonds) ; assistant d'installation dans la coquille (composants par ABI avec tailles, avertissement données mobiles, progression globale, annulation avec reprise, réparation, désinstallation) ; `FORGE_ENGINE_DIR` transmis au moteur (outils Android localisés dans le moteur installé) ; layout des composants publiés conforme à la convention d'installation (engine.jar, lib/, components/ — l'ancien layout à plat aurait écrasé les classes.dex à l'extraction) ; exemples embarqués en assets et importables sans permission ; publications `v0.2.0` puis `v0.2.1` SANS composants redistribuables du SDK (garde juridique §7), APK de test signé de la clé d'essai STABLE joint (jetons CI `FORGE_APK_KEYSTORE_B64`/`FORGE_APK_KEYSTORE_MDP` — sans elle, chaque exécutant éphémère signerait différemment et aucune version ne s'installerait sur la précédente) ; déclencheur CI des étiquettes corrigé (le job release ne se serait jamais exécuté). |
| 15. Diagnostic sur appareil (post-4) | **Fait** (2026-11-20) | Constat de départ : premier test sur appareil (`v0.2.1`) — le moteur meurt au milieu de `compileJava` sans laisser de trace, et aucun journal ne sort de l'appareil. Livré : **capture continue du stderr du moteur** par le client (`JournalMoteur` borné 500 lignes, consommateur en flux, sentinelle du **code de sortie** — la trace qui survit à une mort brutale du processus, et le drainage évite qu'un tube non lu bloque un moteur bavard) ; **rapport copiable/partageable** dans la coquille (contexte versions/appareil, journal de build, journal du moteur, dernière trace JSON, description du code de sortie : propre / signal du tueur de mémoire / explicite) ; à la mort inattendue du moteur, code de sortie et dernières lignes de stderr sont affichés dans le journal (la génération du moteur distingue mort attendue et mort à signaler) ; **moteurs successifs arrêtés** (avant chaque build, à la fin du build, et dans le service hôte — des VM oisives s'accumulaient : un `app_process` de 512 Mo par build/test, mémoire saturée, tueur de processus déclenché au pire moment) ; **filet de sécurité du serveur** contre les `Error` avant les tâches (échec propre au lieu d'un fil mort en silence). Tests : capture de stderr de bout en bout dans un vrai processus (code 7), bornage du journal, moteur factice (code 0), rapport complet/vides/troncature/codes (JVM). |

## Manques et limites constatés (Phases 3-4, étapes 1-14)

- **Écarts du pipeline Java avec le moteur de référence** (détail dans la section
  « Comparaison après implémentation » de [`REFERENCES.md`](REFERENCES.md)) :
  processeurs d'annotations désactivés (parité, à documenter comme décision), compilation
  incrémentale fine par fichier et cache d'ABI absents (améliorations A-8/A-10 non livrées —
  l'up-to-date par tâche et l'empreinte de classpath par contenu donnent déjà l'essentiel),
  `processResources` Java fusionné dans le jar (v1), chaîne de tests par module non livrée.
  Le compilateur sans image pour ART est LIVRÉ (`v0.2.7`, REF-155/156) — avec le correctif
  `originalSourceLevel` que la référence n'a pas (ses expansions varargs binaires échouent).
  Suivi possible : le cœur Java filtré (android.jar moins `android.*`) pour les projets
  purement Java, comme la référence le dérive pour ses applications console.
- **AAR et classifieurs non supportés à ce jour** : le résolveur refuse proprement tout
  empaquetage non-`jar` (message citant l'étape 10) ; les métadonnées Gradle
  `.module` (sélection de variante, contraintes), l'éviction par capacités, les
  alignements de famille, les SNAPSHOTS et la parallélisation par vagues ne sont pas
  livrés (écart consigné dans `REFERENCES.md`) ; les téléchargements d'artefacts sont
  en flux mais sans reprise (petits artefacts — la reprise existe pour les composants
  du moteur).
- **AAR et artefacts Android** : le résolveur refuse toujours les empaquetages
  non-`jar` (AAR à l'étape suivante si nécessaire) ; les dépendances natives
  (`.so`) et le multidex par buckets ne sont pas livrés.
- **Écarts du pipeline Android avec le moteur de référence** (détail dans la section
  « Comparaison après implémentation » de [`REFERENCES.md`](REFERENCES.md)) :
  ressources = granularité tâche (A-9 par fichier non livré), pas de ViewBinding,
  BuildConfig, AIDL, minification R8/L8, bundle, multidex, caches dex à trois
  niveaux, fusion de manifestes multi-modules (manifeste du module application
  seul), R générées en SOURCE et non en bytecode (REF-065), signature de production
  (débogage seul), installation sur appareil (étape 11, côté coquille).
- **Résolution des outils Android (aapt2, android.jar)** : la jonction est FAITE
  (post-4, étape 14) — le moteur installé localise ses outils via `FORGE_ENGINE_DIR`
  (composants par ABI et par API, localisateur testé sur JVM). Depuis `v0.2.7`, le
  composant `platform-34` (android.jar) est publié : c'est lui qui permet à ECJ de
  résoudre `java.lang.Object` sur ART (correctif du build appareil — un module
  sans facette Android reçoit la plate-forme la plus haute installée, un projet
  mixte suit le `targetSdk` de ses modules Android). Depuis `v0.2.8`, le composant
  `aapt2-arm64-v8a` est publié aussi : prébuilt Android du projet ReVanced/aapt2
  (source AOSP Apache-2.0, segments LOAD alignés 16 Ko — se charge sur les appareils
  à pages de 4 Ko ET de 16 Ko) ; le prébuilt x86_64 des build-tools Google reste
  le repli des machines de développement. Exécution du binaire depuis le stockage
  privé **VALIDÉE sur appareil** (`v0.2.8`, rapport du 2026-11-21 : `processResources`
  passe, le bit exécutable est réparé avant la première exécution — W^X, D-04).
- **Écran de build et installation** : l'assistant d'installation est CÂBLÉ depuis
  l'étape 14 (canal, composants, tailles, avertissement données mobiles, progression,
  reprise, réparation, désinstallation). Restent non vérifiés sur appareil réel :
  l'installation par PackageInstaller de l'APK construit et le lancement
  `app_process` du moteur. La rédaction de secrets reste heuristique (étiquettes
  usuelles FR/EN, porteurs) — un secret sans étiquette passe (limite consignée).

- **Vérifications sur appareil réel (ART)** — la chaîne vit ses premiers passages depuis
  `v0.2.2` (moto g06, Android 15, arm64) : lancement `app_process` + `CLASSPATH` **validé**
  (le moteur démarre, répond et meurt proprement, D-01), arrêt des moteurs successifs
  **validé**, `resolveDependencies` **validé** (deux modules en parallèle), capture du
  stderr et rapports **validés** (c'est eux qui ont livré chaque diagnostic des
  correctifs `v0.2.3`→`v0.2.7`). **Build Java complet validé** depuis `v0.2.7` :
  l'exemple `multi-modules-java` construit de bout en bout sur l'appareil (rapports du
  2026-11-20 — `compileJava` des deux modules, `jar`, sortie propre du moteur, 801 ms ;
  le premier rapport « jamais terminé » était un faux négatif : la réimportation des
  exemples avait effacé les traces, corrigé en `v0.2.8` par le verdict final dans le
  journal). **Chaîne Android validée jusqu'à `packageApk`** depuis `v0.2.8` (rapports du
  2026-11-21, exemple `application-android`) : `processResources` (PREMIER `exec` d'un
  binaire téléchargé depuis le stockage privé — W^X, D-04, le bit exécutable est réparé
  avant la première exécution), `compileJava` avec R générées, `dex`, `packageApk` —
  et **`signApk` VALIDÉ** depuis `v0.2.9` (rapport du 2026-11-21 : `application-android` réussit de bout
  en bout, 9 tâches en 3155 ms, APK signé v1+v2 — la clé de débogage en pur Java, correctif `v0.2.9`, tient
  sur ART). Restent à constater : l'installation par PackageInstaller de l'APK produit, le tueur de
  processus Android 12+ (D-02) et la validation appareil du `compileKotlin` corrigé en `v0.2.11`
  (constat `v0.2.10` : `PathUtil` ne peut s'auto-localiser depuis un jar dexé ; correctif par
  arguments publics — maison et racine de configuration, voir la ligne de phase dédiée). Le repli `DexClassLoader`
  reste conçu mais non câblé.
- **Kotlin (v0.2.9-v0.2.11) — périmètre et limites assumés** : modules `java-library`
  ET Android (`application`, `android-library` — admis depuis la `v0.2.11` :
  `compileKotlin` en compilation jointe précède `compileJava`, même répertoire de
  classes ; Compose attendra) ; compilation MIXTE dans les DEUX sens depuis la
  `v0.2.11` — le Java du module voit les classes Kotlin, et le Kotlin voit le Java
  du même module (sources `.java` parsées par kotlinc, `R.java` généré compris,
  jamais émises par lui) ; la stdlib de COMPILATION vient du classpath du
  moteur sur la JVM de test et d'une dépendance Maven du module sur l'appareil (le
  classloader d'ART ne sert pas les entrées `.class` d'un jar dexé — le composant ne
  peut pas la porter en classes lisibles par kotlinc ; son ABSENCE est un
  avertissement actionnable depuis la `v0.2.11`, l'exécution l'exigera de toute
  façon) ; processeurs d'annotations
  (KAPT/KSP) non livrés (parité avec le pipeline Java, où ils sont désactivés). Sur
  ART, le composant est publié EXPÉRIMENTAL : l'audit dédié est MENÉ (`v0.2.11` —
  lecture du bytecode, deux auto-localisations neutralisées par arguments publics,
  AUCUNE passe bytecode contrairement à la référence REF-133 : le jar dexé conserve
  sa configuration d'extensions) ; la validation appareil reste attendue. La RÉSOLUTION Maven des
  dépendances Kotlin est corrigée sur ART en `v0.2.10` : le parseur POM durcissait son DOM par des
  fonctionnalités Xerces inconnues d'ART, et `kotlin-stdlib` (première dépendance Maven d'un build
  appareil) tuait `resolveDependencies` — DOCTYPE désormais rejeté par scan du prologue (portable),
  durcissement au mieux (motif `runCatching` de la référence) ; le crash `compileKotlin` du rapport
  `v0.2.10` (`Resource not found: …/PathUtil.class`, 221 ms) est corrigé en `v0.2.11`.
- **Kotlin sur ART — inconnues restantes (v0.2.11)** : le correctif est validé sur JVM et
  en ART SIMULÉ (nom de VM « Dalvik », composant installé, `android.jar` réel —
  `SimulationArtTest`), mais trois points ne se constateront que sur l'appareil :
  (1) la PREMIÈRE exécution réelle de kotlinc sur ART (tas du moteur 512 Mo — le
  chargement des 6 dex et de l'environnement du compilateur est le gros poste ; le
  keepalive de l'environnement devrait amortir les builds suivants) ; (2) le
  comportement du SECOND build dans le même processus moteur (l'environnement
  applicatif est conservé — `kotlin.environment.keepalive` — mais sa survie à un
  `dispose` du lot précédent n'est prouvée que sur JVM) ; (3) la compilation jointe
  d'un `R.java` volumineux (l'exemple n'en a qu'un exemplaire minuscule).
- **Audit ART des composants distribués (v0.2.6)** — mêmes outils d'audit que pour ECJ
  (lecture des pools de constantes) : `apksig` **propre** (0 classe référencée absente
  d'ART) ; `r8` en porte **4**, toutes hors du chemin D8 du dexage simple que Forge
  exécute sur l'appareil : `sun.misc.Unsafe` (5 classes internes — la classe existe dans
  le runtime Android, cachée), `javax.xml.stream` (rétrécisseur de ressources R8, jamais
  appelé : Forge ne minifie pas) et `com.sun.management.HotSpotDiagnosticMXBean`
  (diagnostics HotSpot, inexistants sur ART). Ces portes se refermeront si le pipeline
  Android sur appareil en touche une — à reprendre dans la trace de tout échec
  `app:dex` sur ART ; aucune action avant cela.
- **Watchdog des sous-processus (D-09, constat S-8)** : pas encore implémenté. Précision
  après l'étape 8 : ECJ compile **en processus** (aucun sous-processus à surveiller) ; le
  watchdog et le registre des enfants viennent donc avec l'étape 10 (aapt2 natif). L'annulation
  des threads est, elle, en place et testée.
- **Écarts assumés avec le moteur de référence** (voir la section « Comparaison après
  implémentation » de [`REFERENCES.md`](REFERENCES.md)) : pas de `mustRunAfter`/
  `mustRunBefore`, pas de statut `NO-SOURCE`, pas d'arêtes inférées producteur→consommateur
  (refus délibéré, constat C-5) — les pipelines livrés n'en ont pas eu besoin.
- **Le serveur de build réel est câblé depuis l'étape 8** : `EngineMain` lance par défaut le
  `BuildEngineServer` (modèle `forge.json` → pipeline → graphe → exécuteur → trames) ; le moteur
  factice reste accessible par l'option `--fake` (tests de contrat du canal).
- **Éditeur/gestion de projets dans la coquille** : minimal — état du moteur,
  assistant d'installation, import des exemples embarqués, écran de build. Un
  véritable éditeur (création de projets, import depuis le stockage partagé,
  arborescence) reste à construire ; les exemples embarqués rendent le premier build
  immédiat sans aucune permission.
- **Licence de Forge choisie : Apache-2.0** (décision propriétaire du 2026-11-20, appliquée
  au dépôt entier — fichier `LICENSE` ; voir `distribution.md` §7 pour les notices des
  dépendances embarquées, dont la génération reste à produire à la publication).
- **Clé de signature de manifeste** : **générée et épinglée** (étape 12) — clé
  publique dans `ClesForge` (coquille, test de format), clé privée en jeton CI
  `FORGE_MANIFEST_KEY` (posé le 2026-11-20 par l'API GitHub, jamais versionnée) ;
  la rotation reste à documenter opérationnellement (`distribution.md` §4).
- **Redistribution de `android.jar` et du binaire `aapt2`** : `platform-34` (android.jar
  du SDK) est publié depuis `v0.2.7` et `aapt2-arm64-v8a` depuis `v0.2.8` — prébuilt
  Android du projet ReVanced/aapt2 (redistribution validée le 2026-11-20 : source AOSP
  Apache-2.0, pratique établie des IDE sur appareil — la référence embarque ce même
  prébuilt dans son APK). Le binaire x86_64 des build-tools Google ne quitte jamais la
  chaîne (développement et tests JVM seulement) ; `android.jar` sert au DEXAGE côté
  publication ET est redistribué comme composant de compilation.
- **Stabilité CI — flocons résolus le 2026-11-20** (échecs intermittents des étapes
  8-13 sur GitHub Actions, reproduits localement en boucle ~1/25) : deux causes
  racines, toutes deux corrigées. (1) Le serveur de build écrivait ses trames depuis
  plusieurs threads dont les files de tâches, éphémères : `PipedInputStream` lève
  « Write end dead » dès que le dernier écrivain du tube meurt — tout le canal passe
  désormais par un fil rédacteur unique et durable (file FIFO, ordre d'émission
  préservé, vidange à l'arrêt). (2) L'exécuteur laissait mourir silencieusement un
  runnable écroulé hors de ses zones protégées (le `Future` avalait l'exception :
  comptage rompu, sous-graphe jamais soumis, build fini sans tâche fautive) — un
  filet de sécurité convertit désormais tout throwable résiduel en échec propre
  (état FAILED, tâche fautive, dépendants bloqués) et `terminer` est idempotent ;
  les enregistreurs des tests, appelés depuis les threads du pool, utilisent des
  collections concurrentes. Validation : 85 exécutions de la suite complète sans
  échec (contre ~1 échec sur 20 avant correctifs).
- **CI rouge v0.2.11 — alignement de version oublié, corrigé le 2026-11-21** : la bump
  `v0.2.11` (`4f618c5`) a mis à jour les `build.gradle` des six modules mais a oublié
  `EngineVersion.CURRENT` (resté `"0.2.10"`) — le test de chargement `EngineSessionTest`
  cherche le jar du moteur PAR NOM (`engine-<CURRENT>.jar`) sur le classpath de test
  et ne trouvait que `engine-0.2.11.jar` : 2 tests sur 18 rouges sur GitHub Actions
  (étape « Tests de la coquille », run `44859033060`), alors que la suite locale
  paraissait verte (`UP-TO-DATE` d'un cache antérieur — repris avec
  `--rerun-tasks` pour reproduire). Le même oubli dans l'étape release de la CI
  (classpath en dur `setup-0.2.10.jar` → cassait la prochaine étiquette) est
  éliminé à la racine : la version des jars y est DÉRIVÉE d'`EngineVersion.java`
  (même constante que les tests, `sed` + garde d'existence, échec nommé sinon) —
  plus rien à mettre à jour dans la CI lors d'une bump. Leçon consignée : la bump
  de version touche SIX `build.gradle` + `EngineVersion.java` + `versionCode`/
  `versionName` de la coquille ; les tests locaux cache-chaud ne prouvent rien
  après un changement de version, `--rerun-tasks` s'impose.

## Hypothèses retenues (Phases 0-3)

1. **`targetSdk 28`** pour la coquille (D-04) : préserve l'exécution des binaires
   téléchargés depuis le stockage privé ; cohérent avec une distribution hors magasin.
2. **Transport stdio + VM enfant** (D-01) : remplacé par `DexClassLoader` + tubes si un
   appareil bloque `app_process` — seul le lanceur change.
3. **ECDSA P-256** pour la signature du manifeste : Ed25519 n'est disponible qu'à partir
   de l'API 33, hors de notre `minSdk` 28.
4. **Le moteur est pur Java** : aucune API Android dans `engine/`, `client/`, `setup/`,
   `api/` — paramètres d'environnement injectés par la coquille.
5. **Répertoire de travail du moteur de référence** : cloné hors du dépôt, SHA épinglé
   `1b1a3052168a52f38a91ea03c87831a0d94c1fff`, jamais copié ni référencé hors de
   `docs/REFERENCES.md`.

## Procédure de test sur appareil (v0.2.11)

La chaîne est testable de bout en bout depuis la publication `v0.2.11` (la `v0.2.3`
compilait encore son premier projet Java en échec : ressources d'ECJ perdues au
dexage, `Can't find bundle … locale fr_HT` — corrigé en `v0.2.4` ; le code de
sortie du moteur est honnête depuis la `v0.2.5` ; le crash `SourceVersion` sur
ART — API `java.compiler` absente d'Android — est corrigé en `v0.2.6` ;
`v0.2.7` corrige l'échec « The type java.lang.Object cannot be resolved »
(plate-forme `platform-34` publiée, compilation sans image, tranches lambda,
correctif varargs) ; `v0.2.8` publie `aapt2-arm64-v8a` et porte le verdict final
dans le journal du build ; `v0.2.9` ajoute l'économie de données (cache par
empreinte de contenu — la mise à jour depuis `v0.2.8` ne re-télécharge PAS
platform-34 ni aapt2), la clé de signature de débogage en pur Java (le crash
keytool d'ART — rapport `v0.2.8`, `app:signApk` — est corrigé) et le composant
`kotlin-compiler`, expérimental sur ART ; `v0.2.10` corrige le parseur POM sur
ART (crash Xerces du rapport `v0.2.9`, première résolution Maven d'un build
appareil) ; `v0.2.11` corrige le premier `compileKotlin` sur ART (crash
`PathUtil` du rapport `v0.2.10`, le jar dexé ne porte plus d'entrées `.class` —
maison et racine de configuration posées par arguments publics, stdlib par Maven,
compilation jointe, Android+Kotlin admis) :

1. **Installer la coquille** : APK signé de la clé d'essai joint à la version GitHub
   `v0.2.11` (`app-debug.apk`), installation par chargement latéral (autoriser les
   sources inconnues). Les versions de test successives s'installent l'une sur
   l'autre (clé d'essai stable, jetons CI) ; une installation antérieure signée
   autrement (clé de débogage éphémère d'un exécutant, comme l'APK d'artefact des
   builds de branche) doit d'abord être désinstallée. Une `v0.2.1`-`v0.2.9` déjà
   installée se met à jour directement par-dessus. Une `v0.2.10` déjà installée se met à jour
   par-dessus (seul le cœur, ~0,1 Mo, passe par le réseau — voir l'étape suivante).
2. **Mettre à jour le moteur** : section *Installation du moteur* — *Charger le
   manifeste* puis *Réinstaller (mise à jour ou réparation)* : les composants de la
   `v0.2.11` remplacent ceux des versions antérieures — le cache par empreinte de
   contenu (économies de données, `v0.2.9`) fait que seuls les composants au contenu
   RÉELLEMENT changé passent par le réseau : la mise à jour `v0.2.10` → `v0.2.11`
   ne re-télécharge que le cœur (~0,1 Mo — le correctif `compileKotlin` y vit), car
   `java-compiler`, `dexeur`, `signeur` et `kotlin-compiler` sont de contenu IDENTIQUE
   (même empreinte → liaison locale, aucun octet), et `platform-34`/`aapt2` résident sur
   la release pérenne « outils ». Le composant `kotlin-compiler` (~19 Mo, 6 dex,
   expérimental sur ART) est proposé depuis la `v0.2.9`. Rappels : le journal du build porte son verdict final depuis la
   `v0.2.8` ; la `v0.2.7` avait apporté `platform-34` (~24 Mo, android.jar) et le
   chemin de compilation sans image ; la `v0.2.6` l'API `java.compiler` pour ART,
   la `v0.2.4` le correctif des ressources, la `v0.2.5` le client au code de
   sortie auto-guéri.
3. **Construire l'exemple Java** : bouton *Importer les projets d'exemple* (stockage
   privé, aucune permission), puis *Construire* — le moteur démarre dans son propre
   processus (`app_process`, tas dédié), ECJ compile sur ART, les jar sortent dans
   `<projet>/build/`. Un second build doit être presque entièrement *à jour*
   (incrémentalité par empreintes). Depuis la `v0.2.2`, le moteur du build
   précédent est arrêté avant chaque lancement et à la fin du build (plus de VM
   oisives qui s'accumulent). **Validé sur appareil le 2026-11-20** (`v0.2.7`) :
   l'exemple `multi-modules-java` construit de bout en bout — attention, la
   réimportation des exemples EFFACE sorties, empreintes et traces du projet.
4. **Construire l'exemple Android** : même parcours avec le chemin
   `application-android` — premier passage COMPLET de la chaîne Android sur ART
   (`processResources` par le binaire `aapt2` du composant, `compileJava` avec les R
   générées, `dex` par d8 en processus, `packageApk`, `signApk`), puis bouton
   *Installer l'APK* sur l'artefact produit. **VALIDÉ DE BOUT EN BOUT** (rapport
   `v0.2.9` du 2026-11-21) : les 9 tâches réussissent en 3155 ms, `signApk` compris —
   APK signé v1+v2, la clé de débogage en pur Java tient sur ART (le crash keytool
   de la `v0.2.8` est bien mort). Le point restant est le bouton *Installer l'APK*
   (PackageInstaller) : premier test réel d'installation de l'APK produit.
5. **Construire l'exemple Kotlin** : même parcours avec le chemin
   `kotlin-simple` — exige le composant `kotlin-compiler` (assistant, ~19 Mo, déjà
   installé si la `v0.2.10` l'était : contenu identique, aucun re-téléchargement).
   Le module `kalc` mélange Kotlin (`src/main/kotlin`) et Java (`src/main/java`) : la
   chaîne est `resolveDependencies` (télécharge `kotlin-stdlib` depuis Maven
   Central — corrigé sur ART en `v0.2.10`) → `compileKotlin` (corrigé en `v0.2.11` :
   le crash `PathUtil` du rapport `v0.2.10` est mort — maison et racine de
   configuration posées par arguments publics, compilation JOINTE, le Kotlin voit
   le Java du module) → `compileJava` → `jar`. **État `v0.2.11` : correctif validé
   sur JVM et ART simulé — LE PREMIER `compileKotlin` RÉEL sur ART est le point à
   observer** (premier chargement des 6 dex du compilateur dans le tas moteur de
   512 Mo, second build dans le même processus) ; tout échec ici reste précieux :
   joindre le rapport (journal du moteur, trace JSON).
6. **Construire l'exemple Android Kotlin** : même parcours avec le chemin
   `application-kotlin` (nouveau, `v0.2.11`) — application Android écrite en
   Kotlin : activité Kotlin + objet Kotlin + classe Java visitant l'objet + module
   `core` Java + `kotlin-stdlib` en dépendance Maven (résolue puis DEXÉE dans
   l'APK). La chaîne complète `resolveDependencies` → `processResources` →
   `compileKotlin` (R joint) → `compileJava` → `dex` → `packageApk` → `signApk`
   est VALIDÉE sur JVM (10 tâches, APK signé v1+v2 vérifié) ; sur ART, elle suit
   le sort du point précédent (même compilateur, mêmes arguments).
7. **En cas de problème (débogage)** : bouton *Copier les journaux* (presse-papiers)
   ou *Partager* (courriel, notes…) — le rapport contient le contexte (versions,
   appareil, projet), le journal du build (qui porte son verdict final depuis la
   `v0.2.8`), le **journal du moteur** (stderr capturé
   en continu : la seule trace qui survit à une mort brutale du processus), les
   **écroulements de la coquille** (depuis `v0.2.3` : piles d'appels des exceptions
   non attrapées, écrites dans un journal borné avant la fermeture du processus —
   un « force close » laisse donc sa trace) et la dernière trace JSON. Si le moteur
   meurt en cours de build, le journal affiché reçoit automatiquement le **code de
   sortie** (0 = propre ; -9 ou 137 = tué par le signal 9, typiquement le tueur de
   mémoire du système face à des VM oisives — corrigé en `v0.2.2` ; positif = échec
   interne, la trace stderr dit lequel) et les dernières lignes de stderr. C'est ce
   rapport qu'il faut joindre à tout ticket.

Points observés par ce test : lancement `app_process` avec `CLASSPATH` (D-01),
jars dexés en lecture seule, tueur de processus Android 12+ (D-02). Le premier
`exec` du binaire `aapt2` téléchargé (W^X, D-04) est VALIDÉ (`v0.2.8`, rapport du
2026-11-21), la chaîne complète jusqu'à l'APK signé v1+v2 est VALIDÉE (`v0.2.9`,
rapport du 2026-11-21) ; restent observables : l'installation par PackageInstaller,
l'économie de données des mises à jour (message « déjà téléchargé — aucun octet »,
Mo évités) et le premier `compileKotlin` RÉEL sur ART (correctif `v0.2.11` : JVM et
ART simulé verts — voir les trois inconnues listées dans « Manques »).

## Prochaines étapes (après la Phase 4)

1. **Vérification sur appareil réel (ART)** — le build Java complet est validé
   (`multi-modules-java`, `v0.2.7`, rapports du 2026-11-20 ; re-confirmé `v0.2.8`,
   801 ms) et **la chaîne Android complète est validée jusqu'à l'APK signé**
   (`v0.2.9`, rapport du 2026-11-21 : 9 tâches, 3155 ms, `signApk` compris — la clé
   en pur Java tient sur ART). Restent : l'installation PackageInstaller de l'APK
   produit, le tueur de processus Android 12+ (D-02) et la VALIDATION APPAREIL du
   `compileKotlin` corrigé (`v0.2.11` corrige le crash `PathUtil` du rapport
   `v0.2.10` — le compilateur ne peut s'auto-localiser depuis un jar dexé ; la
   correction passe par arguments publics, validée sur JVM et ART simulé ; sur
   l'appareil : `kotlin-simple` puis `application-kotlin`, premier ET second build).
   Chaque échec restant se lira dans un
   rapport qui classe les erreurs graves par famille depuis la `v0.2.6` et dont le
   journal porte le verdict final depuis la `v0.2.8` ; les mises à jour successives
   permettent aussi de constater l'économie de données (seul le contenu changé
   passe par le réseau).
2. **Prébuilt `aapt2` ANDROID** — **FAIT en `v0.2.8`** : le composant
   `aapt2-arm64-v8a` est publié (prébuilt du projet ReVanced/aapt2, source AOSP
   Apache-2.0, segments LOAD alignés 16 Ko — la construction AOSP dédiée envisagée
   n'est plus nécessaire ; validation de redistribution dans `distribution.md` §7).
   Les autres ABI (armeabi-v7a, x86, x86_64) suivront à la demande — même source,
   même chaîne CI.
3. **Feuille de route langages — Kotlin, Compose, multiplate-forme** (état :
   Kotlin LIVRÉ sur JVM en `v0.2.9` — modules `java-library`, mixte Java/Kotlin,
   composant `kotlin-compiler` publié EXPÉRIMENTAL sur ART ; la référence prouve
   chaque maillon, voir REF-130..136 et REF-145 ; `v0.2.11` corrige le premier
   `compileKotlin` sur ART, livre la compilation jointe et admet Android+Kotlin) :
   - *Kotlin (cibles JVM/Android)* — **FAIT pour la cible JVM en `v0.2.9`** (composant
     dexé multi-dex de ~19 Mo : `kotlin-compiler-embeddable`
     2.0.21 + dépendances runtime de son POM — l'embarquable seul meurt par
     `NoClassDefFoundError` coroutines ; tâche `compileKotlin` avant `compileJava`,
     même répertoire de classes ; stdlib de compilation par le classpath du moteur
     (JVM) ou en dépendance Maven du module (appareil) ; exemple `kotlin-simple`).
     L'AUDIT ART dédié est MENÉ en `v0.2.11` (lecture du bytecode : deux
     auto-localisations neutralisées par arguments publics — `-kotlin-home`,
     `-Xintellij-plugin-root` — SANS les passes bytecode de la référence
     REF-133/135, le jar dexé conservant sa configuration d'extensions) ; la
     compilation jointe Kotlin→Java du même module est LIVRÉE (les deux sens,
     `R.java` compris) ; le câblage Android+Kotlin est LIVRÉ (`application` et
     `android-library`, exemple `application-kotlin` validé sur JVM jusqu'à l'APK
     signé). Restent : la validation APPAREIL des trois (voir le point 1),
     l'incrémental fin (REF-131/REF-132), puis KSP plutôt que KAPT pour Room/Hilt
     (choix de la référence
     également ; les processeurs d'annotations sont désactivés, parité Java). La
     bibliothèque standard et les coroutines passent par le résolveur Maven livré.
   - *Compose* : au-dessus de Kotlin — plugin compilateur Compose (embarqué avec
     Kotlin 2.x, activation par sonde de classpath REF-136) + dépendances
     `androidx.compose.*` résolues par Maven + la chaîne Android complète
     (`aapt2` arm64, dex, package, signature). Suite IMMÉDIATE du point ci-dessus
     maintenant que Android+Kotlin est livré : c'est la prochaine marche de la
     feuille de route langages (détail : le plugin doit être DEXÉ dans le moteur
     sur ART — son registrar se résout par délégation parente, le jar ne sert que
     de descripteur — motif REF-136) ; la prévisualisation à l'édition relève de la coquille,
     pas du moteur.
   - *Kotlin Multiplateforme* : les cibles Android/JVM seront couvertes par le travail
     Kotlin ci-dessus ; les cibles **native** (chaîne LLVM complète, hors de portée
     d'un moteur embarqué) et **JS** (chaîne Node) ne sont PAS prévues sur appareil.
     Un projet KMP se construirait sur ses cibles Android/JVM uniquement — écart à
     assumer explicitement dans le modèle (la référence importe KMP une cible à la
     fois, REF-145 ; son pliage des ensembles de sources `dependsOn` et son option
     `-Xcommon-sources` pour expect/actual sont le modèle à suivre le jour venu).
4. **Signature de production** de la coquille (clé d'essai stable aujourd'hui,
   jetons CI — les APK de test successifs s'installent l'un sur l'autre ; la clé de
   production et sa rotation restent à produire) et génération des notices de
   licences des dépendances embarquées (§7).
5. Améliorations priorisées restantes du registre de la conception : A-8 (cache de
   sorties par contenu), A-9 (ressources incrémentales par fichier), A-10 (empreintes
   d'ABI), AAR, multidex.
6. Éditeur/gestion de projets dans la coquille (création, import, arborescence).
7. Premier benchmark sur projet Android complet sur appareil.
