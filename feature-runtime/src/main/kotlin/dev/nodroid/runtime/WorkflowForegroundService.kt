package dev.nodroid.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/** Optional user-started foreground service for actively running workflows. */
class WorkflowForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workflowId = intent?.getStringExtra(EXTRA_WORKFLOW_ID) ?: return START_NOT_STICKY
        val channelId = "nodroid_service"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "NodRoid Service", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NodRoid is running a workflow")
            .setContentText(workflowId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
        scope.launch {
            RuntimeHolder.runWorkflow(workflowId, "fg-" + UUID.randomUUID())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WORKFLOW_ID = "workflow_id"
        const val ACTION_START = "dev.nodroid.action.START_WORKFLOW"

        fun start(context: Context, workflowId: String) {
            val intent = Intent(context, WorkflowForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
