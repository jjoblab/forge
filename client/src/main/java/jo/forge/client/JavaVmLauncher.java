package jo.forge.client;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lanceur de développement et de test : exécute l'exécutable {@code java} d'un JDK.
 *
 * <p>Utilisé par les tests d'intégration (le moteur factice tourne alors dans un vrai
 * processus séparé, exactement comme sur appareil) et comme repli de diagnostic. Le
 * binaire vient de {@code JAVA_HOME/bin/java} s'il existe, sinon du {@code PATH}.</p>
 */
public final class JavaVmLauncher implements VmLauncher {

    private final String javaBin;

    /**
     * Crée le lanceur en cherchant {@code java} dans {@code JAVA_HOME} puis le
     * {@code PATH}.
     */
    public JavaVmLauncher() {
        String home = System.getenv("JAVA_HOME");
        if (home != null && new File(home, "bin/java").canExecute()) {
            this.javaBin = new File(home, "bin/java").getAbsolutePath();
        } else {
            this.javaBin = "java";
        }
    }

    /**
     * Crée le lanceur avec un binaire {@code java} explicite (tests).
     *
     * @param javaBin chemin de l'exécutable.
     */
    public JavaVmLauncher(String javaBin) {
        this.javaBin = javaBin;
    }

    @Override
    public Process start(VmSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(spec));
        builder.environment().putAll(environmentFor(spec));
        return builder.start();
    }

    @Override
    public Map<String, String> environmentFor(VmSpec spec) {
        // Le classpath passe par la ligne de commande sous java, pas par l'environnement.
        return Map.of();
    }

    @Override
    public String describeCommand(VmSpec spec) {
        return String.join(" ", buildCommand(spec));
    }

    /**
     * Construit la ligne de commande : {@code java -Xmx… -cp … classe args…}.
     */
    private String[] buildCommand(VmSpec spec) {
        String[] command = new String[5 + spec.args().size()];
        command[0] = javaBin;
        command[1] = "-Xmx" + spec.heapMb() + "m";
        command[2] = "-cp";
        command[3] = String.join(File.pathSeparator, spec.classpathEntries());
        command[4] = spec.mainClass();
        for (int i = 0; i < spec.args().size(); i++) {
            command[5 + i] = spec.args().get(i);
        }
        return command;
    }
}
