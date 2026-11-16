package fr.micolomike.mestransports

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tracés du réseau ferré d'Île-de-France (métro, RER, Transilien, tram, Orlyval) et gares,
 * jeux de données ouverts d'Île-de-France Mobilités (sans jeton). Téléchargés une fois,
 * gardés sur le téléphone et rafraîchis au plus tous les 30 jours.
 */
object Reseau {
    private const val API = "https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets"
    private const val URL_TRACES = "$API/traces-du-reseau-ferre-idf/exports/geojson"
    private const val URL_GARES = "$API/emplacement-des-gares-idf/exports/geojson"
    private const val VALIDITE_MS = 30L * 24 * 3600 * 1000

    data class Trace(val nom: String, val mode: String, val couleur: Int, val ordre: Int, val parties: List<List<GeoPoint>>)
    data class Gare(val nom: String, val point: GeoPoint, val couleur: Int)

    fun traces(ctx: Context, forcer: Boolean = false): List<Trace> {
        val json = JSONObject(lire(ctx, "reseau_traces.geojson", URL_TRACES, forcer))
        val res = mutableListOf<Trace>()
        for (f in elements(json.optJSONArray("features"))) {
            val p = f.optJSONObject("properties") ?: JSONObject()
            val parties = lignes(f.optJSONObject("geometry"))
            if (parties.isEmpty()) continue
            val mode = champ(p, "mode").uppercase()
            val indice = champ(p, "indice_lig", "route_short_name", "ligne")
            val nom = champ(p, "res_com", "nom_ligne", "route_long_name").ifEmpty { "$mode $indice".trim() }
            res += Trace(nom, mode, couleur(p, mode, indice), ordre(mode), parties)
        }
        // Trains et RER dessous, métro et tram par-dessus
        return res.sortedBy { it.ordre }
    }

    fun gares(ctx: Context, forcer: Boolean = false): List<Gare> {
        val json = JSONObject(lire(ctx, "reseau_gares.geojson", URL_GARES, forcer))
        val vues = HashSet<String>()
        val res = mutableListOf<Gare>()
        for (f in elements(json.optJSONArray("features"))) {
            val p = f.optJSONObject("properties") ?: JSONObject()
            val g = f.optJSONObject("geometry") ?: continue
            val c = g.optJSONArray("coordinates") ?: continue
            if (g.optString("type") != "Point" || c.length() < 2) continue
            val nom = champ(p, "nom_long", "nom_gares", "nom_zdl", "nom")
            val pt = GeoPoint(c.optDouble(1), c.optDouble(0))
            // Une gare desservie par plusieurs lignes apparaît plusieurs fois : un seul point
            if (!vues.add("$nom|${"%.3f".format(pt.latitude)}|${"%.3f".format(pt.longitude)}")) continue
            val mode = champ(p, "mode").uppercase()
            res += Gare(nom, pt, couleur(p, mode, champ(p, "indice_lig")))
        }
        return res
    }

    // ------------------------------------------------------------------ téléchargement et cache

    private fun lire(ctx: Context, fichier: String, url: String, forcer: Boolean): String {
        val f = File(ctx.filesDir, fichier)
        val frais = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < VALIDITE_MS
        if (frais && !forcer) return f.readText()
        return try {
            val texte = telecharger(url)
            JSONObject(texte) // ne remplace le cache que par un fichier valide
            File(ctx.filesDir, "$fichier.tmp").apply { writeText(texte); renameTo(f) }
            texte
        } catch (e: Exception) {
            if (f.exists() && f.length() > 0) f.readText() else throw e
        }
    }

    private fun telecharger(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/geo+json, application/json")
        try {
            if (c.responseCode !in 200..299) throw Exception("Données IDFM indisponibles (HTTP ${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ lecture GeoJSON

    private fun elements(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun lignes(g: JSONObject?): List<List<GeoPoint>> {
        if (g == null) return emptyList()
        val c = g.optJSONArray("coordinates") ?: return emptyList()
        return when (g.optString("type")) {
            "LineString" -> listOf(points(c))
            "MultiLineString" -> (0 until c.length()).mapNotNull { c.optJSONArray(it)?.let(::points) }
            else -> emptyList()
        }.filter { it.size >= 2 }
    }

    private fun points(a: JSONArray): List<GeoPoint> = (0 until a.length()).mapNotNull { i ->
        val p = a.optJSONArray(i) ?: return@mapNotNull null
        GeoPoint(p.optDouble(1), p.optDouble(0))
    }

    /** Premier champ non vide parmi les noms proposés (insensible à la casse). */
    private fun champ(p: JSONObject, vararg noms: String): String {
        for (n in noms) {
            val k = p.keys().asSequence().firstOrNull { it.equals(n, ignoreCase = true) } ?: continue
            val v = p.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
            if (v is Number) return v.toString()
        }
        return ""
    }

    private fun ordre(mode: String) = when {
        mode.startsWith("TRAIN") || mode.contains("TER") -> 0
        mode.startsWith("RER") -> 1
        mode.startsWith("TRAM") -> 2
        mode.startsWith("METRO") -> 3
        else -> 2
    }

    /** Couleur IDFM du jeu de données ; à défaut, couleur connue de la ligne ; à défaut, gris. */
    private fun couleur(p: JSONObject, mode: String, indice: String): Int {
        val hex = champ(p, "colourweb_hexa", "route_color", "couleur", "colour", "color")
            .ifEmpty {
                p.keys().asSequence()
                    .firstOrNull { (it.contains("colour", true) || it.contains("couleur", true)) && !it.contains("text", true) }
                    ?.let { p.optString(it) } ?: ""
            }
        if (hex.isNotEmpty()) {
            val c = Pastille.couleur(hex, Color.TRANSPARENT)
            if (c != Color.TRANSPARENT) return c
        }
        val i = indice.uppercase().removePrefix("T").ifEmpty { indice }
        val connu = when {
            mode.startsWith("METRO") -> METRO[indice.lowercase()]
            mode.startsWith("RER") -> RER[indice.uppercase()]
            mode.startsWith("TRAIN") -> TRAIN[indice.uppercase()]
            mode.startsWith("TRAM") -> TRAM[i.lowercase()]
            else -> null
        }
        return Pastille.couleur(connu ?: "8A96A0", Color.GRAY)
    }

    private val METRO = mapOf(
        "1" to "FFCD00", "2" to "003CA6", "3" to "837902", "3bis" to "6EC4E8", "4" to "CF009E",
        "5" to "FF7E2E", "6" to "6ECA97", "7" to "FA9ABA", "7bis" to "6ECA97", "8" to "E19BDF",
        "9" to "B6BD00", "10" to "C9910D", "11" to "704B1C", "12" to "007852", "13" to "6EC4E8",
        "14" to "62259D",
    )
    private val RER = mapOf("A" to "E3051C", "B" to "5291CE", "C" to "FFCD00", "D" to "00814F", "E" to "C04191")
    private val TRAIN = mapOf(
        "H" to "8D5E2A", "J" to "CEC92B", "K" to "9B9842", "L" to "CEADD2", "N" to "00A092",
        "P" to "F0B600", "R" to "E4B4D1", "U" to "B6134C", "V" to "9F9825",
    )
    private val TRAM = mapOf(
        "1" to "003CA6", "2" to "CF009E", "3a" to "FF7E2E", "3b" to "00814F", "4" to "E19BDF",
        "5" to "62259D", "6" to "E3051C", "7" to "704B1C", "8" to "837902", "9" to "5291CE",
        "10" to "6ECA97", "11" to "FF7E2E", "12" to "A50034", "13" to "8D5E2A", "14" to "00A092",
    )
}
