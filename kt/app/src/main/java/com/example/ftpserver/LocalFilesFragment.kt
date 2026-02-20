package com.example.ftpserver

import android.os.Bundle
import android.os.Environment
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFilesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var textPath: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnCopy: Button
    private lateinit var fabPaste: FloatingActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: LocalFileAdapter

    private var currentPath: File = Environment.getExternalStorageDirectory()
    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<Int>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_local_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        textPath = view.findViewById(R.id.textPath)
        btnBack = view.findViewById(R.id.btnBack)
        btnCopy = view.findViewById(R.id.btnCopy)
        fabPaste = view.findViewById(R.id.fabPaste)
        progressBar = view.findViewById(R.id.progressBar)

        adapter = LocalFileAdapter(
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

        fabPaste.setOnClickListener {
            pasteFiles()
        }

        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        updatePasteButtonVisibility()
    }

    private fun loadFiles() {
        textPath.text = currentPath.absolutePath

        val files = currentPath.listFiles()?.toList() ?: emptyList()
        val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        adapter.setFiles(sortedFiles)

        updatePasteButtonVisibility()
    }

    private fun updatePasteButtonVisibility() {
        // 只在剪贴板有远程文件（服务器→本地方向）时显示下载按钮
        fabPaste.visibility = if (FtpClientManager.hasRemoteClipboardItems()) View.VISIBLE else View.GONE
    }

    private fun onFileClick(file: File) {
        if (isSelectionMode) {
            val index = adapter.getFiles().indexOf(file)
            if (index >= 0) adapter.toggleSelection(index)
        } else if (file.isDirectory) {
            currentPath = file
            loadFiles()
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

        val clipboardItems = selectedItems.map { index ->
            val file = files[index]
            FtpClientManager.ClipboardItem(
                localPath = file.absolutePath,
                name = file.name,
                isDirectory = file.isDirectory,
                isLocal = true
            )
        }

        FtpClientManager.copyToClipboard(clipboardItems)
        Toast.makeText(context, "已复制 ${clipboardItems.size} 个项目，请切换到服务器标签页粘贴", Toast.LENGTH_LONG).show()
        exitSelectionMode()
    }

    private fun goToParentDirectory() {
        currentPath.parentFile?.let { parent ->
            currentPath = parent
            loadFiles()
        }
    }

    // 供 FileManagerActivity 调用：系统返回手势时导航上层目录
    fun goBack() {
        goToParentDirectory()
    }

    private fun pasteFiles() {
        val clipboardItems = FtpClientManager.getClipboard().filter { !it.isLocal }
        if (clipboardItems.isEmpty()) {
            Toast.makeText(context, "没有可下载的服务器文件", Toast.LENGTH_SHORT).show()
            return
        }

        val itemNames = clipboardItems.joinToString("\n") { "• ${it.name}" }

        AlertDialog.Builder(requireContext())
            .setTitle("确认下载")
            .setMessage("将以下 ${clipboardItems.size} 个项目下载到:\n${currentPath.absolutePath}\n\n$itemNames")
            .setPositiveButton("下载") { _, _ ->
                performPaste()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performPaste() {
        progressBar.visibility = View.VISIBLE
        fabPaste.isEnabled = false

        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    FtpClientManager.pasteToLocal(currentPath) { fileName ->
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "正在下载: $fileName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (success) {
                    Toast.makeText(context, "下载完成！", Toast.LENGTH_SHORT).show()
                    FtpClientManager.clearClipboard()
                    loadFiles()
                } else {
                    Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "下载错误: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                fabPaste.isEnabled = true
                updatePasteButtonVisibility()
            }
        }
    }

    fun refresh() {
        loadFiles()
    }
}

class LocalFileAdapter(
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (Int) -> Boolean,
    private val onSelectionChanged: (Set<Int>) -> Unit
) : RecyclerView.Adapter<LocalFileAdapter.ViewHolder>() {

    private val files = mutableListOf<File>()
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
        holder.checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = selectedPositions.contains(position)

        if (file.isDirectory) {
            holder.iconFile.setImageResource(android.R.drawable.ic_menu_more)
            val itemCount = file.listFiles()?.size ?: 0
            holder.textInfo.text = "$itemCount 个项目"
        } else {
            holder.iconFile.setImageResource(android.R.drawable.ic_menu_gallery)
            val size = formatFileSize(file.length())
            val date = formatDate(file.lastModified())
            holder.textInfo.text = "$size | $date"
        }

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

    fun setFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    fun getFiles(): List<File> = files.toList()

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

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
