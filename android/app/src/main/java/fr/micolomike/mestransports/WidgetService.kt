package fr.micolomike.mestransports

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Fournit au widget les lignes de sa liste : un trajet par ligne, puis les infos trafic. */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Fabrique(applicationContext)

    private class Fabrique(private val ctx: Context) : RemoteViewsFactory {
        private sealed class Element {
            class UnTrajet(val bloc: Bloc) : Element()
            class UnMessage(val incident: Incident) : Element()
        }

        private var elements: List<Element> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val etat = Stockage.etat(ctx)
            elements = if (etat == null) emptyList() else {
                // Ordre matin / après-midi recalculé à l'affichage (l'état peut dater de la veille)
                val blocs = Prim.ordonner(etat.blocs, Stockage.trajets(ctx))
                // Le 1er incident est déjà dans le résumé en tête du widget
                blocs.map { Element.UnTrajet(it) } + etat.incidents.drop(1).map { Element.UnMessage(it) }
            }
        }

        override fun getCount() = elements.size
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val clic = Intent()
            return when (val e = elements.getOrNull(position)) {
                is Element.UnTrajet -> RemoteViews(ctx.packageName, R.layout.widget_ligne).apply {
                    setImageViewBitmap(R.id.l_pastille, Pastille.dessiner(ctx, e.bloc.ligne, 26))
                    setTextViewText(R.id.l_libelle, e.bloc.libelle)
                    setTextViewText(
                        R.id.l_departs,
                        if (e.bloc.erreur.isNotEmpty() && e.bloc.departs.isEmpty())
                            TransportWidget.departs(emptyList(), vide = e.bloc.erreur)
                        else TransportWidget.departs(e.bloc.departs)
                    )
                    setOnClickFillInIntent(R.id.l_racine, clic)
                }
                is Element.UnMessage -> RemoteViews(ctx.packageName, R.layout.widget_message).apply {
                    setImageViewBitmap(R.id.m_pastille, Pastille.dessiner(ctx, e.incident.ligne, 16))
                    setTextViewText(R.id.m_texte, e.incident.titre.ifEmpty { e.incident.texte })
                    setOnClickFillInIntent(R.id.m_racine, clic)
                }
                null -> RemoteViews(ctx.packageName, R.layout.widget_message)
            }
        }
    }
}
