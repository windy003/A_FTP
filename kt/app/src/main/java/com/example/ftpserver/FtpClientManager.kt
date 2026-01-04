package com.example.ftpserver

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FtpClientManager {
    private var ftpClient: FTPClient? = null
    private var currentHost: String = ""
    private var currentPort: Int = 21

    // 待复制的文件列表
    data class ClipboardItem(
        val remotePath: String,
        val name: String,
        val isDirectory: Boolean
    )

    private val clipboard = mutableListOf<ClipboardItem>()

    fun connect(host: String, port: Int, username: String, password: String): Boolean {
        return try {
            disconnect()
            ftpClient = FTPClient().apply {
                connectTimeout = 10000
                defaultTimeout = 10000
                connect(host, port)
                login(username, password)
                enterLocalPassiveMode()
                setFileType(FTP.BINARY_FILE_TYPE)
                controlEncoding = "UTF-8"
            }
            currentHost = host
            currentPort = port
            ftpClient?.isConnected == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        try {
            ftpClient?.let {
                if (it.isConnected) {
                    it.logout()
                    it.disconnect()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ftpClient = null
    }

    fun isConnected(): Boolean {
        return ftpClient?.isConnected == true
    }

    fun getCurrentPath(): String {
        return try {
            ftpClient?.printWorkingDirectory() ?: "/"
        } catch (e: Exception) {
            "/"
        }
    }

    fun listFiles(path: String? = null): List<FTPFile> {
        return try {
            val targetPath = path ?: getCurrentPath()
            ftpClient?.listFiles(targetPath)?.toList() ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun changeDirectory(path: String): Boolean {
        return try {
            ftpClient?.changeWorkingDirectory(path) ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun changeToParentDirectory(): Boolean {
        return try {
            ftpClient?.changeToParentDirectory() ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 复制文件到剪贴板
    fun copyToClipboard(items: List<ClipboardItem>) {
        clipboard.clear()
        clipboard.addAll(items)
    }

    fun getClipboard(): List<ClipboardItem> = clipboard.toList()

    fun clearClipboard() {
        clipboard.clear()
    }

    fun hasClipboardItems(): Boolean = clipboard.isNotEmpty()

    // 下载单个文件
    fun downloadFile(remotePath: String, localFile: File, progressCallback: ((Long, Long) -> Unit)? = null): Boolean {
        return try {
            val outputStream = FileOutputStream(localFile)
            // 获取文件大小
            val files = ftpClient?.listFiles(remotePath)
            val fileSize = files?.firstOrNull()?.size ?: 0L

            ftpClient?.copyStreamListener = object : org.apache.commons.net.io.CopyStreamListener {
                override fun bytesTransferred(event: org.apache.commons.net.io.CopyStreamEvent) {}
                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    progressCallback?.invoke(totalBytesTransferred, fileSize)
                }
            }

            val success = ftpClient?.retrieveFile(remotePath, outputStream) ?: false
            outputStream.close()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 递归下载文件夹
    fun downloadDirectory(remotePath: String, localDir: File, progressCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val files = ftpClient?.listFiles(remotePath) ?: return false

            for (file in files) {
                if (file.name == "." || file.name == "..") continue

                val remoteFilePath = "$remotePath/${file.name}"
                val localFile = File(localDir, file.name)

                progressCallback?.invoke(file.name)

                if (file.isDirectory) {
                    downloadDirectory(remoteFilePath, localFile, progressCallback)
                } else {
                    downloadFile(remoteFilePath, localFile)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 粘贴剪贴板内容到本地目录
    fun pasteToLocal(localDir: File, progressCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            for (item in clipboard) {
                progressCallback?.invoke(item.name)
                val localFile = File(localDir, item.name)

                if (item.isDirectory) {
                    downloadDirectory(item.remotePath, localFile, progressCallback)
                } else {
                    downloadFile(item.remotePath, localFile)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
