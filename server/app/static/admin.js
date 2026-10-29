/* SelfKTV 管理后台前端逻辑（原生 JS，无构建步骤）。 */
"use strict";

const $ = (sel) => document.querySelector(sel);
const state = {
  token: localStorage.getItem("selfktv_token") || "",
  songs: [],
  works: [],
  setupPolling: false,
};

/* ---------------- 基础工具 ---------------- */

function toast(msg, isError = false) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.toggle("error", isError);
  el.classList.remove("hidden");
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.add("hidden"), 2600);
}

async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers);
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  const resp = await fetch(path, { ...options, headers });
  if (resp.status === 401) {
    logout(true);
    throw new Error("登录已失效，请重新登录");
  }
  let data = null;
  const text = await resp.text();
  try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
  if (!resp.ok) {
    throw new Error((data && data.detail) || `请求失败 (${resp.status})`);
  }
  return data;
}

function show(view) {
  for (const id of ["view-init", "view-login", "view-app"]) {
    $("#" + id).classList.toggle("hidden", id !== view);
  }
}

function logout(silent = false) {
  state.token = "";
  localStorage.removeItem("selfktv_token");
  show("view-login");
  if (!silent) toast("已退出登录");
}

function fmtDuration(sec) {
  sec = Math.round(Number(sec) || 0);
  const m = Math.floor(sec / 60), s = sec % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

const authQ = () => "?to" + "ken" + "=" + encodeURIComponent(state.token);

function esc(str) {
  return String(str ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

/* 分段选择器通用绑定 */
function bindSeg(segSel, onChange) {
  const seg = $(segSel);
  seg.addEventListener("click", (e) => {
    const btn = e.target.closest(".seg-btn");
    if (!btn) return;
    seg.querySelectorAll(".seg-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    onChange(btn.dataset.kind);
  });
}

/* 图片选择后即时预览 */
function bindCoverPreview(inputSel, imgSel) {
  const input = $(inputSel), img = $(imgSel);
  input.addEventListener("change", () => {
    const file = input.files[0];
    if (!file) {
      img.classList.add("hidden");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      img.src = reader.result;
      img.classList.remove("hidden");
    };
    reader.readAsDataURL(file);
  });
}

/* ---------------- 启动流程 ---------------- */

async function boot() {
  try {
    const st = await fetch("/api/admin/status").then((r) => r.json());
    if (!st.initialized) {
      show("view-init");
      return;
    }
  } catch {
    toast("无法连接服务器", true);
  }
  if (state.token) {
    try {
      await enterApp();
      return;
    } catch { /* token 失效走登录 */ }
  }
  show("view-login");
}

async function enterApp() {
  const me = await api("/api/admin/me");
  $("#whoami").textContent = "👤 " + me.username;
  $("#server-url").textContent = location.origin;
  show("view-app");
  await Promise.all([loadSongs(), loadWorks()]);
  await initSetupPanel();
}

/* ---------------- 初始化 / 登录 ---------------- */

$("#form-init").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  if (fd.get("password") !== fd.get("password2")) {
    toast("两次输入的密码不一致", true);
    return;
  }
  try {
    const data = await api("/api/admin/init", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: fd.get("username"), password: fd.get("password") }),
    });
    state.token = data.token;
    localStorage.setItem("selfktv_token", data.token);
    toast("账号创建成功，欢迎使用 SelfKTV 🎉");
    await enterApp();
  } catch (err) {
    toast(err.message, true);
  }
});

$("#form-login").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    const data = await api("/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: fd.get("username"), password: fd.get("password") }),
    });
    state.token = data.token;
    localStorage.setItem("selfktv_token", data.token);
    await enterApp();
  } catch (err) {
    toast(err.message, true);
  }
});

$("#btn-logout").addEventListener("click", () => logout());

/* ---------------- 主题切换（自动 / 白天 / 黑夜） ---------------- */

const THEME_ORDER = ["auto", "light", "dark"];
const THEME_LABEL = { auto: "🖥️ 自动", light: "☀️ 白天", dark: "🌙 黑夜" };

function themeMode() {
  try { return localStorage.getItem("selfktv_theme") || "auto"; } catch { return "auto"; }
}

function applyTheme(mode) {
  try { localStorage.setItem("selfktv_theme", mode); } catch { /* 忽略 */ }
  const dark = mode === "dark" ||
    (mode === "auto" && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
  const btn = $("#btn-theme");
  if (btn) {
    btn.textContent = THEME_LABEL[mode] || THEME_LABEL.auto;
    btn.title = "主题：" + (mode === "auto" ? "自动（跟随系统）" : mode === "light" ? "白天" : "黑夜") + "，点击切换";
  }
}

$("#btn-theme").addEventListener("click", () => {
  const cur = themeMode();
  applyTheme(THEME_ORDER[(THEME_ORDER.indexOf(cur) + 1) % THEME_ORDER.length]);
});

if (window.matchMedia) {
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (themeMode() === "auto") applyTheme("auto"); // 自动模式跟随系统实时切换
  });
}
applyTheme(themeMode());

/* ---------------- 分离组件准备（进度条） ---------------- */

async function initSetupPanel() {
  try {
    const st = await api("/api/admin/setup/status");
    if (st.ready) {
      $("#setup-panel").classList.add("hidden");
      return;
    }
    $("#setup-panel").classList.remove("hidden");
    renderSetup(st);
    if (!st.installing) {
      await api("/api/admin/setup/install", { method: "POST" });
    }
    pollSetup();
  } catch { /* 忽略 */ }
}

function renderSetup(st) {
  $("#setup-bar").style.width = (st.percent || 0) + "%";
  $("#setup-percent").textContent = (st.percent || 0) + "%";
  $("#setup-text").textContent = st.error
    ? `❌ ${st.error}`
    : (st.stage_text || (st.installing ? "正在准备…" : "等待开始…"));
  $("#setup-log").textContent = (st.log || []).join("\n");
  $("#btn-setup-install").style.display = st.installing ? "none" : "";
  $("#btn-setup-install").textContent = st.error ? "重试安装" : "立即安装";
}

function pollSetup() {
  if (state.setupPolling) return;
  state.setupPolling = true;
  const timer = setInterval(async () => {
    try {
      const st = await api("/api/admin/setup/status");
      renderSetup(st);
      if (st.ready || (!st.installing && st.stage === "error")) {
        clearInterval(timer);
        state.setupPolling = false;
        if (st.ready) {
          $("#setup-panel").classList.add("hidden");
          toast("伴奏分离组件已就绪 🎉");
          loadSongs();
        }
      }
    } catch {
      clearInterval(timer);
      state.setupPolling = false;
    }
  }, 1500);
}

$("#btn-setup-install").addEventListener("click", async () => {
  try {
    await api("/api/admin/setup/install", { method: "POST" });
    pollSetup();
  } catch (err) {
    toast(err.message, true);
  }
});

/* ---------------- 导航 ---------------- */

document.querySelectorAll(".nav-item").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const tab = btn.dataset.tab;
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + tab));
    if (tab === "works") loadWorks();
    if (tab === "songs") initSetupPanel();
  });
});

/* ---------------- 歌曲 ---------------- */

async function loadSongs(query = "") {
  const url = "/api/admin/songs" + (query ? `?q=${encodeURIComponent(query)}` : "");
  const data = await api(url);
  state.songs = data.songs;
  renderSongs();
}

function renderSongs() {
  const tbody = $("#song-tbody");
  $("#song-empty").classList.toggle("hidden", state.songs.length > 0);
  tbody.innerHTML = state.songs.map((s) => `
    <tr>
      <td>${s.cover_url
        ? `<img class="cover-thumb" src="${esc(s.cover_url)}${authQ()}" alt="">`
        : `<div class="cover-thumb">🎵</div>`}</td>
      <td><b>${esc(s.title)}</b><div class="muted">${esc(s.artist || "未知歌手")}</div></td>
      <td>${fmtDuration(s.duration)}</td>
      <td>
        <span class="badge ${s.has_vocal && s.has_accomp ? "on" : ""}">原唱</span>
        <span class="badge ${s.has_vocal ? "on" : ""}">人声</span>
        <span class="badge ${s.has_accomp ? "on" : ""}">伴奏</span>
        <span class="badge ${s.smart_available ? "on" : ""}">智能</span>
      </td>
      <td>${sepStatusCell(s)}</td>
      <td>${refStatusCell(s)}</td>
      <td class="muted">${esc(s.updated_at || "")}</td>
      <td>
        ${s.original_file ? `<button class="btn small ghost" onclick="reSeparate(${s.id})">重新分离</button>` : ""}
        ${s.has_vocal ? `<button class="btn small ghost" onclick="transcribeSong(${s.id})">🎯 转录</button>` : ""}
        <button class="btn small ghost" onclick="editSong(${s.id})">编辑</button>
        <button class="btn small danger" onclick="deleteSong(${s.id}, '${esc(s.title)}')">删除</button>
      </td>
    </tr>`).join("");
}

function sepStatusCell(s) {
  if (!s.original_file) return '<span class="badge">手动音轨</span>';
  switch (s.separate_status) {
    case "queued":
    case "running":
      return '<span class="badge on">⏳ 分离中…</span>';
    case "done":
      return '<span class="badge on">✅ 已分离</span>';
    case "error":
      return `<span class="badge" style="background:#fdecec;color:#e5484d" title="${esc(s.separate_error || "")}">❌ 分离失败</span>`;
    default:
      return "";
  }
}

function refStatusCell(s) {
  switch (s.ref_status) {
    case "queued":
    case "running":
      return '<span class="badge on">🎯 转录中…</span>';
    case "error":
      return `<span class="badge" style="background:#fdecec;color:#e5484d" title="${esc(s.ref_error || "")}">🎯 转录失败</span>`;
    case "done":
      return s.has_pitch ? '<span class="badge on">🎯 可打分</span>' : '<span class="badge">🎯 待转录</span>';
    default:
      // 分离中 / 尚未开始转录：始终显示待转录
      return '<span class="badge">🎯 待转录</span>';
  }
}

/* 参考旋律转录（异步任务） */
window.transcribeSong = async (id) => {
  try {
    const job = await api(`/api/admin/songs/${id}/transcribe`, { method: "POST" });
    toast("转录任务已提交，完成后自动刷新…");
    pollTranscribe(job.jobId);
  } catch (err) {
    toast(err.message, true);
  }
};

function pollTranscribe(jobId) {
  clearInterval(window._trT);
  let ticks = 0;
  window._trT = setInterval(async () => {
    ticks++;
    if (ticks > 300) { clearInterval(window._trT); return; }
    try {
      const j = await api(`/api/admin/jobs/${jobId}`);
      if (j.status === "done") {
        clearInterval(window._trT);
        toast("参考旋律转录完成 🎯");
        loadSongs();
      } else if (j.status === "error") {
        clearInterval(window._trT);
        toast("转录失败：" + (j.error || "未知错误"), true);
      }
    } catch {
      clearInterval(window._trT);
    }
  }, 2000);
}

$("#song-search").addEventListener("input", (e) => {
  clearTimeout(window._searchT);
  window._searchT = setTimeout(() => loadSongs(e.target.value.trim()), 300);
});
$("#btn-refresh-songs").addEventListener("click", () => {
  loadSongs($("#song-search").value.trim()).then(() => toast("已刷新"));
});

$("#btn-show-upload").addEventListener("click", () => {
  $("#upload-panel").classList.toggle("hidden");
});
$("#btn-cancel-upload").addEventListener("click", () => {
  $("#upload-panel").classList.add("hidden");
  $("#form-upload").reset();
  $("#upload-cover-preview").classList.add("hidden");
});

bindCoverPreview("#upload-cover", "#upload-cover-preview");
bindCoverPreview("#edit-cover", "#edit-cover-preview");

/* 上传模式：智能分离 / 手动单文件 */
bindSeg("#upload-mode", (kind) => {
  const manual = kind === "manual";
  $("#upload-kind").value = manual ? $("#upload-kind-select").value : "auto";
  $("#wrap-kind").classList.toggle("hidden", !manual);
  $("#wrap-main-file").childNodes[0].nodeValue = manual
    ? "音频文件 "
    : "原曲音频（自动提取歌词 + 分离人声/伴奏） ";
});
$("#upload-kind-select").addEventListener("change", () => {
  if (!$("#wrap-kind").classList.contains("hidden")) {
    $("#upload-kind").value = $("#upload-kind-select").value;
  }
});

/* 选择音频后自动读取歌名/歌手 */
$("#upload-audio").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  $("#probe-hint").textContent = "正在读取音频信息…";
  const fd = new FormData();
  fd.append("file", file);
  try {
    const info = await api("/api/admin/probe", { method: "POST", body: fd });
    if (info.title) $("#upload-title").value = info.title;
    if (info.artist) $("#upload-artist").value = info.artist;
    $("#upload-duration").value = info.duration || 0;
    $("#probe-hint").textContent = `已读取：${info.title || "（无歌名标签）"} - ${info.artist || "（无歌手标签）"}` +
      (info.duration ? `，时长 ${fmtDuration(info.duration)}` : "");
  } catch (err) {
    $("#probe-hint").textContent = "读取音频信息失败：" + err.message;
  }
});

$("#form-upload").addEventListener("submit", (e) => {
  e.preventDefault();
  const form = e.target;
  const fd = new FormData(form);
  if (!fd.get("audio").name && !fd.get("vocal").name && !fd.get("accomp").name) {
    toast("请选择音频文件", true);
    return;
  }
  if (!$("#wrap-kind").classList.contains("hidden")) {
    fd.set("kind", $("#upload-kind-select").value);
  } else {
    fd.set("kind", "auto");
  }
  uploadWithProgress("/api/admin/songs", fd, "歌曲上传成功 🎉", () => {
    form.reset();
    $("#probe-hint").textContent = "";
    $("#upload-cover-preview").classList.add("hidden");
    $("#upload-panel").classList.add("hidden");
    loadSongs();
    refreshSongsWhileSeparating();
  });
});

/* 分离进行中时定时刷新列表 */
function refreshSongsWhileSeparating() {
  clearInterval(window._sepT);
  let ticks = 0;
  window._sepT = setInterval(async () => {
    ticks++;
    if (ticks > 120) { clearInterval(window._sepT); return; }
    try {
      await loadSongs($("#song-search").value.trim());
      if (!state.songs.some((s) => s.separating)) clearInterval(window._sepT);
    } catch { clearInterval(window._sepT); }
  }, 30000);
}

window.reSeparate = async (id) => {
  try {
    await api(`/api/admin/songs/${id}/separate`, { method: "POST" });
    toast("已加入分离队列");
    loadSongs();
    refreshSongsWhileSeparating();
  } catch (err) {
    toast(err.message, true);
  }
};

function uploadWithProgress(url, fd, okMsg, done) {
  const xhr = new XMLHttpRequest();
  xhr.open("POST", url);
  xhr.setRequestHeader("Authorization", "Bearer " + state.token);
  const wrap = $("#upload-progress"), bar = $("#upload-bar");
  wrap.classList.remove("hidden");
  bar.style.width = "0%";

  xhr.upload.onprogress = (ev) => {
    if (ev.lengthComputable) bar.style.width = (ev.loaded / ev.total * 100).toFixed(1) + "%";
  };
  xhr.onload = () => {
    wrap.classList.add("hidden");
    if (xhr.status >= 200 && xhr.status < 300) {
      toast(okMsg);
      done && done();
    } else {
      let msg = "上传失败";
      try { msg = JSON.parse(xhr.responseText).detail || msg; } catch {}
      toast(msg, true);
    }
  };
  xhr.onerror = () => {
    wrap.classList.add("hidden");
    toast("网络错误，上传失败", true);
  };
  xhr.send(fd);
}

window.deleteSong = async (id, title) => {
  if (!confirm(`确定删除歌曲「${title}」？其音频文件将一并删除。`)) return;
  try {
    await api(`/api/admin/songs/${id}`, { method: "DELETE" });
    toast("已删除");
    loadSongs();
  } catch (err) {
    toast(err.message, true);
  }
};

/* ---------------- 编辑歌曲（加载现有数据） ---------------- */

window.editSong = (id) => {
  const song = state.songs.find((s) => s.id === id);
  if (!song) return;
  const form = $("#form-edit");
  form.elements.id.value = song.id;
  $("#edit-title").value = song.title;
  $("#edit-artist").value = song.artist || "";

  // 现有文件状态
  const badge = (label, has) =>
    `<span class="badge ${has ? "on" : ""}">${has ? "✓" : "✗"} ${label}</span>`;
  $("#edit-file-status").innerHTML =
    badge("原曲", song.original_file) +
    badge("人声", song.has_vocal) +
    badge("伴奏", song.has_accomp) +
    badge("歌词", song.has_lyric) +
    badge("封面", song.has_cover) +
    badge("音高数据", song.has_pitch);

  $("#edit-audio").value = "";
  form.elements.lyric.value = "";
  form.elements.cover.value = "";
  // 封面预览：先展示现有封面，选择新图后即时替换
  const coverPreview = $("#edit-cover-preview");
  if (song.cover_url) {
    coverPreview.src = esc(song.cover_url) + authQ();
    coverPreview.classList.remove("hidden");
  } else {
    coverPreview.classList.add("hidden");
  }
  $("#edit-kind").value = "auto";
  $("#edit-mode").querySelectorAll(".seg-btn").forEach((b, i) => b.classList.toggle("active", i === 0));
  $("#edit-wrap-kind").classList.add("hidden");
  $("#edit-wrap-main").childNodes[0].nodeValue = "新的原曲音频（留空不换） ";
  $("#btn-reseparate").hidden = !song.original_file;
  $("#modal-edit").classList.remove("hidden");
};

bindSeg("#edit-mode", (kind) => {
  const manual = kind === "manual";
  $("#edit-kind").value = manual ? $("#edit-kind-select").value : "auto";
  $("#edit-wrap-kind").classList.toggle("hidden", !manual);
  $("#edit-wrap-main").childNodes[0].nodeValue = manual
    ? "替换的音频文件（留空不换） "
    : "新的原曲音频（留空不换） ";
});
$("#edit-kind-select").addEventListener("change", () => {
  if (!$("#edit-wrap-kind").classList.contains("hidden")) {
    $("#edit-kind").value = $("#edit-kind-select").value;
  }
});

$("#btn-reseparate").addEventListener("click", () => {
  const id = $("#form-edit").elements.id.value;
  $("#modal-edit").classList.add("hidden");
  window.reSeparate(Number(id));
});

$("#btn-cancel-edit").addEventListener("click", () => $("#modal-edit").classList.add("hidden"));

$("#form-edit").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target;
  const fd = new FormData(form);
  const id = fd.get("id");
  if (!$("#edit-wrap-kind").classList.contains("hidden")) {
    fd.set("kind", $("#edit-kind-select").value);
  } else {
    fd.set("kind", "auto");
  }
  try {
    await api(`/api/admin/songs/${id}`, { method: "PUT", body: fd });
    toast("保存成功");
    $("#modal-edit").classList.add("hidden");
    loadSongs();
    refreshSongsWhileSeparating();
  } catch (err) {
    toast(err.message, true);
  }
});

/* ---------------- 作品 ---------------- */

async function loadWorks() {
  const data = await api("/api/admin/works");
  state.works = data.works;
  renderWorks();
}

function renderWorks() {
  const tbody = $("#work-tbody");
  $("#work-empty").classList.toggle("hidden", state.works.length > 0);
  tbody.innerHTML = state.works.map((w) => `
    <tr>
      <td><b>${esc(w.title)}</b></td>
      <td>${esc(w.song_title || "-")}</td>
      <td><span class="badge on">${{ mix: "原唱", vocal: "人声", accomp: "伴奏", smart: "智能" }[w.mode] || esc(w.mode)}</span></td>
      <td>${esc(w.username || "-")}</td>
      <td>${fmtDuration(w.duration)}</td>
      <td class="muted">${esc(w.created_at || "")}</td>
      <td><audio controls preload="none" src="${esc(w.audio_url)}${authQ()}" style="height:32px;max-width:220px"></audio></td>
      <td><button class="btn small danger" onclick="deleteWork(${w.id})">删除</button></td>
    </tr>`).join("");
}

$("#btn-refresh-works").addEventListener("click", loadWorks);

window.deleteWork = async (id) => {
  if (!confirm("确定删除该作品？")) return;
  try {
    await api(`/api/admin/works/${id}`, { method: "DELETE" });
    toast("已删除");
    loadWorks();
  } catch (err) {
    toast(err.message, true);
  }
};

/* ---------------- 账号 ---------------- */

$("#form-password").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api("/api/admin/password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        old_password: fd.get("old_password"),
        new_password: fd.get("new_password"),
      }),
    });
    toast("密码已修改");
    e.target.reset();
  } catch (err) {
    toast(err.message, true);
  }
});


/* ---------------- 搜歌导入（GD音乐台） ---------------- */

const musicState = { items: [], picked: new Set(), submitting: false, polling: false, playingIdx: -1 };

function musicStatus(msg, kind = "") {
  const el = $("#music-status");
  el.textContent = msg || "";
  el.className = "music-status " + kind;
}

async function musicSearch() {
  const kw = $("#music-keyword").value.trim();
  if (!kw) { musicStatus("请输入搜索关键字", "err"); return; }
  const source = $("#music-source").value;
  const btn = $("#btn-music-search");
  btn.disabled = true;
  btn.textContent = "搜索中…";
  musicStatus("⏳ 正在搜索，请稍候…");
  try {
    const data = await api(`/api/admin/music/search?keyword=${encodeURIComponent(kw)}&source=${source}&page=1`);
    musicState.items = data.items || [];
    musicState.picked = new Set();
    musicState.playingIdx = -1;
    renderMusicResults();
    musicStatus(
      musicState.items.length
        ? `找到 ${musicState.items.length} 首 · 点「▶ 试听」看封面听歌看歌词 · 勾选后点「导入选中」`
        : "没有找到相关歌曲，换个关键字试试",
      musicState.items.length ? "ok" : ""
    );
  } catch (err) {
    musicState.items = [];
    renderMusicResults();
    musicStatus("搜索失败：" + err.message, "err");
  } finally {
    btn.disabled = false;
    btn.textContent = "搜索";
  }
}

function picUrl(it) {
  return `/api/admin/music/pic?source=${encodeURIComponent(it.source)}&pic_id=${encodeURIComponent(it.pic_id)}&size=300${authQ()}`;
}

function renderMusicResults() {
  const host = $("#music-results");
  const toolbar = $("#music-toolbar");
  if (!musicState.items.length) {
    host.innerHTML = "";
    toolbar.classList.add("hidden");
    return;
  }
  toolbar.classList.remove("hidden");
  host.innerHTML = musicState.items.map((it, i) => `
    <div class="music-item" data-idx="${i}">
      <div class="music-row">
        <input type="checkbox" class="m-pick" data-idx="${i}" ${musicState.picked.has(i) ? "checked" : ""}>
        <img class="m-cover" src="${picUrl(it)}" loading="lazy" alt=""
             onerror="this.classList.add('m-cover-fallback');this.removeAttribute('src')">
        <div class="m-info">
          <div class="m-name">${esc(it.name)} <span class="muted" style="font-weight:normal">— ${esc((it.artist || []).join("、"))}</span></div>
          <div class="m-sub">${esc(it.album || "")}${it.album ? " · " : ""}${esc(it.source || "")}</div>
        </div>
        <button class="btn small ghost m-play" data-idx="${i}">▶ 试听</button>
      </div>
      <div class="m-preview hidden" id="m-preview-${i}"></div>
    </div>`).join("");
  host.querySelectorAll(".m-pick").forEach((el) => {
    el.addEventListener("change", () => {
      const idx = Number(el.dataset.idx);
      if (el.checked) musicState.picked.add(idx);
      else musicState.picked.delete(idx);
      updatePickedCount();
    });
  });
  host.querySelectorAll(".m-play").forEach((el) => {
    el.addEventListener("click", () => togglePreview(Number(el.dataset.idx)));
  });
  updatePickedCount();
}

function stopPreview() {
  document.querySelectorAll(".m-preview").forEach((p) => {
    const a = p.querySelector("audio");
    if (a) { try { a.pause(); } catch (_) {} }
    p.innerHTML = "";
    p.classList.add("hidden");
  });
  document.querySelectorAll(".m-play").forEach((b) => (b.textContent = "▶ 试听"));
  musicState.playingIdx = -1;
}

async function togglePreview(idx) {
  const it = musicState.items[idx];
  if (!it) return;
  if (musicState.playingIdx === idx) { stopPreview(); return; }
  stopPreview();
  const panel = document.getElementById(`m-preview-${idx}`);
  if (!panel) return;
  panel.classList.remove("hidden");
  panel.innerHTML = `<div class="preview-loading">⏳ 加载试听…</div>`;
  const btn = document.querySelector(`.m-play[data-idx="${idx}"]`);
  if (btn) btn.textContent = "⏹ 停止";
  musicState.playingIdx = idx;
  try {
    const data = await api(`/api/admin/music/preview?source=${encodeURIComponent(it.source)}&id=${encodeURIComponent(it.id)}&br=${parseInt($("#music-br").value, 10) || 320}`);
    if (musicState.playingIdx !== idx) return;
    const lyricText = (data.lyric || "")
      .split("\n")
      .map((l) => l.replace(/\[[^\]]*\]/g, "").trim())
      .filter(Boolean)
      .join("\n");
    panel.innerHTML = `
      <div class="preview-inner">
        <img class="p-cover" src="${picUrl(it)}" alt="" onerror="this.classList.add('m-cover-fallback');this.removeAttribute('src')">
        <div class="p-right">
          <audio controls autoplay src="${esc(data.url)}"></audio>
          <pre class="m-lyric">${lyricText ? esc(lyricText) : "（暂无歌词）"}</pre>
        </div>
      </div>`;
  } catch (err) {
    if (musicState.playingIdx !== idx) return;
    panel.innerHTML = `<div class="preview-loading">试听失败：${esc(err.message)}</div>`;
  }
}

function updatePickedCount() {
  const n = musicState.picked.size;
  $("#music-picked-count").textContent = n ? `已选 ${n} 首` : "未选择";
  const btn = $("#btn-music-import");
  btn.textContent = n ? `📥 导入选中（${n} 首）` : "📥 导入选中";
  btn.disabled = musicState.submitting;
}

window.musicImportSelected = async () => {
  const picks = [...musicState.picked].sort((a, b) => a - b).map((i) => musicState.items[i]).filter(Boolean);
  if (!picks.length) { musicStatus("请先勾选要导入的歌曲", "err"); return; }
  if (musicState.submitting) return;
  stopPreview();
  musicState.submitting = true;
  updatePickedCount();
  let submitted = 0;
  for (const it of picks) {
    const br = parseInt($("#music-br").value, 10) || 320;
    try {
      await api("/api/admin/music/import", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(Object.assign({}, it, { br })),
      });
      submitted += 1;
    } catch (err) {
      musicStatus(`提交「${it.name}」失败：${err.message}`, "err");
    }
  }
  musicState.submitting = false;
  musicState.picked = new Set();
  renderMusicResults();
  musicStatus(`已提交 ${submitted}/${picks.length} 首，正在下载（歌曲→歌词→封面→入库），完成后自动刷新歌单`, "ok");
  if (submitted > 0) startImportPolling();
};

function startImportPolling() {
  if (musicState.polling) return;
  musicState.polling = true;
  const tick = async () => {
    // 未登录不发请求：否则 401 会被 api() 当作登录失效强制跳登录页
    // （初始化/登录页阶段被劫持的 bug）
    if (!state.token) {
      musicState.polling = false;
      return;
    }
    try {
      const data = await api("/api/admin/music/imports");
      const jobs = data.jobs || [];
      renderImports(jobs);
      if (jobs.some((j) => j.status === "running")) {
        setTimeout(tick, 3000);
      } else {
        musicState.polling = false;
        loadSongs();
        const done = jobs.filter((j) => j.status === "done").length;
        const failed = jobs.filter((j) => j.status === "error").length;
        if (done) toast(`导入完成 ${done} 首，歌单已刷新（自动分离/转录进行中）`);
        if (failed) toast(`${failed} 首导入失败`, true);
      }
    } catch {
      musicState.polling = false;
    }
  };
  tick();
}

function renderImports(jobs) {
  const host = $("#music-imports");
  if (!jobs.length) { host.innerHTML = ""; return; }
  const stateIcon = { run: "…", done: "✓", fail: "✗", wait: "" };
  host.innerHTML = jobs.map((j) => {
    const icon = j.status === "done" ? "✅" : j.status === "error" ? "❌" : "⏳";
    const chips = (j.steps || [])
      .map((s) => `<span class="step-chip step-${s.state}">${esc(s.label)}${stateIcon[s.state] || ""}</span>`)
      .join("");
    return `<div class="imp-row">
        ${icon} <b>${esc(j.name)}</b>
        <span class="step-chips">${chips}</span>
        <span class="muted">${j.error ? "· " + esc(j.error) : ""}</span>
      </div>`;
  }).join("");
}

$("#btn-music-search").addEventListener("click", musicSearch);
$("#music-keyword").addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); musicSearch(); }
});
$("#btn-music-import").addEventListener("click", () => window.musicImportSelected());
$("#music-check-all").addEventListener("change", (e) => {
  if (e.target.checked) {
    musicState.items.forEach((_, i) => musicState.picked.add(i));
  } else {
    musicState.picked = new Set();
  }
  renderMusicResults();
});
startImportPolling();



boot();
