#!/usr/bin/env bash
# SelfKTV 服务端 · 真机（裸机）安装脚本
#
#   ./install.sh                     # 轻量安装（不带伴奏分离）
#   ./install.sh --with-separation   # 带伴奏分离（demucs + 模型预下载，约 +2GB）
#   ./install.sh --port 9000         # 自定义端口（仅影响打印的启动命令）
#   ./install.sh --no-venv           # 不建虚拟环境，直接装到当前 Python（用户级）
#
# 幂等：重复执行只补装缺失依赖。
set -e

WITH_AI=false
PORT=8000
USE_VENV=true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-separation) WITH_AI=true; shift ;;
        --port) PORT="$2"; shift 2 ;;
        --no-venv) USE_VENV=false; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

DIR="$(cd "$(dirname "$0")/.." && pwd)"     # server/ 根目录
VENV="$DIR/.venv"

echo "== SelfKTV 真机安装 =="
echo "目录: $DIR"

if ! command -v python3 >/dev/null; then
    echo "需要 python3（建议 3.10+）"; exit 1
fi

# ---- 运行环境二选一：虚拟环境（优先） / 当前 Python ----
PY="python3"
PIP_BIN="pip3"
PIP_EXTRA=()
if [[ "$USE_VENV" == "true" ]]; then
    if [[ ! -x "$VENV/bin/python" ]]; then
        echo "-- 创建虚拟环境 $VENV"
        if ! python3 -m venv "$VENV" 2>/dev/null; then
            echo "!! venv 创建失败（通常缺 python3-venv 包，Debian/Ubuntu: apt install python3.12-venv）"
            echo "   回退到用户级安装（pip install --user）"
            rm -rf "$VENV"
            USE_VENV=false
        fi
    fi
    if [[ "$USE_VENV" == "true" ]]; then
        PY="$VENV/bin/python"
        PIP_BIN="$VENV/bin/pip"
    fi
fi
if [[ "$USE_VENV" == "false" ]]; then
    PY="python3"
    PIP_BIN="pip3"
    PIP_EXTRA=(--user)
fi

"$PIP_BIN" install -q "${PIP_EXTRA[@]}" --upgrade pip >/dev/null 2>&1 || true
echo "-- 安装核心依赖"
"$PIP_BIN" install -q "${PIP_EXTRA[@]}" -r "$DIR/requirements.txt"

if [[ "$WITH_AI" == "true" ]]; then
    echo "-- 安装伴奏分离引擎（demucs，含 PyTorch，体积较大）"
    "$PIP_BIN" install -q "${PIP_EXTRA[@]}" -r "$DIR/requirements-separation.txt"
    echo "-- 预下载分离模型（约 80MB）"
    HF_HUB_OFFLINE=1 "$PY" -c "from demucs.pretrained import get_model; get_model('htdemucs')"
fi

mkdir -p "$DIR/data"

echo
echo "== 安装完成 =="
echo "启动服务："
echo "  $PY $DIR/run.py --host 0.0.0.0 --port $PORT"
echo
echo "常驻运行（systemd，仅 venv 模式）："
echo "  sudo cp $DIR/deploy/selfktv.service /etc/systemd/system/selfktv.service"
echo "  # 按需修改服务文件里的路径/用户后："
echo "  sudo systemctl daemon-reload && sudo systemctl enable --now selfktv"
echo
echo "然后打开 http://<本机IP>:$PORT/admin/ 完成账号初始化。"
