package com.aiocw.selfktv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiocw.selfktv.R
import com.aiocw.selfktv.audio.ListenCache
import com.aiocw.selfktv.audio.ListenPlayer
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.ListenPlaylist
import com.aiocw.selfktv.net.Song

/**
 * 听歌：顶部播放器卡 + 队列 / 收藏 / 歌单 / 歌曲 分段列表。
 *
 * - 队列：点行从该首播放；移出队列；清空队列；变更后整份 PUT 同步服务器
 * - 收藏：点行播放；取消收藏；加入队列
 * - 歌单：新建 / 重命名 / 删除 / 播放全部 / 详情（播放、移除）
 * - 歌曲：搜索 + 行操作对话框（播放 / 加入队列 / 收藏 / 加入歌单）
 */
class ListenFragment : Fragment(R.layout.fragment_listen) {

    private enum class Seg { QUEUE, FAV, PLAYLISTS, SONGS }

    private var seg = Seg.QUEUE
    private var openPlaylist: ListenPlaylist? = null

    private val detailSongs = mutableListOf<Song>()
    private val favSongs = mutableListOf<Song>()
    private val favIds = mutableSetOf<Int>()
    private val playlists = mutableListOf<ListenPlaylist>()
    private val searchSongs = mutableListOf<Song>()
    private var currentQuery = ""

    private lateinit var songAdapter: ListenSongAdapter
    private lateinit var playlistAdapter: ListenPlaylistAdapter

    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCache: TextView
    private lateinit var tvFav: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeCur: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvPlayPause: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var btnAction: TextView
    private lateinit var listHeader: View
    private lateinit var playlistHeader: View
    private lateinit var tvPlaylistName: TextView
    private lateinit var searchRow: View
    private lateinit var etSearch: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())

    /** 进度条拖动中：ticker / bindPlayer 不回写，避免跳动。 */
    private var isTracking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (isAdded && ListenPlayer.isPlaying && !isTracking) {
                val pos = ListenPlayer.positionMs.coerceIn(0, seekBar.max)
                seekBar.progress = pos
                tvTimeCur.text = UiKit.fmtTime(ListenPlayer.positionMs.toLong())
            }
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : ListenPlayer.Listener {
        override fun onSongChanged() {
            if (isAdded) bindPlayer()
        }

        override fun onPlayStateChanged() {
            if (isAdded) bindPlayer()
        }

        override fun onQueueChanged() {
            if (isAdded && seg == Seg.QUEUE && openPlaylist == null) refreshList()
        }

        override fun onError(msg: String) {
            toast(msg)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ivCover = view.findViewById(R.id.ivCover)
        tvTitle = view.findViewById(R.id.tvTitle)
        tvArtist = view.findViewById(R.id.tvArtist)
        tvCache = view.findViewById(R.id.tvCache)
        tvFav = view.findViewById(R.id.tvFav)
        seekBar = view.findViewById(R.id.seekBar)
        tvTimeCur = view.findViewById(R.id.tvTimeCur)
        tvTimeTotal = view.findViewById(R.id.tvTimeTotal)
        tvPlayPause = view.findViewById(R.id.tvPlayPause)
        tvListTitle = view.findViewById(R.id.tvListTitle)
        btnAction = view.findViewById(R.id.btnAction)
        listHeader = view.findViewById(R.id.listHeader)
        playlistHeader = view.findViewById(R.id.playlistHeader)
        tvPlaylistName = view.findViewById(R.id.tvPlaylistName)
        searchRow = view.findViewById(R.id.searchRow)
        etSearch = view.findViewById(R.id.etSearch)
        recycler = view.findViewById(R.id.recycler)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        progress = view.findViewById(R.id.progress)

        songAdapter = ListenSongAdapter(
            items = { currentSongs() },
            onClick = { onSongClick(it) },
            onMore = { onSongMore(it) },
            onOpenDetail = { openDetail(it) }
        )
        playlistAdapter = ListenPlaylistAdapter(
            items = { playlists },
            onClick = { openPlaylist(it) },
            onMore = { onPlaylistMore(it) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = songAdapter

        view.findViewById<TextView>(R.id.tabQueue).setOnClickListener { setSeg(Seg.QUEUE) }
        view.findViewById<TextView>(R.id.tabFav).setOnClickListener { setSeg(Seg.FAV) }
        view.findViewById<TextView>(R.id.tabPlaylists).setOnClickListener { setSeg(Seg.PLAYLISTS) }
        view.findViewById<TextView>(R.id.tabSongs).setOnClickListener { setSeg(Seg.SONGS) }

        view.findViewById<TextView>(R.id.tvPlayPause).setOnClickListener {
            ListenPlayer.toggle()
            bindPlayer()
        }
        view.findViewById<TextView>(R.id.tvPrev).setOnClickListener { ListenPlayer.previous() }
        view.findViewById<TextView>(R.id.tvNext).setOnClickListener { ListenPlayer.next() }
        tvFav.setOnClickListener { ListenPlayer.current?.let { toggleFav(it) } }

        // 点封面 / 歌名 / 歌名右侧空白区 → 歌曲详情页（歌词页）
        val openCurrentDetail = View.OnClickListener {
            ListenPlayer.current?.let { openDetail(it) }
        }
        ivCover.setOnClickListener(openCurrentDetail)
        tvTitle.setOnClickListener(openCurrentDetail)
        view.findViewById<View>(R.id.metaBlock).setOnClickListener(openCurrentDetail)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tvTimeCur.text = UiKit.fmtTime(p.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isTracking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                ListenPlayer.seekTo(sb?.progress ?: 0)
            }
        })

        btnAction.setOnClickListener { onListAction() }
        view.findViewById<TextView>(R.id.btnBack).setOnClickListener {
            openPlaylist = null
            applySeg()
        }
        view.findViewById<TextView>(R.id.btnPlayAll).setOnClickListener {
            playAll(detailSongs)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                if (seg == Seg.SONGS && openPlaylist == null) searchSongs()
            }
        })

        ListenPlayer.addListener(playerListener)
        ListenCache.onChanged = {
            if (isAdded) bindPlayer()
        }

        applySeg()
        bindPlayer()
    }

    override fun onResume() {
        super.onResume()
        ListenPlayer.addListener(playerListener) // 幂等，详情页等页面同时注册互不影响
        bindPlayer() // 返回本页时立即对齐真实播放状态
        ListenPlayer.startQueueSync() // 与网页端队列双向同步
        handler.post(ticker)
        loadQueueFromServer()
        loadFavorites()
    }

    override fun onPause() {
        super.onPause()
        ListenPlayer.stopQueueSync()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
        ListenPlayer.removeListener(playerListener)
        if (ListenCache.onChanged != null) ListenCache.onChanged = null
    }

    // ------------------------------------------------ 播放器卡

    private fun bindPlayer() {
        val song = ListenPlayer.current
        if (song == null) {
            tvTitle.text = "未在播放"
            tvArtist.text = "选择一首歌开始收听"
            ivCover.setImageResource(R.drawable.bg_cover_placeholder)
            seekBar.max = 100
            seekBar.progress = 0
            tvTimeCur.text = "00:00"
            tvTimeTotal.text = "00:00"
        } else {
            tvTitle.text = song.title
            tvArtist.text = song.artist.ifEmpty { "未知歌手" }
            UiKit.loadCover(song.coverUrl, ivCover, R.drawable.bg_cover_placeholder)
            val dur = (ListenPlayer.durationMs.takeIf { it > 0 } ?: (song.duration * 1000).toInt())
                .coerceAtLeast(1)
            seekBar.max = dur
            if (!ListenPlayer.isPlaying && !isTracking) seekBar.progress = ListenPlayer.positionMs.coerceIn(0, dur)
            tvTimeTotal.text = UiKit.fmtTime(dur.toLong())
        }
        tvPlayPause.text = if (ListenPlayer.isPlaying) "⏸" else "▶"
        updateFavIcon()
        updateCacheStatus()
    }

    private fun updateFavIcon() {
        val song = ListenPlayer.current
        val fav = song != null && favIds.contains(song.id)
        tvFav.text = if (fav) "❤" else "♡"
        tvFav.setTextColor(
            resources.getColor(if (fav) R.color.brand_green_dark else R.color.text_muted, null)
        )
    }

    private fun updateCacheStatus() {
        val song = ListenPlayer.current
        val track = ListenPlayer.currentTrack.key
        tvCache.text = when {
            song == null -> "在线"
            ListenCache.has(song.id, track) -> "✓ 已缓存"
            ListenCache.isDownloading(song.id, track) -> "⏳ 缓存中…"
            else -> "🌐 在线"
        }
    }

    // ------------------------------------------------ 分段切换

    private fun setSeg(new: Seg) {
        if (seg != new) openPlaylist = null
        seg = new
        applySeg()
    }

    private fun applySeg() {
        view?.findViewById<TextView>(R.id.tabQueue)?.isSelected = seg == Seg.QUEUE
        view?.findViewById<TextView>(R.id.tabFav)?.isSelected = seg == Seg.FAV
        view?.findViewById<TextView>(R.id.tabPlaylists)?.isSelected = seg == Seg.PLAYLISTS
        view?.findViewById<TextView>(R.id.tabSongs)?.isSelected = seg == Seg.SONGS

        val detail = openPlaylist != null
        listHeader.visibility = if (detail) View.GONE else View.VISIBLE
        playlistHeader.visibility = if (detail) View.VISIBLE else View.GONE
        searchRow.visibility = if (seg == Seg.SONGS && !detail) View.VISIBLE else View.GONE

        if (detail) {
            tvPlaylistName.text = openPlaylist?.name ?: "歌单"
            recycler.adapter = songAdapter
        } else when (seg) {
            Seg.QUEUE -> {
                tvListTitle.text = "播放队列"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "🗑 清空"
                recycler.adapter = songAdapter
            }

            Seg.FAV -> {
                tvListTitle.text = "我的收藏"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "▶ 播放全部"
                recycler.adapter = songAdapter
            }

            Seg.PLAYLISTS -> {
                tvListTitle.text = "我的歌单"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "＋ 新建"
                recycler.adapter = playlistAdapter
                loadPlaylists()
            }

            Seg.SONGS -> {
                tvListTitle.text = "歌曲库"
                btnAction.visibility = View.GONE
                recycler.adapter = songAdapter
                searchSongs()
            }
        }
        refreshList()
    }

    private fun currentSongs(): List<Song> = when {
        openPlaylist != null -> detailSongs
        seg == Seg.QUEUE -> ListenPlayer.queue
        seg == Seg.FAV -> favSongs
        seg == Seg.SONGS -> searchSongs
        else -> emptyList()
    }

    private fun refreshList() {
        if (!isAdded) return
        songAdapter.notifyDataSetChanged()
        playlistAdapter.notifyDataSetChanged()
        val empty = if (openPlaylist != null) currentSongs().isEmpty()
        else if (seg == Seg.PLAYLISTS) playlists.isEmpty()
        else currentSongs().isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvEmpty.text = when {
            openPlaylist != null -> "歌单是空的"
            seg == Seg.QUEUE -> "播放队列为空\n去「歌曲」添加喜欢的歌"
            seg == Seg.FAV -> "还没有收藏\n点歌曲行的「收藏」试试"
            seg == Seg.PLAYLISTS -> "还没有歌单\n点右上角「＋ 新建」"
            currentQuery.isEmpty() -> "服务端还没有歌曲\n请在管理页面上传"
            else -> "没有找到「$currentQuery」相关歌曲"
        }
    }

    private fun onListAction() {
        when {
            openPlaylist != null -> {}
            seg == Seg.QUEUE -> confirmClearQueue()
            seg == Seg.FAV -> playAll(favSongs)
            seg == Seg.PLAYLISTS -> promptPlaylistName("新建歌单", "") { name ->
                UiKit.bg {
                    try {
                        ApiClient.listenPlaylistCreate(name)
                        UiKit.ui { loadPlaylists() }
                    } catch (e: Exception) {
                        toast("新建失败：${e.message}")
                    }
                }
            }
        }
    }

    // ------------------------------------------------ 数据加载

    /** 进入听歌页时 GET 恢复服务器队列（正在播放的歌保留在队列里）。 */
    private fun loadQueueFromServer() {
        UiKit.bg {
            try {
                val songs = ApiClient.listenQueue()
                UiKit.ui {
                    ListenPlayer.adoptQueue(songs)
                    if (seg == Seg.QUEUE && openPlaylist == null) refreshList()
                }
            } catch (_: Exception) {
                // 保持本地队列（如离线场景）
            }
        }
    }

    private fun loadFavorites() {
        UiKit.bg {
            try {
                val list = ApiClient.listenFavorites()
                UiKit.ui {
                    favSongs.clear()
                    favSongs.addAll(list)
                    favIds.clear()
                    favIds.addAll(list.map { it.id })
                    updateFavIcon()
                    if (seg == Seg.FAV && openPlaylist == null) refreshList()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadPlaylists() {
        showLoading(true)
        UiKit.bg {
            try {
                val list = ApiClient.listenPlaylists()
                UiKit.ui {
                    showLoading(false)
                    playlists.clear()
                    playlists.addAll(list)
                    refreshList()
                }
            } catch (e: Exception) {
                UiKit.ui {
                    showLoading(false)
                    toast("加载歌单失败：${e.message}")
                    refreshList()
                }
            }
        }
    }

    private fun searchSongs() {
        val query = currentQuery
        showLoading(true)
        UiKit.bg {
            try {
                val list = ApiClient.songs(query)
                UiKit.ui {
                    if (query != currentQuery) return@ui // 过期结果
                    showLoading(false)
                    searchSongs.clear()
                    searchSongs.addAll(list)
                    refreshList()
                }
            } catch (e: Exception) {
                UiKit.ui {
                    showLoading(false)
                    toast("搜索失败：${e.message}")
                    refreshList()
                }
            }
        }
    }

    private fun openPlaylist(pl: ListenPlaylist) {
        showLoading(true)
        UiKit.bg {
            try {
                val (_, songs) = ApiClient.listenPlaylistSongs(pl.id)
                UiKit.ui {
                    showLoading(false)
                    openPlaylist = pl
                    detailSongs.clear()
                    detailSongs.addAll(songs)
                    applySeg()
                }
            } catch (e: Exception) {
                UiKit.ui {
                    showLoading(false)
                    toast("打开歌单失败：${e.message}")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------ 行操作

    /** 点封面 / 歌名 → 歌曲详情页（所有听歌列表行生效）。 */
    private fun openDetail(song: Song) {
        startActivity(ListenDetailActivity.intentFor(requireContext(), song.id))
    }

    private fun onSongClick(song: Song) {
        when {
            openPlaylist != null -> ListenPlayer.play(song)
            seg == Seg.QUEUE -> ListenPlayer.play(song)
            seg == Seg.FAV -> ListenPlayer.play(song)
            else -> songActionDialog(song) // 歌曲库：行操作对话框
        }
    }

    private fun onSongMore(song: Song) {
        when {
            openPlaylist != null -> {
                val isFav = favIds.contains(song.id)
                showActions(
                    song.title,
                    listOf("▶ 播放", "➕ 加入播放队列", "➖ 从歌单移除", if (isFav) "💔 取消收藏" else "❤ 收藏")
                ) { which ->
                    when (which) {
                        0 -> ListenPlayer.play(song)
                        1 -> addToQueue(song)
                        2 -> removeFromPlaylist(song)
                        3 -> toggleFav(song)
                    }
                }
            }

            seg == Seg.QUEUE -> {
                val isFav = favIds.contains(song.id)
                showActions(
                    song.title,
                    listOf("▶ 从这首播放", "➖ 移出队列", if (isFav) "💔 取消收藏" else "❤ 收藏")
                ) { which ->
                    when (which) {
                        0 -> ListenPlayer.play(song)
                        1 -> removeFromQueue(song)
                        2 -> toggleFav(song)
                    }
                }
            }

            seg == Seg.FAV -> showActions(
                song.title,
                listOf("▶ 播放", "➕ 加入播放队列", "💔 取消收藏")
            ) { which ->
                when (which) {
                    0 -> ListenPlayer.play(song)
                    1 -> addToQueue(song)
                    2 -> toggleFav(song)
                }
            }

            else -> songActionDialog(song)
        }
    }

    private fun songActionDialog(song: Song) {
        val isFav = favIds.contains(song.id)
        showActions(
            song.title,
            listOf(
                "▶ 播放",
                "➕ 加入播放队列",
                if (isFav) "💔 取消收藏" else "❤ 收藏",
                "📁 加入歌单"
            )
        ) { which ->
            when (which) {
                // 歌曲页点播放：自动加入播放队列后播放
                0 -> ListenPlayer.playInQueue(song)
                1 -> addToQueue(song)
                2 -> toggleFav(song)
                3 -> addToPlaylistDialog(song)
            }
        }
    }

    private fun onPlaylistMore(pl: ListenPlaylist) {
        showActions(pl.name, listOf("▶ 播放全部", "✏ 重命名", "🗑 删除")) { which ->
            when (which) {
                0 -> playPlaylist(pl)
                1 -> promptPlaylistName("重命名歌单", pl.name) { name ->
                    UiKit.bg {
                        try {
                            ApiClient.listenPlaylistRename(pl.id, name)
                            UiKit.ui { loadPlaylists() }
                        } catch (e: Exception) {
                            toast("重命名失败：${e.message}")
                        }
                    }
                }

                2 -> AlertDialog.Builder(requireContext())
                    .setTitle("删除歌单")
                    .setMessage("确定删除歌单「${pl.name}」？歌曲不会被删除。")
                    .setPositiveButton("删除") { _, _ ->
                        UiKit.bg {
                            try {
                                ApiClient.listenPlaylistDelete(pl.id)
                                UiKit.ui {
                                    if (openPlaylist?.id == pl.id) openPlaylist = null
                                    loadPlaylists()
                                    applySeg()
                                }
                            } catch (e: Exception) {
                                toast("删除失败：${e.message}")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------ 队列 / 收藏 / 歌单 变更

    /** 本地队列是 UI 真源：变更后整份 PUT 回服务器（失败 toast，不影响本地播放）。 */
    private fun pushQueue() {
        val ids = ListenPlayer.queue.map { it.id }
        UiKit.bg {
            try {
                ApiClient.listenQueueReplace(ids)
            } catch (e: Exception) {
                toast("队列同步失败：${e.message}")
            }
        }
    }

    private fun addToQueue(song: Song) {
        if (ListenPlayer.queue.any { it.id == song.id }) {
            toast("已在播放队列中")
            return
        }
        ListenPlayer.queue.add(song)
        ListenPlayer.queueUpdated()
        pushQueue()
        toast("已加入播放队列")
    }

    private fun removeFromQueue(song: Song) {
        ListenPlayer.queue.removeAll { it.id == song.id }
        ListenPlayer.queueUpdated()
        pushQueue()
    }

    private fun confirmClearQueue() {
        if (ListenPlayer.queue.isEmpty()) {
            toast("播放队列已经是空的")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("清空队列")
            .setMessage("确定清空播放队列？（不影响正在播放的歌）")
            .setPositiveButton("清空") { _, _ ->
                ListenPlayer.clearQueue()
                pushQueue()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleFav(song: Song) {
        val add = !favIds.contains(song.id)
        if (add) favIds.add(song.id) else favIds.remove(song.id)
        if (add) favSongs.add(song) else favSongs.removeAll { it.id == song.id }
        updateFavIcon()
        if (seg == Seg.FAV && openPlaylist == null) refreshList()
        UiKit.bg {
            try {
                if (add) ApiClient.listenFavoriteAdd(song.id)
                else ApiClient.listenFavoriteRemove(song.id)
            } catch (e: Exception) {
                // 回滚
                if (add) favIds.remove(song.id) else favIds.add(song.id)
                if (add) favSongs.removeAll { it.id == song.id } else favSongs.add(song)
                UiKit.ui {
                    updateFavIcon()
                    if (seg == Seg.FAV && openPlaylist == null) refreshList()
                    toast("收藏同步失败：${e.message}")
                }
            }
        }
    }

    /** 「播放全部」：把歌单 songIds 整份 PUT 进队列并从第一首播放。 */
    private fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) {
            toast("歌单是空的")
            return
        }
        ListenPlayer.playAll(songs)
        pushQueue()
    }

    private fun playPlaylist(pl: ListenPlaylist) {
        showLoading(true)
        UiKit.bg {
            try {
                val (_, songs) = ApiClient.listenPlaylistSongs(pl.id)
                UiKit.ui {
                    showLoading(false)
                    playAll(songs)
                }
            } catch (e: Exception) {
                UiKit.ui {
                    showLoading(false)
                    toast("播放失败：${e.message}")
                }
            }
        }
    }

    private fun removeFromPlaylist(song: Song) {
        val pl = openPlaylist ?: return
        UiKit.bg {
            try {
                ApiClient.listenPlaylistRemoveSong(pl.id, song.id)
                UiKit.ui {
                    detailSongs.removeAll { it.id == song.id }
                    refreshList()
                }
            } catch (e: Exception) {
                toast("移除失败：${e.message}")
            }
        }
    }

    /** 加入歌单：列出歌单 + 新建。 */
    private fun addToPlaylistDialog(song: Song) {
        UiKit.bg {
            try {
                val list = ApiClient.listenPlaylists()
                UiKit.ui {
                    playlists.clear()
                    playlists.addAll(list)
                    val names = mutableListOf("➕ 新建歌单…")
                    names.addAll(list.map { it.name })
                    AlertDialog.Builder(requireContext())
                        .setTitle("「${song.title}」加入歌单")
                        .setItems(names.toTypedArray()) { _, which ->
                            if (which == 0) {
                                promptPlaylistName("新建歌单", "") { name ->
                                    UiKit.bg {
                                        try {
                                            val pl = ApiClient.listenPlaylistCreate(name)
                                            ApiClient.listenPlaylistAddSong(pl.id, song.id)
                                            UiKit.ui { toast("已加入「${pl.name}」") }
                                        } catch (e: Exception) {
                                            toast("加入失败：${e.message}")
                                        }
                                    }
                                }
                            } else {
                                val pl = list[which - 1]
                                UiKit.bg {
                                    try {
                                        ApiClient.listenPlaylistAddSong(pl.id, song.id)
                                        UiKit.ui { toast("已加入「${pl.name}」") }
                                    } catch (e: Exception) {
                                        toast("加入失败：${e.message}")
                                    }
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                toast("加载歌单失败：${e.message}")
            }
        }
    }

    // ------------------------------------------------ 对话框小工具

    private fun showActions(title: String, items: List<String>, onPick: (Int) -> Unit) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }

    private fun promptPlaylistName(title: String, preset: String, onOk: (String) -> Unit) {
        if (!isAdded) return
        val et = EditText(requireContext()).apply {
            setText(preset)
            hint = "歌单名称"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) toast("名称不能为空")
                else onOk(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        val ctx = activity?.applicationContext ?: return
        UiKit.ui { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}

/** 听歌歌曲行（封面 / 歌名 / 歌手 / 时长 / ⋮ 菜单）。
 *  封面与歌名点击进歌曲详情页；行其余区域保留原操作（点击播放等）。 */
class ListenSongAdapter(
    private val items: () -> List<Song>,
    private val onClick: (Song) -> Unit,
    private val onMore: (Song) -> Unit,
    private val onOpenDetail: (Song) -> Unit
) : RecyclerView.Adapter<ListenSongAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover = view.findViewById<ImageView>(R.id.ivCover)
        val title = view.findViewById<TextView>(R.id.tvTitle)
        val artist = view.findViewById<TextView>(R.id.tvArtist)
        val duration = view.findViewById<TextView>(R.id.tvDuration)
        val more = view.findViewById<TextView>(R.id.tvMore)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_listen_song, parent, false)
        )

    override fun getItemCount(): Int = items().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items()[position]
        holder.title.text = song.title
        holder.artist.text = song.artist.ifEmpty { "未知歌手" }
        holder.duration.text = UiKit.fmtDuration(song.duration)
        UiKit.loadCover(song.coverUrl, holder.cover, R.drawable.bg_cover_placeholder)
        holder.itemView.setOnClickListener { onClick(song) }
        holder.cover.setOnClickListener { onOpenDetail(song) }
        holder.title.setOnClickListener { onOpenDetail(song) }
        holder.more.setOnClickListener { onMore(song) }
    }
}

/** 歌单行（名称 / 首数·时长 / ⋮ 菜单）。 */
class ListenPlaylistAdapter(
    private val items: () -> List<ListenPlaylist>,
    private val onClick: (ListenPlaylist) -> Unit,
    private val onMore: (ListenPlaylist) -> Unit
) : RecyclerView.Adapter<ListenPlaylistAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name = view.findViewById<TextView>(R.id.tvName)
        val meta = view.findViewById<TextView>(R.id.tvMeta)
        val more = view.findViewById<TextView>(R.id.tvMore)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_listen_playlist, parent, false)
        )

    override fun getItemCount(): Int = items().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pl = items()[position]
        holder.name.text = pl.name
        holder.meta.text = "${pl.count} 首 · ${UiKit.fmtDuration(pl.duration)}"
        holder.itemView.setOnClickListener { onClick(pl) }
        holder.more.setOnClickListener { onMore(pl) }
    }
}
