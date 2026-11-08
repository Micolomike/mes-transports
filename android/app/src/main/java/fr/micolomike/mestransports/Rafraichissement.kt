package fr.micolomike.mestransports

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
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
        val chaine = inputData.getString(CHAINE)
        val plages = Stockage.plagesSeulement(ctx)

        // Mise à jour automatique hors des heures de trajet : rien à faire (batterie et quota PRIM)
        if (!manuel && plages && !Planif.dansPlage(ZonedDateTime.now(Prim.PARIS))) {
            TransportWidget.dessiner(ctx)
            return Result.success()
        }

        if (!manuel) {
            // Dans la plage : maillon suivant de la chaîne de 5 minutes
            if (plages) Planif.armerChaine(ctx, chaine)
            // Évite un double appel si la tâche de secours (15 min) tombe juste après un maillon
            val ecart = System.currentTimeMillis() - Stockage.dernierAppelAuto(ctx)
            if (ecart in 0 until Planif.ECART_MINI_MS) {
                TransportWidget.dessiner(ctx)
                return Result.success()
            }
            Stockage.enregistrerAppelAuto(ctx, System.currentTimeMillis())
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
        const val CHAINE = "chaine"
    }
}

object Planif {
    private const val PERIODIQUE = "maj_periodique"
    private const val IMMEDIATE = "maj_immediate"
    private const val CHAINE_A = "maj_chaine_a"
    private const val CHAINE_B = "maj_chaine_b"

    /** Intervalle dans la plage : 5 minutes (chaîne de tâches uniques, Android refuse moins de 15 min en périodique). */
    private const val INTERVALLE_MIN = 5L
    const val ECART_MINI_MS = 4 * 60 * 1000L

    private val reseau = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Tâche de secours toutes les 15 min (minimum imposé par Android pour une tâche périodique).
     * Elle relance la chaîne de 5 minutes à l'entrée dans la plage ou si la chaîne s'est interrompue.
     */
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
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIQUE)
        wm.cancelUniqueWork(CHAINE_A)
        wm.cancelUniqueWork(CHAINE_B)
    }

    /**
     * Programme le prochain maillon dans 5 minutes. Deux noms alternés pour qu'un maillon
     * ne s'annule pas lui-même. Depuis la tâche de secours (depuis == null), ne fait rien
     * si un maillon est déjà en attente, pour ne jamais avoir deux chaînes en parallèle.
     */
    fun armerChaine(ctx: Context, depuis: String?) {
        val wm = WorkManager.getInstance(ctx)
        if (depuis == null) {
            val actif = listOf(CHAINE_A, CHAINE_B).any { nom ->
                wm.getWorkInfosForUniqueWork(nom).get().any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
            }
            if (actif) return
        }
        val suivant = if (depuis == CHAINE_A) CHAINE_B else CHAINE_A
        val req = OneTimeWorkRequestBuilder<RafraichirWorker>()
            .setInitialDelay(INTERVALLE_MIN, TimeUnit.MINUTES)
            .setInputData(workDataOf(RafraichirWorker.CHAINE to suivant))
            .setConstraints(reseau)
            .build()
        wm.enqueueUniqueWork(suivant, ExistingWorkPolicy.REPLACE, req)
    }

    /** Plage d'interrogation, du lundi au vendredi : 5h00 à 19h00 (identique au widget Windows). */
    fun dansPlage(t: ZonedDateTime): Boolean {
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val h = t.toLocalTime()
        return !h.isBefore(LocalTime.of(5, 0)) && h.isBefore(LocalTime.of(19, 0))
    }
}
