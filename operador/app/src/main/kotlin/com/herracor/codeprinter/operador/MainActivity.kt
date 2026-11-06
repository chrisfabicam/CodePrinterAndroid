package com.herracor.codeprinter.operador

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herracor.codeprinter.operador.data.SettingsStore
import com.herracor.codeprinter.operador.ui.OperadorScreen
import com.herracor.codeprinter.operador.ui.OperadorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface {
                    val vm: OperadorViewModel = viewModel(
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(
                                modelClass: Class<T>
                            ): T = OperadorViewModel(SettingsStore(applicationContext)) as T
                        }
                    )
                    OperadorScreen(vm)
                }
            }
        }
    }
}
