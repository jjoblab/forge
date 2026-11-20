package jo.forge.client;

import java.io.IOException;
import java.util.Map;

/**
 * Lance une machine virtuelle enfant selon la stratégie de la plate-forme.
 *
 * <p>Implémentations : {@link DeviceVmLauncher} ({@code app_process} sur appareil, avec
 * {@code CLASSPATH} en variable d'environnement) et {@link JavaVmLauncher} ({@code java}
 * en développement et en test). L'abstraction permet de tester le superviseur sur JVM
 * avec de vrais processus sans toucher au code appareil.</p>
 */
public interface VmLauncher {

    /**
     * Lance la VM décrite.
     *
     * @param spec description de la VM.
     * @return le processus enfant lancé.
     * @throws IOException si le lancement échoue (binaire absent, permissions).
     */
    Process start(VmSpec spec) throws IOException;

    /**
     * Variables d'environnement ajoutées par ce lanceur (utile au diagnostic et aux tests).
     *
     * @param spec description de la VM.
     * @return les variables et leurs valeurs.
     */
    Map<String, String> environmentFor(VmSpec spec);

    /**
     * Représentation lisible de la commande qui serait lancée (jamais exécutée).
     *
     * @param spec description de la VM.
     * @return la commande complète, pour le journal de diagnostic.
     */
    String describeCommand(VmSpec spec);
}
