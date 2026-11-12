package fr.micolomike.mestransports

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Ajout d'un trajet en 4 étapes : ligne, gare de départ, gare d'arrivée, moment. */
class AjoutTrajetActivity : Activity() {

    private enum class Etape { LIGNE, DEPART, ARRIVEE, MOMENT }

    /** Modes proposés et identifiants Navitia correspondants (null : tous). */
    private val modes = listOf(
        "Tous" to null,
        "Métro" to listOf("physical_mode:Metro"),
        "RER" to listOf("physical_mode:RapidTransit"),
        "Train" to listOf("physical_mode:LocalTrain", "physical_mode:Train"),
        "Tram" to listOf("physical_mode:Tramway"),
        "Bus" to listOf("physical_mode:Bus"),
    )

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var contenu: LinearLayout
    private lateinit var liste: LinearLayout

    private var etape = Etape.LIGNE
    private var mode: Pair<String, List<String>?> = modes[1]
    private var recherche = ""
    private var lignes: List<Ligne> = emptyList()
    private var ligne: Ligne? = null
    private var gares: List<Pair<String, String>> = emptyList()
    private var depart: Pair<String, String>? = null
    private var arrivee: Pair<String, String>? = null
    private var filtre = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contenu = Ui.colonne(this, 16)
        setContentView(ScrollView(this).apply {
            addView(contenu, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (Stockage.jeton(this).isEmpty()) {
            toast("Enregistre d'abord ton jeton PRIM")
            finish()
            return
        }
        afficher()
        chargerLignes()
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    @Deprecated("Retour arrière entre les étapes")
    override fun onBackPressed() {
        when (etape) {
            Etape.LIGNE -> finish()
            Etape.DEPART -> { etape = Etape.LIGNE; filtre = ""; afficher() }
            Etape.ARRIVEE -> { etape = Etape.DEPART; filtre = ""; afficher() }
            Etape.MOMENT -> { etape = Etape.ARRIVEE; filtre = ""; afficher() }
        }
    }

    // ---------------------------------------------------------------- affichage

    private fun afficher() {
        contenu.removeAllViews()
        val entete = Ui.ligne(this)
        entete.addView(Ui.icone(this, "←") {
            @Suppress("DEPRECATION") onBackPressed()
        }.apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = Ui.dp(context, 12) } })
        entete.addView(Ui.extensible(Ui.texte(this, "Nouveau trajet", 22f, gras = true)))
        contenu.addView(entete)

        ligne?.takeIf { etape != Etape.LIGNE }?.let { l ->
            val rappel = Ui.ligne(this).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
            rappel.addView(Ui.pastille(this, l, 28))
            val details = listOfNotNull(
                l.nom,
                depart?.takeIf { etape == Etape.ARRIVEE || etape == Etape.MOMENT }?.let { "depuis ${it.second}" },
                arrivee?.takeIf { etape == Etape.MOMENT }?.let { "vers ${it.second}" },
            ).joinToString("\n")
            rappel.addView(Ui.extensible(Ui.texte(this, details, 14f)))
            contenu.addView(rappel)
        }

        when (etape) {
            Etape.LIGNE -> afficherLignes()
            Etape.DEPART -> afficherGares("Gare de départ", null)
            Etape.ARRIVEE -> afficherGares("Gare d'arrivée", depart?.first)
            Etape.MOMENT -> afficherMoment()
        }
    }

    private fun afficherLignes() {
        contenu.addView(Ui.titre(this, "1. Ligne"))
        val chips = Ui.ligne(this)
        for (m in modes) {
            chips.addView(Ui.bouton(this, if (m.first == mode.first) "● ${m.first}" else m.first, ViewGroup.LayoutParams.WRAP_CONTENT) {
                mode = m
                afficher()
                chargerLignes()
            }.apply {
                setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 6)
            })
        }
        contenu.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val champ = Ui.champ(this, "Rechercher une ligne (ex. 14, E, T3, 212)").apply {
            setText(recherche)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    recherche = v.text.toString()
                    chargerLignes()
                    true
                } else false
            }
        }
        contenu.addView(champ)
        contenu.addView(Ui.bouton(this, "Rechercher") {
            recherche = champ.text.toString()
            chargerLignes()
        })

        liste = Ui.colonne(this)
        contenu.addView(liste)
        remplirLignes()
    }

    private fun remplirLignes() {
        liste.removeAllViews()
        if (lignes.isEmpty()) {
            liste.addView(Ui.texte(this, if (mode.second == listOf("physical_mode:Bus") && recherche.isBlank())
                "Saisis un numéro de bus puis Rechercher." else "Aucune ligne.", 14f, pale = true)
                .apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
            return
        }
        for (l in lignes) {
            liste.addView(Ui.carte(this).apply {
                val r = Ui.ligne(context)
                r.addView(Ui.pastille(context, l, 30))
                r.addView(Ui.extensible(Ui.colonne(context).apply {
                    addView(Ui.texte(context, l.nom, 15f, gras = true))
                    addView(Ui.texte(context, l.mode, 12f, pale = true))
                }))
                addView(r)
                setOnClickListener {
                    ligne = l
                    depart = null
                    arrivee = null
                    filtre = ""
                    etape = Etape.DEPART
                    afficher()
                    chargerGares(l)
                }
            })
        }
    }

    private fun afficherGares(titre: String, exclure: String?) {
        contenu.addView(Ui.titre(this, if (etape == Etape.DEPART) "2. $titre" else "3. $titre"))
        val champ = Ui.champ(this, "Filtrer les gares").apply { setText(filtre) }
        contenu.addView(champ)
        liste = Ui.colonne(this)
        contenu.addView(liste)
        fun remplir() {
            liste.removeAllViews()
            if (gares.isEmpty()) {
                liste.addView(Ui.texte(this, "Chargement des gares…", 14f, pale = true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
                return
            }
            val f = Prim.sansAccents(filtre.trim())
            gares.filter { it.first != exclure && (f.isEmpty() || Prim.sansAccents(it.second).contains(f)) }.forEach { g ->
                liste.addView(TextView(this).apply {
                    text = g.second
                    textSize = 16f
                    setTextColor(Ui.encre(context))
                    setPadding(Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 14))
                    setOnClickListener {
                        if (etape == Etape.DEPART) {
                            depart = g
                            etape = Etape.ARRIVEE
                        } else {
                            arrivee = g
                            etape = Etape.MOMENT
                        }
                        filtre = ""
                        afficher()
                    }
                })
            }
        }
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filtre = s?.toString() ?: ""
                remplir()
            }
        })
        remplir()
    }

    private fun afficherMoment() {
        contenu.addView(Ui.titre(this, "4. Quand l'afficher en premier ?"))
        contenu.addView(
            Ui.texte(this, "Le trajet reste toujours visible ; ce choix décide seulement de sa place dans la liste.", 13f, pale = true)
        )
        for (m in Moment.values()) contenu.addView(Ui.bouton(this, m.libelle) { enregistrer(m) })
    }

    private fun enregistrer(m: Moment) {
        val l = ligne ?: return
        val a = depart ?: return
        val b = arrivee ?: return
        val t = Trajet(UUID.randomUUID().toString(), l, a.first, a.second, b.first, b.second, m)
        Stockage.enregistrerTrajets(this, Stockage.trajets(this) + t)
        toast("Trajet ajouté : touche ↻ pour charger ses départs")
        finish()
    }

    // ---------------------------------------------------------------- réseau

    private fun chargerLignes() {
        val jeton = Stockage.jeton(this)
        val q = recherche
        val m = mode.second
        if (q.isBlank() && (m == null || m == listOf("physical_mode:Bus"))) {
            lignes = emptyList()
            if (etape == Etape.LIGNE) remplirLignes()
            return
        }
        portee.launch {
            try {
                val res = withContext(Dispatchers.IO) { Prim.chercherLignes(jeton, q, m) }
                lignes = res
            } catch (e: Exception) {
                lignes = emptyList()
                toast(message(e))
            }
            if (etape == Etape.LIGNE) remplirLignes()
        }
    }

    private fun chargerGares(l: Ligne) {
        gares = emptyList()
        val jeton = Stockage.jeton(this)
        portee.launch {
            try {
                gares = withContext(Dispatchers.IO) { Prim.garesLigne(jeton, l.id) }.sortedBy { Prim.sansAccents(it.second) }
                if (gares.isEmpty()) toast("Aucune gare trouvée pour cette ligne")
            } catch (e: Exception) {
                toast(message(e))
            }
            if (etape == Etape.DEPART || etape == Etape.ARRIVEE) afficher()
        }
    }

    private fun message(e: Exception) = when (e) {
        is Prim.PrimException -> e.message ?: "Erreur PRIM"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "Pas de réseau"
        else -> "Recherche impossible (${e.javaClass.simpleName})"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
