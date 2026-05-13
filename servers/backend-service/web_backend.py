#!/usr/bin/env python3
"""
MeetingNotesApp 简易 Web 后端 + 调试前端

动态配置：
- WEB_BACKEND_HOST: 监听地址，默认 0.0.0.0
- WEB_BACKEND_PORT: 监听端口，默认 8090
- WEB_BACKEND_DB_PATH: SQLite 文件路径，默认 ./data/meeting_notes.db
- STT_SERVICE_BASE_URL: STT 服务基地址，默认 http://127.0.0.1:8888
- STT_LOG_PATH: STT 日志文件路径，默认 ./server/stt_server_runtime_8888.log
"""

from __future__ import annotations

import json
import os
import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

import requests
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field


def _env(name: str, default: str) -> str:
    value = os.getenv(name, default).strip()
    return value or default


HOST = _env("WEB_BACKEND_HOST", "0.0.0.0")
PORT = int(_env("WEB_BACKEND_PORT", "8090"))
DB_PATH = Path(_env("WEB_BACKEND_DB_PATH", "./data/meeting_notes.db")).resolve()
STT_SERVICE_BASE_URL = _env("STT_SERVICE_BASE_URL", "http://127.0.0.1:8888").rstrip("/")
STT_LOG_PATH = Path(_env("STT_LOG_PATH", "../stt-service/logs/stt.log")).resolve()
DB_PATH.parent.mkdir(parents=True, exist_ok=True)


@contextmanager
def db_conn() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with db_conn() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS meetings (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                audio_file_path TEXT
            );

            CREATE TABLE IF NOT EXISTS transcripts (
                id TEXT PRIMARY KEY,
                meeting_id TEXT NOT NULL,
                speaker_name TEXT,
                content TEXT NOT NULL,
                start_time_ms INTEGER NOT NULL DEFAULT 0,
                end_time_ms INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS reports (
                id TEXT PRIMARY KEY,
                meeting_id TEXT NOT NULL UNIQUE,
                summary TEXT NOT NULL DEFAULT '',
                key_points TEXT NOT NULL DEFAULT '[]',
                tasks TEXT NOT NULL DEFAULT '[]',
                decisions TEXT NOT NULL DEFAULT '[]',
                action_items TEXT NOT NULL DEFAULT '[]',
                generated_at INTEGER NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );
            """
        )


class MeetingPayload(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    created_at: int
    duration_ms: int = 0
    audio_file_path: str | None = None


class TranscriptPayload(BaseModel):
    meeting_id: str
    speaker_name: str | None = None
    content: str = Field(min_length=1)
    start_time_ms: int = 0
    end_time_ms: int = 0
    created_at: int


class ReportPayload(BaseModel):
    meeting_id: str
    summary: str = ""
    key_points: list[str] = Field(default_factory=list)
    tasks: list[dict] = Field(default_factory=list)
    decisions: list[str] = Field(default_factory=list)
    action_items: list[str] = Field(default_factory=list)
    generated_at: int


class SwitchSTTPayload(BaseModel):
    engine: str = Field(pattern="^(faster-whisper|sensevoice)$")
    model: str | None = None


app = FastAPI(title="Meeting Notes Web Backend")


@app.on_event("startup")
def on_startup() -> None:
    init_db()


@app.get("/", response_class=HTMLResponse)
@app.get("/web", response_class=HTMLResponse)
def index() -> str:
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Meeting Notes Debug Console</title>
  <style>
    :root {{
      --bg: #f3f5f8;
      --card: #ffffff;
      --ink: #122033;
      --muted: #617189;
      --line: #d8dfe8;
      --accent: #2060ff;
      --accent-soft: #e8efff;
      --good: #0f9d58;
      --warn: #f39c12;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
      background: linear-gradient(180deg, #eff4fb 0%, var(--bg) 100%);
      color: var(--ink);
    }}
    .wrap {{
      max-width: 1280px;
      margin: 0 auto;
      padding: 24px;
    }}
    .hero {{
      display: grid;
      grid-template-columns: 1.25fr 0.75fr;
      gap: 18px;
      margin-bottom: 18px;
    }}
    .card {{
      background: var(--card);
      border: 1px solid var(--line);
      border-radius: 16px;
      padding: 18px;
      box-shadow: 0 12px 28px rgba(19, 43, 76, 0.07);
    }}
    .title {{
      font-size: 15px;
      font-weight: 700;
      margin: 0 0 10px;
    }}
    .headline {{
      font-size: 30px;
      font-weight: 800;
      margin: 0 0 10px;
    }}
    .toolbar {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 16px;
    }}
    .service-panel {{
      display: grid;
      grid-template-columns: 0.95fr 1.05fr;
      gap: 18px;
      margin-bottom: 18px;
    }}
    .switch-grid {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }}
    .switch-card {{
      border: 1px solid var(--line);
      border-radius: 14px;
      padding: 14px;
      background: #fbfcfe;
    }}
    .switch-card strong {{
      display: block;
      margin-bottom: 6px;
    }}
    .engine-badge {{
      display: inline-flex;
      align-items: center;
      padding: 7px 10px;
      border-radius: 999px;
      background: #e8efff;
      color: #1746b5;
      font-weight: 800;
      font-size: 13px;
    }}
    .muted {{ color: var(--muted); }}
    .row {{
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 18px;
      margin-bottom: 18px;
    }}
    .metric {{
      padding: 14px;
      border-radius: 16px;
      background: var(--accent-soft);
    }}
    .metric strong {{
      display: block;
      font-size: 24px;
      margin-top: 4px;
    }}
    .grid {{
      display: grid;
      grid-template-columns: 1.1fr 0.9fr;
      gap: 18px;
    }}
    .list {{
      display: grid;
      gap: 12px;
      max-height: 520px;
      overflow: auto;
    }}
    .item {{
      border: 1px solid var(--line);
      border-radius: 16px;
      padding: 14px;
    }}
    .item h4 {{
      margin: 0 0 8px;
      font-size: 15px;
    }}
    pre {{
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      background: #0f1720;
      color: #d8e7ff;
      border-radius: 14px;
      padding: 14px;
      font-size: 12px;
      max-height: 520px;
      overflow: auto;
    }}
    .pill {{
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      border-radius: 999px;
      background: #eef3f8;
      color: var(--muted);
      font-size: 12px;
      margin-right: 8px;
    }}
    .dot {{
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
      background: var(--warn);
    }}
    .dot.ok {{ background: var(--good); }}
    .actions {{
      display: flex;
      gap: 10px;
      margin-top: 12px;
    }}
    button {{
      border: 0;
      border-radius: 12px;
      padding: 10px 14px;
      font-weight: 700;
      background: var(--accent);
      color: white;
      cursor: pointer;
    }}
    button.secondary {{
      background: #ecf2fa;
      color: var(--ink);
    }}
    button.ghost {{
      background: #ffffff;
      color: var(--ink);
      border: 1px solid var(--line);
    }}
    button:disabled {{
      opacity: 0.55;
      cursor: not-allowed;
    }}
    @media (max-width: 960px) {{
      .hero, .row, .grid, .service-panel {{
        grid-template-columns: 1fr;
      }}
    }}
  </style>
</head>
<body>
  <div class="wrap">
    <section class="hero">
      <div class="card">
        <p class="title">Meeting Notes Debug Console</p>
        <h1 class="headline">Web 端调试台</h1>
        <p class="muted">同时观察简易后端、STT 服务状态、会议记录、流式 partial 事件和最近日志，方便对照手机端流式预览与最终稿。</p>
        <div class="toolbar">
          <button onclick="refreshAll()">刷新全部</button>
          <button class="ghost" onclick="refreshLogs()">仅刷新日志</button>
          <button class="ghost" onclick="clearLogs()">清空日志</button>
          <button class="ghost" onclick="clearEvents()">清空事件</button>
        </div>
      </div>
      <div class="card">
        <p class="title">当前配置</p>
        <div class="list">
          <div class="item"><strong>Backend</strong><div class="muted">http://127.0.0.1:{PORT}</div></div>
          <div class="item"><strong>STT Base URL</strong><div class="muted">{STT_SERVICE_BASE_URL}</div></div>
          <div class="item"><strong>DB Path</strong><div class="muted">{DB_PATH}</div></div>
          <div class="item"><strong>STT Log Path</strong><div class="muted">{STT_LOG_PATH}</div></div>
        </div>
      </div>
    </section>

    <section class="service-panel">
      <div class="card">
        <p class="title">STT 服务切换</p>
        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:14px;">
          <div>
            <div class="muted">当前引擎</div>
            <div id="stt-engine" class="engine-badge">检查中...</div>
          </div>
          <div class="muted" id="stt-model">模型：-</div>
        </div>
        <div class="switch-grid">
          <div class="switch-card">
            <strong>Faster-Whisper</strong>
            <div class="muted">通用本地转写，默认 small 模型。</div>
            <button style="margin-top:12px;" onclick="switchStt('faster-whisper','small')">切换到 Faster</button>
          </div>
          <div class="switch-card">
            <strong>SenseVoice</strong>
            <div class="muted">中文优化方案，使用 SenseVoiceSmall。</div>
            <button style="margin-top:12px;" onclick="switchStt('sensevoice','SenseVoiceSmall')">切换到 SenseVoice</button>
          </div>
        </div>
        <div id="switch-result" class="muted" style="margin-top:12px;">两个引擎都使用 8888 端口，切换时会重启 STT 服务。</div>
      </div>
      <div class="card">
        <p class="title">服务链路</p>
        <div class="list">
          <div class="item"><strong>手机端 STT URL</strong><div class="muted">https://1154083nrki65.vicp.fun</div></div>
          <div class="item"><strong>PC STT 监听</strong><div class="muted">0.0.0.0:8888</div></div>
          <div class="item"><strong>Web 调试台</strong><div class="muted">http://127.0.0.1:{PORT}/web</div></div>
        </div>
      </div>
    </section>

    <section class="row">
      <div class="card metric">
        <span class="pill"><span id="backend-dot" class="dot"></span>Backend</span>
        <strong id="backend-status">检查中...</strong>
        <span id="backend-detail" class="muted"></span>
      </div>
      <div class="card metric">
        <span class="pill"><span id="stt-dot" class="dot"></span>STT</span>
        <strong id="stt-status">检查中...</strong>
        <span id="stt-detail" class="muted"></span>
      </div>
      <div class="card metric">
        <span class="pill"><span class="dot ok"></span>Meetings</span>
        <strong id="meeting-count">0</strong>
        <span class="muted">当前后端已保存会议条目</span>
      </div>
    </section>

    <section class="grid">
      <div class="card">
        <p class="title">最近会议</p>
        <div id="meeting-list" class="list"></div>
      </div>
      <div class="card">
        <p class="title">流式事件</p>
        <pre id="stt-events">加载中...</pre>
      </div>
    </section>

    <section class="grid" style="margin-top: 18px;">
      <div class="card">
        <p class="title">流式摘要</p>
        <div id="stream-summary" class="list"></div>
      </div>
      <div class="card">
        <p class="title">STT 最近日志</p>
        <pre id="stt-log">加载中...</pre>
      </div>
    </section>
  </div>

  <script>
    async function getJson(url) {{
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${{res.status}}`);
      return await res.json();
    }}

    function setStatus(prefix, ok, title, detail) {{
      document.getElementById(`${{prefix}}-dot`).className = `dot${{ok ? ' ok' : ''}}`;
      document.getElementById(`${{prefix}}-status`).textContent = title;
      document.getElementById(`${{prefix}}-detail`).textContent = detail || '';
    }}

    async function refreshBackendHealth() {{
      try {{
        const health = await getJson('/health');
        setStatus('backend', true, 'Backend 正常', `SQLite: ${{health.db_path}}`);
      }} catch (err) {{
        setStatus('backend', false, 'Backend 异常', String(err));
      }}
    }}

    async function refreshSttHealth() {{
      try {{
        const health = await getJson('/api/debug/stt-health');
        const engine = health.engine || 'unknown';
        const model = health.model || 'unknown';
        setStatus('stt', true, `STT 正常 (${{engine}})`, `${{model}} · ${{health.base_url}}`);
        document.getElementById('stt-engine').textContent = engine;
        document.getElementById('stt-model').textContent = `模型：${{model}}`;
      }} catch (err) {{
        setStatus('stt', false, 'STT 异常', String(err));
        document.getElementById('stt-engine').textContent = '离线';
        document.getElementById('stt-model').textContent = '模型：-';
      }}
    }}

    async function switchStt(engine, model) {{
      const result = document.getElementById('switch-result');
      result.textContent = `正在切换到 ${{engine}}，STT 服务会短暂重启...`;
      try {{
        const res = await fetch('/api/admin/stt/switch', {{
          method: 'POST',
          headers: {{ 'Content-Type': 'application/json' }},
          body: JSON.stringify({{ engine, model }})
        }});
        const payload = await res.json();
        if (!res.ok) throw new Error(payload.detail || `HTTP ${{res.status}}`);
        result.textContent = `已发起切换：${{payload.target_engine}} / ${{payload.target_model}}，稍后自动刷新状态。`;
        setTimeout(refreshSttHealth, 3500);
        setTimeout(refreshLogs, 4500);
      }} catch (err) {{
        result.textContent = `切换失败：${{String(err)}}`;
      }}
    }}

    async function refreshMeetings() {{
      const host = document.getElementById('meeting-list');
      host.innerHTML = '';
      try {{
        const meetings = await getJson('/api/meetings');
        document.getElementById('meeting-count').textContent = meetings.length;
        if (!meetings.length) {{
          host.innerHTML = '<div class="item muted">暂无会议记录</div>';
          return;
        }}
        meetings.forEach(meeting => {{
          const div = document.createElement('div');
          div.className = 'item';
          const date = new Date(meeting.created_at).toLocaleString();
          div.innerHTML = `
            <h4>${{meeting.title}}</h4>
            <div class="muted">ID: ${{meeting.id}}</div>
            <div class="muted">创建时间: ${{date}}</div>
            <div class="muted">时长: ${{meeting.duration_ms}} ms</div>
            <div class="muted">音频路径: ${{meeting.audio_file_path || '未记录'}}</div>
          `;
          host.appendChild(div);
        }});
      }} catch (err) {{
        host.innerHTML = `<div class="item muted">加载失败: ${{String(err)}}</div>`;
      }}
    }}

    async function refreshLogs() {{
      try {{
        const payload = await getJson('/api/debug/stt-log');
        document.getElementById('stt-log').textContent = payload.lines.join('\\n') || '暂无日志';
      }} catch (err) {{
        document.getElementById('stt-log').textContent = `读取日志失败: ${{String(err)}}`;
      }}
    }}

    async function refreshStreamEvents() {{
      try {{
        const payload = await getJson('/api/debug/stt-events?limit=24');
        const events = payload.events || [];
        document.getElementById('stt-events').textContent =
          events.map(event => JSON.stringify(event, null, 2)).join('\\n\\n') || '暂无流式事件';

        const partials = events.filter(event => event.type === 'partial');
        const latest = partials.length ? partials[partials.length - 1] : null;
        const host = document.getElementById('stream-summary');
        host.innerHTML = '';

        if (!latest) {{
          host.innerHTML = '<div class="item muted">还没有收到 partial 事件</div>';
          return;
        }}

        const cards = [
          ['会话', latest.session_id || '-'],
          ['稳定段', latest.committed_text || '空'],
          ['波动尾段', latest.preview_text || '空'],
          ['完整文本', latest.text || '空'],
          ['音频字节数', String(latest.audio_bytes || 0)],
          ['事件时间', latest.time || '-'],
        ];

        cards.forEach(([label, value]) => {{
          const div = document.createElement('div');
          div.className = 'item';
          div.innerHTML = `<h4>${{label}}</h4><div class="muted">${{value}}</div>`;
          host.appendChild(div);
        }});
      }} catch (err) {{
        document.getElementById('stt-events').textContent = `读取流式事件失败: ${{String(err)}}`;
        document.getElementById('stream-summary').innerHTML =
          `<div class="item muted">读取流式事件失败: ${{String(err)}}</div>`;
      }}
    }}

    async function refreshAll() {{
      await Promise.all([
        refreshBackendHealth(),
        refreshSttHealth(),
        refreshMeetings(),
        refreshStreamEvents(),
        refreshLogs(),
      ]);
    }}

    async function clearLogs() {{
      if (!confirm('确定要清空 STT 日志吗？')) return;
      try {{
        const res = await fetch('/api/debug/stt-log', {{ method: 'DELETE' }});
        const payload = await res.json();
        alert(payload.message || '日志已清空');
        await refreshLogs();
      }} catch (err) {{
        alert('清空日志失败: ' + String(err));
      }}
    }}

    async function clearEvents() {{
      if (!confirm('确定要清空流式事件吗？')) return;
      try {{
        const res = await fetch('/api/debug/stt-events', {{ method: 'DELETE' }});
        const payload = await res.json();
        alert(payload.message || '事件已清空');
        await refreshStreamEvents();
      }} catch (err) {{
        alert('清空事件失败: ' + String(err));
      }}
    }}

    refreshAll();
    setInterval(refreshLogs, 4000);
    setInterval(refreshStreamEvents, 2500);
    setInterval(refreshSttHealth, 8000);
  </script>
</body>
</html>"""


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "db_path": str(DB_PATH),
        "port": PORT,
    }


@app.get("/api/debug/stt-health")
def stt_health() -> dict:
    try:
        response = requests.get(f"{STT_SERVICE_BASE_URL}/health", timeout=5)
        response.raise_for_status()
        payload = response.json()
        payload["base_url"] = STT_SERVICE_BASE_URL
        return payload
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT health check failed: {exc}") from exc


@app.post("/api/admin/stt/switch")
def switch_stt(payload: SwitchSTTPayload) -> dict:
    model = payload.model or ("SenseVoiceSmall" if payload.engine == "sensevoice" else "small")
    try:
        response = requests.post(
            f"{STT_SERVICE_BASE_URL}/admin/stt/switch",
            json={"engine": payload.engine, "model": model},
            timeout=8,
        )
        response.raise_for_status()
        result = response.json()
        result["base_url"] = STT_SERVICE_BASE_URL
        return result
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT switch failed: {exc}") from exc


@app.get("/api/debug/stt-log")
def stt_log(lines: int = 80) -> dict:
    if not STT_LOG_PATH.exists():
        return {"path": str(STT_LOG_PATH), "lines": ["STT 日志文件不存在"]}
    content = STT_LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    return {
        "path": str(STT_LOG_PATH),
        "lines": content[-max(1, min(lines, 300)):],
    }


@app.delete("/api/debug/stt-log")
def clear_stt_log() -> dict:
    """清空 STT 日志文件"""
    if not STT_LOG_PATH.exists():
        return {"path": str(STT_LOG_PATH), "status": "not_found", "message": "日志文件不存在"}
    STT_LOG_PATH.write_text("", encoding="utf-8")
    return {"path": str(STT_LOG_PATH), "status": "cleared", "message": "日志已清空"}


@app.delete("/api/debug/stt-events")
def clear_stt_events() -> dict:
    """清空 STT 流式事件"""
    try:
        response = requests.delete(f"{STT_SERVICE_BASE_URL}/debug/stream-events", timeout=5)
        response.raise_for_status()
        return response.json()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Failed to clear STT events: {exc}") from exc


@app.get("/api/debug/stt-events")
def stt_events(limit: int = 40) -> dict:
    try:
        response = requests.get(f"{STT_SERVICE_BASE_URL}/debug/stream-events", params={"limit": limit}, timeout=5)
        response.raise_for_status()
        payload = response.json()
        payload["base_url"] = STT_SERVICE_BASE_URL
        return payload
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"STT stream events fetch failed: {exc}") from exc


@app.get("/api/meetings")
def list_meetings() -> list[dict]:
    with db_conn() as conn:
        rows = conn.execute(
            "SELECT id, title, created_at, duration_ms, audio_file_path FROM meetings ORDER BY created_at DESC"
        ).fetchall()
        return [dict(row) for row in rows]


@app.post("/api/meetings")
def create_meeting(payload: MeetingPayload) -> dict:
    meeting_id = str(uuid.uuid4())
    with db_conn() as conn:
        conn.execute(
            """
            INSERT INTO meetings (id, title, created_at, duration_ms, audio_file_path)
            VALUES (?, ?, ?, ?, ?)
            """,
            (meeting_id, payload.title, payload.created_at, payload.duration_ms, payload.audio_file_path),
        )
    return {"id": meeting_id, **payload.model_dump()}


@app.patch("/api/meetings/{meeting_id}")
def update_meeting(meeting_id: str, payload: MeetingPayload) -> dict:
    with db_conn() as conn:
        result = conn.execute(
            """
            UPDATE meetings
            SET title = ?, created_at = ?, duration_ms = ?, audio_file_path = ?
            WHERE id = ?
            """,
            (payload.title, payload.created_at, payload.duration_ms, payload.audio_file_path, meeting_id),
        )
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Meeting not found")
    return {"id": meeting_id, **payload.model_dump()}


@app.get("/api/meetings/{meeting_id}/transcripts")
def list_transcripts(meeting_id: str) -> list[dict]:
    with db_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, meeting_id, speaker_name, content, start_time_ms, end_time_ms, created_at
            FROM transcripts
            WHERE meeting_id = ?
            ORDER BY created_at ASC
            """,
            (meeting_id,),
        ).fetchall()
        return [dict(row) for row in rows]


@app.post("/api/transcripts")
def create_transcript(payload: TranscriptPayload) -> dict:
    transcript_id = str(uuid.uuid4())
    with db_conn() as conn:
        conn.execute(
            """
            INSERT INTO transcripts (id, meeting_id, speaker_name, content, start_time_ms, end_time_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                transcript_id,
                payload.meeting_id,
                payload.speaker_name,
                payload.content,
                payload.start_time_ms,
                payload.end_time_ms,
                payload.created_at,
            ),
        )
    return {"id": transcript_id, **payload.model_dump()}


@app.get("/api/reports/{meeting_id}")
def get_report(meeting_id: str) -> dict:
    with db_conn() as conn:
        row = conn.execute(
            """
            SELECT id, meeting_id, summary, key_points, tasks, decisions, action_items, generated_at
            FROM reports
            WHERE meeting_id = ?
            """,
            (meeting_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Report not found")
        payload = dict(row)
        payload["key_points"] = json.loads(payload["key_points"])
        payload["tasks"] = json.loads(payload["tasks"])
        payload["decisions"] = json.loads(payload["decisions"])
        payload["action_items"] = json.loads(payload["action_items"])
        return payload


@app.put("/api/reports/{meeting_id}")
def upsert_report(meeting_id: str, payload: ReportPayload) -> dict:
    report_id = str(uuid.uuid4())
    if payload.meeting_id != meeting_id:
        raise HTTPException(status_code=400, detail="meeting_id mismatch")

    with db_conn() as conn:
        conn.execute(
            """
            INSERT INTO reports (id, meeting_id, summary, key_points, tasks, decisions, action_items, generated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(meeting_id) DO UPDATE SET
                summary = excluded.summary,
                key_points = excluded.key_points,
                tasks = excluded.tasks,
                decisions = excluded.decisions,
                action_items = excluded.action_items,
                generated_at = excluded.generated_at
            """,
            (
                report_id,
                meeting_id,
                payload.summary,
                json.dumps(payload.key_points, ensure_ascii=False),
                json.dumps(payload.tasks, ensure_ascii=False),
                json.dumps(payload.decisions, ensure_ascii=False),
                json.dumps(payload.action_items, ensure_ascii=False),
                payload.generated_at,
            ),
        )
    return {"id": report_id, **payload.model_dump()}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT)
