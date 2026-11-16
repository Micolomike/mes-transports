package fr.micolomike.mestransports

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File

/**
 * Carte des transports d'Île-de-France : fond OpenStreetMap, tracés officiels IDFM
 * (métro, RER, Transilien, tram) aux couleurs des lignes, gares, et position de l'utilisateur.
 */
class CarteActivity : Activity() {

    private enum class Fond(val libelle: String) { CLAIR("Plan clair"), SOMBRE("Plan sombre"), BUS("Plan bus") }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var carte: MapView
    private lateinit var statut: TextView
    private lateinit var boutonFond: Button
    private lateinit var credits: TextView
    private var maPosition: MyLocationNewOverlay? = null
    private var garesOverlay: SimpleFastPointOverlay? = null
    private var suivreAuRetour = false
    private var fond = Fond.CLAIR

    /** Fond « transports en commun » d'ÖPNVKarte (OpenStreetMap), qui montre aussi les lignes de bus. */
    private val fondBus: OnlineTileSourceBase = XYTileSource(
        "OPNVKarte", 0, 18, 256, ".png", arrayOf("https://tileserver.memomaps.de/tilegen/"),
        "© OpenStreetMap, ÖPNVKarte (memomaps.de)",
    )

    /** Plan sombre : couleurs du plan inversées et légèrement atténuées. */
    private val inverse = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -0.85f, 0f, 0f, 0f, 225f,
        0f, -0.85f, 0f, 0f, 230f,
        0f, 0f, -0.85f, 0f, 235f,
        0f, 0f, 0f, 1f, 0f,
    )))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@CarteActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tuiles")
        }
        val prefs = getSharedPreferences("carte", MODE_PRIVATE)
        fond = try { Fond.valueOf(prefs.getString("fond", Fond.CLAIR.name)!!) } catch (e: Exception) { Fond.CLAIR }

        carte = MapView(this).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 8.0
            maxZoomLevel = 19.0
            controller.setZoom(prefs.getFloat("zoom", 11f).toDouble())
            controller.setCenter(GeoPoint(prefs.getFloat("lat", 48.8566f).toDouble(), prefs.getFloat("lon", 2.3522f).toDouble()))
        }

        val racine = FrameLayout(this)
        racine.addView(carte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Barre du haut : retour, titre, état du chargement
        val haut = Ui.colonne(this, 10).apply { setBackgroundColor(0xE60B1620.toInt()) }
        val ligneHaut = Ui.ligne(this)
        ligneHaut.addView(Ui.icone(this, "←") { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = Ui.dp(context, 12) }
        })
        ligneHaut.addView(Ui.extensible(Ui.texte(this, "Carte des transports", 19f, gras = true)))
        haut.addView(ligneHaut)
        statut = Ui.texte(this, "", 12f, pale = true)
        haut.addView(statut)
        racine.addView(haut, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Boutons en bas à droite : me localiser, fond de carte
        val actions = Ui.colonne(this, 12).apply { gravity = Gravity.END }
        boutonFond = Ui.bouton(this, fond.libelle, ViewGroup.LayoutParams.WRAP_CONTENT) { changerFond() }.apply {
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
        }
        actions.addView(boutonFond)
        actions.addView(Ui.bouton(this, "◎  Me localiser", ViewGroup.LayoutParams.WRAP_CONTENT) { localiser() }.apply {
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
        })
        racine.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))

        credits = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF303840.toInt())
            setBackgroundColor(0xB3FFFFFF.toInt())
            setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        }
        racine.addView(credits, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        // Android 15 affiche l'appli sous les barres système : on décale les commandes
        racine.setOnApplyWindowInsetsListener { _, insets ->
            val (h, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            haut.setPadding(Ui.dp(this, 10), Ui.dp(this, 10) + h, Ui.dp(this, 10), Ui.dp(this, 10))
            actions.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12) + b)
            (credits.layoutParams as FrameLayout.LayoutParams).bottomMargin = b
            credits.requestLayout()
            insets
        }
        setContentView(racine)

        appliquerFond()
        carte.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                majGares()
                return false
            }
        })
        chargerReseau()
    }

    override fun onResume() {
        super.onResume()
        carte.onResume()
        if (suivreAuRetour && aLaPermission()) maPosition?.enableMyLocation()
    }

    override fun onPause() {
        val c = carte.mapCenter
        getSharedPreferences("carte", MODE_PRIVATE).edit()
            .putFloat("lat", c.latitude.toFloat()).putFloat("lon", c.longitude.toFloat())
            .putFloat("zoom", carte.zoomLevelDouble.toFloat()).putString("fond", fond.name).apply()
        suivreAuRetour = maPosition?.isMyLocationEnabled == true
        maPosition?.disableMyLocation()
        carte.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        portee.cancel()
        carte.onDetach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ fond de carte

    private fun changerFond() {
        fond = Fond.values()[(fond.ordinal + 1) % Fond.values().size]
        appliquerFond()
    }

    private fun appliquerFond() {
        carte.setTileSource(if (fond == Fond.BUS) fondBus else TileSourceFactory.MAPNIK)
        carte.overlayManager.tilesOverlay.setColorFilter(if (fond == Fond.SOMBRE) inverse else null)
        boutonFond.text = fond.libelle
        credits.text = if (fond == Fond.BUS) "© OpenStreetMap · ÖPNVKarte · IDFM" else "© contributeurs OpenStreetMap · IDFM"
        carte.invalidate()
    }

    // ------------------------------------------------------------------ réseau IDFM

    private fun chargerReseau() {
        statut.text = "Chargement du réseau ferré IDFM…"
        portee.launch {
            val traces = try {
                withContext(Dispatchers.IO) { Reseau.traces(this@CarteActivity) }
            } catch (e: Exception) {
                statut.text = "Tracés des lignes indisponibles : ${e.message ?: "erreur réseau"}"
                return@launch
            }
            val dossier = FolderOverlay()
            val densite = resources.displayMetrics.density
            for (t in traces) {
                val largeur = when {
                    t.mode.startsWith("RER") -> 4.5f
                    t.mode.startsWith("METRO") -> 3.5f
                    else -> 3f
                } * densite
                for (partie in t.parties) {
                    dossier.add(Polyline(carte).apply {
                        setPoints(partie)
                        outlinePaint.color = t.couleur
                        outlinePaint.strokeWidth = largeur
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        title = t.nom
                        setOnClickListener { _, _, _ ->
                            Toast.makeText(this@CarteActivity, t.nom, Toast.LENGTH_SHORT).show()
                            true
                        }
                    })
                }
            }
            // Sous la position de l'utilisateur si elle est déjà affichée
            carte.overlays.add(0, dossier)
            carte.invalidate()
            statut.text = "Touche une ligne ou une gare pour voir son nom"

            val gares = try {
                withContext(Dispatchers.IO) { Reseau.gares(this@CarteActivity) }
            } catch (e: Exception) {
                return@launch // les tracés suffisent
            }
            afficherGares(gares)
        }
    }

    private fun afficherGares(gares: List<Reseau.Gare>) {
        if (gares.isEmpty()) return
        val points: List<IGeoPoint> = gares.map { LabelledGeoPoint(it.point.latitude, it.point.longitude, it.nom) }
        val d = resources.displayMetrics.density
        val options = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
            .setRadius(3.5f * d)
            .setCellSize(12)
            .setIsClickable(true)
            .setMinZoomShowLabels(15)
            .setPointStyle(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
            .setTextStyle(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF15232F.toInt()
                textSize = 12f * d
                textAlign = Paint.Align.CENTER
                setShadowLayer(3f, 0f, 0f, Color.WHITE)
            })
        val overlay = SimpleFastPointOverlay(SimplePointTheme(points, true), options).apply {
            setOnClickListener { pts, i ->
                (pts.get(i) as? LabelledGeoPoint)?.label?.let {
                    Toast.makeText(this@CarteActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
        garesOverlay = overlay
        val idx = carte.overlays.indexOfFirst { it is MyLocationNewOverlay }
        if (idx >= 0) carte.overlays.add(idx, overlay) else carte.overlays.add(overlay)
        majGares()
    }

    /** Les gares ne s'affichent qu'à partir d'un zoom suffisant (sinon la carte est saturée). */
    private fun majGares() {
        val o = garesOverlay ?: return
        val visible = carte.zoomLevelDouble >= 12.5
        if (o.isEnabled != visible) {
            o.isEnabled = visible
            carte.invalidate()
        }
    }

    // ------------------------------------------------------------------ géolocalisation

    private fun aLaPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun localiser() {
        if (!aLaPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), DEMANDE_POSITION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) {
            Toast.makeText(this, "Active la localisation du téléphone", Toast.LENGTH_LONG).show()
            return
        }
        val overlay = maPosition ?: creerPosition().also { maPosition = it }
        overlay.enableMyLocation()
        overlay.myLocation?.let {
            carte.controller.animateTo(it, maxOf(carte.zoomLevelDouble, 15.0), 600L)
            overlay.enableFollowLocation()
            return
        }
        statut.text = "Recherche de ta position…"
        overlay.runOnFirstFix {
            runOnUiThread {
                statut.text = "Touche une ligne ou une gare pour voir son nom"
                overlay.myLocation?.let { carte.controller.animateTo(it, maxOf(carte.zoomLevelDouble, 15.0), 600L) }
                overlay.enableFollowLocation()
            }
        }
    }

    private fun creerPosition(): MyLocationNewOverlay {
        val fournisseur = GpsMyLocationProvider(this).apply {
            // Avec la position approximative seulement (Android 12+), le GPS est refusé
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                clearLocationSources()
                addLocationSource(LocationManager.NETWORK_PROVIDER)
            }
            locationUpdateMinTime = 2000
            locationUpdateMinDistance = 5f
        }
        return MyLocationNewOverlay(fournisseur, carte).apply {
            isDrawAccuracyEnabled = true
            carte.overlays.add(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != DEMANDE_POSITION) return
        if (aLaPermission()) localiser()
        else Toast.makeText(this, "Sans autorisation de localisation, la carte reste sans ta position", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val DEMANDE_POSITION = 42
    }
}
