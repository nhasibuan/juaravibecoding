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

import android.os.Build
import com.example.server.LogUtility

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogUtility.initialize(this)

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogUtility.logError("UncaughtCrash", throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }

        LogUtility.logMessage("MainActivity", "onCreate initialized at ${System.currentTimeMillis()}")
        
        // Request notification permissions dynamically on Android 13+ to support foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 101)
            }
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
