package de.sonoss1.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.sonoss1.app.ui.MainScreen
import de.sonoss1.app.ui.S1Theme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.factory((application as SonosApp).repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { S1Theme { MainScreen(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.startPolling()
    }

    override fun onStop() {
        vm.stopPolling()
        super.onStop()
    }
}
