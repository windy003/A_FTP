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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var adapter: ServerFileAdapter

    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<Int>()

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

        loadFiles()
    }

    private fun loadFiles() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    FtpClientManager.getCurrentPath()
                }
                val files = withContext(Dispatchers.IO) {
                    FtpClientManager.listFiles()
                }

                textPath.text = path
                adapter.setFiles(files.filter { it.name != "." && it.name != ".." })
                exitSelectionMode()
            } catch (e: Exception) {
                Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun onFileClick(file: FTPFile) {
        if (isSelectionMode) {
            // 在选择模式下，点击切换选择状态
            adapter.toggleSelection(adapter.getFiles().indexOf(file))
        } else if (file.isDirectory) {
            // 进入目录
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
                isDirectory = file.isDirectory
            )
        }

        FtpClientManager.copyToClipboard(clipboardItems)
        Toast.makeText(context, "已复制 ${clipboardItems.size} 个项目，请切换到本地标签页粘贴", Toast.LENGTH_LONG).show()
        exitSelectionMode()
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
        // 排序：文件夹在前，文件在后
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
