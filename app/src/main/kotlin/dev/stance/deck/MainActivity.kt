package dev.stance.deck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : ComponentActivity() {
    private val vm: DeckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.onStart()
            override fun onStop(owner: LifecycleOwner) = vm.onStop()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        setContent { StanceApp(vm) }
        if (savedInstanceState == null) vm.shareIn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.shareIn(intent)
    }
}
