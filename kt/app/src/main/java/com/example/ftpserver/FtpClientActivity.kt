package com.example.ftpserver

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FtpClientActivity : AppCompatActivity() {

    private lateinit var editIp: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var textStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ftp_client)

        supportActionBar?.title = "FTP客户端"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editIp = findViewById(R.id.editIp)
        editPort = findViewById(R.id.editPort)
        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)
        btnConnect = findViewById(R.id.btnConnect)
        textStatus = findViewById(R.id.textStatus)

        btnConnect.setOnClickListener {
            connectToFtp()
        }
    }

    private fun connectToFtp() {
        val ip = editIp.text.toString().trim()
        val portStr = editPort.text.toString().trim()
        val username = editUsername.text.toString().trim().ifEmpty { "anonymous" }
        val password = editPassword.text.toString()

        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入服务器IP地址", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull() ?: 21

        textStatus.text = "正在连接..."
        btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    FtpClientManager.connect(ip, port, username, password)
                }

                if (success) {
                    textStatus.text = "连接成功！"
                    // 跳转到文件管理界面
                    val intent = Intent(this@FtpClientActivity, FileManagerActivity::class.java)
                    startActivity(intent)
                } else {
                    textStatus.text = "连接失败，请检查服务器地址和端口"
                }
            } catch (e: Exception) {
                textStatus.text = "连接错误: ${e.message}"
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
