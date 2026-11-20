package jo.forge.engine.pipeline.android;

import com.android.apksig.ApkSigner;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Tâche {@code <module>:signApk} : signature de l'APK (schémas v1 + v2 — v1 conservé
 * pour les cibles d'API antérieures à 24, v2 requis ensuite).
 *
 * <p>Clé de DÉBOGAGE générée à la première utilisation dans le magasin du projet
 * ({@code .forge/debug-key.forge}) — EN PROCESSUS depuis v0.2.9 (correctif du crash
 * « Cannot run program keytool » sur ART : {@link DebugKeyStore}) — aucun secret en
 * clair, aucun sous-processus, aucun mot de passe sur une ligne de commande (écart
 * assumé avec le moteur de référence, constat sécurité de la
 * revue). La signature de production (magasin fourni, secret hors journal) est une
 * évolution consignée dans {@code docs/limites-et-suite.md}.</p>
 */
public final class SignApkTask implements Task {

    /** Version du code de cette tâche (v0.2.9 : clé en pur Java, conteneur .forge). */
    static final String TASK_VERSION = "2";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final PackageApkTask empaquetage;

    /**
     * Crée la tâche.
     *
     * @param module     module Android.
     * @param layout     disposition du projet.
     * @param empaquetage tâche de packaging (APK non signé).
     */
    public SignApkTask(ForgeModule module, ProjectLayout layout,
                       PackageApkTask empaquetage) {
        this.module = module;
        this.layout = layout;
        this.empaquetage = empaquetage;
    }

    @Override
    public String name() {
        return module.name() + ":signApk";
    }

    @Override
    public List<String> dependencies() {
        return List.of(empaquetage.name());
    }

    @Override
    public TaskIO io() {
        return new TaskIO(List.of(empaquetage.apkNonSigné()), List.of(),
                Map.of("schémas", "v1+v2", "minSdk",
                        Integer.toString(module.android().orElseThrow().minSdk())),
                List.of(apkSigné()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        int minSdk = module.android().orElseThrow().minSdk();
        DebugKeyStore clés = new DebugKeyStore(layout.root().resolve(".forge"));
        DebugKeyStore.Clé clé = clés.chargerOuGénérer();

        Path signé = apkSigné();
        Files.createDirectories(signé.getParent());
        Path temporaire = signé.resolveSibling(signé.getFileName() + ".part");
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                DebugKeyStore.ALIAS, clé.privée(), List.of(clé.certificat())).build();
        ApkSigner signeur = new ApkSigner.Builder(List.of(config))
                .setInputApk(empaquetage.apkNonSigné().toFile())
                .setOutputApk(temporaire.toFile())
                // Schémas choisis EXPLICITEMENT : v1 (JAR, pour les vieilles cibles
                // et les vérificateurs génériques) + v2 (requis API 24+). Le minSdk
                // passé au signeur reste 1 : il ne sert qu'aux choix internes de
                // condensés, jamais à désactiver un schéma demandé.
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setMinSdkVersion(1)
                .build();
        try {
            signeur.sign();
        } catch (Exception echec) {
            throw new IOException("échec de la signature de " + module.name() + " : "
                    + echec.getMessage(), echec);
        }
        try {
            Files.move(temporaire, signé, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, signé, StandardCopyOption.REPLACE_EXISTING);
        }
        context.logger().info("APK signé (v1+v2) : " + signé.getFileName());
    }

    /** APK final signé. */
    Path apkSigné() {
        return layout.buildDir(module.name()).resolve("outputs")
                .resolve(module.name() + "-debug.apk");
    }
}
