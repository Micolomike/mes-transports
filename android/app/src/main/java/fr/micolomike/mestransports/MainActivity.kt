package fr.micolomike.mestransports

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Réglages : jeton PRIM, plages horaires, batterie ; aperçu du dernier état. */
class MainActivity : Activity() {

    private lateinit var champJeton: EditText
    private lateinit var apercu: TextView
    private lateinit var boutonBatterie: Button

    private val ecoute = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { majApercu() } }
    private val formatMaj = DateTimeFormatter.ofPattern("HH'h'mm").withZone(Prim.PARIS)

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        champJeton = findViewById(R.id.jeton)
        apercu = findViewById(R.id.apercu)
        boutonBatterie = findViewById(R.id.batterie)

        champJeton.setText(Stockage.jeton(this))

        findViewById<Button>(R.id.coller).setOnClickListener {
            val presse = getSystemService(ClipboardManager::class.java)
            val texte = presse?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (texte.isNullOrBlank()) toast("Presse-papiers vide") else champJeton.setText(texte.trim())
        }

        findViewById<Button>(R.id.enregistrer).setOnClickListener {
            val jeton = champJeton.text.toString().trim()
            if (jeton.isEmpty()) {
                toast("Saisis le jeton PRIM")
                return@setOnClickListener
            }
            Stockage.enregistrerJeton(this, jeton)
            Planif.programmer(this)
            Planif.maintenant(this)
            toast("Jeton enregistré, mise à jour en cours")
        }


        findViewById<Button>(R.id.actualiser).setOnClickListener {
            apercu.text = "Mise à jour…"
            TransportWidget.dessiner(this, enCours = true)
            Planif.maintenant(this)
        }

        boutonBatterie.setOnClickListener { demanderExclusionBatterie() }

        findViewById<Button>(R.id.epingler).setOnClickListener { epinglerWidget() }

        Planif.programmer(this)
    }

    override fun onResume() {
        super.onResume()
        Stockage.prefs(this).registerOnSharedPreferenceChangeListener(ecoute)
        majApercu()
        val pm = getSystemService(PowerManager::class.java)
        val exclu = pm?.isIgnoringBatteryOptimizations(packageName) == true
        boutonBatterie.text = if (exclu) "Batterie : appli déjà exclue de l'optimisation ✓" else "Exclure l'appli de l'optimisation de batterie"
        boutonBatterie.isEnabled = !exclu
    }

    override fun onPause() {
        Stockage.prefs(this).unregisterOnSharedPreferenceChangeListener(ecoute)
        super.onPause()
    }

    private fun majApercu() {
        val etat = Stockage.etat(this)
        val erreur = Stockage.erreur(this)
        val sb = SpannableStringBuilder()
        if (etat == null) {
            sb.append(if (erreur.isNotEmpty()) "⚠ $erreur" else "Aucune donnée pour l'instant.")
        } else {
            sb.append(etat.resume).append("\n")
            etat.sens.forEach { s ->
                sb.append("\n").append(s.libelle).append("\n")
                sb.append(TransportWidget.departs(s.departs)).append("\n")
            }
            if (etat.detail.isNotEmpty()) sb.append("\n").append(etat.detail).append("\n")
            sb.append("\nMise à jour : ").append(formatMaj.format(Instant.ofEpochMilli(etat.maj)))
            if (erreur.isNotEmpty()) sb.append("\n⚠ Dernier essai : ").append(erreur)
        }
        apercu.text = sb
        findViewById<View>(R.id.point).background.mutate().setTint(
            TransportWidget.couleur(if (etat == null || erreur.isNotEmpty()) Niveau.GRIS else etat.niveau)
        )
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
