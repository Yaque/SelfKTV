package com.aiocw.selfktv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiocw.selfktv.R
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song

/** 歌房：歌曲列表 + 搜索 + 刷新（拉取服务器新上传/分离完成的歌曲）。 */
class SongsFragment : Fragment(R.layout.fragment_songs) {

    private val songs = mutableListOf<Song>()
    private lateinit var adapter: SongAdapter
    private var currentQuery = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)

        adapter = SongAdapter(songs) { song ->
            startActivity(
                Intent(requireContext(), SongDetailActivity::class.java)
                    .putExtra(SongDetailActivity.EXTRA_SONG_ID, song.id)
            )
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                load(progress, tvEmpty, swipe, silent = true)
            }
        })

        swipe.setOnRefreshListener { load(progress, tvEmpty, swipe, silent = true) }
        view.findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            load(progress, tvEmpty, swipe, silent = false)
        }

        load(progress, tvEmpty, swipe, silent = false)
    }

    private fun load(progress: ProgressBar, tvEmpty: TextView, swipe: SwipeRefreshLayout, silent: Boolean) {
        if (!silent) progress.visibility = View.VISIBLE
        UiKit.bg {
            try {
                val list = ApiClient.songs(currentQuery)
                UiKit.ui {
                    progress.visibility = View.GONE
                    swipe.isRefreshing = false
                    songs.clear()
                    songs.addAll(list)
                    adapter.notifyDataSetChanged()
                    tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = if (currentQuery.isEmpty()) "服务端还没有歌曲\n请在管理页面上传"
                                   else "没有找到「$currentQuery」相关歌曲"
                }
            } catch (e: Exception) {
                UiKit.ui {
                    progress.visibility = View.GONE
                    swipe.isRefreshing = false
                    Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** 歌曲列表适配器。 */
class SongAdapter(
    private val items: List<Song>,
    private val onClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover = view.findViewById<android.widget.ImageView>(R.id.ivCover)
        val title = view.findViewById<TextView>(R.id.tvTitle)
        val artist = view.findViewById<TextView>(R.id.tvArtist)
        val duration = view.findViewById<TextView>(R.id.tvDuration)
        val chipMix = view.findViewById<TextView>(R.id.chipMix)
        val chipVocal = view.findViewById<TextView>(R.id.chipVocal)
        val chipAccomp = view.findViewById<TextView>(R.id.chipAccomp)
        val chipSmart = view.findViewById<TextView>(R.id.chipSmart)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        holder.title.text = song.title
        holder.artist.text = buildString {
            append(song.artist.ifEmpty { "未知歌手" })
            if (song.separating) append("　⏳ 分离中…")
        }
        holder.duration.text = UiKit.fmtDuration(song.duration)
        UiKit.loadCover(song.coverUrl, holder.cover, R.drawable.bg_cover_placeholder)

        holder.chipMix.visibility = if (song.supportsMode(Song.MODE_MIX)) View.VISIBLE else View.GONE
        holder.chipVocal.visibility = if (song.supportsMode(Song.MODE_VOCAL)) View.VISIBLE else View.GONE
        holder.chipAccomp.visibility = if (song.supportsMode(Song.MODE_ACCOMP)) View.VISIBLE else View.GONE
        holder.chipSmart.visibility = if (song.supportsMode(Song.MODE_SMART)) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(song) }
    }
}
