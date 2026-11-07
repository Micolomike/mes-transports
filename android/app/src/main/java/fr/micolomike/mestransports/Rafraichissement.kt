package fr.micolomike.mestransports

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Une mise à jour : appel PRIM, enregistrement, redessin du widget. */
class RafraichirWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val manuel = inputData.getBoolean(MANUEL, false)

        // Mise à jour automatique hors des heures de trajet : rien à faire (batterie et quota PRIM)
        if (!manuel && Stockage.plagesSeulement(ctx) && !Planif.dansPlage(ZonedDateTime.now(Prim.PARIS))) {
            TransportWidget.dessiner(ctx)
            return Result.success()
        }

        val jeton = Stockage.jeton(ctx)
        if (jeton.isEmpty()) {
            Stockage.enregistrerErreur(ctx, "Jeton PRIM à saisir dans l'appli")
            TransportWidget.dessiner(ctx)
            return Result.success()
        }

        try {
            val etat = withContext(Dispatchers.IO) { Prim.calculer(jeton) }
            Stockage.enregistrerSucces(ctx, etat)
        } catch (e: Exception) {
            val message = when (e) {
                is Prim.PrimException -> e.message ?: "Erreur PRIM"
                is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "Pas de réseau"
                else -> "Données indisponibles (${e.javaClass.simpleName})"
            }
            Stockage.enregistrerErreur(ctx, message)
        }
        TransportWidget.dessiner(ctx)
        return Result.success()
    }

    companion object {
        const val MANUEL = "manuel"
    }
}

object Planif {
    private const val PERIODIQUE = "maj_periodique"
    private const val IMMEDIATE = "maj_immediate"

    private val reseau = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Mise à jour toutes les 15 min (minimum imposé par Android pour une tâche périodique). */
    fun programmer(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RafraichirWorker>(15, TimeUnit.MINUTES)
            .setConstraints(reseau)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Mise à jour immédiate (bouton du widget, ouverture de l'appli, jeton enregistré). */
    fun maintenant(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RafraichirWorker>()
            .setInputData(workDataOf(RafraichirWorker.MANUEL to true))
            .setConstraints(reseau)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, req)
    }

    fun arreter(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIQUE)
    }

    /**
     * Heures de trajet, du lundi au vendredi : 6h00 à 9h15 et 16h00 à 18h15
     * (plages des alertes trafic, avec un peu de marge avant le départ).
     */
    fun dansPlage(t: ZonedDateTime): Boolean {
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val h = t.toLocalTime()
        return h.entre(6, 0, 9, 15) || h.entre(16, 0, 18, 15)
    }

    private fun LocalTime.entre(h1: Int, m1: Int, h2: Int, m2: Int): Boolean =
        !isBefore(LocalTime.of(h1, m1)) && !isAfter(LocalTime.of(h2, m2))
}
