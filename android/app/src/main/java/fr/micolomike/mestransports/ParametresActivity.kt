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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
                    "« Le matin » / « L'après-midi » : le trajet passe en tête de liste à ce moment de la journée. " +
                    "Pour changer l'ordre, fais glisser un trajet avec la poignée ≡ (ou appui long sur la carte).",
                13f, pale = true,
            )
        )
        contenu.addView(listeTrajets(Stockage.trajets(this)))
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

    /** Liste des trajets réordonnable au doigt (glisser-déposer). */
    private fun listeTrajets(trajets: List<Trajet>): RecyclerView {
        val ordre = trajets.toMutableList()
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false // la page entière défile, pas la liste
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lateinit var glisser: ItemTouchHelper
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = ordre.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(Ui.colonne(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val boite = holder.itemView as LinearLayout
                boite.removeAllViews()
                boite.addView(carteTrajet(ordre[position]) { glisser.startDrag(holder) })
            }
        }
        glisser = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            private var deplace = false

            override fun onMove(rv: RecyclerView, de: RecyclerView.ViewHolder, vers: RecyclerView.ViewHolder): Boolean {
                val a = de.bindingAdapterPosition
                val b = vers.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                ordre.add(b, ordre.removeAt(a))
                rv.adapter?.notifyItemMoved(a, b)
                deplace = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.apply {
                    elevation = Ui.dp(context, 10).toFloat()
                    scaleX = 1.03f; scaleY = 1.03f
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.apply { elevation = 0f; scaleX = 1f; scaleY = 1f }
                if (deplace) {
                    deplace = false
                    enregistrerOrdre(ordre.toList())
                }
            }
        })
        glisser.attachToRecyclerView(rv)
        return rv
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun carteTrajet(t: Trajet, commencerGlisser: () -> Unit) = Ui.carte(this).apply {
        val haut = Ui.ligne(context)
        // Poignée : un simple appui lance le glisser-déposer
        haut.addView(Ui.texte(context, "≡", 26f, pale = true).apply {
            setPadding(0, 0, Ui.dp(context, 12), 0)
            contentDescription = "Déplacer le trajet"
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) commencerGlisser()
                false
            }
        })
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
        bas.addView(Ui.icone(context, "✕") {
            modifier { l -> l.filter { it.id != t.id } }
            toast("Trajet supprimé")
        })
        addView(bas)
    }

    /** Nouvel ordre après un glisser-déposer : enregistré sans redessiner la liste. */
    private fun enregistrerOrdre(liste: List<Trajet>) {
        Stockage.enregistrerTrajets(this, liste)
        TransportWidget.dessiner(this)
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
