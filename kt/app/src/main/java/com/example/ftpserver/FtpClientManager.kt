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

    // 剪贴板项目：isLocal=true 表示本地文件（准备上传），false 表示远程文件（准备下载）
    data class ClipboardItem(
        val remotePath: String = "",   // 远程文件路径（服务器→本地时使用）
        val localPath: String = "",    // 本地文件路径（本地→服务器时使用）
        val name: String,
        val isDirectory: Boolean,
        val isLocal: Boolean = false   // true=本地文件，false=远程文件
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
            if (path != null) {
                ftpClient?.listFiles(path)?.toList() ?: emptyList()
            } else {
                // 不传路径，直接列出当前工作目录，兼容性更好
                ftpClient?.listFiles()?.toList() ?: emptyList()
            }
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

    // 剪贴板中是否有远程文件（服务器→本地方向）
    fun hasRemoteClipboardItems(): Boolean = clipboard.any { !it.isLocal }

    // 剪贴板中是否有本地文件（本地→服务器方向）
    fun hasLocalClipboardItems(): Boolean = clipboard.any { it.isLocal }

    // ========== 下载（服务器→本地）==========

    fun downloadFile(remotePath: String, localFile: File, progressCallback: ((Long, Long) -> Unit)? = null): Boolean {
        return try {
            val outputStream = FileOutputStream(localFile)
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

    fun pasteToLocal(localDir: File, progressCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            for (item in clipboard) {
                if (item.isLocal) continue
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

    // ========== 上传（本地→服务器）==========

    fun uploadFile(localFile: File, remotePath: String): Boolean {
        return try {
            val inputStream = FileInputStream(localFile)
            val success = ftpClient?.storeFile(remotePath, inputStream) ?: false
            inputStream.close()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun uploadDirectory(localDir: File, remotePath: String, progressCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            ftpClient?.makeDirectory(remotePath)
            val files = localDir.listFiles() ?: return false

            for (file in files) {
                progressCallback?.invoke(file.name)
                val remoteFilePath = "$remotePath/${file.name}"
                if (file.isDirectory) {
                    uploadDirectory(file, remoteFilePath, progressCallback)
                } else {
                    uploadFile(file, remoteFilePath)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun pasteToRemote(remotePath: String, progressCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            for (item in clipboard) {
                if (!item.isLocal) continue
                progressCallback?.invoke(item.name)
                val remoteFilePath = if (remotePath.endsWith("/")) {
                    "$remotePath${item.name}"
                } else {
                    "$remotePath/${item.name}"
                }
                val localFile = File(item.localPath)
                if (item.isDirectory) {
                    uploadDirectory(localFile, remoteFilePath, progressCallback)
                } else {
                    uploadFile(localFile, remoteFilePath)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
