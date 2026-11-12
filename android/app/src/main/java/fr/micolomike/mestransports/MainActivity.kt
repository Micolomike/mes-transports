package fr.micolomike.mestransports

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Écran principal : prochains départs détaillés de chaque trajet et infos trafic complètes. */
class MainActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private var enCours = false

    private val ecoute = SharedPreferences.OnSharedPreferenceChangeListener { _, cle ->
        if (cle == Stockage.CLE_ETAT || cle == Stockage.CLE_ERREUR) runOnUiThread {
            enCours = false
            afficher()
        }
    }
    private val formatMaj = DateTimeFormatter.ofPattern("HH'h'mm").withZone(Prim.PARIS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contenu = Ui.colonne(this, 16)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(contenu, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        Planif.programmer(this)
    }

    override fun onResume() {
        super.onResume()
        Stockage.prefs(this).registerOnSharedPreferenceChangeListener(ecoute)
        afficher()
    }

    override fun onPause() {
        Stockage.prefs(this).unregisterOnSharedPreferenceChangeListener(ecoute)
        super.onPause()
    }

    private fun actualiser() {
        if (Stockage.jeton(this).isEmpty()) {
            startActivity(Intent(this, ParametresActivity::class.java))
            return
        }
        enCours = true
        afficher()
        TransportWidget.dessiner(this, enCours = true)
        Planif.maintenant(this)
    }

    private fun afficher() {
        contenu.removeAllViews()
        val etat = Stockage.etat(this)
        val erreur = Stockage.erreur(this)

        // En-tête : point d'état, titre, actualiser, paramètres
        val entete = Ui.ligne(this)
        val niveau = if (etat == null || erreur.isNotEmpty()) Niveau.GRIS else etat.niveau
        entete.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.point)?.mutate()
                ?.also { it.setTint(TransportWidget.couleur(niveau)) }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 12), Ui.dp(context, 12))
                .apply { marginEnd = Ui.dp(context, 10) }
        })
        entete.addView(Ui.extensible(Ui.texte(this, "Mes transports", 22f, gras = true)))
        entete.addView(Ui.icone(this, "↻") { actualiser() })
        entete.addView(Ui.icone(this, "⚙") { startActivity(Intent(this, ParametresActivity::class.java)) })
        contenu.addView(entete)

        val statut = when {
            enCours -> "Mise à jour…"
            etat != null -> "Mise à jour à " + formatMaj.format(Instant.ofEpochMilli(etat.maj))
            else -> ""
        }
        if (statut.isNotEmpty()) contenu.addView(Ui.texte(this, statut, 13f, pale = true))
        if (erreur.isNotEmpty()) contenu.addView(Ui.texte(this, "⚠ $erreur", 14f).apply { setTextColor(TransportWidget.ORANGE) })

        if (Stockage.jeton(this).isEmpty()) {
            contenu.addView(Ui.carte(this).apply {
                addView(Ui.texte(this@MainActivity, "Bienvenue", 17f, gras = true))
                addView(Ui.texte(this@MainActivity, "Saisis ton jeton PRIM dans les paramètres, puis touche ↻.", 14f, pale = true))
                addView(Ui.bouton(this@MainActivity, "Ouvrir les paramètres") {
                    startActivity(Intent(this@MainActivity, ParametresActivity::class.java))
                })
            })
            return
        }

        if (etat == null) {
            contenu.addView(Ui.texte(this, "\nAucune donnée : touche ↻ pour charger les départs.", 15f, pale = true))
            return
        }

        contenu.addView(Ui.texte(this, etat.resume, 15f).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })

        // Trajets
        val trajets = Stockage.trajets(this)
        val ids = trajets.map { it.id }.toSet()
        for (b in Prim.ordonner(etat.blocs.filter { it.trajetId in ids }, trajets)) contenu.addView(carteTrajet(b))

        // Infos trafic
        if (etat.incidents.isNotEmpty()) {
            contenu.addView(Ui.titre(this, "Infos trafic"))
            for (i in etat.incidents) contenu.addView(carteIncident(i))
        }

        contenu.addView(
            Ui.texte(
                this,
                "\n~ : horaire prévu, absent du temps réel. Retard et suppression inconnus pour ce train.",
                12f, pale = true,
            )
        )
    }

    private fun carteTrajet(b: Bloc) = Ui.carte(this).apply {
        val haut = Ui.ligne(context)
        haut.addView(Ui.pastille(context, b.ligne, 32))
        haut.addView(Ui.extensible(Ui.colonne(context).apply {
            addView(Ui.texte(context, b.libelle, 16f, gras = true))
            addView(Ui.texte(context, b.ligne.nom, 12f, pale = true))
        }))
        addView(haut)

        if (b.departs.isEmpty()) {
            addView(Ui.texte(context, b.erreur.ifEmpty { "Aucun départ annoncé" }, 14f, pale = true)
                .apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
            return@apply
        }
        for (d in b.departs) addView(ligneDepart(d))
    }

    private fun ligneDepart(d: Depart) = Ui.ligne(this).apply {
        setPadding(0, Ui.dp(context, 10), 0, 0)
        addView(Ui.texte(context, TransportWidget.heure(d), 17f).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 72), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(Ui.extensible(Ui.colonne(context).apply {
            addView(Ui.texte(context, Trajet.raccourci(d.destination).ifEmpty { "Destination non précisée" }, 14f))
            val infos = listOfNotNull(
                d.mission.ifEmpty { null },
                if (d.voie.isNotEmpty()) "voie ${d.voie}" else null,
            ).joinToString(" · ")
            if (infos.isNotEmpty()) addView(Ui.texte(context, infos, 12f, pale = true))
        }))
        val (etatTexte, couleur) = when {
            d.supprime -> Pair("Supprimé", TransportWidget.ROUGE)
            d.theorique -> Pair("Prévu", TransportWidget.ENCRE_PALE)
            d.retard > 0 -> Pair("+${d.retard} min", TransportWidget.couleurRetard(d.retard))
            else -> Pair("À l'heure", TransportWidget.VERT)
        }
        addView(Ui.texte(context, etatTexte, 13f, gras = true).apply { setTextColor(couleur) })
    }

    private fun carteIncident(i: Incident) = Ui.carte(this).apply {
        val haut = Ui.ligne(context)
        haut.addView(Ui.pastille(context, i.ligne, 24))
        haut.addView(Ui.extensible(Ui.texte(context, i.titre.ifEmpty { i.ligne.nom }, 15f, gras = true).apply {
            if (i.bloquant) setTextColor(TransportWidget.ROUGE)
        }))
        addView(haut)
        if (i.texte.isNotEmpty() && i.texte != i.titre)
            addView(Ui.texte(context, i.texte, 13f, pale = true).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
    }
}
