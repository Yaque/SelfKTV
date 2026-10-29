/* SelfKTV 管理后台 · 🎵 听歌页（播放队列 / 收藏 / 歌单 / 全部歌曲）。
   依赖 admin.js 提供的 $、api、toast、esc、fmtDuration、authQ、bindSeg。 */
"use strict";

/* ---------------- 状态 ---------------- */

const lsc = {
  inited: false,
  queue: [],          // 播放队列 songObj[]
  favs: [],           // 收藏 songObj[]
  playlists: [],      // 歌单摘要 [{id,name,count,duration,...}]
  library: [],        // 全部歌曲（搜索结果）songObj[]
  plDetail: null,     // 当前打开的歌单 {playlist, songs}
  current: null,      // 正在播放的 songObj
  pickSongId: null,   // 「加入歌单/移出歌单」弹窗当前选中的歌曲
  scrubbing: false,
  segKind: "queue",   // 列表区当前分区（详情页返回时恢复）
  playMode: "list",   // 播放模式：list / shuffle / one
  dSong: null,        // 歌曲详情页当前展示的 songObj
  dTrack: "",         // 详情页选中的音轨 key（original/vocal/accomp）
  dScrubbing: false,
  fbTried: false,     // 当前曲目是否已尝试过备选音轨（媒体错误兑底）
  lyricIdx: -1,       // 歌词当前行下标
  lyricCache: new Map(), // songId -> {timed, lines}
  plSongs: new Map(),    // playlistId -> Set(songId)（歌单勾选状态缓存）
  lastLocalChange: 0,    // 本地队列最近变更时间（轮询避让推送在途）
};

try {
  const _m = localStorage.getItem("selfktv_play_mode");
  if (["list", "shuffle", "one"].includes(_m)) lsc.playMode = _m;
} catch { /* localStorage 不可用时用默认值 */ }

/* ---------------- 工具 ---------------- */

/* 媒体地址（封面 / 音频流）拼鉴权参数；URL 已带 ?track= 时用 & 连接 */
function lscMedia(url) {
  if (!url) return "";
  return url + (url.includes("?") ? authQ().replace("?", "&") : authQ());
}

/* 听歌播放轨选取：原唱 > 人声 > 伴奏 */
function lscTrackUrl(song) {
  const t = (song && song.tracks) || {};
  return t.original || t.vocal || t.accomp || "";
}

/* 可用音轨优先级（返回音轨 key）：原曲 > 人声 > 伴奏 */
function lscPickTrackKey(song) {
  const t = (song && song.tracks) || {};
  return t.original ? "original" : t.vocal ? "vocal" : t.accomp ? "accomp" : "";
}

/* 播放模式：🔁 列表循环 → 🔀 随机播放 → 🔂 单曲循环 */
const lscModes = [
  { key: "list", icon: "🔁", label: "列表循环" },
  { key: "shuffle", icon: "🔀", label: "列表随机" },
  { key: "one", icon: "🔂", label: "单曲循环" },
];

function lscModeInfo() {
  return lscModes.find((m) => m.key === lsc.playMode) || lscModes[0];
}

function setPlayMode(key) {
  lsc.playMode = key;
  try { localStorage.setItem("selfktv_play_mode", key); } catch { /* localStorage 不可用时忽略 */ }
  renderDetail();
  const m = lscModeInfo();
  toast(`播放模式：${m.icon} ${m.label}`);
}

function lscCover(song, cls = "cover-thumb") {
  return song && song.cover_url
    ? `<img class="${cls}" src="${esc(lscMedia(song.cover_url))}" loading="lazy" alt="">`
    : `<div class="${cls}">🎵</div>`;
}

function lscSong(id) {
  return lsc.library.find((s) => s.id === id)
    || lsc.queue.find((s) => s.id === id)
    || lsc.favs.find((s) => s.id === id)
    || (lsc.plDetail ? (lsc.plDetail.songs || []).find((s) => s.id === id) : null);
}

function lscIsFav(id) {
  return lsc.favs.some((s) => s.id === id);
}

function lscInQueue(id) {
  return lsc.queue.some((s) => s.id === id);
}

/* 歌曲行模板：actions 为行内按钮（data-act 事件委托） */
function lscRow(song, actions) {
  return `<div class="lsc-row" data-id="${song.id}">
    <div class="lsc-cover-link" data-open-detail="1" title="查看歌曲详情">${lscCover(song)}</div>
    <div class="lsc-info">
      <div class="lsc-name"><a href="javascript:void(0)" class="lsc-name-link" data-open-detail="1" title="查看歌曲详情">${esc(song.title)}</a></div>
      <div class="lsc-sub muted">${esc(song.artist || "未知歌手")}</div>
    </div>
    <div class="lsc-dur muted">${fmtDuration(song.duration)}</div>
    <div class="lsc-acts">${actions}</div>
  </div>`;
}

function lscAct(act, label, cls = "ghost", extra = "") {
  return `<button type="button" class="btn small ${cls}" data-act="${act}" ${extra}>${label}</button>`;
}

/* ---------------- 数据加载（进入听歌页 / 服务器同步恢复） ---------------- */

async function lscLoadAll() {
  await Promise.all([lscLoadQueue(), lscLoadFavs(), lscLoadPlaylists(), lscLoadLibrary()]);
  lscRenderAll();
}

function lscRenderAll() {
  renderQueue();
  renderFavs();
  renderPlaylists();
  renderLibrary();
  updatePlayerUI();
}

async function lscLoadQueue() {
  const data = await api("/api/listen/queue");
  lsc.queue = data.songs || [];
}

async function lscLoadFavs() {
  const data = await api("/api/listen/favorites");
  lsc.favs = data.songs || [];
}

async function lscLoadPlaylists() {
  const data = await api("/api/listen/playlists");
  lsc.playlists = data.playlists || [];
}

async function lscLoadLibrary(query = "") {
  const data = await api("/api/songs" + (query ? `?q=${encodeURIComponent(query)}` : "?q="));
  lsc.library = data.songs || [];
}

/* 队列任何变更后整份 PUT 回服务器（双端同步语义） */
async function syncQueue(msg = "") {
  const ids = lsc.queue.map((s) => s.id);
  lsc.lastLocalChange = Date.now();
  renderQueue();
  try {
    const data = await api("/api/listen/queue", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ songIds: ids }),
    });
    lsc.queue = data.songs || lsc.queue;
    renderQueue();
    if (msg) toast(msg);
  } catch (err) {
    toast("队列同步失败：" + err.message, true);
    try { await lscLoadQueue(); } catch { /* 保留本地状态 */ }
    renderQueue();
  }
}

/* ---------------- 播放器 ---------------- */

const audio = () => $("#lp-audio");

/* 安全播放：兼容 play() 同步抛错 / Promise 拒绝（部分 WebView）；自动播放被拦截时给出提示 */
function lscSafePlay(a, hint = true) {
  try {
    const p = a.play();
    if (p && typeof p.catch === "function") {
      p.catch((err) => {
        const name = (err && err.name) || "";
        if (name === "AbortError") return; // 换源/停播引起的中止不是错误
        if (name === "NotAllowedError") {
          if (hint) toast("浏览器拦截了自动播放，再点一次播放键即可", true);
          return;
        }
        toast("播放失败：" + ((err && err.message) || name || "未知错误"), true);
      });
    }
  } catch (err) {
    toast("播放失败：" + ((err && err.message) || String(err)), true);
  }
}

/* trackKey 可选：详情页音轨选择；缺省按 原曲 > 人声 > 伴奏 取轨 */
function playSong(song, trackKey = "") {
  const t = (song && song.tracks) || {};
  const key = trackKey && t[trackKey] ? trackKey : lscPickTrackKey(song);
  const url = key ? t[key] : "";
  if (!url) {
    toast(`「${song.title}」暂无可播放音轨`, true);
    return;
  }
  lsc.current = song;
  lsc.dTrack = key;
  lsc.fbTried = false;
  const a = audio();
  a.src = lscMedia(url);
  lscSafePlay(a);
  if (lsc.dSong) {
    // 详情页与列表共用同一份播放状态：详情页跟随当前曲目
    lsc.dSong = song;
    renderDetail();
    loadDetailLyrics(song);
  }
  updatePlayerUI();
  renderQueue();
}

/* 空态点播放：先恢复队列取第一首；队列仍为空则取曲库第一首入队播放 */
async function playFirstAvailable() {
  if (!lsc.queue.length) {
    try {
      await lscLoadQueue();
      renderQueue();
    } catch { /* 队列恢复失败则继续尝试曲库 */ }
  }
  if (lsc.queue.length) {
    playSong(lsc.queue[0]);
    return;
  }
  try {
    const data = await api("/api/songs");
    const songs = data.songs || [];
    if (!songs.length) {
      toast("曲库还没有歌曲", true);
      return;
    }
    const song = songs[0];
    lsc.queue.push(song);
    syncQueue();
    playSong(song);
  } catch (err) {
    toast("播放失败：" + err.message, true);
  }
}

function togglePlay() {
  const a = audio();
  if (!lsc.current || !a.src) {
    playFirstAvailable();
    return;
  }
  if (a.paused) lscSafePlay(a);
  else a.pause();
}

/* dir=1 下一首 / -1 上一首；auto 表示播完自动切（受播放模式影响） */
function stepQueue(dir, auto = false) {
  const mode = lsc.playMode;
  if (auto && mode === "one" && lsc.current) {
    // 🔂 单曲循环：当前曲播完重播当前曲
    const a = audio();
    a.currentTime = 0;
    lscSafePlay(a, false);
    return;
  }
  if (!lsc.queue.length) {
    if (!auto) {
      if (!lsc.current) playFirstAvailable();
      else toast("播放队列为空", true);
    } else {
      audio().pause();
    }
    return;
  }
  if (mode === "shuffle") {
    // 🔀 随机：随机取队列中不同的一首
    let idx = Math.floor(Math.random() * lsc.queue.length);
    const curId = lsc.current ? lsc.current.id : null;
    if (lsc.queue.length > 1) {
      while (lsc.queue[idx].id === curId) idx = Math.floor(Math.random() * lsc.queue.length);
    }
    playSong(lsc.queue[idx]);
    return;
  }
  // 🔁 列表循环：队列播完回到队首继续（上一首到头则回到队尾）
  const curId = lsc.current ? lsc.current.id : null;
  const idx = lsc.queue.findIndex((s) => s.id === curId);
  const next = idx >= 0
    ? lsc.queue[(idx + dir + lsc.queue.length) % lsc.queue.length]
    : (dir > 0 ? lsc.queue[0] : lsc.queue[lsc.queue.length - 1]);
  playSong(next);
}

function updatePlayerUI() {
  const s = lsc.current;
  const cover = $("#lp-cover");
  if (s) {
    cover.innerHTML = s.cover_url
      ? `<img src="${esc(lscMedia(s.cover_url))}" alt="">`
      : "🎵";
    $("#lp-title").textContent = s.title;
    $("#lp-artist").textContent = s.artist || "未知歌手";
    cover.title = "查看歌曲详情";
    $("#lp-title").title = "查看歌曲详情";
  } else {
    cover.innerHTML = "🎵";
    $("#lp-title").textContent = "未在播放";
    $("#lp-artist").textContent = "从下方选一首歌开始";
    cover.title = "";
    $("#lp-title").title = "";
  }
  $("#lp-play").textContent = audio().paused ? "▶" : "⏸";
  const fav = $("#lp-fav");
  const on = s && lscIsFav(s.id);
  fav.textContent = on ? "❤️" : "🤍";
  fav.title = on ? "取消收藏" : "收藏";
  renderDetail();
}

/* ---------------- 渲染：播放队列 ---------------- */

function renderQueue() {
  const host = $("#lsc-queue-list");
  $("#lsc-queue-empty").classList.toggle("hidden", lsc.queue.length > 0);
  $("#lsc-queue-count").textContent = lsc.queue.length ? `共 ${lsc.queue.length} 首` : "";
  const curId = lsc.current ? lsc.current.id : null;
  host.innerHTML = lsc.queue.map((s) => lscRow(s, `
    ${lscAct("q-play", s.id === curId ? "⏸ 正在播放" : "▶ 从这首播放", s.id === curId ? "primary" : "ghost")}
    ${lscAct("q-remove", "✖ 移出队列", "ghost")}`)).join("");
}

/* ---------------- 渲染：收藏 ---------------- */

function renderFavs() {
  const host = $("#lsc-fav-list");
  $("#lsc-fav-empty").classList.toggle("hidden", lsc.favs.length > 0);
  $("#lsc-fav-count").textContent = lsc.favs.length ? `共 ${lsc.favs.length} 首` : "";
  $("#lsc-fav-playall").classList.toggle("hidden", lsc.favs.length === 0);
  host.innerHTML = lsc.favs.map((s) => lscRow(s, `
    ${lscAct("f-play", "▶ 播放")}
    ${lscAct("f-addq", "➕ 加入队列")}
    ${lscAct("f-remove", "取消收藏")}`)).join("");
}

/* ---------------- 渲染：歌单 ---------------- */

function renderPlaylists() {
  const host = $("#lsc-pl-list");
  $("#lsc-pl-empty").classList.toggle("hidden", lsc.playlists.length > 0);
  $("#lsc-pl-count").textContent = lsc.playlists.length ? `共 ${lsc.playlists.length} 个` : "";
  host.innerHTML = lsc.playlists.map((p) => `
    <div class="lsc-row" data-plid="${p.id}">
      <div class="cover-thumb">📁</div>
      <div class="lsc-info">
        <div class="lsc-name"><a href="javascript:void(0)" data-act="pl-open">${esc(p.name)}</a></div>
        <div class="lsc-sub muted">${p.count} 首 · ${fmtDuration(p.duration)}</div>
      </div>
      <div class="lsc-acts">
        ${lscAct("pl-playall", "▶ 播放全部", "primary")}
        ${lscAct("pl-rename", "重命名")}
        ${lscAct("pl-open", "详情")}
        ${lscAct("pl-delete", "删除", "danger")}
      </div>
    </div>`).join("");
}

function renderPlDetail() {
  const d = lsc.plDetail;
  if (!d) {
    $("#lsc-pl-detail").classList.add("hidden");
    $("#lsc-pl-main").classList.remove("hidden");
    return;
  }
  $("#lsc-pl-main").classList.add("hidden");
  $("#lsc-pl-detail").classList.remove("hidden");
  $("#lsc-pl-detail-name").textContent = "📁 " + (d.playlist ? d.playlist.name : "");
  const songs = d.songs || [];
  $("#lsc-pl-detail-empty").classList.toggle("hidden", songs.length > 0);
  $("#lsc-pl-detail-list").innerHTML = songs.map((s) => lscRow(s, `
    ${lscAct("p-play", "▶ 播放")}
    ${lscAct("p-remove", "✖ 从歌单移除")}`)).join("");
}

/* ---------------- 渲染：全部歌曲 ---------------- */

function renderLibrary() {
  const host = $("#lsc-lib-list");
  $("#lsc-lib-empty").classList.toggle("hidden", lsc.library.length > 0);
  $("#lsc-lib-count").textContent = `共 ${lsc.library.length} 首`;
  host.innerHTML = lsc.library.map((s) => lscRow(s, `
    ${lscAct("l-play", "▶ 播放", "primary")}
    ${lscAct("l-addq", "➕ 加入队列")}
    ${lscAct("l-fav", lscIsFav(s.id) ? "❤ 已收藏" : "🤍 收藏")}
    ${lscAct("l-addpl", "➕ 加入歌单")}`)).join("");
}

/* ---------------- 队列操作 ---------------- */

function qAdd(song, msg = "已加入队列 ➕") {
  if (lscInQueue(song.id)) {
    toast("该歌曲已在队列中");
    return;
  }
  lsc.queue.push(song);
  syncQueue(msg);
}

function qRemove(song) {
  const idx = lsc.queue.findIndex((s) => s.id === song.id);
  if (idx < 0) return;
  lsc.queue.splice(idx, 1);
  syncQueue("已移出队列 ✖");
}

function qClear() {
  if (!lsc.queue.length) { toast("队列已经是空的"); return; }
  if (!confirm("确定清空播放队列？")) return;
  lsc.queue = [];
  syncQueue("队列已清空");
}

/* 全部歌曲「▶ 播放」：插到当前曲目后并播放，同步队列 */
function playFromLibrary(song) {
  if (!lscInQueue(song.id)) {
    const curIdx = lsc.queue.findIndex((s) => s.id === (lsc.current ? lsc.current.id : null));
    const at = curIdx >= 0 ? curIdx + 1 : lsc.queue.length;
    lsc.queue.splice(at, 0, song);
    syncQueue();
  }
  playSong(song);
}

/* ---------------- 收藏操作（乐观更新，失败回滚） ---------------- */

async function toggleFav(song) {
  const idx = lsc.favs.findIndex((s) => s.id === song.id);
  const paint = () => { renderFavs(); renderLibrary(); updatePlayerUI(); renderDetail(); };
  if (idx >= 0) {
    lsc.favs.splice(idx, 1);
    paint();
    try {
      await api(`/api/listen/favorites/${song.id}`, { method: "DELETE" });
      toast("已取消收藏");
    } catch (err) {
      lsc.favs.splice(idx, 0, song);
      paint();
      toast("操作失败：" + err.message, true);
    }
  } else {
    lsc.favs.push(song);
    paint();
    try {
      await api(`/api/listen/favorites/${song.id}`, { method: "POST" });
      toast("已收藏 ❤");
    } catch (err) {
      lsc.favs = lsc.favs.filter((s) => s.id !== song.id);
      paint();
      toast("操作失败：" + err.message, true);
    }
  }
}

/* ---------------- 歌单操作 ---------------- */

function openPlNameModal(mode, playlist = null) {
  $("#pl-name-title").textContent = mode === "rename" ? "重命名歌单" : "新建歌单";
  $("#pl-name-id").value = playlist ? String(playlist.id) : "";
  $("#pl-name-input").value = playlist ? playlist.name : "";
  $("#modal-pl-name").classList.remove("hidden");
  setTimeout(() => $("#pl-name-input").focus(), 50);
}

$("#form-pl-name").addEventListener("submit", async (e) => {
  e.preventDefault();
  const name = $("#pl-name-input").value.trim();
  const id = $("#pl-name-id").value;
  if (!name) { toast("请输入歌单名称", true); return; }
  try {
    if (id) {
      const data = await api(`/api/listen/playlists/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
      });
      if (lsc.plDetail && lsc.plDetail.playlist && lsc.plDetail.playlist.id === Number(id)) {
        lsc.plDetail.playlist = data.playlist;
      }
      toast("已重命名");
    } else {
      const data = await api("/api/listen/playlists", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
      });
      toast(`歌单「${data.playlist.name}」已创建`);
      if (lsc.pickSongId) {
        // 从「加入歌单」弹窗顺手新建：建完直接把歌加进去，然后回到弹层
        const songId = lsc.pickSongId;
        await plAddSong(data.playlist.id, songId);
        await refreshPlMembership();
        renderPlPick();
        $("#modal-pl-pick").classList.remove("hidden");
      }
    }
    $("#modal-pl-name").classList.add("hidden");
    await lscLoadPlaylists();
    renderPlaylists();
    renderPlDetail();
  } catch (err) {
    toast(err.message, true);
  }
});

$("#pl-name-cancel").addEventListener("click", () => {
  $("#modal-pl-name").classList.add("hidden");
  lsc.pickSongId = null;
});

async function plAddSong(plId, songId) {
  const data = await api(`/api/listen/playlists/${plId}/songs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ songId }),
  });
  if (lsc.plDetail && lsc.plDetail.playlist && lsc.plDetail.playlist.id === plId) {
    const song = lscSong(songId);
    if (song && !(lsc.plDetail.songs || []).some((s) => s.id === songId)) lsc.plDetail.songs.push(song);
    renderPlDetail();
  }
  await lscLoadPlaylists();
  renderPlaylists();
  toast(`已加入歌单（共 ${data.count} 首）`);
}

async function plRemoveSong(plId, song) {
  const d = lsc.plDetail;
  const idx = d ? (d.songs || []).findIndex((s) => s.id === song.id) : -1;
  if (idx >= 0) {
    d.songs.splice(idx, 1);
    renderPlDetail();
  }
  try {
    await api(`/api/listen/playlists/${plId}/songs/${song.id}`, { method: "DELETE" });
    toast("已从歌单移除");
    await lscLoadPlaylists();
    renderPlaylists();
  } catch (err) {
    toast("操作失败：" + err.message, true);
    if (idx >= 0) {
      d.songs.splice(idx, 0, song);
      renderPlDetail();
    }
  }
}

async function plOpen(plId) {
  try {
    const data = await api(`/api/listen/playlists/${plId}`);
    lsc.plDetail = { playlist: data.playlist, songs: data.songs || [] };
    renderPlDetail();
  } catch (err) {
    toast(err.message, true);
  }
}

/* ▶ 播放全部：整份 songIds PUT 进队列并播放第一首 */
async function plPlayAll(plId) {
  try {
    const data = await api(`/api/listen/playlists/${plId}`);
    const songs = data.songs || [];
    if (!songs.length) { toast("歌单里还没有歌曲", true); return; }
    lsc.queue = songs.slice();
    syncQueue();
    playSong(songs[0]);
    toast(`开始播放歌单「${data.playlist.name}」`);
  } catch (err) {
    toast(err.message, true);
  }
}

function plDelete(pl) {
  if (!confirm(`确定删除歌单「${pl.name}」？（其中的歌曲不受影响）`)) return;
  api(`/api/listen/playlists/${pl.id}`, { method: "DELETE" })
    .then(async () => {
      if (lsc.plDetail && lsc.plDetail.playlist && lsc.plDetail.playlist.id === pl.id) lsc.plDetail = null;
      await lscLoadPlaylists();
      renderPlaylists();
      renderPlDetail();
      toast("歌单已删除");
    })
    .catch((err) => toast(err.message, true));
}

/* 「加入歌单 / 移出歌单」选择弹窗：勾选状态 = 当前歌是否在该歌单，点选即加入/移出 */
function openPlPick(songId) {
  lsc.pickSongId = songId;
  $("#modal-pl-pick").classList.remove("hidden");
  renderPlPick();
  refreshPlMembership().then(renderPlPick).catch(() => { /* 保留当前渲染 */ });
}

function renderPlPick() {
  const host = $("#lsc-pick-list");
  $("#lsc-pick-empty").classList.toggle("hidden", lsc.playlists.length > 0);
  const songId = lsc.pickSongId;
  host.innerHTML = lsc.playlists.map((p) => {
    const inPl = (lsc.plSongs.get(p.id) || new Set()).has(songId);
    return `
    <div class="lsc-row" data-plid="${p.id}">
      <div class="cover-thumb">📁</div>
      <div class="lsc-info">
        <div class="lsc-name">${esc(p.name)}</div>
        <div class="lsc-sub muted">${p.count} 首</div>
      </div>
      <div class="lsc-acts">
        <label class="pl-pick-check">
          <input type="checkbox" data-act="pick-toggle" ${inPl ? "checked" : ""}>
          <span>${inPl ? "✓ 在歌单中" : "加入歌单"}</span>
        </label>
      </div>
    </div>`;
  }).join("");
}

/* 拉取每个歌单包含的歌曲，供勾选状态判断 */
async function refreshPlMembership() {
  const map = new Map();
  await Promise.all(lsc.playlists.map(async (p) => {
    try {
      const data = await api(`/api/listen/playlists/${p.id}`);
      map.set(p.id, new Set((data.songs || []).map((s) => s.id)));
    } catch {
      map.set(p.id, new Set());
    }
  }));
  lsc.plSongs = map;
}

/* 勾选切换：加入 / 移出歌单（本地乐观 + 服务器同步） */
async function pickToggle(pl) {
  const songId = lsc.pickSongId;
  if (!songId) return;
  const set = lsc.plSongs.get(pl.id) || new Set();
  const inPl = set.has(songId);
  try {
    if (inPl) {
      await api(`/api/listen/playlists/${pl.id}/songs/${songId}`, { method: "DELETE" });
      set.delete(songId);
      toast("已移出歌单 ✖");
    } else {
      const data = await api(`/api/listen/playlists/${pl.id}/songs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ songId }),
      });
      set.add(songId);
      toast(`已加入歌单（共 ${data.count} 首）`);
    }
    lsc.plSongs.set(pl.id, set);
    await lscLoadPlaylists();
    renderPlaylists();
    renderPlPick();
    if (lsc.plDetail && lsc.plDetail.playlist && lsc.plDetail.playlist.id === pl.id) await plOpen(pl.id);
  } catch (err) {
    toast("操作失败：" + err.message, true);
    renderPlPick();
  }
}

/* ---------------- 歌曲详情页 ---------------- */

function openDetail(songId) {
  const song = lscSong(songId);
  if (!song) { toast("找不到该歌曲", true); return; }
  lsc.dSong = song;
  lsc.dTrack = (lsc.current && lsc.current.id === song.id && lsc.dTrack)
    ? lsc.dTrack
    : lscPickTrackKey(song);
  $("#lsc-detail").classList.remove("hidden");
  $("#lsc-seg").classList.add("hidden");
  document.querySelectorAll("#tab-listen > .lsc-panel").forEach((p) => p.classList.add("hidden"));
  renderDetail();
  loadDetailLyrics(song);
}

function closeDetail() {
  lsc.dSong = null;
  $("#lsc-detail").classList.add("hidden");
  $("#lsc-seg").classList.remove("hidden");
  ["queue", "fav", "playlists", "library"].forEach((k) => {
    $("#lsc-panel-" + k).classList.toggle("hidden", k !== lsc.segKind);
  });
}

function renderDetail() {
  const s = lsc.dSong;
  if (!s) return;
  const cover = $("#lsc-d-cover");
  cover.innerHTML = s.cover_url
    ? `<img src="${esc(lscMedia(s.cover_url))}" alt="">`
    : "🎵";
  $("#lsc-d-title").textContent = s.title;
  $("#lsc-d-artist").textContent = s.artist || "未知歌手";

  /* 音轨选择：只显示存在的轨 */
  const labels = { original: "原曲", vocal: "人声", accomp: "伴奏" };
  const t = s.tracks || {};
  const avail = ["original", "vocal", "accomp"].filter((k) => t[k]);
  if (!avail.includes(lsc.dTrack)) lsc.dTrack = avail[0] || "";
  $("#lsc-d-track-seg").innerHTML = avail.map((k) =>
    `<button type="button" class="seg-btn ${k === lsc.dTrack ? "active" : ""}" data-kind="${k}">${labels[k]}</button>`).join("");
  $("#lsc-d-track-wrap").classList.toggle("hidden", avail.length === 0);

  const playing = !!(lsc.current && lsc.current.id === s.id && !audio().paused);
  $("#lsc-d-play").textContent = playing ? "⏸" : "▶";
  const m = lscModeInfo();
  $("#lsc-d-mode").textContent = `${m.icon} ${m.label}`;
  const fav = $("#lsc-d-fav");
  const on = lscIsFav(s.id);
  fav.textContent = on ? "❤️" : "🤍";
  fav.title = on ? "取消收藏" : "收藏";
}

/* 切换音轨：保持当前播放进度继续播（currentTime 记住后换 src 再 seek） */
function switchTrack(key) {
  const s = lsc.dSong;
  if (!s || !s.tracks || !s.tracks[key]) return;
  if (key === lsc.dTrack && lsc.current && lsc.current.id === s.id && audio().src) {
    // 已在播这条音轨：不重复换源，避免进度丢失
    renderDetail();
    return;
  }
  lsc.dTrack = key;
  const a = audio();
  if (lsc.current && lsc.current.id === s.id && a.src) {
    const t = a.currentTime;
    const wasPlaying = !a.paused;
    const onMeta = () => {
      a.removeEventListener("loadedmetadata", onMeta);
      try { a.currentTime = t; } catch { /* seek 失败则从头播 */ }
      if (wasPlaying) lscSafePlay(a, false);
    };
    a.addEventListener("loadedmetadata", onMeta);
    lsc.fbTried = false;
    a.src = lscMedia(s.tracks[key]);
    if (!wasPlaying) updatePlayerUI();
  } else {
    // 该曲尚未在播：直接用所选音轨开始播放（“只播放人声/伴奏”）
    playSong(s, key);
  }
  renderDetail();
}

/* ---------------- 歌词（LRC 解析 + 播放联动） ---------------- */

function parseLrc(text) {
  const timed = [];
  const plain = [];
  let hasTime = false;
  /* 兼容 \r\n / \r / \n / \u2028 换行，避免整份歌词被当成一句 */
  for (const raw of String(text || "").split(/\r\n|\r|\n|\u2028|\u2029/)) {
    /* 时间标签：[mm:ss]、[mm:ss.xx]、[mm:ss:xx]（毫秒 1~4 位均兼容） */
    const tags = [...raw.matchAll(/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,4}))?\]/g)];
    const content = raw.replace(/\[[^\]]*\]/g, "").trim();
    if (tags.length) {
      hasTime = true;
      for (const m of tags) {
        const frac = m[3] ? Number("0." + m[3]) : 0;
        timed.push({ t: Number(m[1]) * 60 + Number(m[2]) + frac, text: content });
      }
    } else if (raw.trim()) {
      plain.push(raw.trim());
    }
  }
  if (!hasTime) return { timed: false, lines: plain };
  timed.sort((a, b) => a.t - b.t);
  return { timed: true, lines: timed };
}

async function loadDetailLyrics(song) {
  const host = $("#lsc-d-lyrics");
  lsc.lyricIdx = -1;
  host.innerHTML = '<div class="empty muted">歌词加载中…</div>';
  let parsed = lsc.lyricCache.get(song.id);
  if (!parsed) {
    try {
      const resp = await fetch(`/api/songs/${song.id}/lyrics`, {
        headers: state.token ? { Authorization: "Bearer " + state.token } : {},
      });
      if (resp.status === 404) parsed = { timed: false, lines: [], none: true };
      else if (!resp.ok) throw new Error(`请求失败 (${resp.status})`);
      else parsed = parseLrc(await resp.text());
    } catch {
      parsed = { timed: false, lines: [], none: true };
    }
    lsc.lyricCache.set(song.id, parsed);
  }
  if (!lsc.dSong || lsc.dSong.id !== song.id) return; // 已切换到别的歌
  if (!parsed.lines.length) {
    host.innerHTML = '<div class="empty muted">暂无歌词</div>';
    return;
  }
  if (!parsed.timed) {
    // 非 LRC 纯文本：静态展示
    host.innerHTML = parsed.lines.map((t) => `<div class="lsc-lyric-line plain">${esc(t)}</div>`).join("");
    return;
  }
  host.innerHTML = parsed.lines.map((l, i) =>
    `<div class="lsc-lyric-line" data-idx="${i}" data-t="${l.t}">${esc(l.text || "……")}</div>`).join("");
  updateLyricHighlight(true);
}

function updateLyricHighlight(force = false) {
  const parsed = lsc.dSong ? lsc.lyricCache.get(lsc.dSong.id) : null;
  if (!parsed || !parsed.timed) return;
  const lines = parsed.lines;
  const t = audio().currentTime;
  /* 二分定位最后一条 time <= t 的行 */
  let lo = 0, hi = lines.length - 1, idx = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].t <= t + 0.05) { idx = mid; lo = mid + 1; }
    else hi = mid - 1;
  }
  if (idx === lsc.lyricIdx && !force) return;
  lsc.lyricIdx = idx;
  const host = $("#lsc-d-lyrics");
  host.querySelectorAll(".lsc-lyric-line.active").forEach((el) => el.classList.remove("active"));
  if (idx >= 0) {
    const el = host.querySelector(`.lsc-lyric-line[data-idx="${idx}"]`);
    if (el) {
      el.classList.add("active");
      el.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }
}

/* ---------------- 事件绑定 ---------------- */

/* 分区切换 */
bindSeg("#lsc-seg", (kind) => {
  lsc.segKind = kind;
  ["queue", "fav", "playlists", "library"].forEach((k) => {
    $("#lsc-panel-" + k).classList.toggle("hidden", k !== kind);
  });
});

/* 详情页音轨选择 */
bindSeg("#lsc-d-track-seg", (key) => switchTrack(key));

/* 播放器条 */
$("#lp-play").addEventListener("click", togglePlay);
$("#lp-prev").addEventListener("click", () => stepQueue(-1));
$("#lp-next").addEventListener("click", () => stepQueue(1));
$("#lp-fav").addEventListener("click", () => {
  if (!lsc.current) { toast("还没有正在播放的歌曲", true); return; }
  toggleFav(lsc.current);
});
/* 播放器条封面 / 歌名点击 → 歌曲详情页 */
$("#lp-cover").addEventListener("click", () => { if (lsc.current) openDetail(lsc.current.id); });
$("#lp-title").addEventListener("click", () => { if (lsc.current) openDetail(lsc.current.id); });

const lpAudio = $("#lp-audio");
lpAudio.addEventListener("play", updatePlayerUI);
lpAudio.addEventListener("pause", updatePlayerUI);
lpAudio.addEventListener("ended", () => stepQueue(1, true));
lpAudio.addEventListener("timeupdate", () => {
  const a = lpAudio;
  $("#lp-cur").textContent = fmtDuration(a.currentTime);
  $("#lp-dur").textContent = fmtDuration(a.duration || (lsc.current && lsc.current.duration) || 0);
  if (!lsc.scrubbing && a.duration) $("#lp-seek").value = String(Math.floor(a.currentTime / a.duration * 1000));
  /* 详情页进度 / 歌词联动 */
  if (lsc.dSong) {
    $("#lsc-d-cur").textContent = fmtDuration(a.currentTime);
    $("#lsc-d-dur").textContent = fmtDuration(a.duration || lsc.dSong.duration || 0);
    if (!lsc.dScrubbing && a.duration) $("#lsc-d-seek").value = String(Math.floor(a.currentTime / a.duration * 1000));
    updateLyricHighlight();
  }
});
lpAudio.addEventListener("loadedmetadata", () => {
  $("#lp-dur").textContent = fmtDuration(lpAudio.duration);
});
/* 媒体加载/解码失败：先尝试同曲其他音轨兑底，仍失败则明确提示原因 */
lpAudio.addEventListener("error", () => {
  const e = lpAudio.error;
  if (!e || e.code === 1 || !lpAudio.src) return; // 主动中止 / 无源不算错误
  const reasons = {
    2: "网络错误，音频加载失败",
    3: "音频解码失败，文件可能已损坏",
    4: "不支持的音频格式或音轨不存在",
  };
  const s = lsc.current;
  const labels = { original: "原曲", vocal: "人声", accomp: "伴奏" };
  if (!lsc.fbTried && s && s.tracks) {
    const alt = ["original", "vocal", "accomp"].find((k) => k !== lsc.dTrack && s.tracks[k]);
    if (alt) {
      lsc.fbTried = true;
      lsc.dTrack = alt;
      toast(`当前音轨无法播放，已切换到「${labels[alt]}」`);
      lpAudio.src = lscMedia(s.tracks[alt]);
      renderDetail();
      lscSafePlay(lpAudio, false);
      return;
    }
  }
  toast(`播放失败：${reasons[e.code] || "音频加载出错"}`, true);
});
$("#lp-seek").addEventListener("input", (e) => {
  const a = lpAudio;
  if (!a.duration) return;
  lsc.scrubbing = true;
  a.currentTime = (Number(e.target.value) / 1000) * a.duration;
  $("#lp-cur").textContent = fmtDuration(a.currentTime);
});
$("#lp-seek").addEventListener("change", () => { lsc.scrubbing = false; });

/* 详情页：返回 / 播放控件 / 进度条 / 模式 / 收藏 / 歌单 */
$("#lsc-d-back").addEventListener("click", closeDetail);
$("#lsc-d-play").addEventListener("click", () => {
  // 详情页展示的不是当前播放曲：直接播本曲（并入队）；否则照常播放/暂停
  const shown = lsc.dSong;
  if (shown && (!lsc.current || lsc.current.id !== shown.id)) {
    playFromLibrary(shown);
    return;
  }
  togglePlay();
});
$("#lsc-d-prev").addEventListener("click", () => stepQueue(-1));
$("#lsc-d-next").addEventListener("click", () => stepQueue(1));
$("#lsc-d-mode").addEventListener("click", () => {
  const i = lscModes.findIndex((m) => m.key === lsc.playMode);
  setPlayMode(lscModes[(i + 1) % lscModes.length].key);
});
$("#lsc-d-fav").addEventListener("click", () => {
  if (!lsc.dSong) return;
  toggleFav(lsc.dSong);
});
$("#lsc-d-pl").addEventListener("click", () => {
  if (!lsc.dSong) return;
  openPlPick(lsc.dSong.id);
});
$("#lsc-d-seek").addEventListener("input", (e) => {
  const a = lpAudio;
  if (!a.duration) return;
  lsc.dScrubbing = true;
  a.currentTime = (Number(e.target.value) / 1000) * a.duration;
  $("#lsc-d-cur").textContent = fmtDuration(a.currentTime);
});
$("#lsc-d-seek").addEventListener("change", () => { lsc.dScrubbing = false; });

/* 歌词行点击 → seek 到该时间点 */
$("#lsc-d-lyrics").addEventListener("click", (e) => {
  const line = e.target.closest(".lsc-lyric-line[data-t]");
  if (!line) return;
  const a = lpAudio;
  if (!a.src) return;
  a.currentTime = Number(line.dataset.t);
  lsc.lyricIdx = -1;
  updateLyricHighlight(true);
});

/* 队列 */
$("#lsc-queue-clear").addEventListener("click", qClear);

/* 收藏「▶ 播放全部」：整份收藏 PUT 进队列并从第一首播放 */
$("#lsc-fav-playall").addEventListener("click", () => {
  if (!lsc.favs.length) { toast("还没有收藏", true); return; }
  lsc.queue = lsc.favs.slice();
  syncQueue();
  playSong(lsc.queue[0]);
  toast("开始播放收藏（共 " + lsc.queue.length + " 首）");
});

/* 歌单 */
$("#lsc-pl-new").addEventListener("click", () => {
  lsc.pickSongId = null;
  openPlNameModal("create");
});
$("#lsc-pl-back").addEventListener("click", () => {
  lsc.plDetail = null;
  renderPlDetail();
});
$("#lsc-pl-playall").addEventListener("click", () => {
  if (lsc.plDetail && lsc.plDetail.playlist) plPlayAll(lsc.plDetail.playlist.id);
});

/* 加入歌单弹窗 */
$("#lsc-pick-close").addEventListener("click", () => {
  $("#modal-pl-pick").classList.add("hidden");
  lsc.pickSongId = null;
});
$("#lsc-pick-new").addEventListener("click", () => {
  $("#modal-pl-pick").classList.add("hidden");
  openPlNameModal("create");
});

/* 全部歌曲搜索 */
$("#lsc-search").addEventListener("input", (e) => {
  clearTimeout(window._lscSearchT);
  window._lscSearchT = setTimeout(async () => {
    await lscLoadLibrary(e.target.value.trim());
    renderLibrary();
  }, 300);
});

/* 行内按钮事件委托（听歌页 + 听歌相关弹窗；弹窗挂在 body 下，故监听 document） */
document.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-act], a[data-act], input[data-act]");
  if (!btn) return;
  const inListen = document.querySelector("#tab-listen").contains(btn)
    || btn.closest("#modal-pl-name")
    || btn.closest("#modal-pl-pick");
  if (!inListen) return;
  const act = btn.dataset.act;

  /* 歌单行 */
  const plRow = btn.closest("[data-plid]");
  if (plRow) {
    const pl = lsc.playlists.find((p) => p.id === Number(plRow.dataset.plid));
    if (!pl) return;
    if (act === "pl-open") plOpen(pl.id);
    else if (act === "pl-playall") plPlayAll(pl.id);
    else if (act === "pl-rename") openPlNameModal("rename", pl);
    else if (act === "pl-delete") plDelete(pl);
    else if (act === "pick-toggle") {
      pickToggle(pl).catch(() => renderPlPick());
    }
    return;
  }

  /* 歌曲行 */
  const row = btn.closest("[data-id]");
  if (!row) return;
  const song = lscSong(Number(row.dataset.id));
  if (!song) return;

  switch (act) {
    case "q-play": playSong(song); break;
    case "q-remove": qRemove(song); break;
    case "f-play": playSong(song); break;
    case "f-addq": qAdd(song); break;
    case "f-remove": toggleFav(song); break;
    case "p-play": playSong(song); break;
    case "p-remove":
      if (lsc.plDetail && lsc.plDetail.playlist) plRemoveSong(lsc.plDetail.playlist.id, song);
      break;
    case "l-play": playFromLibrary(song); break;
    case "l-addq": qAdd(song); break;
    case "l-fav": toggleFav(song); break;
    case "l-addpl": openPlPick(song.id); break;
  }
});

/* 点击歌曲封面 / 歌曲名称 → 歌曲详情页（所有听歌列表行生效） */
document.addEventListener("click", (e) => {
  const link = e.target.closest("[data-open-detail]");
  if (!link) return;
  const row = link.closest(".lsc-row[data-id]");
  if (!row) return;
  openDetail(Number(row.dataset.id));
});

/* ---------------- 队列双向同步（与手机端） ---------------- */

/* 轮询服务器队列：与本地不一致且非本地刚变更（推送在途）时采纳，
   保持与手机端“一方变动另一边也同步”。不动当前播放。 */
async function lscPollQueue() {
  // 未登录 / 未进入听歌页 / 页面隐藏时绝不发请求：
  // 否则 401 会被 api() 当作登录失效强制跳登录页（初始化阶段被劫持的 bug）
  if (!state.token || !lsc.inited) return;
  if (document.visibilityState !== "visible") return;
  const tab = document.querySelector("#tab-listen");
  if (!tab || !tab.classList.contains("active")) return;
  if (Date.now() - lsc.lastLocalChange < 3000) return; // 本地刚改，等服务器追平
  try {
    const data = await api("/api/listen/queue");
    const server = data.songs || [];
    const a = server.map((s) => s.id).join(",");
    const b = lsc.queue.map((s) => s.id).join(",");
    if (a !== b && Date.now() - lsc.lastLocalChange >= 3000) {
      lsc.queue = server;
      renderQueue();
    }
  } catch { /* 离线等场景忽略 */ }
}

/* ---------------- 进入听歌页 ---------------- */

async function lscEnter() {
  try {
    await lscLoadAll();
    lsc.inited = true;
  } catch (err) {
    toast("听歌数据加载失败：" + err.message, true);
  }
}

document.querySelector('.nav-item[data-tab="listen"]').addEventListener("click", lscEnter);

/* 队列双向同步：进入听歌页后每 5s 轮询一次（页面隐藏时跳过） */
setInterval(lscPollQueue, 5000);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") lscPollQueue();
});
