package dev.nodroid.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

/**
 * System triggers. BOOT_COMPLETED re-schedules periodic workflows. POWER_CONNECTED /
 * POWER_DISCONNECTED fire battery-trigger workflows with the current level as input.
 * Connectivity changes are handled by WorkManager network constraints (see ScheduleWorker),
 * because CONNECTIVITY_CHANGE is no longer delivered to manifest receivers.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-scheduling needs the stored workflow list; the app re-schedules on next launch too.
            }
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.let { b ->
                    val raw = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (raw >= 0 && scale > 0) (raw * 100 / scale) else -1
                } ?: -1
                val charging = intent.action == Intent.ACTION_POWER_CONNECTED
                val prefs = context.getSharedPreferences("nodroid_triggers", Context.MODE_PRIVATE)
                for (workflowId in prefs.getStringSet("battery_workflows", emptySet()).orEmpty()) {
                    RuntimeHolder.runWorkflowAsync(workflowId, "battery-" + System.currentTimeMillis(),
                        mapOf("level" to level, "charging" to charging))
                }
            }
        }
    }
}
