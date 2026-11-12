package com.rxinfiles.tool

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 文件列表适配器。
 *
 * 除了正常的列表展示，还承担两件事：
 *  - **多选模式**的渲染：进入多选后，右侧的进入箭头/播放图标会换成勾选框，
 *    点击变成选中而不是打开；
 *  - **定位高亮**：从外部「用其他应用打开」进来时，把用户带到的那一行亮一下。
 *
 * 这两套状态**互相独立**（[selected] vs [revealPath]），绝不能混在一起 ——
 * 详见 [setReveal] 与 [ViewHolder.bind] 里的优先级说明。
 */
class FileListAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()

    /** 多选模式 */
    private var selectionMode = false

    /** 已选路径 */
    private var selected: Set<String> = emptySet()

    /**
     * 「定位到该文件」要亮的路径（来自外部打开入口）；null 表示没有。
     *
     * **与多选态 [selected] 彻底分开**：它只是「刚才把用户带到哪一行」的临时标记，
     * 绝不可进多选集合 —— 否则用户一进多选就会看到莫名其妙的选中项。
     * 两者同时命中时以**多选为准**（见 [ViewHolder.bind]）。
     */
    private var revealPath: String? = null

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelection(mode: Boolean, paths: Set<String>) {
        val changed = mode != selectionMode
        selectionMode = mode
        selected = paths
        if (changed) {
            notifyDataSetChanged()
        } else {
            // 只更新勾选框
            for (i in items.indices) {
                notifyItemChanged(i)
            }
        }
    }

    /**
     * 设置要「亮一下」的路径（null 表示清掉）。
     *
     * 刻意**只动 [revealPath]**，绝不碰 [selected] —— 定位与多选是两件事。
     */
    fun setReveal(path: String?) {
        if (revealPath == path) return
        revealPath = path
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val fileDetail: TextView = itemView.findViewById(R.id.fileDetail)
        private val fileAction: ImageView = itemView.findViewById(R.id.fileAction)

        fun bind(item: FileItem) {
            fileName.text = item.name
            fileIcon.setImageResource(iconFor(item))
            fileDetail.text = buildDetail(item)

            // 多选态下「这一行能不能被勾选」：只在多选模式且不是 ".." 时成立。
            val checkable = selectionMode && item.name != ".."
            val checked = checkable && selected.contains(item.path)

            // 「定位高亮」是一条**独立**的视觉通道：只看 [revealPath]，绝不看 [selected]。
            // 但它比多选低一级 —— 多选是用户此刻正在进行的操作，两者同时命中时以多选为准。
            // 所以这里用 `!checkable` 把多选态整个排除掉：进了多选就不再显示定位底色，
            // 免得用户把「刚才定位到的那行」误当成本次选中的一行。
            val revealed = !checkable && revealPath != null && item.path == revealPath

            if (checkable) {
                fileAction.setImageResource(
                    if (checked) {
                        android.R.drawable.checkbox_on_background
                    } else {
                        android.R.drawable.checkbox_off_background
                    }
                )
                fileAction.visibility = View.VISIBLE
            } else {
                // 目录显示进入箭头；脚本显示播放符号，暗示「点击即在终端执行」
                fileAction.setImageResource(
                    if (item.isScript && !item.isDirectory) R.drawable.ic_play
                    else R.drawable.ic_chevron_right
                )
                fileAction.visibility =
                    if (item.isDirectory || item.isScript) View.VISIBLE else View.INVISIBLE
            }

            // 卡片背景的优先级：多选 > 外部定位 > 默认。
            itemView.setBackgroundResource(
                when {
                    checked -> R.drawable.bg_file_row_selected
                    revealed -> R.drawable.bg_file_row_revealed
                    else -> R.drawable.bg_file_row
                }
            )

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        /** 按类型给图标：图片/视频/文本/压缩包/脚本/目录/普通文件 */
        private fun iconFor(item: FileItem): Int = when {
            item.name == ".." -> R.drawable.ic_folder
            item.isDirectory -> R.drawable.ic_folder
            FileRepository.isImageName(item.name) -> R.drawable.ic_image
            FileRepository.isVideoName(item.name) -> R.drawable.ic_video
            FileRepository.isArchiveName(item.name) -> R.drawable.ic_archive
            item.isScript -> R.drawable.ic_script
            FileRepository.isTextName(item.name) -> R.drawable.ic_text
            else -> R.drawable.ic_file
        }

        private fun buildDetail(item: FileItem): String {
            if (item.isDirectory) {
                return FileRepository.describeType(item.name, true) + " · " + item.path
            }
            val size = FileRepository.formatSize(item.size)
            val time = if (item.lastModified > 0) {
                android.text.format.DateFormat.format("MM/dd HH:mm", item.lastModified).toString()
            } else {
                ""
            }
            val type = FileRepository.describeType(item.name, false)
            return if (time.isEmpty()) "$type · $size" else "$type · $size · $time"
        }
    }

}
