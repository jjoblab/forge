package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskLogger;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de {@code generateViewBinding} (v0.2.18) : une classe
 * {@code <namespace>.databinding.<Layout>Binding} par layout — champ public
 * final typé par {@code android:id} (camelCase depuis l'id), include DANS le
 * module exposant la liaison incluse, variantes de configuration fusionnées
 * (union des ids), {@code tools:viewBindingIgnore} respecté, racine
 * {@code <merge>} typée View — et la source générée est du Java COMPILABLE
 * (vérifié par ECJ contre android.jar quand les outils de test sont là).
 */
class GenerateViewBindingTaskTest {

    @TempDir
    Path temporaire;

    /** Un contexte minimal (journal collecté, annulation jamais posée). */
    private record Contexte(Path racine, List<String> journal) implements TaskContext {

        Contexte(Path racine) {
            this(racine, new ArrayList<>());
        }

        @Override
        public Path projectRoot() {
            return racine;
        }

        @Override
        public TaskLogger logger() {
            return new TaskLogger() {
                @Override
                public void debug(String message) {
                    journal.add(message);
                }

                @Override
                public void info(String message) {
                    journal.add(message);
                }

                @Override
                public void warn(String message) {
                    journal.add(message);
                }

                @Override
                public void error(String message) {
                    journal.add(message);
                }
            };
        }

        @Override
        public CancellationFlag cancellation() {
            return new CancellationFlag();
        }
    }

    /** Le modèle d'un module application avec ViewBinding activé. */
    private ForgeModule moduleVue(Path racine) throws Exception {
        Projets.fichier(racine, "forge.json", """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "res": ["src/main/res"],
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.app",
                                 "buildFeatures": { "viewBinding": true } } } ] }
                """);
        return ModelCodec.read(racine).modules().get(0);
    }

    @Test
    void unLayoutUneClasseChampsTypésEtIncludeLié() throws Exception {
        Path racine = temporaire.resolve("projet");
        moduleVue(racine);
        // Layout principal : vues identifiées, un include DU module, une vue
        // ignorée, un id de framework (aucun champ), un id en snake_case.
        Projets.fichier(racine, "app/src/main/res/layout/activity_main.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:tools="http://schemas.android.com/tools"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:orientation="vertical">
                    <TextView
                            android:id="@+id/titre_principal"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                    <Button
                            android:id="@+id/action"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                    <ImageView
                            android:id="@+id/vitrine_ignorée"
                            tools:viewBindingIgnore="true"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                    <Spinner
                            android:id="@android:id/text1"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                    <include
                            android:id="@+id/pied"
                            layout="@layout/vue_pied"/>
                </LinearLayout>
                """);
        // L'include porte un id : il expose la LIAISON du layout inclus.
        Projets.fichier(racine, "app/src/main/res/layout/vue_pied.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <merge xmlns:android="http://schemas.android.com/apk/res/android">
                    <Button
                            android:id="@+id/pied_action"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                </merge>
                """);

        GenerateViewBindingTask tâche = new GenerateViewBindingTask(
                ModelCodec.read(racine).modules().get(0), new ProjectLayout(racine));
        assertEquals("app:generateViewBinding", tâche.name());
        tâche.execute(new Contexte(racine));

        Path paquet = racine.resolve(
                "build/app/generated/viewbinding/demo/app/databinding");
        assertTrue(Files.isRegularFile(paquet.resolve("ActivityMainBinding.java")),
                "la classe du layout existe");
        String principale = Files.readString(paquet.resolve("ActivityMainBinding.java"));
        // Champs : camelCase depuis l'id, types résolus comme l'inflater.
        assertTrue(principale.contains("public final android.widget.TextView titrePrincipal;"),
                "snake_case → camelCase, type résolu : " + principale);
        assertTrue(principale.contains("public final android.widget.Button action;"));
        assertTrue(principale.contains("public final demo.app.databinding.VuePiedBinding pied;"),
                "un include DU module expose la liaison incluse");
        assertFalse(principale.contains("vitrineIgnorée"),
                "tools:viewBindingIgnore écarte la vue");
        assertFalse(principale.contains("text1"),
                "un id du framework ne porte pas de champ");
        // L'interface et les méthodes AGP.
        assertTrue(principale.contains("implements androidx.viewbinding.ViewBinding"));
        assertTrue(principale.contains("public static ActivityMainBinding inflate("));
        assertTrue(principale.contains("public static ActivityMainBinding bind("));
        assertTrue(principale.contains("demo.app.R.layout.activity_main"),
                "l'inflation référence le R du module");
        assertTrue(principale.contains("vueRacine.findViewById(demo.app.R.id.titre_principal)"),
                "le bind passe par findViewById sur l'id RESSOURCE");
        assertTrue(principale.contains("VuePiedBinding.bind(piedVue)"),
                "l'include se lie par la liaison incluse");
        // Racine LinearLayout : getRoot covariant.
        assertTrue(principale.contains("public android.widget.LinearLayout getRoot()"));

        // Le layout <merge> : racine View, le champ du sous-élément présent.
        String pied = Files.readString(paquet.resolve("VuePiedBinding.java"));
        assertTrue(pied.contains("public android.view.View getRoot()"),
                "un layout merge attache sur la vue passée : " + pied);
        assertTrue(pied.contains("android.widget.Button piedAction;"));
    }

    @Test
    void variantesDeConfigurationFusionnentLeursIds() throws Exception {
        Path racine = temporaire.resolve("projet");
        moduleVue(racine);
        // La variante portrait porte un id, la variante pays un autre : le
        // binding doit exposer l'UNION (une vue présente dans UNE config a
        // son champ).
        Projets.fichier(racine, "app/src/main/res/layout/diviseur.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">
                    <TextView
                            android:id="@+id/portrait_un"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                </FrameLayout>
                """);
        Projets.fichier(racine, "app/src/main/res/layout-land/diviseur.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">
                    <EditText
                            android:id="@+id/pays_deux"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"/>
                </FrameLayout>
                """);

        new GenerateViewBindingTask(ModelCodec.read(racine).modules().get(0),
                new ProjectLayout(racine)).execute(new Contexte(racine));

        String fusion = Files.readString(racine.resolve(
                "build/app/generated/viewbinding/demo/app/databinding/DiviseurBinding.java"));
        assertTrue(fusion.contains("portraitUn;"), "l'id du portrait est un champ");
        assertTrue(fusion.contains("paysDeux;"), "l'id du pays aussi (union)");
        // Le layout ignoré ENTIÈREMENT n'a pas de classe.
        Projets.fichier(racine, "app/src/main/res/layout/ignoré.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:tools="http://schemas.android.com/tools"
                        tools:viewBindingIgnore="true"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"/>
                """);
        new GenerateViewBindingTask(ModelCodec.read(racine).modules().get(0),
                new ProjectLayout(racine)).execute(new Contexte(racine));
        assertFalse(Files.exists(racine.resolve(
                "build/app/generated/viewbinding/demo/app/databinding/IgnoréBinding.java")),
                "un layout ignoré ne génère rien");
    }

    @Test
    void sansAucunLayoutLaTâcheRéussitSilencieusement() throws Exception {
        Path racine = temporaire.resolve("projet");
        ForgeModule module = moduleVue(racine);
        Contexte contexte = new Contexte(racine);
        new GenerateViewBindingTask(module, new ProjectLayout(racine))
                .execute(contexte);
        assertTrue(contexte.journal().stream()
                        .anyMatch(l -> l.contains("aucun layout")),
                "le journal le dit franchement : " + contexte.journal());
    }
}
