package jo.forge.engine.pipeline.android;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import jo.forge.engine.tasks.TaskContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dexage PAR BIBLIOTHÈQUE avec seaux à hash de contenu (décision D-16) — le modèle du
 * transform d'artefacts {@code DexingTransform} d'AGP et du moteur de référence :
 * chaque jar est dexé SÉPARÉMENT, en mode intermédiaire (un
 * {@code .dex} par classe), dans un seau {@code <cache>/minApi&lt;N&gt;-<format>/
 * <sha256-du-contenu>/} scellé par une sentinelle de complétude.
 *
 * <p>Pourquoi : le constat appareil v0.2.21 (rapport du 2026-11-21) — une seule
 * invocation d8 monolithique sur ~45 jars (~30 Mo de classes) dans un tas de 1 024 Mo
 * sur ART, des minutes de silence puis la mort par signal 9 (tueur de processus
 * Android 12+, décision D-02). Ici chaque invocation d8 ne voit qu'UN jar : le pic de
 * mémoire est borné par la plus grosse bibliothèque, pas par la fermeture entière ;
 * chaque seau terminé SURVIT à une mort brutale (le build suivant ne re-dexe que ce
 * qui manque) ; et un build dont les dépendances n'ont pas changé re-fusionne des
 * seaux déjà prêts SANS re-dexer.</p>
 *
 * <p>La fusion (d8 est son propre fusionneur, comme AGP) regroupe les {@code .dex} par
 * classe en LOTS bornés (multidex natif dès {@code minApi 21} : chaque lot devient un
 * {@code classes.dex}, {@code classes2.dex}… indépendant) ; sous 21, un seul lot comme
 * avant. L'exactitude post-dex est VÉRIFIÉE : chaque classe dexable du jar doit avoir
 * son {@code .dex} dans le seau (un dexeur peut réussir en silenciant des classes —
 * leçon du moteur de référence).</p>
 *
 * <p>Clé de seau : hash du CONTENU du jar assaini (sans {@code module-info.class},
 * copie déterministe — horodatages fixes, entrées triées) ;{@code minApi} et format
 * dans le chemin parent. Dès {@code minApi 26} la clé est de contenu seul (pas de
 * désucrage — parité {@code DESUGAR_FREE_MIN_API}) ; sous 26, le désucrage dépend de
 * l'ensemble du classpath : le seau est alors régénéré par fusion complète (la clé
 * embarque l'empreinte de l'univers des jars, passée par l'appelant).</p>
 */
final class DexBibliothèques {

    /** Version du format des seaux (tout changement de dexeur l'incrémente). */
    private static final String FORMAT_SEAU = "v1";

    /** Sentinelle de complétude d'un seau (présente = seau utilisable tel quel). */
    private static final String SENTINELLE = ".seau-complet";

    /**
     * Taille maximale d'un lot de fusion en fichiers {@code .dex} par classe —
     * borne le pic mémoire de CHAQUE invocation de fusion (le moteur de référence
     * passe par défaut à 6 000 classes par lot).
     */
    private static final int MAX_PAR_LOT = 6_000;

    /** Niveau d'API minimal du module (clé de seau, borne d8). */
    private final int minApi;

    /** Racine du cache de seaux (partagé entre projets quand le moteur l'expose). */
    private final Path répertoireCache;

    /** Empreinte de l'univers des jars (désucrage sous minApi 26), vide sinon. */
    private final String empreinteUnivers;

    /**
     * Crée le dexeur par bibliothèque.
     *
     * @param répertoireCache racine du cache ({@code .forge/dex} du projet, ou cache
     *                        partagé du moteur quand il existe — survit à une
     *                        réimportation du projet).
     * @param minApi          niveau d'API minimal du module.
     */
    DexBibliothèques(Path répertoireCache, int minApi) {
        this(répertoireCache, minApi, "");
    }

    /**
     * Crée le dexeur par bibliothèque avec empreinte d'univers (minApi &lt; 26 : le
     * désucrage d8 dépend de l'ensemble du classpath, la clé de seau doit en tenir
     * compte — parité avec le {@code desugarDigest} du moteur de référence).
     *
     * @param répertoireCache   racine du cache.
     * @param minApi            niveau d'API minimal du module.
     * @param empreinteUnivers  empreinte (hexadécimale, éventuellement vide) de
     *                          l'ensemble des jars à dexer ; ignorée dès minApi 26.
     */
    DexBibliothèques(Path répertoireCache, int minApi, String empreinteUnivers) {
        this.répertoireCache = répertoireCache;
        this.minApi = minApi;
        this.empreinteUnivers = minApi >= 26 ? "" : empreinteUnivers;
    }

    /**
     * Le répertoire racine des seaux pour cette configuration ({@code minApi} + format
     * + univers éventuel).
     */
    private Path racineSeaux() {
        String clé = "minApi" + minApi + "-" + FORMAT_SEAU;
        if (!empreinteUnivers.isEmpty()) {
            clé += "-u" + empreinteUnivers;
        }
        return répertoireCache.resolve(clé);
    }

    /**
     * Dexe un jar dans son seau (ou reprend le seau existant) et retourne le
     * répertoire du seau — l'appelant y lit les {@code .dex} par classe.
     *
     * @param jar     le jar à dexer (assaini au passage : {@code module-info} écarté).
     * @param androidJar jar de la plate-forme (bibliothèque de référence d8).
     * @param context contexte d'exécution (progression, annulation, diagnostics).
     * @return le répertoire du seau complet.
     * @throws IOException si le dexage ou la vérification échoue.
     */
    Path seauDe(Path jar, Path androidJar, TaskContext context) throws IOException {
        Path assaini = assainir(jar, context);
        String hash = empreinte(assaini);
        Path seau = racineSeaux().resolve(hash);
        Path sentinelle = seau.resolve(SENTINELLE);
        if (Files.isRegularFile(sentinelle)) {
            return seau; // Seau complet d'un build précédent : reprise immédiate.
        }
        // Seau partiel d'une exécution tuée en cours : on repart proprement.
        supprimerArbre(seau);
        Files.createDirectories(seau);

        List<String> classesAttendues = classesDe(assaini);
        D8Command.Builder commande = D8Command.builder(new DiagnosticsVersPuits(context))
                .addProgramFiles(assaini)
                .addLibraryFiles(androidJar)
                .setMinApiLevel(minApi)
                .setIntermediate(true)
                .setOutput(seau, OutputMode.DexFilePerClassFile)
                .setGlobalSyntheticsOutput(seau);
        try {
            D8.run(commande.build());
        } catch (CompilationFailedException echec) {
            supprimerArbre(seau);
            throw new IOException("échec du dex de " + jar.getFileName()
                    + " — voir les diagnostics", echec);
        }
        // Exactitude : chaque classe dexable du jar doit avoir son .dex — un dexeur
        // peut « réussir » en silenciant des classes (leçon du moteur de référence).
        List<String> manquantes = new ArrayList<>();
        for (String classe : classesAttendues) {
            if (!Files.isRegularFile(seau.resolve(classe + ".dex"))) {
                manquantes.add(classe);
            }
        }
        if (!manquantes.isEmpty()) {
            supprimerArbre(seau);
            throw new IOException("d8 a silencieusement écarté " + manquantes.size()
                    + " classe(s) de " + jar.getFileName() + " — première : "
                    + manquantes.get(0));
        }
        sceller(seau, classesAttendues.size());
        return seau;
    }

    /**
     * Fusionne les seaux en {@code classes.dex}, {@code classes2.dex}… dans le
     * répertoire de dex du module — d8 est son propre fusionneur (modèle AGP) : les
     * {@code .dex} intermédiaires par classe sont regroupés en LOTS bornés, chaque
     * lot fusionné en dex indexé indépendant (multidex natif dès minApi 21 ; sous 21,
     * un seul lot — la limite 64 k méthodes du multidex légendaire s'applique).
     *
     * @param seaux          seaux complets à fusionner.
     * @param répertoireDex  répertoire de sortie (vidé de ses classes*.dex).
     * @param androidJar     jar de la plate-forme.
     * @param context        contexte d'exécution (progression, annulation).
     * @throws IOException si une fusion échoue.
     */
    void fusionner(List<Path> seaux, Path répertoireDex, Path androidJar,
                   TaskContext context) throws IOException {
        Files.createDirectories(répertoireDex);
        // Nettoyage des classes*.dex d'un build précédent (les autres fichiers
        // éventuels du répertoire ne regardent pas la fusion).
        try (DirectoryStream<Path> anciens = Files.newDirectoryStream(répertoireDex,
                "classes*.dex")) {
            for (Path ancien : anciens) {
                Files.deleteIfExists(ancien);
            }
        }
        // Tous les .dex par classe (triés — déterminisme), plus les synthétiques
        // globaux (fichiers non-.dex des seaux, rejoués à la fusion). Le parcours
        // est RÉCURSIF : d8 écrit chaque .dex au chemin de sa classe
        // (demo/app/MainActivity.dex) — un balayage plat ne verrait que les
        // répertoires de paquets.
        List<Path> parClasse = new ArrayList<>();
        List<Path> synthétiques = new ArrayList<>();
        for (Path seau : seaux) {
            List<Path> fichiers = new ArrayList<>();
            try (var parcours = Files.walk(seau)) {
                parcours.filter(Files::isRegularFile).forEach(fichiers::add);
            }
            fichiers.sort(Comparator.comparing(seau::relativize));
            for (Path fichier : fichiers) {
                String nom = fichier.getFileName().toString();
                if (nom.equals(SENTINELLE) || nom.endsWith(".part")) {
                    continue;
                }
                if (nom.endsWith(".dex")) {
                    parClasse.add(fichier);
                } else {
                    synthétiques.add(fichier);
                }
            }
        }
        if (parClasse.isEmpty() && synthétiques.isEmpty()) {
            return; // Rien à fusionner (module sans classes).
        }
        // Lots : bornés en multidex natif, sinon un seul (comportement d'avant).
        List<List<Path>> lots = new ArrayList<>();
        if (minApi >= 21) {
            for (int i = 0; i < parClasse.size(); i += MAX_PAR_LOT) {
                lots.add(parClasse.subList(i, Math.min(i + MAX_PAR_LOT,
                        parClasse.size())));
            }
        } else {
            lots.add(parClasse);
        }
        int indiceDex = 0; // classes.dex, classes2.dex, classes3.dex…
        for (int i = 0; i < lots.size(); i++) {
            context.checkCancelled();
            List<Path> lot = lots.get(i);
            context.progression("fusion des dex : lot " + (i + 1) + "/" + lots.size()
                    + " (" + lot.size() + " classes)");
            Path tampon = Files.createTempDirectory(répertoireDex, "lot-");
            try {
                List<Path> programmes = new ArrayList<>(lot);
                if (i == 0) {
                    // Les synthétiques globaux n'ont de sens qu'une fois, au premier
                    // lot (ils matérialisent des classes partagées).
                    programmes.addAll(synthétiques);
                }
                D8Command.Builder commande = D8Command.builder(
                                new DiagnosticsVersPuits(context))
                        .addProgramFiles(programmes)
                        .addLibraryFiles(androidJar)
                        .setMinApiLevel(minApi)
                        .setOutput(tampon, OutputMode.DexIndexed);
                try {
                    D8.run(commande.build());
                } catch (CompilationFailedException echec) {
                    throw new IOException("échec de la fusion des dex (lot "
                            + (i + 1) + "/" + lots.size() + ") — voir les diagnostics",
                            echec);
                }
                // d8 peut produire PLUSIEURS classes*.dex si un lot dépasse la limite
                // de méthodes : chacun prend le nom suivant de la séquence APK.
                List<Path> produits = new ArrayList<>();
                try (DirectoryStream<Path> parcours =
                             Files.newDirectoryStream(tampon, "classes*.dex")) {
                    parcours.forEach(produits::add);
                }
                produits.sort(Comparator.comparing(Path::getFileName));
                for (Path produit : produits) {
                    Path cible = répertoireDex.resolve(nomDex(indiceDex++));
                    Files.move(produit, cible, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                supprimerArbre(tampon);
            }
        }
        if (indiceDex == 0) {
            throw new IOException("la fusion n'a produit aucun dex — seaux vides ?");
        }
    }

    /** Nom APK du i-ème dex : {@code classes.dex}, {@code classes2.dex}… */
    private static String nomDex(int indice) {
        return indice == 0 ? "classes.dex" : "classes" + (indice + 1) + ".dex";
    }

    /** Scelle un seau : sentinelle atomique avec le compte de classes. */
    private static void sceller(Path seau, int classes) throws IOException {
        Path temporaire = seau.resolve(SENTINELLE + ".part");
        Files.writeString(temporaire, classes + "\n");
        try {
            Files.move(temporaire, seau.resolve(SENTINELLE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, seau.resolve(SENTINELLE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Les entrées .class d'un jar (chemins internes, sans module-info). */
    private static List<String> classesDe(Path jar) throws IOException {
        List<String> classes = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (java.util.zip.ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".class"))
                    .filter(e -> !e.getName().endsWith("module-info.class"))
                    .sorted(java.util.Comparator.comparing(
                            java.util.zip.ZipEntry::getName))
                    .toList()) {
                classes.add(entrée.getName().substring(0,
                        entrée.getName().length() - ".class".length()));
            }
        }
        return classes;
    }

    /** SHA-256 du contenu d'un fichier, 24 hexadécimaux (identité de seau). */
    private static String empreinte(Path fichier) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var entrée = Files.newInputStream(fichier)) {
                byte[] tampon = new byte[64 * 1024];
                int lus;
                while ((lus = entrée.read(tampon)) >= 0) {
                    digest.update(tampon, 0, lus);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte octet : digest.digest()) {
                if (hex.length() >= 24) {
                    break;
                }
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent de la plate-forme",
                    impossible);
        }
    }

    /** Empreinte de l'univers des jars (sous minApi 26) : SHA-256 des noms d'artefacts
     * (triés) et tailles — l'identité Maven (groupe:artefact:version → nom+taille
     * stables par immutabilité des coordonnées) suffit, sans re-hacher tout le
     * contenu une seconde fois. */
    static String empreinteUnivers(List<Path> jars) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent de la plate-forme",
                    impossible);
        }
        List<String> identités = new ArrayList<>();
        for (Path jar : jars) {
            identités.add(jar.getFileName() + ":" + Files.size(jar));
        }
        identités.sort(String::compareTo);
        for (String identité : identités) {
            digest.update(identité.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0x1F);
        }
        StringBuilder hex = new StringBuilder();
        for (byte octet : digest.digest()) {
            if (hex.length() >= 12) {
                break;
            }
            hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
            hex.append(Character.forDigit(octet & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Assainit un jar pour d8 : sans {@code module-info.class} (racine ou
     * {@code META-INF/versions/*}). Le jar d'origine est renvoyé TEL QUEL quand il
     * est déjà propre (le cas de la plupart) ; la copie assainie vit dans le cache de
     * seaux (déterministe : mêmes octets pour un même artefact, donc même clé).
     */
    private static Path assainir(Path jar, TaskContext context) throws IOException {
        boolean àNettoyer;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            àNettoyer = zip.stream().anyMatch(entrée ->
                    entrée.getName().endsWith("module-info.class"));
        } catch (IOException illisible) {
            return jar; // jar absent/illisible : d8 le dira nommément.
        }
        if (!àNettoyer) {
            return jar;
        }
        // Nom de la copie par empreinte du jar D'ORIGINE : deux artefacts distincts
        // au même nom de fichier (groupes différents) ne se marchent jamais dessus,
        // et un même artefact resert toujours la même copie (contenu déterministe).
        String nom = jar.getFileName().toString();
        Path cible = racineAssainis(context)
                .resolve(empreinte(jar) + "-nettoye.jar");
        if (Files.isRegularFile(cible)) {
            return cible;
        }
        Files.createDirectories(cible.getParent());
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile());
             java.util.zip.ZipOutputStream sortie = new java.util.zip.ZipOutputStream(
                     Files.newOutputStream(temporaire))) {
            for (java.util.zip.ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> !e.getName().endsWith("module-info.class"))
                    .sorted(java.util.Comparator.comparing(
                            java.util.zip.ZipEntry::getName))
                    .toList()) {
                java.util.zip.ZipEntry copie = new java.util.zip.ZipEntry(
                        entrée.getName());
                copie.setTime(315532800000L);
                sortie.putNextEntry(copie);
                zip.getInputStream(entrée).transferTo(sortie);
                sortie.closeEntry();
            }
        }
        try {
            Files.move(temporaire, cible,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, cible,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        context.logger().debug("jar assaini pour d8 (module-info écarté) : "
                + cible.getFileName());
        return cible;
    }

    /** Répertoire des copies assainies, dans le cache de la tâche. */
    private static Path racineAssainis(TaskContext context) {
        return context.projectRoot().resolve("build").resolve("assainis-dex");
    }

    /** Supprime un répertoire entier (silencieux si absent). */
    private static void supprimerArbre(Path répertoire) throws IOException {
        if (!Files.exists(répertoire)) {
            return;
        }
        try (var parcours = Files.walk(répertoire)) {
            parcours.sorted(java.util.Comparator.reverseOrder())
                    .forEach(fichier -> {
                        try {
                            Files.deleteIfExists(fichier);
                        } catch (IOException illisible) {
                            // Un reste indélégable ne doit pas bloquer le build : le
                            // seau sera reconstruit à côté (hash différent) ou la
                            // sentinelle manquante déclenchera sa reconstruction.
                        }
                    });
        }
    }

    /**
     * Traduit les diagnostics d8 vers le puits de la tâche (erreurs localisées).
     */
    private record DiagnosticsVersPuits(TaskContext context) implements DiagnosticsHandler {

        @Override
        public void warning(Diagnostic diagnostic) {
            context.logger().warn("d8 : " + diagnostic.getDiagnosticMessage());
        }

        @Override
        public void error(Diagnostic diagnostic) {
            jo.forge.engine.diag.Diagnostic traduit =
                    new jo.forge.engine.diag.Diagnostic(
                            jo.forge.api.BuildEvent.Severity.ERROR,
                            diagnostic.getOrigin() == null ? ""
                                    : diagnostic.getOrigin().toString(),
                            0, 0,
                            "d8",
                            diagnostic.getDiagnosticMessage(),
                            "dexeur");
            context.diagnostics().report(traduit);
        }
    }
}
