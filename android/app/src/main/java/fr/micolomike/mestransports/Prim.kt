package fr.micolomike.mestransports

import android.net.Uri
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Trafic RER E / ligne 14 en direct via l'API PRIM d'Île-de-France Mobilités.
 * Portage de TrafficService.cs (widget Windows) : mêmes appels, mêmes filtres, mêmes niveaux.
 */
object Prim {
    private const val BASE = "https://prim.iledefrance-mobilites.fr/marketplace"
    private const val CODE_RER_E = "C01729"
    private const val CODE_M14 = "C01384"

    // Haussmann Saint-Lazare (RER E) et Émerainville - Pontault-Combault
    private const val REF_HAUSSMANN = "STIF:StopArea:SP:58718:"
    private const val REF_EMERAINVILLE = "STIF:StopArea:SP:43074:"

    /** Retard (min) à partir duquel la pastille passe à l'orange. */
    const val SEUIL_ORANGE = 5

    val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    // Trains de la branche Tournan (ceux qui desservent Emerainville)
    private val BRANCHE_TOURNAN = listOf("tournan", "gretz", "ozoir", "roissy", "emerainville")
    // Vers Paris depuis Emerainville : on écarte les trains qui repartent vers Tournan
    private val EXCLURE_VERS_PARIS = listOf("tournan", "ozoir", "roissy-en-brie", "gretz")

    private val GARES_TRAJET_RER_E = listOf(
        "emerainville", "yvris", "noisy-le-grand", "villiers", "boullereaux", "champigny", "nogent",
        "val de fontenay", "rosny", "noisy-le-sec", "noisy le sec", "pantin", "rosa parks", "magenta",
        "haussmann", "saint-lazare", "st lazare", "tournan", "gretz", "ozoir", "roissy",
    )
    private val GARES_HORS_TRAJET_RER_E = listOf(
        "nanterre", "defense", "maillot", "bondy", "raincy", "gagny", "chenay", "chelles", "gournay",
    )
    private val GARES_TRAJET_M14 = listOf(
        "saint-lazare", "st lazare", "pont cardinet", "porte de clichy", "saint-ouen", "st ouen",
    )
    private val GARES_HORS_TRAJET_M14 = listOf(
        "madeleine", "pyramides", "chatelet", "gare de lyon", "bercy", "emilion", "bibliotheque",
        "olympiades", "maison blanche", "bicetre", "villejuif", "hay-les-roses", "chevilly", "thiais",
        "orly", "pleyel",
    )
    // Messages qui ne concernent que l'accès en gare, pas la circulation
    private val HORS_CIRCULATION = listOf(
        "ascenseur", "escalier", "escalator", "cheminement", "acces du", "acces au", "acces en gare",
    )

    private val FORMAT_NAVITIA = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val FORMAT_HEURE = DateTimeFormatter.ofPattern("HH'h'mm").withZone(PARIS)

    private data class Incident(val ligne: String, val titre: String, val bloquant: Boolean)
    private data class Passage(
        val attendu: Instant, val retardMin: Int, val supprime: Boolean, val destination: String,
        val prevu: Instant? = null,
    )

    /** Identifiants Navitia des gares (stop_area), trouvés une fois puis gardés en mémoire. */
    private val idsNavitia = mutableMapOf<String, String>()

    class PrimException(message: String) : Exception(message)

    /** Interroge PRIM et construit l'état affiché par le widget. Lève une exception si les données sont indisponibles. */
    fun calculer(cle: String): Etat {
        val incidents = lireIncidents(cle, CODE_RER_E, "RER E", GARES_TRAJET_RER_E, GARES_HORS_TRAJET_RER_E) +
            lireIncidents(cle, CODE_M14, "Ligne 14", GARES_TRAJET_M14, GARES_HORS_TRAJET_M14)

        val versTournan = completer(cle, lirePassages(cle, REF_HAUSSMANN), "haussmann")
            .filter { p -> BRANCHE_TOURNAN.any { sansAccents(p.destination).contains(it) } }
            .take(3)
        val versParis = completer(cle, lirePassages(cle, REF_EMERAINVILLE), "emerainville")
            .filter { p -> EXCLURE_VERS_PARIS.none { sansAccents(p.destination).contains(it) } }
            .take(3)

        // Niveau global : vert à l'heure ou retard < 5 min, orange retard de 5 à 9 min ou incident, rouge >= 10 min, supprimé ou bloquant
        val tous = versTournan + versParis
        var niveau = Niveau.VERT
        if (tous.any { it.retardMin >= SEUIL_ORANGE } || incidents.isNotEmpty()) niveau = Niveau.ORANGE
        if (tous.any { it.supprime || it.retardMin >= 10 } || incidents.any { it.bloquant }) niveau = Niveau.ROUGE
        if (tous.isEmpty() && incidents.isEmpty()) niveau = Niveau.GRIS

        fun versDepart(p: Passage) = Depart(FORMAT_HEURE.format(p.attendu), p.retardMin, p.supprime)

        val resume = if (incidents.isNotEmpty())
            "${incidents[0].ligne} : ${raccourcir(incidents[0].titre, 90)}"
        else "Trafic normal RER E et ligne 14"
        val detail = incidents.drop(1).take(2).joinToString("\n") { "${it.ligne} : ${raccourcir(it.titre, 90)}" }

        val allerParis = Sens("Emerainville → St-Lazare", versParis.map(::versDepart))
        val retour = Sens("St-Lazare → Tournan", versTournan.map(::versDepart))
        // Le matin, le sens vers Paris en premier ; l'après-midi, le retour
        val matin = LocalDateTime.now(PARIS).hour < 12
        return Etat(
            niveau = niveau,
            resume = resume,
            detail = detail,
            maj = System.currentTimeMillis(),
            sens = if (matin) listOf(allerParis, retour) else listOf(retour, allerParis),
        )
    }

    // ---------------------------------------------------------------- PRIM

    private fun get(url: String, cle: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("apikey", cle)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "mes-transports/1.0")
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw PrimException("PRIM refuse le jeton ($code)")
            if (code == 429) throw PrimException("Quota PRIM dépassé (429)")
            if (code !in 200..299) throw PrimException("PRIM : erreur HTTP $code")
            val texte = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(texte)
        } finally {
            c.disconnect()
        }
    }

    private fun lireIncidents(
        cle: String, code: String, nomLigne: String,
        garesTrajet: List<String>, garesHorsTrajet: List<String>,
    ): List<Incident> {
        val res = mutableListOf<Incident>()
        val url = "$BASE/v2/navitia/line_reports/lines/${Uri.encode("line:IDFM:$code")}/line_reports?count=100"
        val perturbations = get(url, cle).optJSONArray("disruptions") ?: return res
        val maintenant = LocalDateTime.now(PARIS)

        for (d in objets(perturbations)) {
            // En cours maintenant ?
            val actif = objets(d.optJSONArray("application_periods")).any { p ->
                val deb = dateNavitia(chaine(p, "begin"))
                val fin = dateNavitia(chaine(p, "end"))
                deb != null && fin != null && !deb.isAfter(maintenant) && !maintenant.isAfter(fin)
            }
            if (!actif) continue

            var titre = ""
            var texte = ""
            for (m in objets(d.optJSONArray("messages"))) {
                val t = nettoyer(chaine(m, "text"))
                var estTitre = false
                m.optJSONObject("channel")?.let { canal ->
                    val types = canal.optJSONArray("types")
                    if (types != null) for (i in 0 until types.length()) if (types.optString(i) == "title") estTitre = true
                    if (sansAccents(chaine(canal, "name")).startsWith("titre")) estTitre = true
                }
                if (estTitre) {
                    if (titre.isEmpty()) titre = t
                } else if (t.length > texte.length) texte = t
            }

            val sev = d.optJSONObject("severity")
            val gravite = sev?.let { chaine(it, "name") } ?: ""
            val effet = sev?.let { chaine(it, "effect") } ?: ""

            val tout = sansAccents("$titre $texte ${chaine(d, "cause")}")

            // Filtrage : accès en gare (ascenseurs, escaliers...) ignoré
            if (HORS_CIRCULATION.any { tout.contains(it) }) continue

            // Pertinence : gare du trajet citée, ou aucune gare hors trajet citée (message général)
            val surTrajet = garesTrajet.any { tout.contains(it) }
            val horsTrajet = garesHorsTrajet.any { tout.contains(it) }
            if (!surTrajet && horsTrajet) continue

            val bloquant = sansAccents(gravite).contains("bloquant") || effet == "NO_SERVICE"
            res.add(Incident(nomLigne, titre.ifEmpty { texte }, bloquant))
        }
        return res
    }

    private fun lirePassages(cle: String, monitoringRef: String): List<Passage> {
        val res = mutableListOf<Passage>()
        val url = "$BASE/stop-monitoring?MonitoringRef=${Uri.encode(monitoringRef)}"
        val livraisons = get(url, cle).optJSONObject("Siri")
            ?.optJSONObject("ServiceDelivery")
            ?.optJSONArray("StopMonitoringDelivery") ?: return res

        val limite = Instant.now().minusSeconds(60)
        for (liv in objets(livraisons)) {
            for (visite in objets(liv.optJSONArray("MonitoredStopVisit"))) {
                val mvj = visite.optJSONObject("MonitoredVehicleJourney") ?: continue
                if (!valeur(mvj, "LineRef").contains(CODE_RER_E)) continue
                val appel = mvj.optJSONObject("MonitoredCall") ?: continue

                val prevu = dateIso(chaine(appel, "AimedDepartureTime")) ?: dateIso(chaine(appel, "AimedArrivalTime"))
                val attendu = dateIso(chaine(appel, "ExpectedDepartureTime"))
                    ?: dateIso(chaine(appel, "ExpectedArrivalTime")) ?: prevu
                if (attendu == null || attendu.isBefore(limite)) continue

                val statut = (chaine(appel, "DepartureStatus") + chaine(appel, "ArrivalStatus")).lowercase()
                val retard = if (prevu != null) Math.floorDiv(Duration.between(prevu, attendu).seconds, 60L).toInt() else 0
                val destination = valeur(appel, "DestinationDisplay").ifEmpty { valeur(mvj, "DestinationName") }

                res.add(Passage(attendu, maxOf(retard, 0), statut.contains("cancel"), destination, prevu))
            }
        }
        return res.sortedBy { it.attendu }
    }

    /**
     * Le flux temps réel (stop-monitoring) omet parfois des trains bien prévus au service
     * (ex. 12/11/2026 : TINU de 16h44 et 17h29 absents à Haussmann, présents dans l'appli SNCF).
     * On ajoute donc les départs de l'horaire théorique Navitia qui n'ont pas d'équivalent
     * temps réel (même heure prévue à 90 s près). En cas d'échec, on garde le temps réel seul.
     */
    private fun completer(cle: String, tempsReel: List<Passage>, gare: String): List<Passage> {
        val theoriques = try {
            lireTheorique(cle, gare)
        } catch (e: Exception) {
            return tempsReel
        }
        val limite = Instant.now().minusSeconds(60)
        val ajouts = theoriques.filter { t ->
            !t.attendu.isBefore(limite) && tempsReel.none { r ->
                val ref = r.prevu ?: r.attendu
                Math.abs(Duration.between(ref, t.attendu).seconds) <= 90
            }
        }
        return (tempsReel + ajouts).sortedBy { it.attendu }
    }

    private fun idNavitia(cle: String, gare: String): String? {
        idsNavitia[gare]?.let { return it }
        val url = "$BASE/v2/navitia/lines/${Uri.encode("line:IDFM:$CODE_RER_E")}/stop_areas?count=100"
        for (sa in objets(get(url, cle).optJSONArray("stop_areas"))) {
            val nom = sansAccents(chaine(sa, "name"))
            val id = chaine(sa, "id")
            if (id.isEmpty()) continue
            if (nom.contains("haussmann")) idsNavitia["haussmann"] = id
            if (nom.contains("emerainville")) idsNavitia["emerainville"] = id
        }
        return idsNavitia[gare]
    }

    private fun lireTheorique(cle: String, gare: String): List<Passage> {
        val id = idNavitia(cle, gare) ?: return emptyList()
        val depuis = LocalDateTime.now(PARIS).minusMinutes(2).format(FORMAT_NAVITIA)
        val url = "$BASE/v2/navitia/lines/${Uri.encode("line:IDFM:$CODE_RER_E")}/stop_areas/${Uri.encode(id)}" +
            "/departures?count=40&data_freshness=base_schedule&from_datetime=$depuis"
        val res = mutableListOf<Passage>()
        for (d in objets(get(url, cle).optJSONArray("departures"))) {
            val sdt = d.optJSONObject("stop_date_time") ?: continue
            val heure = dateNavitia(chaine(sdt, "base_departure_date_time").ifEmpty { chaine(sdt, "departure_date_time") })
                ?: continue
            val di = d.optJSONObject("display_informations")
            val destination = di?.let { chaine(it, "direction").ifEmpty { chaine(it, "headsign") } } ?: ""
            res.add(Passage(heure.atZone(PARIS).toInstant(), 0, false, destination))
        }
        return res
    }

    // ---------------------------------------------------------------- utilitaires

    private fun objets(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** Valeur texte d'un champ, "" si absent, null ou d'un autre type. */
    private fun chaine(o: JSONObject, nom: String): String = o.opt(nom) as? String ?: ""

    /** Champs SIRI de la forme {"value": ...} ou [{"value": ...}]. */
    private fun valeur(o: JSONObject, nom: String): String {
        var p: Any? = o.opt(nom)
        if (p is JSONArray) p = if (p.length() > 0) p.opt(0) else null
        if (p is JSONObject) p = p.opt("value")
        return p as? String ?: ""
    }

    private fun dateIso(s: String): Instant? {
        if (s.isEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun dateNavitia(s: String): LocalDateTime? =
        try { LocalDateTime.parse(s, FORMAT_NAVITIA) } catch (e: Exception) { null }

    fun sansAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun nettoyer(texte: String): String =
        Html.fromHtml(texte, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun raccourcir(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max - 1) + "…"
}
