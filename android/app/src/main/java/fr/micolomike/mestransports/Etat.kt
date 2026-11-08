package fr.micolomike.mestransports

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Niveau { VERT, ORANGE, ROUGE, GRIS }

data class Depart(val heure: String, val retard: Int, val supprime: Boolean)
data class Sens(val libelle: String, val departs: List<Depart>)

/** Ce qu'affiche le widget ; conservé entre deux mises à jour. */
data class Etat(
    val niveau: Niveau,
    val resume: String,
    val detail: String,
    val maj: Long,
    val sens: List<Sens>,
) {
    fun versJson(): String = JSONObject().apply {
        put("niveau", niveau.name)
        put("resume", resume)
        put("detail", detail)
        put("maj", maj)
        put("sens", JSONArray().apply {
            sens.forEach { s ->
                put(JSONObject().apply {
                    put("libelle", s.libelle)
                    put("departs", JSONArray().apply {
                        s.departs.forEach { d ->
                            put(JSONObject().put("heure", d.heure).put("retard", d.retard).put("supprime", d.supprime))
                        }
                    })
                })
            }
        })
    }.toString()

    companion object {
        fun depuisJson(s: String): Etat? = try {
            val o = JSONObject(s)
            val sens = o.getJSONArray("sens")
            Etat(
                niveau = Niveau.valueOf(o.getString("niveau")),
                resume = o.getString("resume"),
                detail = o.optString("detail", ""),
                maj = o.getLong("maj"),
                sens = (0 until sens.length()).map { i ->
                    val so = sens.getJSONObject(i)
                    val deps = so.getJSONArray("departs")
                    Sens(so.getString("libelle"), (0 until deps.length()).map { j ->
                        val d = deps.getJSONObject(j)
                        Depart(d.getString("heure"), d.getInt("retard"), d.getBoolean("supprime"))
                    })
                },
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Réglages et dernier état, dans les SharedPreferences de l'appli. */
object Stockage {
    private const val FICHIER = "mes_transports"
    const val CLE_ETAT = "etat"
    private const val CLE_JETON = "jeton_prim"
    private const val CLE_PLAGES = "plages_seulement"
    private const val CLE_DERNIER_APPEL = "dernier_appel_auto"
    const val CLE_ERREUR = "erreur"

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

    fun enregistrerErreur(ctx: Context, message: String) =
        prefs(ctx).edit().putString(CLE_ERREUR, message).apply()
}
