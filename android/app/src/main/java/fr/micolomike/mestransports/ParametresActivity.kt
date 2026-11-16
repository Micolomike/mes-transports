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
import android.view.HapticFeedbackConstants
import android.view.WindowInsets

/** Paramètres : jeton PRIM, trajets (ajout, ordre, moment, suppression), widget, batterie. */
class ParametresActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private lateinit var defilement: ScrollView
    private lateinit var champJeton: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contenu = Ui.colonne(this, 16)
        defilement = ScrollView(this).apply {
            addView(contenu, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Android 15 dessine sous les barres système : marge pour atteindre le bas de la page
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                val (h, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    i.top to i.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                v.setPadding(0, h, 0, b)
                insets
            }
        }
        setContentView(defilement)
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
                    "Pour changer l'ordre, fais glisser un trajet avec la poignée ≡ .",
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

    /**
     * Liste des trajets réordonnable au doigt. Simple colonne (pas de RecyclerView, qui se mesurait
     * mal dans la page défilante et coupait le bas de la liste) ; la page défile toute seule
     * quand on amène un trajet près du haut ou du bas de l'écran.
     */
    private fun listeTrajets(trajets: List<Trajet>): LinearLayout {
        val boite = Ui.colonne(this)
        val ordre = trajets.toMutableList()
        trajets.forEach { t -> boite.addView(carteTrajet(t) { carte, e -> glisser(boite, ordre, carte, e) }) }
        return boite
    }

    // État du glisser-déposer en cours
    private var carteGlissee: View? = null
    private var ancrage = 0f
    private var dernierY = 0f
    private val defilerAuto = object : Runnable {
        override fun run() {
            val carte = carteGlissee ?: return
            val loc = IntArray(2).also { defilement.getLocationOnScreen(it) }
            val zone = Ui.dp(this@ParametresActivity, 80)
            val haut = loc[1] + defilement.paddingTop
            val bas = loc[1] + defilement.height - defilement.paddingBottom
            val pas = when {
                dernierY < haut + zone -> -Ui.dp(this@ParametresActivity, 12)
                dernierY > bas - zone -> Ui.dp(this@ParametresActivity, 12)
                else -> 0
            }
            if (pas != 0 && defilement.canScrollVertically(pas)) {
                defilement.scrollBy(0, pas)
                (carte.parent as? LinearLayout)?.let { placer(it, carte) }
            }
            defilement.postOnAnimation(this)
        }
    }

    private fun glisser(boite: LinearLayout, ordre: MutableList<Trajet>, carte: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                carteGlissee = carte
                dernierY = e.rawY
                ancrage = e.rawY + defilement.scrollY - carte.top
                carte.elevation = Ui.dp(this, 10).toFloat()
                carte.scaleX = 1.03f; carte.scaleY = 1.03f
                carte.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                defilement.requestDisallowInterceptTouchEvent(true)
                defilement.postOnAnimation(defilerAuto)
            }
            MotionEvent.ACTION_MOVE -> {
                dernierY = e.rawY
                placer(boite, carte)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                carteGlissee = null
                defilement.removeCallbacks(defilerAuto)
                carte.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(150)
                    .withEndAction { carte.elevation = 0f }.start()
                // L'ordre des cartes à l'écran fait foi
                val ids = (0 until boite.childCount).map { boite.getChildAt(it).tag as String }
                if (ids != ordre.map { it.id }) {
                    val parId = ordre.associateBy { it.id }
                    ordre.clear()
                    ids.mapNotNullTo(ordre) { parId[it] }
                    enregistrerOrdre(ordre.toList())
                }
            }
        }
        return true
    }

    /** Place la carte sous le doigt et l'échange avec sa voisine quand elle la dépasse de moitié. */
    private fun placer(boite: LinearLayout, carte: View): Boolean {
        val voulu = dernierY + defilement.scrollY - ancrage
        var echange = false
        var haut = carte.top.toFloat()
        val i = boite.indexOfChild(carte)
        val milieu = voulu + carte.height / 2f
        if (i > 0) {
            val avant = boite.getChildAt(i - 1)
            if (milieu < avant.top + avant.height / 2f) {
                // On déplace la voisine (jamais la carte tenue, sinon Android annule le geste)
                val nouveauHaut = avant.top.toFloat()
                boite.removeViewAt(i - 1)
                boite.addView(avant, i)
                haut = nouveauHaut
                echange = true
            }
        }
        if (!echange && i < boite.childCount - 1) {
            val apres = boite.getChildAt(i + 1)
            if (milieu > apres.top + apres.height / 2f) {
                val nouveauHaut = (apres.bottom - carte.height).toFloat()
                boite.removeViewAt(i + 1)
                boite.addView(apres, i)
                haut = nouveauHaut
                echange = true
            }
        }
        carte.translationY = voulu - haut
        if (echange) carte.post { carte.translationY = dernierY + defilement.scrollY - ancrage - carte.top }
        return echange
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun carteTrajet(t: Trajet, poignee: (View, MotionEvent) -> Boolean): LinearLayout = Ui.carte(this).apply {
        tag = t.id
        val carte = this
        val haut = Ui.ligne(context)
        // Poignée : un simple appui lance le glisser-déposer
        haut.addView(Ui.texte(context, "≡", 26f, pale = true).apply {
            contentDescription = "Déplacer le trajet"
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
            setOnTouchListener { _, e -> poignee(carte, e) }
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
