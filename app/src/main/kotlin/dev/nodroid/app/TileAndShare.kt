package dev.nodroid.app

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity

/** Quick Settings tile: opens NodRoid and runs the first manual workflow. */
class NodRoidTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_RUN_TILE, true)
        }
        startActivity(intent)
    }
}

/** Share Sheet entry point: forwards shared text / image URIs to MainActivity. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val sharedStream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        val forward = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHARED_TEXT, sharedText)
            putExtra(MainActivity.EXTRA_SHARED_URI, sharedStream?.toString())
        }
        startActivity(forward)
        finish()
    }
}
