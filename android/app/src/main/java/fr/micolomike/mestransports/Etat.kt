package fr.micolomike.mestransports

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Niveau { VERT, ORANGE, ROUGE, GRIS }

/** Moment où un trajet passe en tête de liste : le matin, l'après-midi, ou sans préférence. */
enum class Moment(val libelle: String) {
    TOUJOURS("Toute la journée"), MATIN("Le matin"), SOIR("L'après-midi");

    fun suivant(): Moment = values()[(ordinal + 1) % values().size]
}

/** Ligne de transport (identifiant Navitia "line:IDFM:Cxxxxx"). */
data class Ligne(
    val id: String,
    val code: String,
    val nom: String,
    val mode: String,
    val couleur: String,
    val couleurTexte: String,
) {
    /** Code de ligne IDFM (Cxxxxx), utilisé par l'API temps réel. */
    val codeIdfm: String get() = id.substringAfterLast(":")

    fun versJson(): JSONObject = JSONObject()
        .put("id", id).put("code", code).put("nom", nom).put("mode", mode)
        .put("couleur", couleur).put("couleurTexte", couleurTexte)

    companion object {
        fun depuisJson(o: JSONObject) = Ligne(
            o.optString("id"), o.optString("code"), o.optString("nom"), o.optString("mode"),
            o.optString("couleur"), o.optString("couleurTexte"),
        )
    }
}

/** Trajet paramétré : une ligne, une gare de départ, une gare d'arrivée. */
data class Trajet(
    val id: String,
    val ligne: Ligne,
    val departId: String,
    val departNom: String,
    val arriveeId: String,
    val arriveeNom: String,
    val moment: Moment,
) {
    val libelle: String get() = "${raccourci(departNom)} → ${raccourci(arriveeNom)}"

    fun versJson(): JSONObject = JSONObject()
        .put("id", id).put("ligne", ligne.versJson())
        .put("departId", departId).put("departNom", departNom)
        .put("arriveeId", arriveeId).put("arriveeNom", arriveeNom)
        .put("moment", moment.name)

    companion object {
        fun depuisJson(o: JSONObject) = Trajet(
            o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            Ligne.depuisJson(o.optJSONObject("ligne") ?: JSONObject()),
            o.optString("departId"), o.optString("departNom"),
            o.optString("arriveeId"), o.optString("arriveeNom"),
            try { Moment.valueOf(o.optString("moment")) } catch (e: Exception) { Moment.TOUJOURS },
        )

        /** Noms courts pour le widget : "Haussmann Saint-Lazare" → "St-Lazare", etc. */
        fun raccourci(nom: String): String {
            var n = nom.substringBefore(" - ").trim()
            n = n.replace("Haussmann Saint-Lazare", "St-Lazare")
                .replace("Saint-", "St-").replace("Sainte-", "Ste-")
            return n.ifEmpty { nom }
        }
    }
}

data class Depart(
    val heure: String,
    val retard: Int,
    val supprime: Boolean,
    /** Venu de l'horaire prévu, sans confirmation temps réel. */
    val theorique: Boolean = false,
    val destination: String = "",
    val mission: String = "",
    val voie: String = "",
)

/** Prochains départs d'un trajet, tels qu'affichés. */
data class Bloc(
    val trajetId: String,
    val ligne: Ligne,
    val libelle: String,
    val departs: List<Depart>,
    val erreur: String = "",
)

data class Incident(val ligne: Ligne, val titre: String, val texte: String, val bloquant: Boolean)

/** Ce qu'affichent le widget et l'appli ; conservé entre deux mises à jour. */
data class Etat(
    val niveau: Niveau,
    val resume: String,
    val maj: Long,
    val blocs: List<Bloc>,
    val incidents: List<Incident>,
) {
    fun versJson(): String = JSONObject().apply {
        put("niveau", niveau.name)
        put("resume", resume)
        put("maj", maj)
        put("blocs", JSONArray().apply {
            blocs.forEach { b ->
                put(JSONObject().apply {
                    put("trajetId", b.trajetId)
                    put("ligne", b.ligne.versJson())
                    put("libelle", b.libelle)
                    put("erreur", b.erreur)
                    put("departs", JSONArray().apply {
                        b.departs.forEach { d ->
                            put(
                                JSONObject().put("heure", d.heure).put("retard", d.retard)
                                    .put("supprime", d.supprime).put("theorique", d.theorique)
                                    .put("destination", d.destination).put("mission", d.mission)
                                    .put("voie", d.voie)
                            )
                        }
                    })
                })
            }
        })
        put("incidents", JSONArray().apply {
            incidents.forEach { i ->
                put(
                    JSONObject().put("ligne", i.ligne.versJson()).put("titre", i.titre)
                        .put("texte", i.texte).put("bloquant", i.bloquant)
                )
            }
        })
    }.toString()

    companion object {
        fun depuisJson(s: String): Etat? = try {
            val o = JSONObject(s)
            val blocs = o.optJSONArray("blocs") ?: JSONArray()
            val incidents = o.optJSONArray("incidents") ?: JSONArray()
            Etat(
                niveau = Niveau.valueOf(o.getString("niveau")),
                resume = o.optString("resume"),
                maj = o.getLong("maj"),
                blocs = (0 until blocs.length()).map { i ->
                    val b = blocs.getJSONObject(i)
                    val deps = b.optJSONArray("departs") ?: JSONArray()
                    Bloc(
                        b.optString("trajetId"),
                        Ligne.depuisJson(b.optJSONObject("ligne") ?: JSONObject()),
                        b.optString("libelle"),
                        (0 until deps.length()).map { j ->
                            val d = deps.getJSONObject(j)
                            Depart(
                                d.optString("heure"), d.optInt("retard"), d.optBoolean("supprime"),
                                d.optBoolean("theorique"), d.optString("destination"),
                                d.optString("mission"), d.optString("voie"),
                            )
                        },
                        b.optString("erreur"),
                    )
                },
                incidents = (0 until incidents.length()).map { i ->
                    val x = incidents.getJSONObject(i)
                    Incident(
                        Ligne.depuisJson(x.optJSONObject("ligne") ?: JSONObject()),
                        x.optString("titre"), x.optString("texte"), x.optBoolean("bloquant"),
                    )
                },
            )
        } catch (e: Exception) {
            null // ancien format (versions 1.0.x) : ignoré, recalculé à la prochaine mise à jour
        }
    }
}

/** Réglages, trajets, caches et dernier état, dans les SharedPreferences de l'appli. */
object Stockage {
    private const val FICHIER = "mes_transports"
    const val CLE_ETAT = "etat2"
    private const val CLE_JETON = "jeton_prim"
    private const val CLE_PLAGES = "plages_seulement"
    private const val CLE_DERNIER_APPEL = "dernier_appel_auto"
    const val CLE_ERREUR = "erreur"
    private const val CLE_TRAJETS = "trajets"
    private const val CLE_DESSERTE = "cache_desserte"
    private const val CLE_GARES = "cache_gares"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    fun jeton(ctx: Context): String = prefs(ctx).getString(CLE_JETON, "")?.trim() ?: ""
    fun enregistrerJeton(ctx: Context, jeton: String) = prefs(ctx).edit().putString(CLE_JETON, jeton.trim()).apply()

    fun plagesSeulement(ctx: Context): Boolean = prefs(ctx).getBoolean(CLE_PLAGES, true)
    fun enregistrerPlages(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(CLE_PLAGES, v).apply()

    fun dernierAppelAuto(ctx: Context): Long = prefs(ctx).getLong(CLE_DERNIER_APPEL, 0L)
    fun enregistrerAppelAuto(ctx: Context, t: Long) = prefs(ctx).edit().putLong(CLE_DERNIER_APPEL, t).apply()

    fun etat(ctx: Context): Etat? = prefs(ctx).getString(CLE_ETAT, null)?.let { Etat.depuisJson(it) }

    /** Dernière erreur rencontrée (vide si la dernière mise à jour a réussi). */
    fun erreur(ctx: Context): String = prefs(ctx).getString(CLE_ERREUR, "") ?: ""

    fun enregistrerSucces(ctx: Context, etat: Etat) =
        prefs(ctx).edit().putString(CLE_ETAT, etat.versJson()).putString(CLE_ERREUR, "").apply()

    /** Remplace l'état affiché sans toucher à la dernière erreur. */
    fun enregistrerEtat(ctx: Context, etat: Etat) = prefs(ctx).edit().putString(CLE_ETAT, etat.versJson()).apply()

    fun enregistrerErreur(ctx: Context, message: String) =
        prefs(ctx).edit().putString(CLE_ERREUR, message).apply()

    // ---------------------------------------------------------------- trajets

    fun trajets(ctx: Context): List<Trajet> {
        val s = prefs(ctx).getString(CLE_TRAJETS, null) ?: return trajetsParDefaut().also { enregistrerTrajets(ctx, it) }
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { Trajet.depuisJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            trajetsParDefaut()
        }
    }

    fun enregistrerTrajets(ctx: Context, liste: List<Trajet>) {
        val a = JSONArray()
        liste.forEach { a.put(it.versJson()) }
        prefs(ctx).edit().putString(CLE_TRAJETS, a.toString()).apply()
    }

    /** Trajets des versions précédentes ; les identifiants de gares sont retrouvés par leur nom à la 1re mise à jour. */
    private fun trajetsParDefaut(): List<Trajet> {
        val rerE = Ligne("line:IDFM:C01729", "E", "RER E", "RER", "C04191", "FFFFFF")
        val m14 = Ligne("line:IDFM:C01384", "14", "Métro 14", "Métro", "662483", "FFFFFF")
        return listOf(
            Trajet("defaut-1", rerE, "", "Émerainville - Pontault-Combault", "", "Haussmann Saint-Lazare", Moment.MATIN),
            Trajet("defaut-2", m14, "", "Saint-Lazare", "", "Saint-Ouen", Moment.MATIN),
            Trajet("defaut-3", m14, "", "Saint-Ouen", "", "Saint-Lazare", Moment.SOIR),
            Trajet("defaut-4", rerE, "", "Haussmann Saint-Lazare", "", "Émerainville - Pontault-Combault", Moment.SOIR),
        )
    }

    // ---------------------------------------------------------------- caches

    /** Desserte : "le train de telle mission / destination, au départ de A, s'arrête-t-il à B ?" */
    fun desserte(ctx: Context): MutableMap<String, Boolean> {
        val o = try { JSONObject(prefs(ctx).getString(CLE_DESSERTE, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val m = mutableMapOf<String, Boolean>()
        o.keys().forEach { k -> m[k] = o.optBoolean(k) }
        return m
    }

    fun enregistrerDesserte(ctx: Context, m: Map<String, Boolean>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(CLE_DESSERTE, o.toString()).apply()
    }

    /** Noms des gares par ligne et des gares traversées par trajet (pertinence des infos trafic). */
    fun gares(ctx: Context): MutableMap<String, List<String>> {
        val o = try { JSONObject(prefs(ctx).getString(CLE_GARES, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val m = mutableMapOf<String, List<String>>()
        o.keys().forEach { k ->
            val a = o.optJSONArray(k) ?: JSONArray()
            m[k] = (0 until a.length()).map { a.optString(it) }
        }
        return m
    }

    fun enregistrerGares(ctx: Context, m: Map<String, List<String>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs(ctx).edit().putString(CLE_GARES, o.toString()).apply()
    }

    /** À appeler quand les trajets changent : on oublie l'état affiché (il ne correspond plus). */
    fun oublierEtat(ctx: Context) = prefs(ctx).edit().remove(CLE_ETAT).apply()
}
