package org.jetbrains.kotlin.com.intellij.util.containers;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'ombre Forge du conteneur {@code Unsafe} du compilateur Kotlin — le
 * correctif v0.2.13 du crash appareil
 * {@code NoSuchMethodException: copyMemory} (rapport v0.2.12 : l'initialiseur avide
 * de la classe originale enveloppait la recherche de l'API cachée
 * {@code sun.misc.Unsafe.copyMemory} dans un {@code java.lang.Error} qui tuait
 * {@code CoreProgressManager} et tout le compilateur).
 *
 * <p>La classe testée est celle que le moteur charge DÉSORMAIS (première du
 * classpath) : les suites Kotlin existantes ({@code KotlinPipelineTest},
 * {@code SimulationArtTest}) compilent donc AUSSI à travers elle — la présente
 * classe couvre ce qu'elles ne peuvent pas : le repli CAS, le repli tableau de
 * {@code copyMemory}, la tolérance de recherche et la preuve d'ordre.</p>
 */
class UnsafeOmbreTest {

    /** Champ cible (INSTANCE : sun.misc.Unsafe.objectFieldOffset refuse les
     * champs statiques sur les JDK modernes — les appelants réels du jar
     * n'utilisent que des champs d'instance). */
    private volatile int compteur;

    private volatile Object témoin;

    /** Décalage du champ compteur, par la primitive elle-même. */
    private long décalageCompteur() throws Exception {
        Field champ = UnsafeOmbreTest.class.getDeclaredField("compteur");
        return Unsafe.objectFieldOffset(champ);
    }

    @Test
    void lOmbreEstBienLaClasseChargée() throws Exception {
        // La classe résolue par NOM est la nôtre (marqueur absent de l'originale) :
        // c'est la preuve que l'ombre précède le jar du compilateur sur le classpath.
        Class<?> chargée = Class.forName(
                "org.jetbrains.kotlin.com.intellij.util.containers.Unsafe");
        assertSame(Unsafe.class, chargée,
                "l'ombre Forge doit être la définition retenue sur le classpath de test");
        assertEquals("0.2.13", Unsafe.OMBRE_FORGE);
    }

    @Test
    void comparaisonEtÉchangeIntFonctionne() throws Exception {
        compteur = 41;
        long décalage = décalageCompteur();
        assertTrue(Unsafe.compareAndSwapInt(this, décalage, 41, 42),
                "l'échange depuis la valeur attendue réussit");
        assertEquals(42, compteur);
        assertFalse(Unsafe.compareAndSwapInt(this, décalage, 41, 0),
                "l'échange depuis une valeur périmée échoue");
        assertEquals(42, compteur);
    }

    @Test
    void lectureEtÉcritureVolatilesFonctionnent() throws Exception {
        long décalage = Unsafe.objectFieldOffset(
                UnsafeOmbreTest.class.getDeclaredField("témoin"));
        Unsafe.putObjectVolatile(this, décalage, "a");
        assertEquals("a", Unsafe.getObjectVolatile(this, décalage));
    }

    @Test
    void ajoutAtomiqueParHandleEtParBoucleCasSAccordent() throws Exception {
        long décalage = décalageCompteur();
        compteur = 10;
        assertEquals(10, Unsafe.getAndAddInt(this, décalage, 5),
                "l'ajout atomique retourne l'ANCIENNE valeur");
        assertEquals(15, compteur);

        // Le repli CAS (chemin ART quand le handle natif est caché) doit s'accorder.
        compteur = 100;
        assertEquals(100, Unsafe.getAndAddIntParBoucleCas(this, décalage, 7));
        assertEquals(107, compteur);
    }

    @Test
    void boucleCasSurvitÀUneConcurrenceRéelle() throws Exception {
        // Huit fils, mille incréments chacun : la boucle CAS ne doit rien perdre.
        int fils = 8;
        int parFil = 1000;
        compteur = 0;
        long décalage = décalageCompteur();
        CountDownLatch départ = new CountDownLatch(1);
        AtomicInteger échecs = new AtomicInteger();
        Thread[] pile = new Thread[fils];
        for (int i = 0; i < fils; i++) {
            pile[i] = new Thread(() -> {
                try {
                    départ.await();
                    for (int j = 0; j < parFil; j++) {
                        Unsafe.getAndAddIntParBoucleCas(this, décalage, 1);
                    }
                } catch (Exception échec) {
                    échecs.incrementAndGet();
                }
            });
            pile[i].start();
        }
        départ.countDown();
        for (Thread fil : pile) {
            fil.join(10_000);
        }
        assertEquals(0, échecs.get());
        assertEquals(fils * parFil, compteur,
                "aucun incrément perdu : la boucle CAS est atomique");
    }

    @Test
    void décalagesDeTableauxSontCohérents() {
        int base = Unsafe.arrayBaseOffset(byte[].class);
        int échelle = Unsafe.arrayIndexScale(byte[].class);
        assertTrue(base >= 0);
        assertEquals(1, échelle, "l'échelle d'un byte[] vaut un octet");
        assertNotEquals(1, Unsafe.arrayIndexScale(long[].class),
                "l'échelle d'un long[] dépasse un octet");
    }

    @Test
    void repliTableauDeCopieMémoireCopieExactement() {
        // Le chemin ART : le handle copyMemory vaut null, le repli par System.arraycopy.
        byte[] source = "Forge sur ART".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] destination = new byte[source.length + 4];
        long base = Unsafe.arrayBaseOffset(byte[].class);
        Unsafe.copieMémoireParTableaux(source, base + 0, destination, base + 2,
                source.length);
        for (int i = 0; i < source.length; i++) {
            assertEquals(source[i], destination[i + 2],
                    "l'octet " + i + " doit atterrir à l'indice décalé");
        }
        assertEquals(0, destination[0]);
        assertEquals(0, destination[1]);
    }

    @Test
    void repliTableauRefuseLesHorsBornesEtLesTypesDissemblables() {
        byte[] source = new byte[4];
        long base = Unsafe.arrayBaseOffset(byte[].class);
        assertThrows(UnsupportedOperationException.class,
                () -> Unsafe.copieMémoireParTableaux(source, base + 0,
                        new byte[2], base + 0, 4),
                "copier 4 octets dans un tableau de 2 doit être refusé franchement");
        assertThrows(UnsupportedOperationException.class,
                () -> Unsafe.copieMémoireParTableaux(source, base + 0,
                        new int[4], base + 0, 4),
                "des types dissemblables ne peuvent pas être copiés par tableau");
    }

    @Test
    void messageIndisponibleEstFrancEtNommé() {
        UnsupportedOperationException erreur = assertThrows(
                UnsupportedOperationException.class,
                () -> Unsafe.copieMémoireParTableaux(null, 0, null, 0, 0));
        assertTrue(erreur.getMessage().contains("copyMemory"),
                "le message nomme la primitive manquante : " + erreur.getMessage());
        assertTrue(erreur.getMessage().contains("API cachée"),
                "le message dit la cause : " + erreur.getMessage());
    }

    @Test
    void rechercheToléranteRenvoieNullSansÉchecPourUneMéthodeInexistante() throws Exception {
        // Par réflexion sur la méthode privée : une recherche impossIBLE (nom absent
        // de sun.misc.Unsafe sur tout runtime) ne lève PAS — c'est le cœur du
        // correctif (l'originale enveloppait en java.lang.Error).
        java.lang.reflect.Method trouver = Unsafe.class.getDeclaredMethod(
                "trouver", String.class, Class.class, Class[].class);
        trouver.setAccessible(true);
        Object handle = trouver.invoke(null, "méthodeInexistanteForge", int.class,
                new Class<?>[] {Object.class, long.class});
        assertNull(handle, "une méthode introuvable vaut null, jamais une exception");
    }
}
