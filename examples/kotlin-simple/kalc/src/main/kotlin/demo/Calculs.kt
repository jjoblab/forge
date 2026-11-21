package demo

/**
 * Utilitaires Kotlin de l'exemple : le module est déclaré "language": "kotlin",
 * ses sources .kt vivent dans src/main/kotlin (convention), le Java du module
 * cohabite dans src/main/java et compile CONTRE les classes Kotlin.
 */
object Calculs {

    fun doubler(x: Int): Int = x * 2

    fun saluer(nom: String): String = "Salut, $nom !"
}
