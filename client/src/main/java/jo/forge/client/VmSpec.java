package jo.forge.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Description d'une machine virtuelle enfant à lancer (moteur ou, plus tard, travailleuse).
 *
 * <p>Paramètres : classpath, classe principale, tas, arguments, variables
 * d'environnement. Le lanceur ({@link VmLauncher}) traduit cette description en
 * commande concrète — {@code app_process} sur appareil, {@code java} en développement
 * et en test.</p>
 *
 * @param classpathEntries entrées de classpath (jars ou répertoires), dans l'ordre ;
 *                         séparées par le caractère de séparation de la plate-forme cible.
 * @param mainClass        classe principale qualifiée (ex.
 *                         {@code jo.forge.engine.bootstrap.EngineMain}).
 * @param heapMb           tas maximum de la VM en Mo (décision D-02 : 512 par défaut
 *                         pour le moteur, 384 pour une travailleuse, 256 en basse mémoire).
 * @param args             arguments transmis au programme (jamais au lanceur de VM).
 * @param env              variables d'environnement AJOUTÉES à celles du lanceur
 *                         (jamais de secret : la coquille y passe des chemins, jamais
 *                         d'identifiants — le journal de diagnostic les affiche).
 */
public record VmSpec(List<String> classpathEntries, String mainClass, int heapMb,
                     List<String> args, Map<String, String> env) {

    /**
     * Compatibilité : VM sans variables d'environnement additionnelles.
     *
     * @param classpathEntries entrées de classpath, dans l'ordre.
     * @param mainClass        classe principale qualifiée.
     * @param heapMb           tas maximum en Mo.
     * @param args             arguments transmis au programme.
     */
    public VmSpec(List<String> classpathEntries, String mainClass, int heapMb,
                  List<String> args) {
        this(classpathEntries, mainClass, heapMb, args, Map.of());
    }

    /**
     * Valide la description.
     *
     * @throws IllegalArgumentException si le tas n'est pas positif, si la classe
     *                                  principale est absente ou si l'environnement
     *                                  contient une valeur nulle.
     */
    public VmSpec {
        classpathEntries = List.copyOf(classpathEntries);
        args = List.copyOf(args);
        env = copierEnv(env);
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("Classe principale manquante");
        }
        if (heapMb <= 0) {
            throw new IllegalArgumentException("Le tas doit être positif : " + heapMb);
        }
    }

    /**
     * Copie l'environnement en préservant l'ordre d'insertion (journal déterministe) ;
     * refus net d'une valeur nulle (un {@code Map.of} désordonné ou lacunaire
     * serait silencieusement trompeur pour le moteur).
     */
    private static Map<String, String> copierEnv(Map<String, String> env) {
        if (env == null) {
            return Map.of();
        }
        Map<String, String> copie = new LinkedHashMap<>();
        for (Map.Entry<String, String> entree : env.entrySet()) {
            if (entree.getValue() == null) {
                throw new IllegalArgumentException(
                        "Valeur nulle dans l'environnement de la VM : " + entree.getKey());
            }
            copie.put(entree.getKey(), entree.getValue());
        }
        return java.util.Collections.unmodifiableMap(copie);
    }
}
