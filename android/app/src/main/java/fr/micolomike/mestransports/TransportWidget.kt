package fr.micolomike.mestransports

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Widget d'écran d'accueil : état des lignes et prochains départs dans les deux sens. */
class TransportWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Pas d'appel automatique : seul le bouton ↻ interroge PRIM (quota)
        dessiner(context)
        Planif.programmer(context)
    }

    override fun onEnabled(context: Context) {
        Planif.programmer(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ACTUALISER) {
            dessiner(context, enCours = true)
            Planif.maintenant(context)
        }
    }

    companion object {
        private const val ACTION_ACTUALISER = "fr.micolomike.mestransports.ACTUALISER"

        private val VERT = Color.parseColor("#2FBF71")
        private val JAUNE = Color.parseColor("#F2D338")
        private val ORANGE = Color.parseColor("#F5A524")
        private val ROUGE = Color.parseColor("#E5484D")
        private val GRIS = Color.parseColor("#8B949E")
        private val ENCRE = Color.parseColor("#F2F5F7")
        private val ENCRE_PALE = Color.parseColor("#9AA7B0")

        private val FORMAT_MAJ = DateTimeFormatter.ofPattern("HH'h'mm").withZone(Prim.PARIS)

        fun couleur(n: Niveau) = when (n) {
            Niveau.VERT -> VERT
            Niveau.ORANGE -> ORANGE
            Niveau.ROUGE -> ROUGE
            Niveau.GRIS -> GRIS
        }

        fun dessiner(ctx: Context, enCours: Boolean = false) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val cn = ComponentName(ctx, TransportWidget::class.java)
                if (mgr.getAppWidgetIds(cn).isEmpty()) return

                val etat = Stockage.etat(ctx)
                val erreur = Stockage.erreur(ctx)
                val v = RemoteViews(ctx.packageName, R.layout.widget_transports)

                val niveau = when {
                    etat == null -> Niveau.GRIS
                    erreur.isNotEmpty() -> Niveau.GRIS
                    else -> etat.niveau
                }
                v.setInt(R.id.w_point, "setColorFilter", couleur(niveau))

                v.setTextViewText(
                    R.id.w_maj,
                    when {
                        enCours -> "Mise à jour…"
                        etat != null -> "maj " + FORMAT_MAJ.format(Instant.ofEpochMilli(etat.maj))
                        else -> ""
                    }
                )

                if (etat == null) {
                    v.setTextViewText(R.id.w_resume, if (erreur.isNotEmpty()) erreur else "Toucher pour configurer")
                    v.setViewVisibility(R.id.w_sens1, View.GONE)
                    v.setViewVisibility(R.id.w_departs1, View.GONE)
                    v.setViewVisibility(R.id.w_sens2, View.GONE)
                    v.setViewVisibility(R.id.w_departs2, View.GONE)
                    v.setViewVisibility(R.id.w_detail, View.GONE)
                } else {
                    v.setTextViewText(R.id.w_resume, etat.resume)
                    val sens = etat.sens
                    remplirSens(v, R.id.w_sens1, R.id.w_departs1, sens.getOrNull(0))
                    remplirSens(v, R.id.w_sens2, R.id.w_departs2, sens.getOrNull(1))
                    val detail = if (erreur.isNotEmpty()) "⚠ $erreur" else etat.detail
                    v.setTextViewText(R.id.w_detail, detail)
                    v.setViewVisibility(R.id.w_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
                }

                val ouvrir = PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                v.setOnClickPendingIntent(R.id.w_racine, ouvrir)

                // Bouton ↻ : service de premier plan (accès réseau garanti, voir ServiceActualiser)
                val actualiser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    PendingIntent.getForegroundService(
                        ctx, 3, Intent(ctx, ServiceActualiser::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                else PendingIntent.getService(
                    ctx, 3, Intent(ctx, ServiceActualiser::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                v.setOnClickPendingIntent(R.id.w_actualiser, actualiser)

                mgr.updateAppWidget(cn, v)
            } catch (e: Exception) {
                // Un redessin raté ne doit jamais faire planter la mise à jour.
            }
        }

        private fun remplirSens(v: RemoteViews, idLibelle: Int, idDeparts: Int, s: Sens?) {
            if (s == null) {
                v.setViewVisibility(idLibelle, View.GONE)
                v.setViewVisibility(idDeparts, View.GONE)
                return
            }
            v.setViewVisibility(idLibelle, View.VISIBLE)
            v.setViewVisibility(idDeparts, View.VISIBLE)
            v.setTextViewText(idLibelle, s.libelle)
            v.setTextViewText(idDeparts, departs(s.departs))
        }

        /** "07h42  07h57 +3  08h12 supprimé", avec couleurs. */
        fun departs(liste: List<Depart>): CharSequence {
            val sb = SpannableStringBuilder()
            if (liste.isEmpty()) {
                ajouter(sb, "Aucun départ annoncé", ForegroundColorSpan(ENCRE_PALE))
                return sb
            }
            liste.forEachIndexed { i, d ->
                if (i > 0) sb.append("   ")
                when {
                    d.supprime -> {
                        ajouter(sb, d.heure, ForegroundColorSpan(ROUGE), StrikethroughSpan())
                        ajouter(sb, " suppr.", ForegroundColorSpan(ROUGE))
                    }
                    d.retard > 0 -> {
                        // 1 min : sans couleur ; 2 à 4 min : jaune ; 5 à 10 min : orange ; plus de 10 min : rouge
                        val c = when {
                            d.retard > 10 -> ROUGE
                            d.retard > 4 -> ORANGE
                            d.retard >= 2 -> JAUNE
                            else -> ENCRE
                        }
                        ajouter(sb, d.heure, ForegroundColorSpan(c), StyleSpan(Typeface.BOLD))
                        ajouter(sb, " +${d.retard}", ForegroundColorSpan(c))
                    }
                    else -> ajouter(sb, d.heure, ForegroundColorSpan(ENCRE), StyleSpan(Typeface.BOLD))
                }
            }
            return sb
        }

        private fun ajouter(sb: SpannableStringBuilder, texte: String, vararg styles: Any) {
            val debut = sb.length
            sb.append(texte)
            styles.forEach { sb.setSpan(it, debut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
