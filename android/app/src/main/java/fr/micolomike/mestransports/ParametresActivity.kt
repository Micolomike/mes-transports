package fr.micolomike.mestransports

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Paramètres : jeton PRIM, trajets (ajout, ordre, moment, suppression), widget, batterie. */
class ParametresActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private lateinit var champJeton: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contenu = Ui.colonne(this, 16)
        setContentView(ScrollView(this).apply {
            addView(contenu, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        champJeton = Ui.champ(this, "Jeton d'API PRIM").apply { setText(Stockage.jeton(this@ParametresActivity)) }
    }

    override fun onResume() {
        super.onResume()
        afficher()
    }

    private fun afficher() {
        contenu.removeAllViews()
        val entete = Ui.ligne(this)
        entete.addView(Ui.icone(this, "←") { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = Ui.dp(context, 12) }
        })
        entete.addView(Ui.extensible(Ui.texte(this, "Paramètres", 22f, gras = true)))
        contenu.addView(entete)

        // ---------------------------------------------------------------- trajets
        contenu.addView(Ui.titre(this, "Trajets"))
        contenu.addView(
            Ui.texte(
                this,
                "Chaque trajet affiche les prochains départs de la gare de départ qui desservent la gare d'arrivée. " +
                    "« Le matin » / « L'après-midi » : le trajet passe en tête de liste à ce moment de la journée.",
                13f, pale = true,
            )
        )
        val trajets = Stockage.trajets(this)
        trajets.forEachIndexed { i, t -> contenu.addView(carteTrajet(t, i, trajets.size)) }
        contenu.addView(Ui.bouton(this, "+ Ajouter un trajet") {
            startActivity(Intent(this, AjoutTrajetActivity::class.java))
        })

        // ---------------------------------------------------------------- jeton
        contenu.addView(Ui.titre(this, "Jeton PRIM"))
        contenu.addView(
            Ui.texte(this, "Portail PRIM d'Île-de-France Mobilités > Mon compte > Mon jeton d'API.", 13f, pale = true)
        )
        (champJeton.parent as? ViewGroup)?.removeView(champJeton)
        contenu.addView(champJeton)
        val boutons = Ui.ligne(this)
        boutons.addView(Ui.extensible(Ui.bouton(this, "Coller") {
            val presse = getSystemService(ClipboardManager::class.java)
            val texte = presse?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (texte.isNullOrBlank()) toast("Presse-papiers vide") else champJeton.setText(texte.trim())
        }).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 8) })
        boutons.addView(Ui.extensible(Ui.bouton(this, "Enregistrer") {
            val jeton = champJeton.text.toString().trim()
            if (jeton.isEmpty()) {
                toast("Saisis le jeton PRIM")
                return@bouton
            }
            Stockage.enregistrerJeton(this, jeton)
            toast("Jeton enregistré")
        }))
        contenu.addView(boutons)

        // ---------------------------------------------------------------- widget et batterie
        contenu.addView(Ui.titre(this, "Widget"))
        contenu.addView(
            Ui.texte(
                this,
                "Le widget se met à jour uniquement avec le bouton ↻ (quota PRIM). Étire-le pour voir plus de trajets ; " +
                    "la liste défile si elle dépasse.",
                13f, pale = true,
            )
        )
        contenu.addView(Ui.bouton(this, "Ajouter le widget à l'écran d'accueil") { epinglerWidget() })
        val pm = getSystemService(PowerManager::class.java)
        val exclu = pm?.isIgnoringBatteryOptimizations(packageName) == true
        contenu.addView(
            Ui.bouton(this, if (exclu) "Batterie : appli déjà exclue de l'optimisation ✓" else "Exclure l'appli de l'optimisation de batterie") {
                demanderExclusionBatterie()
            }.apply { isEnabled = !exclu }
        )
    }

    private fun carteTrajet(t: Trajet, i: Int, n: Int) = Ui.carte(this).apply {
        val haut = Ui.ligne(context)
        haut.addView(Ui.pastille(context, t.ligne, 30))
        haut.addView(Ui.extensible(Ui.colonne(context).apply {
            addView(Ui.texte(context, "${t.departNom} → ${t.arriveeNom}", 15f, gras = true))
            addView(Ui.texte(context, t.ligne.nom, 12f, pale = true))
        }))
        addView(haut)

        val bas = Ui.ligne(context)
        bas.addView(Ui.extensible(Ui.bouton(context, t.moment.libelle) {
            modifier { l -> l.map { if (it.id == t.id) it.copy(moment = it.moment.suivant()) else it } }
        }).apply { (layoutParams as LinearLayout.LayoutParams).height = Ui.dp(context, 44) })
        bas.addView(Ui.icone(context, "↑") {
            if (i > 0) modifier { l -> l.toMutableList().apply { add(i - 1, removeAt(i)) } }
        }.apply { isEnabled = i > 0 })
        bas.addView(Ui.icone(context, "↓") {
            if (i < n - 1) modifier { l -> l.toMutableList().apply { add(i + 1, removeAt(i)) } }
        }.apply { isEnabled = i < n - 1 })
        bas.addView(Ui.icone(context, "✕") {
            modifier { l -> l.filter { it.id != t.id } }
            toast("Trajet supprimé")
        })
        addView(bas)
    }

    private fun modifier(f: (List<Trajet>) -> List<Trajet>) {
        val liste = f(Stockage.trajets(this))
        Stockage.enregistrerTrajets(this, liste)
        // Le widget n'affiche plus les trajets supprimés ; les nouveaux arrivent au prochain ↻
        Stockage.etat(this)?.let { e ->
            val ids = liste.map { it.id }.toSet()
            Stockage.enregistrerEtat(this, e.copy(blocs = e.blocs.filter { it.trajetId in ids }))
        }
        TransportWidget.dessiner(this)
        afficher()
    }

    @SuppressLint("BatteryLife")
    private fun demanderExclusionBatterie() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun epinglerWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr != null && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, TransportWidget::class.java), null, null)
        } else {
            toast("Appui long sur l'écran d'accueil > Widgets > Mes transports")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
