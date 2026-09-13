package com.example.ftpserver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var btnClient: Button

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val MANAGE_STORAGE_REQUEST_CODE = 2
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 3
    }

    /** 接收 FtpService 的状态变化，刷新界面 */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra(FtpService.EXTRA_RUNNING, false) ?: false
            val message = intent?.getStringExtra(FtpService.EXTRA_MESSAGE) ?: ""
            updateUi(running, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        btnClient = findViewById(R.id.btnClient)

        toggleButton.setOnClickListener {
            if (FtpService.isRunning) {
                FtpService.stop(this)
            } else {
                checkPermissionAndStartServer()
            }
        }

        btnClient.setOnClickListener {
            val intent = Intent(this, FtpClientActivity::class.java)
            startActivity(intent)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(FtpService.ACTION_STATE_CHANGED)
        )

        // 应用启动时自动请求权限
        checkAndRequestPermissions()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateUi(FtpService.isRunning, FtpService.statusMessage)
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    private fun updateUi(running: Boolean, message: String) {
        statusText.text = message
        toggleButton.text = if (running) "停止服务器" else "启动服务器"
    }

    // Android 13+ 前台服务通知需要通知权限
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    // 请求忽略电池优化，避免锁屏后被系统冻结
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "请允许后台运行，锁屏后FTP才不会被系统关闭", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 应用启动时检查并请求权限
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要请求所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE)
                    Toast.makeText(this, "请授予所有文件访问权限以使用FTP服务器", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE)
                    Toast.makeText(this, "请授予所有文件访问权限以使用FTP服务器", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Android 10 及以下使用传统权限请求
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
            }
        }
    }

    private fun checkPermissionAndStartServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 检查所有文件访问权限
            if (Environment.isExternalStorageManager()) {
                startFtpServer()
            } else {
                checkAndRequestPermissions()
            }
        } else {
            // Android 10 及以下使用传统权限
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "已获得所有文件访问权限", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "需要所有文件访问权限才能使用FTP服务器", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startFtpServer() {
        // 启动前台服务，由服务持有 WakeLock/WifiLock，锁屏后继续运行
        requestIgnoreBatteryOptimizations()
        FtpService.start(this)
    }
}
