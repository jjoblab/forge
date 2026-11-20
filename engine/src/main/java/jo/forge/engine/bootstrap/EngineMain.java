package jo.forge.engine.bootstrap;

import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.MessageIO;
import jo.forge.engine.server.BuildEngineServer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * Point d'entrée de la machine virtuelle du moteur.
 *
 * <p>Lancé par la coquille (ou par un test JVM) comme processus enfant :
 * {@code app_process -Xmx…&nbsp;/ jo.forge.engine.bootstrap.EngineMain [options]} avec
 * {@code CLASSPATH} pointant sur les jars dexés du moteur. Le canal de contrôle est
 * stdin/stdout ; stderr est réservé aux diagnostics d'écroulement
 * (décisions D-01 et D-03).</p>
 *
 * <p>Depuis l'étape 8, le serveur câblé par défaut est le **vrai moteur de build**
 * ({@link BuildEngineServer} : modèle {@code forge.json}, pipeline Java, empreintes).
 * L'option {@code --fake} conserve le moteur scripté de l'étape 2 (ping, version,
 * builds simulés), utile aux tests de contrat du canal.</p>
 */
public final class EngineMain {

    /**
     * Constructeur privé : classe utilitaire d'entrée.
     */
    private EngineMain() {
    }

    /**
     * Démarre le moteur sur les flux standards.
     *
     * @param args options : {@code --fake} pour le moteur scripté (tests de contrat).
     */
    public static void main(String[] args) {
        PrintStream diagnostics = System.err;
        boolean factice = Arrays.asList(args).contains("--fake");
        Runnable serveur = factice
                ? () -> lancerFactice(diagnostics)
                : () -> lancerReel(diagnostics);
        serveur.run();
    }

    /**
     * Lance le moteur scripté (étape 2).
     */
    private static void lancerFactice(PrintStream diagnostics) {
        FakeEngineServer server = new FakeEngineServer(System.out, diagnostics);
        try {
            server.serve(System.in);
            System.exit(0);
        } catch (IOException ioError) {
            diagnostics.println("Écroulement du canal de contrôle : " + ioError.getMessage());
            System.exit(1);
        } catch (RuntimeException protocolError) {
            diagnostics.println("Erreur de protocole : " + protocolError.getMessage());
            System.exit(2);
        }
    }

    /**
     * Lance le vrai moteur de build (étape 8 et suivantes).
     */
    private static void lancerReel(PrintStream diagnostics) {
        BuildEngineServer server = new BuildEngineServer(System.out, diagnostics);
        try {
            server.serve(System.in);
            System.exit(0);
        } catch (IOException ioError) {
            diagnostics.println("Écroulement du canal de contrôle : " + ioError.getMessage());
            System.exit(1);
        } catch (RuntimeException protocolError) {
            diagnostics.println("Erreur de protocole : " + protocolError.getMessage());
            System.exit(2);
        }
    }
}
