package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GatewayForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var repository: GatewayRepository? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val CHANNEL_ID = "gateway_foreground_service_channel"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.example.server.ACTION_START"
        const val ACTION_STOP = "com.example.server.ACTION_STOP"
        const val EXTRA_PORT = "com.example.server.EXTRA_PORT"

        fun startService(context: Context, port: Int) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                LogUtility.logError("GatewayForegroundServiceStart", t)
                try {
                    context.startService(intent)
                } catch (e: Throwable) {
                    LogUtility.logError("GatewayForegroundServiceStartFallback", e)
                }
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                LogUtility.logError("GatewayForegroundServiceStopCall", t)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(applicationContext)
        repository = GatewayRepository(db)
        ProxyServerManager.initialize(repository!!, applicationContext)
        createNotificationChannel()

        // Initialize WifiLock to keep socket layer alive on standby
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GatewayWifiLock")
        } catch (t: Throwable) {
            LogUtility.logError("GatewayForegroundServiceWifiLockInit", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            LogUtility.logMessage("GatewayForegroundService", "Stop command received via intent.")
            stopForegroundService()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        LogUtility.logMessage("GatewayForegroundService", "Initializing gateway foreground persistent task on port $port")

        val notification = createNotification(port)
        var foregroundStarted = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    foregroundStarted = true
                } catch (t: Throwable) {
                    LogUtility.logError("GatewayForegroundServiceStartSpecialUse", t)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                            foregroundStarted = true
                        } catch (e: Throwable) {
                            LogUtility.logError("GatewayForegroundServiceStartDataSync", e)
                        }
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            }
        } catch (t: Throwable) {
            LogUtility.logError("GatewayForegroundServiceStartForeground", t)
        }

        if (!foregroundStarted) {
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } catch (e: Throwable) {
                LogUtility.logError("GatewayForegroundServiceStartForegroundFallback", e)
            }
        }

        if (!foregroundStarted) {
            LogUtility.logMessage("GatewayForegroundService", "Could not request foreground status. Launching inside default execution thread context.")
            stopSelf()
        }

        // Acquire WifiLock to prevent network drops
        try {
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    LogUtility.logMessage("GatewayForegroundService", "High performance WifiLock acquired.")
                }
            }
        } catch (t: Throwable) {
            LogUtility.logError("GatewayForegroundServiceWifiLockAcquire", t)
        }

        // Engage HTTP server via our model manager
        ProxyServerManager.startServer(port)

        return START_STICKY
    }

    private fun stopForegroundService() {
        ProxyServerManager.stopServer()
        releaseWifiLock()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            LogUtility.logError("GatewayForegroundServiceStopForeground", t)
        }
        stopSelf()
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    LogUtility.logMessage("GatewayForegroundService", "WifiLock released.")
                }
            }
        } catch (t: Throwable) {
            LogUtility.logError("GatewayForegroundServiceWifiLockRelease", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWifiLock()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI Local Gateway Core Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Signals the runtime status, port coordinates, and active local LLM models container."
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (t: Throwable) {
                LogUtility.logError("GatewayForegroundServiceCreateChannel", t)
            }
        }
    }

    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action intent for user convenience
        val stopSeqIntent = Intent(this, GatewayForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            5,
            stopSeqIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Active AI Local Gateway Running")
            .setContentText("Listening for incoming OpenAI calls securely on port $port")
            .setSmallIcon(android.R.drawable.stat_sys_download_done) // system fallback icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deactivate Core", stopPendingIntent)
            .build()
    }
}
