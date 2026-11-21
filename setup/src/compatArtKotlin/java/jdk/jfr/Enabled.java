package jdk.jfr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activation d'événement — annotation no-op pour ART (correctif v0.2.14, voir
 * {@link Event} pour le pourquoi du paquet) : la valeur par défaut {@code true}
 * mime l'API du JDK, aucun événement n'est activé pour autant (c'est
 * {@link Event#isEnabled()} qui décide, toujours faux).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Enabled {

    /** L'état d'activation déclaré (jamais consulté headless). */
    boolean value() default true;
}
