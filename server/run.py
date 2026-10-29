#!/usr/bin/env python3
"""SelfKTV 服务端启动入口。

用法：
    python run.py                 # 默认 0.0.0.0:8000
    python run.py --port 9000     # 指定端口
    python run.py --reload        # 开发热重载
"""
from __future__ import annotations

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(description="SelfKTV 服务端")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=8000, help="监听端口（默认 8000）")
    parser.add_argument("--reload", action="store_true", help="开发模式热重载")
    args = parser.parse_args()

    import uvicorn

    uvicorn.run("app.main:app", host=args.host, port=args.port, reload=args.reload)


if __name__ == "__main__":
    main()
