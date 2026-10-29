package de.sonoss1.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.sonoss1.app.ui.S1Theme
import de.sonoss1.app.ui.ShareScreen

/** Receives links shared from other apps and sends the audio to a Sonos room. */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels { ShareViewModel.factory((application as SonosApp).repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }.orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent?.getStringExtra(Intent.EXTRA_TITLE)
        vm.start(text, subject)
        setContent { S1Theme { ShareScreen(vm, onClose = ::finish) } }
    }
}
