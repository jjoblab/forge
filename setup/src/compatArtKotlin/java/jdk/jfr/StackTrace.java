package jdk.jfr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pile d'appel d'événement — annotation no-op pour ART (correctif v0.2.14,
 * voir {@link Event} pour le pourquoi du paquet).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface StackTrace {

    /** L'état de capture de pile déclaré (jamais consulté headless). */
    boolean value() default true;
}
