package demo.app

/**
 * Utilitaires Kotlin de l'exemple : gabarit de chaîne (stdlib) et expression
 * when — les dépendances Maven du module servent la compilation et l'exécution.
 */
object Outils {

    fun légende(salutation: String, nom: String): String = "${salutation}, ${nom} !"

    fun nombreDeMots(texte: String): Int = when (texte.isBlank()) {
        true -> 0
        else -> texte.trim().split(Regex("\\s+")).size
    }
}
