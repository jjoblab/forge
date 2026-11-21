package org.jetbrains.kotlin.com.intellij.util.containers;

import java.lang.reflect.Field;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * OMBRE Forge du conteneur {@code Unsafe} du compilateur Kotlin (kotlin-compiler-embeddable
 * 2.0.21) — même paquet, même nom, même API publique EXACTE (les dix méthodes statiques,
 * vérifiées par balayage de TOUS les sites d'appel du jar), mais qui SURVIT À ART.
 *
 * <p><b>Le problème</b> (rapport appareil v0.2.12, moto g06, Android 15) : la classe
 * originale résout AVIDEMENT dix handles de méthodes de {@code sun.misc.Unsafe} dans son
 * initialiseur et enveloppe tout échec dans {@code java.lang.Error} — or
 * {@code copyMemory(Object, long, Object, long, long)} est une API cachée sur ART :
 * le chargeur la voit inexistante, l'initialiseur meurt, et avec lui
 * {@code ConcurrentLongObjectHashMap} → {@code CoreProgressManager} → tout
 * {@code KotlinCoreEnvironment}. Les neuf autres méthodes (compareAndSwap*,
 * get/putObjectVolatile, getAndAddInt, objectFieldOffset, arrayBaseOffset/IndexScale)
 * RÉSOLVENT sur l'appareil — l'échec du rapport porte sur la dernière recherche, preuve
 * que les précédentes ont passé.</p>
 *
 * <p><b>La solution</b> (motif {@code UnsafeFindTolerantPass} du moteur de référence,
 * REF-135/REF-137 — même diagnostic, même remède) : chaque recherche devient TOLÉRANTE
 * (un handle introuvable vaut {@code null}, jamais un {@code Error}), et les méthodes
 * publiques se replient honnêtement quand leur handle manque — {@code getAndAddInt}
 * par une boucle CAS sur {@code getIntVolatile}/{@code compareAndSwapInt} (le repli
 * {@code VarHandleCompat} de la référence, pour le cas où l'API cachée
 * {@code max-target-r} serait refusée), {@code copyMemory} par {@code System.arraycopy}
 * pour des tableaux de même type. Seul appelant réel de {@code copyMemory} dans tout le
 * jar : {@code ByteBufferUtil}, couche d'E/S jamais sur le chemin d'une compilation par
 * lots.</p>
 *
 * <p><b>Pourquoi une ombre plutôt qu'une passe bytecode sur le composant</b> : la
 * référence patche le jar du compilateur par instrumentation ASM pendant sa construction
 * Android (REF-135) — mais notre compilateur est un COMPOSANT TÉLÉCHARGÉ dont le contenu
 * doit rester stable (économie de données v0.2.9 : 19 Mo de cache appareil préservés,
 * seul le cœur ~116 Ko re-descend). L'original du moteur de référence résolvait ces
 * classes exactement ainsi — des copies sources patchées shadowées sur le classpath —
 * le fork s'en est écarté uniquement pour réduire la maintenance ; nous assumons la
 * charge pour UNE classe. La shadowing s'appuie sur l'ordre du classpath moteur :
 * {@code engine.jar} (qui porte cette ombre) PRÉCÈDE {@code lib/kotlin-compiler.jar}
 * (convention {@code EngineSession.specFor}) — le chargeur d'ART résout la première
 * définition.</p>
 *
 * <p><b>Contrat de maintenance</b> : l'API publique est épinglée à la forme utilisée
 * par kotlin-compiler-embeddable 2.0.21 (dix descripteurs exacts, aucun de plus dans
 * le jar). Toute montée de version du compilateur doit re-balayer les sites d'appel
 * (javap, cible {@code containers/Unsafe.}) et ré-auditer cette ombre — le champ
 * {@link #OMBRE_FORGE} permet au moteur de VÉRIFIER sur ART que c'est bien l'ombre qui
 * est chargée (garde franche dans {@code KotlinCompiler.compiler}, échec nommé sinon).
 * Aucune référence de COMPILATION à {@code sun.misc} : tout par réflexion, le dexage
 * reste propre.</p>
 */
public final class Unsafe {

    /**
     * Marqueur d'ombre Forge : absent de la classe originale, lu par le moteur sur ART
     * pour prouver que c'est cette classe (tolérante) que le chargeur a retenue.
     */
    public static final String OMBRE_FORGE = "0.2.13";

    private static final MethodHandle putObjectVolatile =
            trouver("putObjectVolatile", void.class, Object.class, long.class, Object.class);

    private static final MethodHandle getObjectVolatile =
            trouver("getObjectVolatile", Object.class, Object.class, long.class);

    private static final MethodHandle compareAndSwapObject = trouver("compareAndSwapObject",
            boolean.class, Object.class, long.class, Object.class, Object.class);

    private static final MethodHandle compareAndSwapInt = trouver("compareAndSwapInt",
            boolean.class, Object.class, long.class, int.class, int.class);

    private static final MethodHandle compareAndSwapLong = trouver("compareAndSwapLong",
            boolean.class, Object.class, long.class, long.class, long.class);

    private static final MethodHandle getAndAddInt =
            trouver("getAndAddInt", int.class, Object.class, long.class, int.class);

    private static final MethodHandle getIntVolatile =
            trouver("getIntVolatile", int.class, Object.class, long.class);

    private static final MethodHandle objectFieldOffset =
            trouver("objectFieldOffset", long.class, Field.class);

    private static final MethodHandle arrayIndexScale =
            trouver("arrayIndexScale", int.class, Class.class);

    private static final MethodHandle arrayBaseOffset =
            trouver("arrayBaseOffset", int.class, Class.class);

    private static final MethodHandle copyMemory = trouver("copyMemory", void.class,
            Object.class, long.class, Object.class, long.class, long.class);

    private Unsafe() {
    }

    /**
     * Le {@code sun.misc.Unsafe} de la plate-forme, par réflexion pure (aucune référence
     * de compilation : le dexage ne voit jamais {@code sun.*}).
     *
     * @return l'instance, ou {@code null} si le runtime ne l'expose pas.
     */
    private static Object sunUnsafe() {
        try {
            Class<?> classe = Class.forName("sun.misc.Unsafe");
            Field champ = classe.getDeclaredField("theUnsafe");
            champ.setAccessible(true);
            return champ.get(null);
        } catch (Throwable absent) {
            return null;
        }
    }

    /**
     * Recherche TOLÉRANTE d'un handle : introuvable (API cachée d'ART, runtime
     * restreint) vaut {@code null} — jamais un {@code java.lang.Error} comme la classe
     * originale, dont l'initialiseur avide tuait tout le compilateur pour UNE méthode
     * que rien sur le chemin de compilation n'appelle.
     */
    private static MethodHandle trouver(String nom, Class<?> retour, Class<?>... paramètres) {
        try {
            Object unsafe = sunUnsafe();
            if (unsafe == null) {
                return null;
            }
            return MethodHandles.publicLookup()
                    .findVirtual(unsafe.getClass(), nom,
                            MethodType.methodType(retour, paramètres))
                    .bindTo(unsafe);
        } catch (Throwable indisponible) {
            return null;
        }
    }

    /**
     * Lecture volatile d'un champ objet.
     */
    public static Object getObjectVolatile(Object objet, long décalage) {
        if (getObjectVolatile == null) {
            throw indisponible("getObjectVolatile");
        }
        try {
            return getObjectVolatile.invokeExact(objet, décalage);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Écriture volatile d'un champ objet.
     */
    public static void putObjectVolatile(Object objet, long décalage, Object valeur) {
        if (putObjectVolatile == null) {
            throw indisponible("putObjectVolatile");
        }
        try {
            putObjectVolatile.invokeExact(objet, décalage, valeur);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Comparer-et-échanger un champ objet.
     */
    public static boolean compareAndSwapObject(
            Object objet, long décalage, Object attendu, Object nouveau) {
        if (compareAndSwapObject == null) {
            throw indisponible("compareAndSwapObject");
        }
        try {
            return (boolean) compareAndSwapObject.invokeExact(objet, décalage, attendu, nouveau);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Comparer-et-échanger un champ int.
     */
    public static boolean compareAndSwapInt(
            Object objet, long décalage, int attendu, int nouveau) {
        if (compareAndSwapInt == null) {
            throw indisponible("compareAndSwapInt");
        }
        try {
            return (boolean) compareAndSwapInt.invokeExact(objet, décalage, attendu, nouveau);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Comparer-et-échanger un champ long.
     */
    public static boolean compareAndSwapLong(
            Object objet, long décalage, long attendu, long nouveau) {
        if (compareAndSwapLong == null) {
            throw indisponible("compareAndSwapLong");
        }
        try {
            return (boolean) compareAndSwapLong.invokeExact(objet, décalage, attendu, nouveau);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Ajout atomique à un champ int. Le handle natif manque sur certains runtimes
     * ({@code max-target-r} : API cachée refusée selon le {@code targetSdk}) — le
     * repli est une boucle CAS sur {@code getIntVolatile} et
     * {@code compareAndSwapInt}, eux toujours autorisés (motif
     * {@code VarHandleCompat} du moteur de référence) ; le déverrouillage lecture
     * des bins d'arbres des maps concurrentes en dépend, il doit rester correct.
     */
    public static int getAndAddInt(Object objet, long décalage, int delta) {
        if (getAndAddInt != null) {
            try {
                return (int) getAndAddInt.invokeExact(objet, décalage, delta);
            } catch (Throwable échec) {
                throw new RuntimeException(échec);
            }
        }
        return getAndAddIntParBoucleCas(objet, décalage, delta);
    }

    /**
     * Boucle CAS d'ajout atomique (repli) : relit volatile, tente l'échange,
     * réessaie tant qu'un autre fil a gagné.
     */
    static int getAndAddIntParBoucleCas(Object objet, long décalage, int delta) {
        if (getIntVolatile == null || compareAndSwapInt == null) {
            throw indisponible("getAndAddInt");
        }
        while (true) {
            int actuel;
            try {
                actuel = (int) getIntVolatile.invokeExact(objet, décalage);
            } catch (Throwable échec) {
                throw new RuntimeException(échec);
            }
            int suivant = actuel + delta;
            try {
                if ((boolean) compareAndSwapInt.invokeExact(objet, décalage, actuel, suivant)) {
                    return actuel;
                }
            } catch (Throwable échec) {
                throw new RuntimeException(échec);
            }
        }
    }

    /**
     * Décalage mémoire d'un champ (pour les opérations ci-dessus).
     */
    public static long objectFieldOffset(Field champ) {
        if (objectFieldOffset == null) {
            throw indisponible("objectFieldOffset");
        }
        try {
            return (long) objectFieldOffset.invokeExact(champ);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Taille d'élément d'un type de tableau.
     */
    public static int arrayIndexScale(Class<?> type) {
        if (arrayIndexScale == null) {
            throw indisponible("arrayIndexScale");
        }
        try {
            return (int) arrayIndexScale.invokeExact(type);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Décalage de base d'un type de tableau.
     */
    public static int arrayBaseOffset(Class<?> type) {
        if (arrayBaseOffset == null) {
            throw indisponible("arrayBaseOffset");
        }
        try {
            return (int) arrayBaseOffset.invokeExact(type);
        } catch (Throwable échec) {
            throw new RuntimeException(échec);
        }
    }

    /**
     * Copie mémoire brute. Le handle manque sur ART (API cachée — le crash du rapport
     * appareil v0.2.12) : repli par {@code System.arraycopy} pour des tableaux de MÊME
     * type, décalages reconvertis en indices par les accès publiés ci-dessus ; sinon
     * échec franc — le seul appelant du jar ({@code ByteBufferUtil}, couche d'E/S)
     * n'est jamais sur le chemin d'une compilation par lots.
     */
    public static void copyMemory(
            Object source, long décalageSource, Object destination, long décalageDestination,
            long octets) {
        if (copyMemory != null) {
            try {
                copyMemory.invokeExact(source, décalageSource, destination,
                        décalageDestination, octets);
                return;
            } catch (Throwable échec) {
                throw new RuntimeException(échec);
            }
        }
        copieMémoireParTableaux(source, décalageSource, destination, décalageDestination, octets);
    }

    /**
     * Repli tableau de {@link #copyMemory} : indices retrouvés par base et échelle du
     * type, bornes vérifiées — honnête même pour un appel inattendu.
     */
    static void copieMémoireParTableaux(
            Object source, long décalageSource, Object destination, long décalageDestination,
            long octets) {
        if (source != null && destination != null && source.getClass().isArray()
                && destination.getClass() == source.getClass()) {
            int échelle = arrayIndexScale(source.getClass());
            if (échelle > 0) {
                long base = arrayBaseOffset(source.getClass());
                long indexSource = (décalageSource - base) / échelle;
                long indexDestination = (décalageDestination - base) / échelle;
                long éléments = octets / échelle;
                int longueurSource = java.lang.reflect.Array.getLength(source);
                int longueurDestination = java.lang.reflect.Array.getLength(destination);
                if (indexSource >= 0 && indexDestination >= 0 && éléments >= 0
                        && indexSource + éléments <= longueurSource
                        && indexDestination + éléments <= longueurDestination) {
                    System.arraycopy(source, (int) indexSource, destination,
                            (int) indexDestination, (int) éléments);
                    return;
                }
            }
        }
        throw indisponible("copyMemory");
    }

    /** Message franc d'une primitive indisponible sur ce runtime. */
    private static UnsupportedOperationException indisponible(String nom) {
        return new UnsupportedOperationException("sun.misc.Unsafe." + nom
                + " n'est pas accessible sur ce runtime (API cachée ou absente)"
                + " — primitive du conteneur Unsafe indisponible");
    }
}
