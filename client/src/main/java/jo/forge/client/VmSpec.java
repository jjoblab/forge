package jo.forge.client;

import java.util.List;

/**
 * Description d'une machine virtuelle enfant à lancer (moteur ou, plus tard, travailleuse).
 *
 * <p>Paramètres : classpath, classe principale, tas, arguments. Le lanceur
 * ({@link VmLauncher}) traduit cette description en commande concrète —
 * {@code app_process} sur appareil, {@code java} en développement et en test.</p>
 *
 * @param classpathEntries entrées de classpath (jars ou répertoires), dans l'ordre ;
 *                         séparées par le caractère de séparation de la plate-forme cible.
 * @param mainClass        classe principale qualifiée (ex.
 *                         {@code jo.forge.engine.bootstrap.EngineMain}).
 * @param heapMb           tas maximum de la VM en Mo (décision D-02 : 512 par défaut
 *                         pour le moteur, 384 pour une travailleuse, 256 en basse mémoire).
 * @param args             arguments transmis au programme (jamais au lanceur de VM).
 */
public record VmSpec(List<String> classpathEntries, String mainClass, int heapMb,
                     List<String> args) {

    /**
     * Valide la description.
     *
     * @throws IllegalArgumentException si le tas n'est pas positif ou si la classe
     *                                  principale est absente.
     */
    public VmSpec {
        classpathEntries = List.copyOf(classpathEntries);
        args = List.copyOf(args);
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("Classe principale manquante");
        }
        if (heapMb <= 0) {
            throw new IllegalArgumentException("Le tas doit être positif : " + heapMb);
        }
    }
}
