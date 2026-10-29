package com.aiocw.selfktv.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiocw.selfktv.R
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Work

/** 作品：我的录音列表，可试听 / 删除。 */
class WorksFragment : Fragment(R.layout.fragment_works) {

    private val works = mutableListOf<Work>()
    private lateinit var adapter: WorkAdapter
    private var player: MediaPlayer? = null
    private var playingId = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        adapter = WorkAdapter(
            works,
            playingIdProvider = { playingId },
            onPlay = { togglePlay(it) },
            onDelete = { confirmDelete(it) },
            onItemClick = { openDetail(it) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        super.onPause()
        stopPlay()
    }

    private fun load() {
        val progress = view?.findViewById<ProgressBar>(R.id.progress)
        val tvEmpty = view?.findViewById<TextView>(R.id.tvEmpty)
        UiKit.bg {
            try {
                val list = ApiClient.works()
                UiKit.ui {
                    progress?.visibility = View.GONE
                    works.clear()
                    works.addAll(list)
                    adapter.notifyDataSetChanged()
                    tvEmpty?.visibility = if (works.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                UiKit.ui {
                    progress?.visibility = View.GONE
                    Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openDetail(work: Work) {
        startActivity(WorkDetailActivity.intentFor(requireContext(), work))
    }

    private fun togglePlay(work: Work) {
        if (playingId == work.id) {
            stopPlay()
            adapter.notifyDataSetChanged()
            return
        }
        stopPlay()
        try {
            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(
                    requireContext(),
                    Uri.parse(ApiClient.absolute(work.audioUrl)),
                    ApiClient.mediaHeaders()
                )
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    playingId = -1
                    adapter.notifyDataSetChanged()
                    release()
                    player = null
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(requireContext(), "播放失败", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            }
            player = p
            playingId = work.id
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlay() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingId = -1
    }

    private fun confirmDelete(work: Work) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除作品")
            .setMessage("确定删除「${work.title}」？")
            .setPositiveButton("删除") { _, _ ->
                UiKit.bg {
                    try {
                        ApiClient.deleteWork(work.id)
                        UiKit.ui {
                            if (playingId == work.id) stopPlay()
                            load()
                        }
                    } catch (e: Exception) {
                        UiKit.ui {
                            Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

class WorkAdapter(
    private val items: List<Work>,
    private val playingIdProvider: () -> Int,
    private val onPlay: (Work) -> Unit,
    private val onDelete: (Work) -> Unit,
    private val onItemClick: (Work) -> Unit
) : RecyclerView.Adapter<WorkAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title = view.findViewById<TextView>(R.id.tvTitle)
        val songTitle = view.findViewById<TextView>(R.id.tvSongTitle)
        val mode = view.findViewById<TextView>(R.id.tvMode)
        val duration = view.findViewById<TextView>(R.id.tvDuration)
        val date = view.findViewById<TextView>(R.id.tvDate)
        val btnPlay = view.findViewById<ImageView>(R.id.btnPlay)
        val btnDelete = view.findViewById<ImageView>(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_work, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val work = items[position]
        holder.title.text = work.title
        holder.songTitle.text = if (work.songTitle.isEmpty()) "自由演唱" else work.songTitle
        holder.mode.text = work.modeLabel
        holder.duration.text = UiKit.fmtDuration(work.duration)
        holder.date.text = work.createdAt.take(16)
        holder.btnPlay.setImageResource(
            if (playingIdProvider() == work.id) R.drawable.ic_pause else R.drawable.ic_play
        )
        holder.btnPlay.setOnClickListener { onPlay(work) }
        holder.btnDelete.setOnClickListener { onDelete(work) }
        holder.itemView.setOnClickListener { onItemClick(work) }
    }
}
