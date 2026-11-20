package jo.forge.engine.bootstrap;

import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.MessageIO;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Point d'entrée de la machine virtuelle du moteur.
 *
 * <p>Lancé par la coquille (ou par un test JVM) comme processus enfant :
 * {@code app_process -Xmx…&nbsp;/ jo.forge.engine.bootstrap.EngineMain [options]} avec
 * {@code CLASSPATH} pointant sur les jars dexés du moteur. Le canal de contrôle est
 * stdin/stdout ; stderr est réservé aux diagnostics d'écroulement
 * (voir {@code docs/conception.md}, décisions D-01 et D-03).</p>
 *
 * <p>Étape 2 de la Phase 4 : le serveur câblé ici est le moteur factice
 * {@link FakeEngineServer} (ping, version, état, builds scriptés). Les étapes suivantes
 * remplaceront cette affectation par le vrai moteur de tâches, sans changer le point
 * d'entrée ni le protocole.</p>
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
     * @param args arguments de ligne de commande (ignorés à cette étape ; un jour :
     *             {@code --engine-home &lt;répertoire&gt;}).
     */
    public static void main(String[] args) {
        PrintStream diagnostics = System.err;
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
}
