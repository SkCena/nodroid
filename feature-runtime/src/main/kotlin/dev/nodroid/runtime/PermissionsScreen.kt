package dev.nodroid.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Permissions helper screen. Only safe permissions are ever requested here. */
@Composable
fun PermissionsScreen(context: Context) {
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Permissions")
        Text(text = "Notifications: " + if (notificationGranted) "granted" else "not granted", modifier = Modifier.padding(top = 8.dp))
        Text(text = "NodRoid needs notification permission only to show workflow alerts. Internet access is used only by the HTTP / LLM nodes you add yourself.", modifier = Modifier.padding(top = 8.dp))
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.padding(top = 12.dp)) {
                Text(text = "Grant notifications")
            }
        }
        Text(text = "No SMS, call log, accessibility or app-list permission is requested — those features do not exist in NodRoid.", modifier = Modifier.padding(top = 12.dp))
    }
}
