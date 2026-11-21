package jdk.jfr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Catégorie d'événement — annotation no-op pour ART (correctif v0.2.14, voir
 * {@link Event} pour le pourquoi du paquet).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Category {

    /** Les noms de catégories (jamais lus headless). */
    String[] value();
}
