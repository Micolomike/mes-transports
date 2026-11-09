package fr.micolomike.mestransports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mise à jour lancée par le bouton ↻ du widget.
 *
 * Avant : le bouton passait par WorkManager, donc l'appel partait d'une tâche de fond.
 * One UI (économiseur de données, mise en veille des applis, données en arrière-plan
 * restreintes) coupe le réseau de ces tâches : PRIM était injoignable ("Pas de réseau"),
 * alors que le même appel fonctionnait depuis l'appli ouverte.
 *
 * Maintenant : un service de premier plan court (quelques secondes). Android autorise
 * son démarrage depuis un clic sur un widget, et un service de premier plan a accès au
 * réseau comme une appli ouverte.
 */
class ServiceActualiser : Service() {

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            // Démarrage au premier plan refusé : on repasse par WorkManager (ancien comportement)
            Planif.maintenant(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        TransportWidget.dessiner(this, enCours = true)
        portee.launch {
            try {
                MajPrim.executer(applicationContext)
            } finally {
                ServiceCompat.stopForeground(this@ServiceActualiser, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 14
        private const val CANAL = "maj"

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CANAL, "Mise à jour du widget", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
            }
            return NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_train)
                .setContentTitle("Mise à jour des transports…")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build()
        }
    }
}
