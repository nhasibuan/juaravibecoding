package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.GatewayScreen
import com.example.ui.GatewayViewModel
import com.example.ui.theme.AiProxyGatewayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val folder = getExternalFilesDir(null)
                if (folder != null) {
                    if (!folder.exists()) folder.mkdirs()
                    val file = java.io.File(folder, "crash_log.txt")
                    val trace = android.util.Log.getStackTraceString(throwable)
                    file.writeText(trace)
                }
            } catch (e: Exception) {
                // ignore
            }
            originalHandler?.uncaughtException(thread, throwable)
        }

        try {
            val folder = getExternalFilesDir(null)
            if (folder != null) {
                if (!folder.exists()) folder.mkdirs()
                java.io.File(folder, "startup_log.txt").writeText("MainActivity onCreate initialized at ${System.currentTimeMillis()}")
            }
        } catch (e: Exception) {
            // ignore
        }
        
        // Supports borderless status and navigation bars content rendering
        enableEdgeToEdge()
        
        setContent {
            AiProxyGatewayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val vm = viewModel<GatewayViewModel>()
                        GatewayScreen(viewModel = vm)
                    }
                }
            }
        }
    }
}
