package dev.ronova.celestia

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.ronova.celestia.ui.App
import dev.ronova.celestia.ui.CelestiaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CelestiaTheme { App(vm) } }
    }
}
