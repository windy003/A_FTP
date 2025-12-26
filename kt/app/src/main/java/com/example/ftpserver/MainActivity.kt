package com.example.ftpserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private var server: FtpServer? = null
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    companion object {
        private const val PORT = 2121
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (server?.isStopped == false) {
                stopFtpServer()
            } else {
                checkPermissionAndStartServer()
            }
        }
    }

    private fun checkPermissionAndStartServer() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startFtpServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty()) {
                val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allPermissionsGranted) {
                    startFtpServer()
                } else {
                    // 处理权限被拒绝的情况
                    Toast.makeText(this, "需要所有权限才能启动服务器", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startFtpServer() {
        try {
            val serverFactory = FtpServerFactory()
            val factory = ListenerFactory()

            factory.port = PORT

            val userManagerFactory = PropertiesUserManagerFactory()
            val userManager = userManagerFactory.createUserManager()

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

            val authorities = mutableListOf<Authority>(
                WritePermission(),
                ConcurrentLoginPermission(10, 10),
                TransferRatePermission(0, 0)
            )
            anonymousUser.authorities = authorities

            userManager.save(anonymousUser)
            serverFactory.userManager = userManager

            val configFactory = ConnectionConfigFactory()
            configFactory.isAnonymousLoginEnabled = true
            serverFactory.connectionConfig = configFactory.createConnectionConfig()

            serverFactory.addListener("default", factory.createListener())

            server = serverFactory.createServer()
            server?.start()

            val ipAddress = getLocalIpAddress()
            val homeDir = Environment.getExternalStorageDirectory()
            statusText.text = """
                FTP服务器运行中
                IP: $ipAddress
                端口: $PORT
                根目录: ${homeDir?.absolutePath}
                支持匿名访问
            """.trimIndent()
            toggleButton.text = "停止服务器"

        } catch (e: Exception) {
            e.printStackTrace()
            statusText.text = "启动服务器失败: ${e.message}"
        }
    }

    private fun stopFtpServer() {
        server?.let {
            it.stop()
            statusText.text = "FTP服务器已停止"
            toggleButton.text = "启动服务器"
        }
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
