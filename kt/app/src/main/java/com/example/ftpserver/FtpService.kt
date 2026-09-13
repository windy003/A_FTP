package com.example.ftpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 以前台服务的方式承载 FTP 服务器，并持有 WakeLock / WifiLock，
 * 保证锁屏（黑屏）之后 CPU 与 Wi-Fi 不会休眠，服务继续可用。
 */
class FtpService : Service() {

    private var server: FtpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "com.example.ftpserver.action.START"
        const val ACTION_STOP = "com.example.ftpserver.action.STOP"

        /** 服务器状态变化广播，MainActivity 据此刷新界面 */
        const val ACTION_STATE_CHANGED = "com.example.ftpserver.action.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"

        const val PORT = 2121

        private const val CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 1001

        /** 供界面查询当前是否在运行 */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var statusMessage: String = "FTP服务器已停止"
            private set

        fun start(context: Context) {
            val intent = Intent(context, FtpService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFtpServer()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 先进入前台，避免 Android 8+ 的 5 秒限制
                startForeground(NOTIFICATION_ID, buildNotification("FTP服务器启动中..."))
                startFtpServer()
            }
        }
        // 进程被系统回收后自动重启，继续保持服务
        return START_STICKY
    }

    override fun onDestroy() {
        stopFtpServer()
        super.onDestroy()
    }

    // ---------------- FTP 服务器 ----------------

    private fun startFtpServer() {
        if (server?.isStopped == false) return

        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = PORT

            val userManager = PropertiesUserManagerFactory().createUserManager()

            val anonymousUser = BaseUser().apply {
                name = "anonymous"
                password = ""

                // 直接使用外部存储根目录（储存卡）
                val homeDir = Environment.getExternalStorageDirectory()
                homeDirectory = when {
                    homeDir?.exists() == true -> homeDir.absolutePath
                    getExternalFilesDir(null) != null -> getExternalFilesDir(null)!!.absolutePath
                    else -> filesDir.absolutePath
                }
            }

            anonymousUser.authorities = mutableListOf<Authority>(
                WritePermission(),
                ConcurrentLoginPermission(10, 10),
                TransferRatePermission(0, 0)
            )

            userManager.save(anonymousUser)
            serverFactory.userManager = userManager

            val configFactory = ConnectionConfigFactory()
            configFactory.isAnonymousLoginEnabled = true
            serverFactory.connectionConfig = configFactory.createConnectionConfig()

            serverFactory.addListener("default", listenerFactory.createListener())

            server = serverFactory.createServer()
            server?.start()

            acquireLocks()

            val ipAddress = getLocalIpAddress()
            val homeDir = Environment.getExternalStorageDirectory()
            val message = """
                FTP服务器运行中
                IP: $ipAddress
                端口: $PORT
                根目录: ${homeDir?.absolutePath}
                支持匿名访问
            """.trimIndent()

            updateState(true, message)
            updateNotification("ftp://$ipAddress:$PORT  锁屏后保持运行")
        } catch (e: Exception) {
            e.printStackTrace()
            releaseLocks()
            updateState(false, "启动服务器失败: ${e.message}")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopFtpServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server = null
        releaseLocks()
        updateState(false, "FTP服务器已停止")
    }

    // ---------------- 唤醒锁 ----------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FtpServer::CpuLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifiLock == null) {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(mode, "FtpServer::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null

        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wifiLock = null
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "FTP服务器",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "FTP服务器运行状态"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(content: String): Notification {
        createChannel()

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FtpService::class.java).apply { action = ACTION_STOP },
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("FTP服务器运行中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(contentIntent)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(null, "停止服务器", stopIntent).build()
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(0, "停止服务器", stopIntent)
        }

        return builder.build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------------- 状态广播 ----------------

    private fun updateState(running: Boolean, message: String) {
        isRunning = running
        statusMessage = message
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: "未知"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "未知"
    }
}
