"""SQLite 数据访问层（个人使用规模，无需外部数据库）。"""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from typing import Any, Iterator

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    salt          TEXT NOT NULL,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS songs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT NOT NULL,
    artist          TEXT NOT NULL DEFAULT '',
    duration        REAL NOT NULL DEFAULT 0,
    cover_file      TEXT,
    vocal_file      TEXT,
    accomp_file     TEXT,
    lyric_file      TEXT,
    original_file   TEXT,
    separate_status TEXT NOT NULL DEFAULT 'done',
    separate_error  TEXT NOT NULL DEFAULT '',
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS works (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    song_id     INTEGER,
    song_title  TEXT NOT NULL DEFAULT '',
    title       TEXT NOT NULL DEFAULT '',
    mode        TEXT NOT NULL DEFAULT 'accomp',
    audio_file  TEXT NOT NULL,
    duration    REAL NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_works_user ON works(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS favorites (
    user_id     INTEGER NOT NULL,
    song_id     INTEGER NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, song_id)
);

CREATE TABLE IF NOT EXISTS playlists (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    name        TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS playlist_songs (
    playlist_id INTEGER NOT NULL,
    song_id     INTEGER NOT NULL,
    position    INTEGER NOT NULL,
    added_at    TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (playlist_id, song_id)
);

CREATE TABLE IF NOT EXISTS play_queue (
    user_id     INTEGER NOT NULL,
    song_id     INTEGER NOT NULL,
    position    INTEGER NOT NULL,
    PRIMARY KEY (user_id, song_id)
);
"""


def init_db() -> None:
    """建库建表（幂等），并补齐旧库缺失的列。"""
    config.ensure_dirs()
    with get_db() as db:
        db.executescript(SCHEMA)
        existing = {row[1] for row in db.execute("PRAGMA table_info(songs)").fetchall()}
        for column, ddl in (
            ("original_file", "TEXT"),
            ("separate_status", "TEXT NOT NULL DEFAULT 'done'"),
            ("separate_error", "TEXT NOT NULL DEFAULT ''"),
            ("ref_status", "TEXT NOT NULL DEFAULT ''"),
            ("ref_error", "TEXT NOT NULL DEFAULT ''"),
        ):
            if column not in existing:
                db.execute(f"ALTER TABLE songs ADD COLUMN {column} {ddl}")


@contextmanager
def get_db() -> Iterator[sqlite3.Connection]:
    """每次调用打开独立连接，自动提交。"""
    conn = sqlite3.connect(config.DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def fetch_one(sql: str, params: tuple | list = ()) -> sqlite3.Row | None:
    with get_db() as db:
        return db.execute(sql, params).fetchone()


def fetch_all(sql: str, params: tuple | list = ()) -> list[sqlite3.Row]:
    with get_db() as db:
        return list(db.execute(sql, params).fetchall())


def execute(sql: str, params: tuple | list = ()) -> int:
    """执行写语句，返回最后插入的 rowid。"""
    with get_db() as db:
        cur = db.execute(sql, params)
        return int(cur.lastrowid or 0)


def scalar(sql: str, params: tuple | list = (), default: Any = None) -> Any:
    row = fetch_one(sql, params)
    return row[0] if row is not None else default
