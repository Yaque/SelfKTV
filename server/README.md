# SelfKTV 服务端

Python 编写的 K 歌系统服务端：REST API + 网页管理后台 + SQLite 存储。

## 部署

两套部署规则按环境二选一：

### 方式一：Docker（推荐服务器/NAS）

```bash
cd server

# 规则A 轻量版（不带伴奏分离，镜像小）
docker compose up -d selfktv

# 规则B 分离增强版（demucs + 模型已内置，开箱即自动分离，镜像约 +2GB）
docker compose up -d selfktv-ai
```

- 运行期数据持久化在 `./data/`（数据库/歌曲/作品/密钥）；增强版另有 `torch-cache` 卷存模型缓存
- 两套规则共用 `Dockerfile`，由 `build.args: WITH_AI` 区分；也可手动 `docker build --build-arg WITH_AI=true -t selfktv-server-ai .`
- 轻量版容器内可通过管理页「分离组件准备」在线安装分离引擎（需容器可上网；重建容器会丢失，建议直接用增强版）
- 健康检查：`/api/admin/status`，每 30s 探测

### 方式二：真机（裸机，物理机/虚拟机/树莓派）

```bash
cd server
./deploy/install.sh                    # 轻量安装
./deploy/install.sh --with-separation  # 带伴奏分离（demucs + 模型预下载）
```

- 脚本自动创建 `.venv`、装依赖、建 data 目录（幂等，可重复执行）
- 启动：`.venv/bin/python run.py --host 0.0.0.0 --port 8000`
- 常驻：`deploy/selfktv.service` 为 systemd 单元模板（改路径/用户后 `systemctl enable --now selfktv`）

### 环境要求

- Python 3.10+
- （可选）伴奏分离引擎：`pip install -r requirements-separation.txt`（安装 demucs）；也可在管理页初始化向导里**一键安装（带进度条）**

### 启动

```bash
pip install -r requirements.txt
pip install -r requirements-separation.txt   # 可选：自动分离人声/伴奏（推荐）
python run.py                 # 监听 0.0.0.0:8000
python run.py --port 9000     # 自定义端口
python run.py --reload        # 开发模式
```

> 以上为裸机直接运行；Docker/脚本部署见「部署方式」。

启动后访问：

| 地址 | 用途 |
| --- | --- |
| `http://<host>:8000/admin/` | 网页管理后台 |
| `http://<host>:8000/api/docs` | API 文档（Swagger） |

### 首次部署：账号初始化

第一次打开管理页会自动进入**账号初始化向导**：设置用户名和密码即完成。
该账号为唯一个人账号，同时用于：

- 管理后台登录（上传/管理歌曲、作品）
- Android App 登录

> 初始化只能执行一次；忘记密码可删除 `server/data/` 后重新初始化（歌曲数据也会清空），或用 SQLite 手工处理。

### 管理页功能

- **初始化向导**：创建账号后自动展示**分离组件准备进度条**（安装 demucs + 下载模型，带阶段/百分比/日志，失败可重试）
- **歌曲管理**：上传、搜索、编辑、删除
  - **上传二选一**：🤖 智能分离（上传原曲）或 ✍️ 手动上传单个文件并指定其为 人声/伴奏/原唱混合
  - 选择音频后**自动读取歌曲名/歌手**（ID3/MP4/Vorbis 标签）并填入表单
  - 自动提取内嵌歌词；**分离完成自动生成参考旋律**（供 App 音准器/打分）
  - 分离状态可视（排队/分离中/完成/失败），支持一键**重新分离**；服务重启自动恢复未完成任务
  - **编辑弹窗加载现有数据**：标题/歌手 + 原曲/人声/伴奏/歌词/封面/音高数据的现状徽章，留空的文件不替换
  - 分离引擎：**demucs**（Meta 开源，htdemucs）；备选 audio-separator（UVR-MDX-Net ONNX）
- **作品管理**：查看/试听/删除用户录音作品
- **账号设置**：修改密码、查看 App 连接地址

## API 概览

所有 `/api/*`（除登录/初始化/状态）需请求头 `Authorization: Bearer <token>`；
音频流接口额外支持 `?token=` 查询参数（供网页 `<audio>` 标签使用）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ping` | 健康检查 |
| POST | `/api/auth/login` | App 登录，返回 token |
| GET | `/api/songs?q=` | 歌曲列表/搜索（含音轨 URL、模式可用性） |
| GET | `/api/songs/{id}` | 歌曲详情 |
| GET | `/api/songs/{id}/stream?track=vocal\|accomp\|original\|auto` | 音频流（支持 HTTP Range；auto 优先伴奏→原唱→原曲） |
| GET | `/api/songs/{id}/lyrics` | LRC 歌词 |
| GET | `/api/songs/{id}/pitch` | 参考音高轮廓（旧版兼容） |
| GET | `/api/songs/{id}/notes` | 参考旋律音符事件 `{songId, version, notes:[{t,d,m,c}]}` |
| GET | `/api/songs/{id}/cover` | 封面图 |
| POST | `/api/works` | 上传录音作品（multipart） |
| GET | `/api/works` | 我的作品列表 |
| GET | `/api/works/{id}/stream` | 作品音频流 |
| DELETE | `/api/works/{id}` | 删除作品 |
| GET | `/api/admin/status` | 是否已初始化 |
| POST | `/api/admin/init` | 账号初始化（仅首次） |
| POST | `/api/admin/login` | 管理登录 |
| GET/POST/PUT/DELETE | `/api/admin/songs...` | 歌曲管理（上传原曲自动分离） |
| POST | `/api/admin/songs/{id}/separate` | 手动触发/重新触发分离 |
| POST | `/api/admin/songs/{id}/transcribe` | 异步转录参考旋律（202 + jobId） |
| GET | `/api/admin/jobs/{jobId}` | 转录任务状态/结果（queued/running/done/error） |
| POST | `/api/admin/probe` | 读取音频标签（自动填充歌名/歌手/时长） |
| GET | `/api/admin/setup/status` | 分离组件准备进度 |
| POST | `/api/admin/setup/install` | 开始安装分离组件（后台，带进度） |
| GET/DELETE | `/api/admin/works...` | 作品管理 |
| POST | `/api/admin/password` | 修改密码 |
| GET | `/api/admin/music/search?keyword=&source=&page=` | 搜歌（GD音乐台）：曲目列表（id/歌名/歌手/专辑/封面ID/歌词ID） |
| POST | `/api/admin/music/import` | 一键导入：下载音频/歌词/封面 → 建歌 → 自动分离 → 自动转录 |
| GET | `/api/admin/music/imports` | 导入任务进度（排队/下载中/完成/失败） |
| GET/PUT/POST/DELETE | `/api/listen/queue` | 听歌·播放队列（服务端存储，网页/App 双端同步；PUT=整份替换） |
| GET/POST/DELETE | `/api/listen/favorites...` | 听歌·收藏（幂等添加/取消） |
| GET/POST/PUT/DELETE | `/api/listen/playlists...` | 听歌·自建歌单（创建/重命名/删除/加歌/删歌/排序） |

### 参考旋律转录（打分/音准器）

人声分离完成后自动转录为**音符事件 JSON**（t 起始秒 / d 时长 / m MIDI / c 置信度），客户端缓存后做实时比对：

- 🥇 **Basic Pitch**（`requirements-transcribe.txt`，需 TF，建议 Python 3.10~3.12）——一步输出音符事件
- 🥈 **pYIN**（librosa）——单音基频 + 音符切分
- 🥉 内置自相关切分——零依赖兕底，始终可用

工程规则：置信度 < 0.3 剔除、最短音符 60ms、人声/伴奏包络互相关对齐校正时间原点、信号量串行转录、结果持久化不重复推理。

## 听歌模块

独立于 K 歌的音乐收听能力，网页端与 App 双端实现，数据服务端同步：

- **播放队列**：存服务器，双端同步（整份替换、后写覆盖；进入页面自动恢复）
- **收藏 / 自建歌单**：增删改、排序、播放全部；删除歌曲自动级联清理关联
- **App 原曲缓存**：播放过的音轨自动落盘（LRU 500MB），下次离线可播

## 数据目录

```
server/data/
├── selfktv.db          # SQLite 数据库
├── secret.key          # 令牌签名密钥（自动生成）
└── uploads/
    ├── songs/<id>/     # original.* vocal.* accomp.* lyric.lrc cover.*
    └── works/          # 录音作品 WAV
（另有 data/models/ 保存分离模型缓存，约 350MB）
```

备份 `server/data/` 即完整备份；删除该目录即重置系统。

## 搜歌导入（GD音乐台）

管理页「🎵 搜歌导入」：搜索 → 下载（音频 + LRC 歌词（含译文合并）+ 专辑封面）→ 自动分离 → 自动转录，全流程无人值守。

- 音乐源：`netease` / `joox` / `bilibili`（按站点公告动态开放）；音质 128/192/320/740（16bit）/999（Hi-Res）
- 多节点回退：`music-api-cn/hk/us.gdstudio.xyz` + `music-api.gdstudio.xyz`（网页镜像 music.gdstudio.org 带防爬，仅兜底）
- 客户端主动限速：5 分钟 ≤ 45 次（站方限制 50 次）
- **数据来源：GD音乐台（music.gdstudio.xyz）· 仅供个人学习使用，请勿商用**

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SELFKTV_DATA` | `server/data` | 数据目录 |
| `SELFKTV_MAX_UPLOAD_MB` | `512` | 单文件上传上限 |
| `SELFKTV_TOKEN_TTL` | `2592000` | 登录令牌有效期（秒） |

## 生产部署建议

个人使用直接 `python run.py` 即可。如需长期运行/对外暴露：

```bash
# systemd / supervisor 常驻
uvicorn app.main:app --host 0.0.0.0 --port 8000

# 对外暴露建议套 HTTPS 反代（App 端 http/https 均支持）
```
