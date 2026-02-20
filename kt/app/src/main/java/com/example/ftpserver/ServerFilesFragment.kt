package com.example.ftpserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPFile

class ServerFilesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var textPath: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnCopy: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var fabUpload: FloatingActionButton
    private lateinit var adapter: ServerFileAdapter

    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<Int>()
    private var isLoading = false  // 防止并发加载

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_server_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        textPath = view.findViewById(R.id.textPath)
        btnBack = view.findViewById(R.id.btnBack)
        btnCopy = view.findViewById(R.id.btnCopy)
        progressBar = view.findViewById(R.id.progressBar)
        fabUpload = view.findViewById(R.id.fabUpload)

        adapter = ServerFileAdapter(
            onItemClick = { file -> onFileClick(file) },
            onItemLongClick = { position -> onFileLongClick(position) },
            onSelectionChanged = { selected -> onSelectionChanged(selected) }
        )

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            goToParentDirectory()
        }

        btnCopy.setOnClickListener {
            copySelectedFiles()
        }

        fabUpload.setOnClickListener {
            uploadFiles()
        }

        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        if (::fabUpload.isInitialized) {
            updateUploadButtonVisibility()
        }
    }

    private fun loadFiles() {
        if (isLoading) return  // 防止并发加载
        isLoading = true
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val (path, files, error) = withContext(Dispatchers.IO) {
                    try {
                        val p = FtpClientManager.getCurrentPath()
                        val f = FtpClientManager.listFiles()
                        Triple(p, f, null)
                    } catch (e: Exception) {
                        Triple("/", emptyList<org.apache.commons.net.ftp.FTPFile>(), e.message)
                    }
                }

                if (error != null) {
                    Toast.makeText(context, "加载失败: $error", Toast.LENGTH_LONG).show()
                } else {
                    val filtered = files.filter { it.name != "." && it.name != ".." }
                    textPath.text = path
                    adapter.setFiles(filtered)
                    exitSelectionMode()
                    updateUploadButtonVisibility()
                    if (filtered.isEmpty()) {
                        Toast.makeText(context, "目录为空 (路径: $path)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "加载错误: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                isLoading = false
            }
        }
    }

    fun updateUploadButtonVisibility() {
        if (::fabUpload.isInitialized) {
            fabUpload.visibility = if (FtpClientManager.hasLocalClipboardItems()) View.VISIBLE else View.GONE
        }
    }

    // 供 Activity 切换标签时调用：只更新上传按钮，不重新加载文件
    fun refreshUploadButton() {
        updateUploadButtonVisibility()
    }

    private fun onFileClick(file: FTPFile) {
        if (isSelectionMode) {
            adapter.toggleSelection(adapter.getFiles().indexOf(file))
        } else if (file.isDirectory) {
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    FtpClientManager.changeDirectory(file.name)
                }
                if (success) {
                    loadFiles()
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, "无法进入目录", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onFileLongClick(position: Int): Boolean {
        if (!isSelectionMode) {
            enterSelectionMode()
        }
        adapter.toggleSelection(position)
        return true
    }

    private fun onSelectionChanged(selected: Set<Int>) {
        selectedItems.clear()
        selectedItems.addAll(selected)

        if (selected.isEmpty() && isSelectionMode) {
            exitSelectionMode()
        } else if (selected.isNotEmpty()) {
            btnCopy.text = "复制 (${selected.size})"
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        adapter.setSelectionMode(true)
        btnCopy.visibility = View.VISIBLE
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        adapter.setSelectionMode(false)
        adapter.clearSelection()
        selectedItems.clear()
        btnCopy.visibility = View.GONE
    }

    private fun copySelectedFiles() {
        val files = adapter.getFiles()
        val currentPath = textPath.text.toString()

        val clipboardItems = selectedItems.map { index ->
            val file = files[index]
            val remotePath = if (currentPath.endsWith("/")) {
                "$currentPath${file.name}"
            } else {
                "$currentPath/${file.name}"
            }
            FtpClientManager.ClipboardItem(
                remotePath = remotePath,
                name = file.name,
                isDirectory = file.isDirectory,
                isLocal = false
            )
        }

        FtpClientManager.copyToClipboard(clipboardItems)
        Toast.makeText(context, "已复制 ${clipboardItems.size} 个项目，请切换到本地标签页粘贴", Toast.LENGTH_LONG).show()
        exitSelectionMode()
    }

    // 上传本地剪贴板文件到当前服务器目录
    private fun uploadFiles() {
        val clipboardItems = FtpClientManager.getClipboard().filter { it.isLocal }
        if (clipboardItems.isEmpty()) {
            Toast.makeText(context, "没有可上传的本地文件", Toast.LENGTH_SHORT).show()
            return
        }

        val currentPath = textPath.text.toString()
        val itemNames = clipboardItems.joinToString("\n") { "• ${it.name}" }

        AlertDialog.Builder(requireContext())
            .setTitle("确认上传")
            .setMessage("将以下 ${clipboardItems.size} 个项目上传到:\n$currentPath\n\n$itemNames")
            .setPositiveButton("上传") { _, _ ->
                performUpload(currentPath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performUpload(remotePath: String) {
        progressBar.visibility = View.VISIBLE
        fabUpload.isEnabled = false

        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    FtpClientManager.pasteToRemote(remotePath) { fileName ->
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "正在上传: $fileName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (success) {
                    Toast.makeText(context, "上传完成！", Toast.LENGTH_SHORT).show()
                    FtpClientManager.clearClipboard()
                    loadFiles()
                } else {
                    Toast.makeText(context, "上传失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "上传错误: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                fabUpload.isEnabled = true
                updateUploadButtonVisibility()
            }
        }
    }

    private fun goToParentDirectory() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                FtpClientManager.changeToParentDirectory()
            }
            if (success) {
                loadFiles()
            } else {
                progressBar.visibility = View.GONE
            }
        }
    }

    // 供 FileManagerActivity 调用：系统返回手势时导航上层目录
    fun goBack() {
        goToParentDirectory()
    }

    fun refresh() {
        loadFiles()
    }
}

class ServerFileAdapter(
    private val onItemClick: (FTPFile) -> Unit,
    private val onItemLongClick: (Int) -> Boolean,
    private val onSelectionChanged: (Set<Int>) -> Unit
) : RecyclerView.Adapter<ServerFileAdapter.ViewHolder>() {

    private val files = mutableListOf<FTPFile>()
    private var isSelectionMode = false
    private val selectedPositions = mutableSetOf<Int>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconFile: android.widget.ImageView = view.findViewById(R.id.iconFile)
        val textName: TextView = view.findViewById(R.id.textName)
        val textInfo: TextView = view.findViewById(R.id.textInfo)
        val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]

        holder.textName.text = file.name

        if (file.isDirectory) {
            holder.iconFile.setImageResource(android.R.drawable.ic_menu_more)
            holder.textInfo.text = "文件夹"
        } else {
            holder.iconFile.setImageResource(android.R.drawable.ic_menu_gallery)
            holder.textInfo.text = formatFileSize(file.size)
        }

        holder.checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = selectedPositions.contains(position)

        holder.checkbox.setOnClickListener {
            toggleSelection(position)
        }

        holder.itemView.setOnClickListener {
            onItemClick(file)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(position)
        }
    }

    override fun getItemCount() = files.size

    fun setFiles(newFiles: List<FTPFile>) {
        files.clear()
        files.addAll(newFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        notifyDataSetChanged()
    }

    fun getFiles(): List<FTPFile> = files.toList()

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged(selectedPositions.toSet())
    }

    fun clearSelection() {
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
