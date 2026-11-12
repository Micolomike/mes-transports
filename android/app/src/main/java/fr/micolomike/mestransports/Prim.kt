package fr.micolomike.mestransports

import android.content.Context
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
 * Données PRIM d'Île-de-France Mobilités pour les trajets paramétrés.
 *
 * Pour chaque trajet (ligne, gare A, gare B) :
 *  - temps réel : stop-monitoring (SIRI) à la gare A, filtré sur la ligne ;
 *  - horaire prévu : departures Navitia à la gare A, qui complète les trains absents du flux temps réel
 *    (ex. 12/11/2026 : TINU de 16h44 et 17h29 absents à Haussmann, présents dans l'appli SNCF) ;
 *  - desserte : un train ne compte que s'il s'arrête ensuite à B. On le vérifie une fois par mission
 *    ou destination en lisant la liste des arrêts d'un de ses trains (cache durable).
 * Infos trafic : line_reports Navitia par ligne, filtrées sur les gares des trajets.
 */
object Prim {
    private const val BASE = "https://prim.iledefrance-mobilites.fr/marketplace"

    /** Retard (min) à partir duquel la pastille passe à l'orange. */
    const val SEUIL_ORANGE = 5

    /** Départs gardés par trajet (l'appli en montre plus que le widget). */
    const val DEPARTS_PAR_TRAJET = 8

    /** Vérifications de desserte au plus par trajet et par mise à jour (quota PRIM). */
    private const val VERIFS_MAX = 6

    val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    // Messages qui ne concernent que l'accès en gare, pas la circulation
    private val HORS_CIRCULATION = listOf(
        "ascenseur", "escalier", "escalator", "cheminement", "acces du", "acces au", "acces en gare",
    )

    private val FORMAT_NAVITIA = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val FORMAT_HEURE = DateTimeFormatter.ofPattern("HH'h'mm").withZone(PARIS)
    private val MISSION = Regex("^[A-Z]{4}$")

    private data class Passage(
        val attendu: Instant,
        val prevu: Instant?,
        val retardMin: Int,
        val supprime: Boolean,
        val destination: String,
        val mission: String,
        val voie: String,
        val theorique: Boolean,
        /** Train de l'horaire prévu (pour vérifier la desserte), vide pour le temps réel. */
        val course: String = "",
    )

    class PrimException(message: String) : Exception(message)

    /** Problème propre à un trajet, affiché tel quel sous ce trajet. */
    private class TrajetException(message: String) : Exception(message)

    // ================================================================ calcul global

    /** Interroge PRIM et construit l'état affiché. Lève une exception si PRIM refuse (jeton, quota). */
    fun calculer(ctx: Context, cle: String): Etat {
        val desserte = Stockage.desserte(ctx)
        val gares = Stockage.gares(ctx)

        // 1. Trajets par défaut : identifiants de gares (par leur nom) et couleurs à retrouver
        val trajets = Stockage.trajets(ctx).map { t ->
            try { resoudre(cle, t, gares) } catch (e: PrimException) { throw e } catch (e: Exception) { t }
        }
        Stockage.enregistrerTrajets(ctx, trajets)

        // 2. Départs de chaque trajet
        val blocs = mutableListOf<Bloc>()
        val passagesParTrajet = mutableMapOf<String, List<Passage>>()
        for (t in trajets) {
            if (t.departId.isEmpty() || t.arriveeId.isEmpty()) {
                blocs.add(Bloc(t.id, t.ligne, t.libelle, emptyList(), "Gare introuvable, trajet à refaire dans les paramètres"))
                continue
            }
            try {
                val p = departsTrajet(cle, t, desserte, gares)
                passagesParTrajet[t.id] = p
                blocs.add(Bloc(t.id, t.ligne, t.libelle, p.map(::versDepart), if (p.isEmpty()) "Aucun départ annoncé" else ""))
            } catch (e: PrimException) {
                throw e
            } catch (e: TrajetException) {
                blocs.add(Bloc(t.id, t.ligne, t.libelle, emptyList(), e.message ?: ""))
            } catch (e: Exception) {
                blocs.add(Bloc(t.id, t.ligne, t.libelle, emptyList(), "Départs indisponibles"))
            }
        }
        Stockage.enregistrerDesserte(ctx, desserte)
        Stockage.enregistrerGares(ctx, gares)

        // 3. Infos trafic, une fois par ligne
        val incidents = mutableListOf<Incident>()
        for (ligne in trajets.map { it.ligne }.distinctBy { it.id }) {
            val garesTrajets = trajets.filter { it.ligne.id == ligne.id }
                .flatMap { gares["trajet:${it.id}"] ?: listOf(it.departNom, it.arriveeNom) }
            val garesLigne = gares["ligne:${ligne.id}"] ?: emptyList()
            try {
                incidents += lireIncidents(cle, ligne, garesTrajets, garesLigne)
            } catch (e: PrimException) {
                throw e
            } catch (e: Exception) {
                // info trafic indisponible pour cette ligne : on n'invente rien
            }
        }

        // 4. Niveau global : sur les 3 prochains départs confirmés de chaque trajet
        val proches = passagesParTrajet.values.flatMap { it.take(3) }.filter { !it.theorique }
        var niveau = Niveau.VERT
        if (proches.any { it.retardMin >= SEUIL_ORANGE } || incidents.isNotEmpty()) niveau = Niveau.ORANGE
        if (proches.any { it.supprime || it.retardMin >= 10 } || incidents.any { it.bloquant }) niveau = Niveau.ROUGE
        if (passagesParTrajet.values.all { it.isEmpty() } && incidents.isEmpty()) niveau = Niveau.GRIS

        val resume = if (incidents.isNotEmpty())
            "${incidents[0].ligne.nom} : ${raccourcir(incidents[0].titre.ifEmpty { incidents[0].texte }, 90)}"
        else "Trafic normal " + trajets.map { it.ligne.nom }.distinct().joinToString(", ")

        return Etat(niveau, resume, System.currentTimeMillis(), ordonner(blocs, trajets), incidents)
    }

    /** Le matin, les trajets "matin" d'abord ; l'après-midi, les trajets "soir". Ordre choisi conservé sinon. */
    fun ordonner(blocs: List<Bloc>, trajets: List<Trajet>): List<Bloc> {
        val moment = trajets.associate { it.id to it.moment }
        val matin = LocalDateTime.now(PARIS).hour < 12
        val rang = if (matin) mapOf(Moment.MATIN to 0, Moment.TOUJOURS to 1, Moment.SOIR to 2)
        else mapOf(Moment.SOIR to 0, Moment.TOUJOURS to 1, Moment.MATIN to 2)
        return blocs.sortedBy { rang[moment[it.trajetId] ?: Moment.TOUJOURS] ?: 1 }
    }

    private fun versDepart(p: Passage) = Depart(
        FORMAT_HEURE.format(p.attendu), p.retardMin, p.supprime, p.theorique, p.destination, p.mission, p.voie,
    )

    // ================================================================ départs d'un trajet

    private fun departsTrajet(
        cle: String, t: Trajet, desserte: MutableMap<String, Boolean>, gares: MutableMap<String, List<String>>,
    ): List<Passage> {
        val tempsReel = try { lireTempsReel(cle, t) } catch (e: PrimException) { throw e } catch (e: Exception) { emptyList() }
        val prevus = try { lirePrevus(cle, t) } catch (e: PrimException) { throw e } catch (e: Exception) { emptyList() }

        // Desserte à vérifier : une course par mission/destination inconnue, dans la limite du budget
        var budget = VERIFS_MAX
        for ((cleD, groupe) in prevus.groupBy { cleDesserte(t, it) }) {
            if (desserte.containsKey(cleD) || budget <= 0) continue
            val course = groupe.firstOrNull { it.course.isNotEmpty() }?.course ?: continue
            budget--
            val res = try { verifierCourse(cle, course, t) } catch (e: PrimException) { throw e } catch (e: Exception) { null }
                ?: continue
            desserte[cleD] = res.first
            // la destination sert de repli pour le temps réel sans code mission
            desserte[cleDestination(t, groupe[0].destination)] = res.first
            if (res.first && res.second.isNotEmpty()) gares["trajet:${t.id}"] = res.second
        }

        fun dessert(p: Passage): Boolean? {
            if (p.mission.isNotEmpty()) desserte[cleMission(t, p.mission)]?.let { return it }
            return desserte[cleDestination(t, p.destination)]
        }

        val limite = Instant.now().minusSeconds(60)
        val retenus = mutableListOf<Passage>()
        // Temps réel : on ne garde que les trains dont on sait qu'ils desservent B
        for (r in tempsReel) {
            if (r.attendu.isBefore(limite)) continue
            val ok = dessert(r) ?: prevus.firstOrNull { memeTrain(it, r) }?.let { dessert(it) } ?: false
            if (ok) retenus.add(r)
        }
        // Horaire prévu : trains qui desservent B et absents du temps réel
        for (p in prevus) {
            if (p.attendu.isBefore(limite)) continue
            if (dessert(p) != true) continue
            if (tempsReel.any { memeTrain(p, it) }) continue
            retenus.add(p)
        }
        if (retenus.isEmpty() && prevus.isEmpty() && tempsReel.isNotEmpty())
            throw TrajetException("Horaires prévus indisponibles : desserte de ${Trajet.raccourci(t.arriveeNom)} inconnue")
        return retenus.sortedBy { it.attendu }.take(DEPARTS_PAR_TRAJET)
    }

    /** Même train : heure prévue à 90 s près et mission ou destination compatible. */
    private fun memeTrain(prevu: Passage, reel: Passage): Boolean {
        val ref = reel.prevu ?: reel.attendu
        if (Math.abs(Duration.between(ref, prevu.attendu).seconds) > 90) return false
        if (prevu.mission.isNotEmpty() && reel.mission.isNotEmpty()) return prevu.mission == reel.mission
        val a = clef(prevu.destination)
        val b = clef(reel.destination)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    private fun cleMission(t: Trajet, mission: String) = "${t.ligne.id}|${t.departId}|${t.arriveeId}|m:$mission"
    private fun cleDestination(t: Trajet, dest: String) = "${t.ligne.id}|${t.departId}|${t.arriveeId}|d:${clef(dest)}"
    private fun cleDesserte(t: Trajet, p: Passage) =
        if (p.mission.isNotEmpty()) cleMission(t, p.mission) else cleDestination(t, p.destination)

    /** "Tournan (Tournan-en-Brie)" et "Tournan" → "tournan" ; 7 premiers caractères pour tolérer les variantes. */
    private fun clef(dest: String): String =
        sansAccents(dest.substringBefore("(")).replace(Regex("[^a-z0-9]"), "").take(7)

    // ================================================================ PRIM : temps réel (SIRI)

    private fun lireTempsReel(cle: String, t: Trajet): List<Passage> {
        val zone = t.departId.substringAfterLast(":")
        val ref = "STIF:StopArea:SP:$zone:"
        val url = "$BASE/stop-monitoring?MonitoringRef=${Uri.encode(ref)}&LineRef=${Uri.encode("STIF:Line::${t.ligne.codeIdfm}:")}"
        val livraisons = get(url, cle).optJSONObject("Siri")
            ?.optJSONObject("ServiceDelivery")
            ?.optJSONArray("StopMonitoringDelivery") ?: return emptyList()

        val res = mutableListOf<Passage>()
        for (liv in objets(livraisons)) {
            for (visite in objets(liv.optJSONArray("MonitoredStopVisit"))) {
                val mvj = visite.optJSONObject("MonitoredVehicleJourney") ?: continue
                if (!valeur(mvj, "LineRef").contains(t.ligne.codeIdfm)) continue
                val appel = mvj.optJSONObject("MonitoredCall") ?: continue

                val prevu = dateIso(chaine(appel, "AimedDepartureTime")) ?: dateIso(chaine(appel, "AimedArrivalTime"))
                val attendu = dateIso(chaine(appel, "ExpectedDepartureTime"))
                    ?: dateIso(chaine(appel, "ExpectedArrivalTime")) ?: prevu ?: continue

                val statut = (chaine(appel, "DepartureStatus") + chaine(appel, "ArrivalStatus")).lowercase()
                val retard = if (prevu != null) Math.floorDiv(Duration.between(prevu, attendu).seconds, 60L).toInt() else 0
                val destination = valeur(appel, "DestinationDisplay").ifEmpty { valeur(mvj, "DestinationName") }
                val mission = valeur(mvj, "JourneyNote").trim().takeIf { MISSION.matches(it) } ?: ""
                val voie = valeur(appel, "DeparturePlatformName").ifEmpty { valeur(appel, "ArrivalPlatformName") }

                res.add(Passage(attendu, prevu, maxOf(retard, 0), statut.contains("cancel"), destination, mission, voie, false))
            }
        }
        return res.sortedBy { it.attendu }
    }

    // ================================================================ PRIM : horaire prévu (Navitia)

    private fun lirePrevus(cle: String, t: Trajet): List<Passage> {
        val depuis = LocalDateTime.now(PARIS).minusMinutes(2).format(FORMAT_NAVITIA)
        val url = "$BASE/v2/navitia/lines/${Uri.encode(t.ligne.id)}/stop_areas/${Uri.encode(t.departId)}" +
            "/departures?count=40&data_freshness=base_schedule&from_datetime=$depuis"
        val res = mutableListOf<Passage>()
        for (d in objets(get(url, cle).optJSONArray("departures"))) {
            val sdt = d.optJSONObject("stop_date_time") ?: continue
            val heure = dateNavitia(chaine(sdt, "base_departure_date_time").ifEmpty { chaine(sdt, "departure_date_time") })
                ?: continue
            val di = d.optJSONObject("display_informations")
            val destination = di?.let { chaine(it, "direction") } ?: ""
            val mission = listOf("headsign", "trip_short_name", "name")
                .map { k -> di?.let { chaine(it, k) }?.trim() ?: "" }
                .firstOrNull { MISSION.matches(it) } ?: ""
            val course = objets(d.optJSONArray("links"))
                .firstOrNull { chaine(it, "type") == "vehicle_journey" }?.let { chaine(it, "id") } ?: ""
            val instant = heure.atZone(PARIS).toInstant()
            res.add(Passage(instant, instant, 0, false, destination, mission, "", true, course))
        }
        return res
    }

    /**
     * Lit les arrêts d'un train : (s'arrête à B après A ?, gares traversées de A à B).
     * Null si la réponse ne permet pas de conclure.
     */
    private fun verifierCourse(cle: String, course: String, t: Trajet): Pair<Boolean, List<String>>? {
        val vj = get("$BASE/v2/navitia/vehicle_journeys/${Uri.encode(course)}?depth=2", cle)
            .optJSONArray("vehicle_journeys")?.optJSONObject(0) ?: return null
        val arrets = objets(vj.optJSONArray("stop_times")).mapNotNull { st ->
            val sp = st.optJSONObject("stop_point") ?: return@mapNotNull null
            val sa = sp.optJSONObject("stop_area")
            val id = sa?.let { chaine(it, "id") } ?: ""
            val nom = sa?.let { chaine(it, "name") }?.ifEmpty { null } ?: chaine(sp, "name")
            Pair(id, nom)
        }
        if (arrets.isEmpty()) return null
        fun indice(id: String, nom: String): Int {
            val i = arrets.indexOfFirst { it.first.isNotEmpty() && it.first == id }
            if (i >= 0) return i
            return arrets.indexOfFirst { sansAccents(it.second) == sansAccents(nom) }
        }
        val a = indice(t.departId, t.departNom)
        if (a < 0) return null
        val b = indice(t.arriveeId, t.arriveeNom)
        val ok = b > a
        return Pair(ok, if (ok) arrets.subList(a, b + 1).map { it.second } else emptyList())
    }

    // ================================================================ PRIM : recherche (paramètres)

    /** Lignes correspondant à une recherche, ou toutes les lignes d'un mode si la recherche est vide. */
    fun chercherLignes(cle: String, texte: String, modes: List<String>?): List<Ligne> {
        val q = texte.trim()
        val lignes = if (q.isEmpty()) {
            if (modes.isNullOrEmpty()) return emptyList()
            modes.flatMap { m ->
                objets(get("$BASE/v2/navitia/physical_modes/${Uri.encode(m)}/lines?count=300", cle).optJSONArray("lines"))
            }
        } else {
            objets(get("$BASE/v2/navitia/pt_objects?q=${Uri.encode(q)}&type%5B%5D=line&count=50", cle).optJSONArray("pt_objects"))
                .mapNotNull { it.optJSONObject("line") }
                .filter { l -> modes == null || objets(l.optJSONArray("physical_modes")).any { chaine(it, "id") in modes } }
        }
        return lignes.map(::ligneDepuis).filter { it.id.isNotEmpty() }.distinctBy { it.id }
            .sortedWith(compareBy({ it.mode }, { it.code.length }, { it.code }))
    }

    fun ligneDepuis(l: JSONObject): Ligne {
        val modeNom = l.optJSONObject("commercial_mode")?.let { chaine(it, "name") }?.ifEmpty { null }
            ?: objets(l.optJSONArray("physical_modes")).firstOrNull()?.let { chaine(it, "name") } ?: ""
        val code = chaine(l, "code").ifEmpty { chaine(l, "name") }
        return Ligne(
            chaine(l, "id"), code, nomLigne(modeNom, code, chaine(l, "name")), modeNom,
            chaine(l, "color").ifEmpty { "8B949E" }, chaine(l, "text_color").ifEmpty { "FFFFFF" },
        )
    }

    private fun nomLigne(mode: String, code: String, nom: String): String {
        val m = sansAccents(mode)
        return when {
            m.contains("metro") -> "Métro $code"
            m.contains("rer") -> "RER $code"
            m.contains("tram") -> "Tram $code"
            m.contains("train") || m.contains("transilien") -> "Train $code"
            m.contains("bus") -> "Bus $code"
            else -> nom.ifEmpty { code }
        }
    }

    /** Gares d'une ligne (id, nom). */
    fun garesLigne(cle: String, ligneId: String): List<Pair<String, String>> =
        objets(get("$BASE/v2/navitia/lines/${Uri.encode(ligneId)}/stop_areas?count=400", cle).optJSONArray("stop_areas"))
            .map { Pair(chaine(it, "id"), chaine(it, "name")) }
            .filter { it.first.isNotEmpty() }

    /** Complète un trajet : identifiants des gares (trouvés par leur nom), couleur, gares de la ligne. */
    private fun resoudre(cle: String, t: Trajet, gares: MutableMap<String, List<String>>): Trajet {
        var r = t
        if (r.departId.isEmpty() || r.arriveeId.isEmpty() || !gares.containsKey("ligne:${r.ligne.id}")) {
            val liste = garesLigne(cle, r.ligne.id)
            if (liste.isNotEmpty()) gares["ligne:${r.ligne.id}"] = liste.map { it.second }
            fun trouver(nom: String): Pair<String, String>? {
                val n = sansAccents(nom)
                val court = n.substringBefore(" - ")
                return liste.firstOrNull { sansAccents(it.second) == n }
                    ?: liste.firstOrNull { sansAccents(it.second) == court }
                    ?: liste.firstOrNull { sansAccents(it.second).startsWith(court) }
                    ?: liste.firstOrNull { sansAccents(it.second).contains(court) }
            }
            if (r.departId.isEmpty()) trouver(r.departNom)?.let { r = r.copy(departId = it.first, departNom = it.second) }
            if (r.arriveeId.isEmpty()) trouver(r.arriveeNom)?.let { r = r.copy(arriveeId = it.first, arriveeNom = it.second) }
        }
        // Couleurs officielles des trajets par défaut (les trajets ajoutés les ont déjà)
        if (r.id.startsWith("defaut-")) {
            val c = gares["couleur:${r.ligne.id}"] ?: run {
                val l = get("$BASE/v2/navitia/lines/${Uri.encode(r.ligne.id)}", cle).optJSONArray("lines")?.optJSONObject(0)
                    ?: return r
                val lu = ligneDepuis(l)
                listOf(lu.couleur, lu.couleurTexte).also { gares["couleur:${r.ligne.id}"] = it }
            }
            if (c.size == 2) r = r.copy(ligne = r.ligne.copy(couleur = c[0], couleurTexte = c[1]))
        }
        return r
    }

    // ================================================================ PRIM : infos trafic

    private fun lireIncidents(cle: String, ligne: Ligne, garesTrajets: List<String>, garesLigne: List<String>): List<Incident> {
        val res = mutableListOf<Incident>()
        val url = "$BASE/v2/navitia/line_reports/lines/${Uri.encode(ligne.id)}/line_reports?count=100"
        val perturbations = get(url, cle).optJSONArray("disruptions") ?: return res
        val maintenant = LocalDateTime.now(PARIS)
        val surTrajet = garesTrajets.flatMap(::variantes).distinct()
        val horsTrajet = garesLigne.flatMap(::variantes).distinct().filter { it !in surTrajet }

        for (d in objets(perturbations)) {
            val actif = objets(d.optJSONArray("application_periods")).any { p ->
                val deb = dateNavitia(chaine(p, "begin"))
                val fin = dateNavitia(chaine(p, "end"))
                deb != null && fin != null && !deb.isAfter(maintenant) && !maintenant.isAfter(fin)
            }
            if (!actif) continue

            var titre = ""
            var texte = ""
            for (m in objets(d.optJSONArray("messages"))) {
                val tx = nettoyer(chaine(m, "text"))
                var estTitre = false
                m.optJSONObject("channel")?.let { canal ->
                    val types = canal.optJSONArray("types")
                    if (types != null) for (i in 0 until types.length()) if (types.optString(i) == "title") estTitre = true
                    if (sansAccents(chaine(canal, "name")).startsWith("titre")) estTitre = true
                }
                if (estTitre) {
                    if (titre.isEmpty()) titre = tx
                } else if (tx.length > texte.length) texte = tx
            }

            val sev = d.optJSONObject("severity")
            val gravite = sev?.let { chaine(it, "name") } ?: ""
            val effet = sev?.let { chaine(it, "effect") } ?: ""
            val tout = sansAccents("$titre $texte ${chaine(d, "cause")}")

            if (HORS_CIRCULATION.any { tout.contains(it) }) continue
            // Pertinence : gare d'un trajet citée, ou aucune autre gare de la ligne citée (message général)
            val cite = surTrajet.any { tout.contains(it) }
            val autre = horsTrajet.any { tout.contains(it) }
            if (!cite && autre) continue

            val bloquant = sansAccents(gravite).contains("bloquant") || effet == "NO_SERVICE"
            if (res.any { it.titre == titre && it.texte == texte }) continue
            res.add(Incident(ligne, titre, texte, bloquant))
        }
        return res
    }

    /** "Émerainville - Pontault-Combault" → ["emerainville - pontault-combault", "emerainville", "pontault-combault"]. */
    private fun variantes(nom: String): List<String> {
        val n = sansAccents(nom)
        return (listOf(n) + n.split(" - ")).map { it.trim() }.filter { it.length >= 5 }
    }

    // ================================================================ HTTP

    private fun get(url: String, cle: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("apikey", cle)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "mes-transports/2.0")
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw PrimException("PRIM refuse le jeton ($code)")
            if (code == 429) throw PrimException("Quota PRIM dépassé (429)")
            if (code == 404) return JSONObject() // objet inconnu de Navitia : "pas de données"
            if (code !in 200..299) throw java.io.IOException("PRIM : erreur HTTP $code")
            val texte = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(texte)
        } finally {
            c.disconnect()
        }
    }

    // ================================================================ utilitaires

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
