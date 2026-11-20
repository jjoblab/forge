package jo.forge.client;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lanceur pour appareil : exécute {@code /system/bin/app_process} avec le classpath du
 * moteur dans la variable d'environnement {@code CLASSPATH}.
 *
 * <p>C'est le mécanisme par lequel le système lui-même démarre du code Java ; il donne
 * à la VM enfant son propre tas ({@code -Xmx}) et son propre destin — un écroulement du
 * moteur ne touche pas la coquille (décisions D-01 à D-03). Ce lanceur n'est jamais
 * exécuté dans les tests JVM : il est câblé par la coquille Android.</p>
 */
public final class DeviceVmLauncher implements VmLauncher {

    /** Chemin du lanceur de VM du système. */
    public static final String APP_PROCESS = "/system/bin/app_process";

    /** Variable de classpath attendue par {@code app_process}. */
    public static final String CLASSPATH_VAR = "CLASSPATH";

    /** Répertoire de commande conventionnel d'{@code app_process} (premier argument). */
    private static final String COMMAND_DIR = "/";

    @Override
    public Process start(VmSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(spec));
        builder.environment().putAll(environmentFor(spec));
        return builder.start();
    }

    @Override
    public Map<String, String> environmentFor(VmSpec spec) {
        // CLASSPATH pour app_process, puis les variables additionnelles de la VM
        // (ex. FORGE_ENGINE_DIR) : elles écrasent CLASSPATH si un appelant s'y risque,
        // ce que le contrat interdit — la classe principale vient de notre classpath.
        Map<String, String> env = new LinkedHashMap<>();
        env.put(CLASSPATH_VAR, String.join(":", spec.classpathEntries()));
        env.putAll(spec.env());
        if (!String.join(":", spec.classpathEntries())
                .equals(env.get(CLASSPATH_VAR))) {
            throw new IllegalStateException(
                    "La VM enfant tente d'écraser CLASSPATH : interdit");
        }
        return env;
    }

    @Override
    public String describeCommand(VmSpec spec) {
        return "CLASSPATH=" + environmentFor(spec).get(CLASSPATH_VAR) + " "
                + String.join(" ", buildCommand(spec));
    }

    /**
     * Construit la ligne de commande : {@code app_process -Xmx… / classe args…}.
     */
    private String[] buildCommand(VmSpec spec) {
        String[] command = new String[3 + 1 + spec.args().size()];
        command[0] = APP_PROCESS;
        command[1] = "-Xmx" + spec.heapMb() + "m";
        command[2] = COMMAND_DIR;
        command[3] = spec.mainClass();
        for (int i = 0; i < spec.args().size(); i++) {
            command[4 + i] = spec.args().get(i);
        }
        return command;
    }
}
