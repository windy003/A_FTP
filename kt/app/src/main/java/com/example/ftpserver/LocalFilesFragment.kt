package com.example.ftpserver

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var fabPaste: FloatingActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: LocalFileAdapter

    private var currentPath: File = Environment.getExternalStorageDirectory()

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
        fabPaste = view.findViewById(R.id.fabPaste)
        progressBar = view.findViewById(R.id.progressBar)

        adapter = LocalFileAdapter { file ->
            onFileClick(file)
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            goToParentDirectory()
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
        // 排序：文件夹在前，文件在后
        val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        adapter.setFiles(sortedFiles)

        updatePasteButtonVisibility()
    }

    private fun updatePasteButtonVisibility() {
        fabPaste.visibility = if (FtpClientManager.hasClipboardItems()) View.VISIBLE else View.GONE
    }

    private fun onFileClick(file: File) {
        if (file.isDirectory) {
            currentPath = file
            loadFiles()
        }
    }

    private fun goToParentDirectory() {
        currentPath.parentFile?.let { parent ->
            currentPath = parent
            loadFiles()
        }
    }

    private fun pasteFiles() {
        val clipboardItems = FtpClientManager.getClipboard()
        if (clipboardItems.isEmpty()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }

        val itemNames = clipboardItems.joinToString("\n") { "• ${it.name}" }

        AlertDialog.Builder(requireContext())
            .setTitle("确认粘贴")
            .setMessage("将以下 ${clipboardItems.size} 个项目下载到:\n${currentPath.absolutePath}\n\n$itemNames")
            .setPositiveButton("粘贴") { _, _ ->
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
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<LocalFileAdapter.ViewHolder>() {

    private val files = mutableListOf<File>()

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
        holder.checkbox.visibility = View.GONE

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

        holder.itemView.setOnClickListener {
            onItemClick(file)
        }
    }

    override fun getItemCount() = files.size

    fun setFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
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
