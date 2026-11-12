package fr.micolomike.mestransports

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Widget d'écran d'accueil : état global, puis une ligne par trajet (pastille + prochains départs)
 * et les infos trafic. La liste défile : en étirant le widget, on voit plus de trajets.
 */
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

        val VERT = Color.parseColor("#2FBF71")
        val JAUNE = Color.parseColor("#F2D338")
        val ORANGE = Color.parseColor("#F5A524")
        val ROUGE = Color.parseColor("#E5484D")
        val GRIS = Color.parseColor("#8B949E")
        val ENCRE = Color.parseColor("#F2F5F7")
        val ENCRE_PALE = Color.parseColor("#9AA7B0")

        /** Départs montrés par trajet dans le widget (l'appli en montre plus). */
        const val DEPARTS_WIDGET = 4

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
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isEmpty()) return

                val etat = Stockage.etat(ctx)
                val erreur = Stockage.erreur(ctx)

                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_transports)
                    val niveau = if (etat == null || erreur.isNotEmpty()) Niveau.GRIS else etat.niveau
                    v.setInt(R.id.w_point, "setColorFilter", couleur(niveau))
                    v.setTextViewText(
                        R.id.w_maj,
                        when {
                            enCours -> "Mise à jour…"
                            etat != null -> "maj " + FORMAT_MAJ.format(Instant.ofEpochMilli(etat.maj))
                            else -> ""
                        }
                    )
                    v.setTextViewText(
                        R.id.w_resume,
                        when {
                            erreur.isNotEmpty() -> "⚠ $erreur"
                            etat != null -> etat.resume
                            else -> "Toucher pour configurer"
                        }
                    )

                    // Liste des trajets (service de liste, une adresse par widget pour éviter le partage du cache)
                    val adaptateur = Intent(ctx, WidgetService::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    adaptateur.data = Uri.parse(adaptateur.toUri(Intent.URI_INTENT_SCHEME))
                    @Suppress("DEPRECATION")
                    v.setRemoteAdapter(R.id.w_liste, adaptateur)
                    v.setEmptyView(R.id.w_liste, R.id.w_vide)

                    val ouvrir = PendingIntent.getActivity(
                        ctx, 1,
                        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    v.setOnClickPendingIntent(R.id.w_entete, ouvrir)
                    v.setOnClickPendingIntent(R.id.w_resume, ouvrir)
                    v.setOnClickPendingIntent(R.id.w_vide, ouvrir)
                    val modele = PendingIntent.getActivity(
                        ctx, 2,
                        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or
                            PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    v.setPendingIntentTemplate(R.id.w_liste, modele)

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

                    mgr.updateAppWidget(id, v)
                }
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_liste)
            } catch (e: Exception) {
                // Un redessin raté ne doit jamais faire planter la mise à jour.
            }
        }

        /** "07h42   07h57 +3   ~08h12   08h27 suppr.", avec couleurs. "~" : horaire prévu non confirmé. */
        fun departs(liste: List<Depart>, max: Int = DEPARTS_WIDGET, vide: String = "Aucun départ annoncé"): CharSequence {
            val sb = SpannableStringBuilder()
            if (liste.isEmpty()) {
                ajouter(sb, vide, ForegroundColorSpan(ENCRE_PALE))
                return sb
            }
            liste.take(max).forEachIndexed { i, d ->
                if (i > 0) sb.append("   ")
                ajouter(sb, texteHeure(d), *styleHeure(d))
                suffixe(d)?.let { (t, c) -> ajouter(sb, t, ForegroundColorSpan(c)) }
            }
            return sb
        }

        /** Heure d'un départ seule, avec sa couleur (écran des départs). */
        fun heure(d: Depart): CharSequence {
            val sb = SpannableStringBuilder()
            ajouter(sb, texteHeure(d), *styleHeure(d))
            return sb
        }

        private fun styleHeure(d: Depart): Array<Any> = when {
            d.supprime -> arrayOf(ForegroundColorSpan(ROUGE), StrikethroughSpan())
            d.theorique -> arrayOf(ForegroundColorSpan(ENCRE_PALE))
            d.retard > 0 -> arrayOf(ForegroundColorSpan(couleurRetard(d.retard)), StyleSpan(Typeface.BOLD))
            else -> arrayOf(ForegroundColorSpan(ENCRE), StyleSpan(Typeface.BOLD))
        }

        /** Les trains de l'horaire prévu, sans confirmation temps réel, reçoivent un "~" devant l'heure. */
        private fun texteHeure(d: Depart) = if (d.theorique) "~${d.heure}" else d.heure

        /** Texte ajouté après l'heure : " +3", " suppr.", ou rien. */
        fun suffixe(d: Depart): Pair<String, Int>? = when {
            d.supprime -> Pair(" suppr.", ROUGE)
            d.theorique -> null
            d.retard > 0 -> Pair(" +${d.retard}", couleurRetard(d.retard))
            else -> null
        }

        /** 1 min : sans couleur ; 2 à 4 min : jaune ; 5 à 10 min : orange ; plus de 10 min : rouge. */
        fun couleurRetard(r: Int) = when {
            r > 10 -> ROUGE
            r > 4 -> ORANGE
            r >= 2 -> JAUNE
            else -> ENCRE
        }

        private fun ajouter(sb: SpannableStringBuilder, texte: String, vararg styles: Any) {
            val debut = sb.length
            sb.append(texte)
            styles.forEach { sb.setSpan(it, debut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
