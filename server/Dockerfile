# SelfKTV 服务端镜像
#
#   轻量版（不带伴奏分离）：  docker build -t selfktv-server .
#   分离增强版（含 demucs）：  docker build --build-arg WITH_AI=true -t selfktv-server-ai .
#
# 两套规则的差异：
#   WITH_AI=true  额外安装 demucs 并预下载 htdemucs 模型（约 +2GB 镜像），启动即可自动分离
#   默认 false    仅核心服务；如需分离可在管理页「分离组件准备」里在线安装（需容器可上网）

FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt requirements-separation.txt ./
RUN pip install --no-cache-dir -r requirements.txt

ARG WITH_AI=false
RUN if [ "$WITH_AI" = "true" ]; then \
        pip install --no-cache-dir -r requirements-separation.txt && \
        HF_HUB_OFFLINE=1 python -c "from demucs.pretrained import get_model; get_model('htdemucs')" ; \
    fi

COPY . .

# 运行期数据（数据库/上传/密钥）统一放 /app/data，挂卷持久化
ENV SELFKTV_DATA=/app/data \
    PYTHONUNBUFFERED=1

VOLUME ["/app/data"]
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/api/admin/status').status==200 else 1)"

CMD ["python", "run.py", "--host", "0.0.0.0", "--port", "8000"]
