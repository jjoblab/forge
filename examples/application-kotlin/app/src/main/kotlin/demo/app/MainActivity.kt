package demo.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import demo.core.Salutations

/**
 * Activité principale de l'exemple Android KOTLIN : le R.java généré par aapt2
 * participe à la compilation jointe, le Java du module « core » est référencé
 * depuis le Kotlin, et une classe Java du même module rend la visite.
 */
class MainActivity : Activity() {

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        val message = Outils.légende(Salutations.app(), getString(R.string.app_name))
        val mots = Vérificateur.mots(message)
        val texte = TextView(this)
        texte.text = "$message ($mots mots)"
        setContentView(texte)
    }
}
