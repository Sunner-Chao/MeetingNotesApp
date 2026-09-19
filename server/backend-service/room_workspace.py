"""Durable shared room transcript and report state, scoped by existing membership."""
from __future__ import annotations

import time
import uuid
import hashlib
import json
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import JSONResponse


def now_ms():
    return int(time.time() * 1000)


SCHEMA = """
CREATE TABLE IF NOT EXISTS room_worker (
    id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, heartbeat_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS room_transcription (
    room_id TEXT PRIMARY KEY REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL, state TEXT NOT NULL, origin_ms INTEGER NOT NULL,
    worker TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0,
    cutoff_ms INTEGER NOT NULL DEFAULT 0,
    message TEXT NOT NULL DEFAULT '', updated_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS room_transcript_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    room_id TEXT NOT NULL REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL, track_key TEXT NOT NULL, segment_key INTEGER NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
    text TEXT NOT NULL, finalized INTEGER NOT NULL, revision INTEGER NOT NULL,
    UNIQUE(room_id, run_id, track_key, segment_key)
);
CREATE INDEX IF NOT EXISTS room_transcript_revision ON room_transcript_segments(room_id, revision);
CREATE TABLE IF NOT EXISTS room_reports (
    room_id TEXT PRIMARY KEY REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL, state TEXT NOT NULL, source_revision INTEGER NOT NULL,
    requested_by TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    snapshot TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '',
    worker TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL
);
CREATE TRIGGER IF NOT EXISTS room_workspace_member_deleted AFTER DELETE ON meeting_room_members
BEGIN
    DELETE FROM room_reports WHERE room_id=OLD.room_id;
END;
"""


def worker_available(db):
    row = db.execute("SELECT heartbeat_ms FROM room_worker WHERE id=1").fetchone()
    return bool(row and row[0] > now_ms() - 15_000)


def transcription_state(db, room_id):
    row = db.execute("SELECT state,origin_ms,revision,message,updated_ms FROM room_transcription WHERE room_id=?", (room_id,)).fetchone()
    result = dict(row) if row else {"state": "idle", "origin_ms": 0, "revision": 0, "message": "", "updated_ms": 0}
    result["available"] = worker_available(db)
    if result["state"] in {"starting", "running", "stopping"} and not result["available"]:
        result.update(state="interrupted", message="转写服务暂时离线，已识别内容仍保留")
    return result


def finish_segments(db, room_id):
    rows = db.execute("SELECT id FROM room_transcript_segments WHERE room_id=? AND finalized=0", (room_id,)).fetchall()
    for row in rows:
        db.execute("UPDATE room_transcription SET revision=revision+1 WHERE room_id=?", (room_id,))
        db.execute("UPDATE room_transcript_segments SET finalized=1,revision=(SELECT revision FROM room_transcription WHERE room_id=?) WHERE id=?", (room_id, row[0]))


def pause_capture(db, room_id, message, state="paused"):
    db.execute("UPDATE room_transcription SET state=?,message=?,updated_ms=? WHERE room_id=? AND state IN ('starting','running','stopping')",
               (state, message, now_ms(), room_id))
    finish_segments(db, room_id)


def reconcile_capture(db, room_id):
    room = db.execute("SELECT state FROM meeting_rooms WHERE id=?", (room_id,)).fetchone()
    if room is None or room[0] != "open":
        request_stop(db, room_id)
    elif db.execute("SELECT 1 FROM meeting_room_members WHERE room_id=? AND left_at IS NULL AND recording_consent=0", (room_id,)).fetchone():
        pause_capture(db, room_id, "成员尚未全部同意，转写已暂停")


def request_stop(db, room_id):
    db.execute("UPDATE room_transcription SET state='stopping',cutoff_ms=?-origin_ms,message='正在保存最后一句…',updated_ms=? WHERE room_id=? AND state IN ('starting','running')", (now_ms(), now_ms(), room_id))


class RoomWorkspaceService:
    def __init__(self, path: Path):
        from meeting_rooms import RoomService
        self.rooms = RoomService(path)

    def _host(self, db, room_id, user_id):
        room = self.rooms.snapshot(db, room_id, user_id)
        if room["host_id"] != user_id:
            raise HTTPException(403, "请由主持人操作")
        return room

    def read(self, user_id, room_id, after=0, limit=200):
        with self.rooms.connect(write=False) as db:
            room = self.rooms.snapshot(db, room_id, user_id)
            rows = db.execute("SELECT * FROM room_transcript_segments WHERE room_id=? AND revision>? ORDER BY revision LIMIT ?",
                              (room_id, after, limit + 1)).fetchall()
            page = [{**dict(row), "finalized": bool(row["finalized"])} for row in rows[:limit]]
            report = db.execute("SELECT request_id,state,source_revision,text,message,created_ms,updated_ms FROM room_reports WHERE room_id=?", (room_id,)).fetchone()
            return {"room_id": room_id, "transcription": room["transcription"], "segments": page,
                    "snapshot_key": hashlib.sha256(json.dumps(room["members"], sort_keys=True).encode()).hexdigest(),
                    "next_cursor": page[-1]["revision"] if page else after, "has_more": len(rows) > limit,
                    "report": dict(report) if report else None}

    def start(self, user_id, room_id):
        with self.rooms.connect() as db:
            room = self._host(db, room_id, user_id)
            if room["state"] != "open":
                raise HTTPException(409, "房间已结束")
            if not room["all_recording_consented"]:
                raise HTTPException(409, "请等待所有在场成员同意录音与转写")
            if not worker_available(db):
                raise HTTPException(503, "转写服务尚未就绪，请稍后重试")
            current = db.execute("SELECT state FROM room_transcription WHERE room_id=?", (room_id,)).fetchone()
            if current and current[0] == "stopping":
                raise HTTPException(409, "正在保存最后一句，请稍候")
            if not current or current[0] not in {"starting", "running"}:
                finish_segments(db, room_id)
                db.execute("""INSERT INTO room_transcription(room_id,run_id,state,origin_ms,updated_ms)
                    VALUES(?,?,'starting',?,?) ON CONFLICT(room_id) DO UPDATE SET run_id=excluded.run_id,
                    state='starting',worker='',message='',cutoff_ms=0,updated_ms=excluded.updated_ms""",
                    (room_id, uuid.uuid4().hex, now_ms(), now_ms()))
            return transcription_state(db, room_id)

    def pause(self, user_id, room_id):
        with self.rooms.connect() as db:
            self._host(db, room_id, user_id)
            request_stop(db, room_id)
            return transcription_state(db, room_id)

    def heartbeat(self, owner):
        with self.rooms.connect() as db:
            current = db.execute("SELECT * FROM room_worker WHERE id=1").fetchone()
            if current and current["owner"] != owner and current["heartbeat_ms"] > now_ms() - 15_000:
                return False
            if current and current["heartbeat_ms"] <= now_ms() - 15_000:
                # Even the same process may have lost its streams during a long
                # stall. Require explicit resume rather than reviving stale jobs.
                for row in db.execute("SELECT room_id FROM room_transcription WHERE worker<>'' AND state IN ('starting','running','stopping')").fetchall():
                    pause_capture(db, row[0], "转写服务重新连接，请点击继续", "failed")
                db.execute("UPDATE room_reports SET state='failed',message='生成连接已中断，请重试' WHERE state='generating'")
            db.execute("INSERT INTO room_worker VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET owner=excluded.owner,heartbeat_ms=excluded.heartbeat_ms", (owner, now_ms()))
            for row in db.execute("SELECT room_id FROM room_transcription WHERE worker<>? AND worker<>'' AND state IN ('starting','running','stopping')", (owner,)).fetchall():
                pause_capture(db, row[0], "转写服务重新连接，请点击继续", "failed")
            db.execute("UPDATE room_reports SET state='failed',message='生成服务已重启，请重试',updated_ms=? WHERE state='generating' AND worker<>?", (now_ms(), owner))
            return True

    def _owns_worker(self, db, owner):
        return db.execute("SELECT 1 FROM room_worker WHERE id=1 AND owner=? AND heartbeat_ms>?", (owner, now_ms() - 15_000)).fetchone() is not None

    def jobs(self, owner):
        with self.rooms.connect() as db:
            if not self._owns_worker(db, owner):
                return [], []
            for row in db.execute("SELECT room_id FROM room_transcription WHERE state IN ('starting','running','stopping')").fetchall():
                reconcile_capture(db, row[0])
            db.execute("UPDATE room_transcription SET worker=? WHERE state IN ('starting','stopping') AND worker=''", (owner,))
            runs = [dict(row) for row in db.execute("SELECT * FROM room_transcription WHERE state IN ('starting','running','stopping') AND worker=?", (owner,))]
            reports = [dict(row) for row in db.execute("SELECT * FROM room_reports WHERE state='queued'")]
            return runs, reports

    def capture_context(self, room_id, run_id, owner):
        with self.rooms.connect(write=False) as db:
            if not self._owns_worker(db, owner):
                return None
            run = db.execute("SELECT * FROM room_transcription WHERE room_id=? AND run_id=? AND worker=? AND state IN ('starting','running','stopping')", (room_id, run_id, owner)).fetchone()
            if not run:
                return None
            room = db.execute("SELECT * FROM meeting_rooms WHERE id=?", (room_id,)).fetchone()
            members = [dict(row) for row in db.execute("SELECT * FROM meeting_room_members WHERE room_id=? AND left_at IS NULL", (room_id,))]
            if not room or (room["state"] != "open" and run["state"] != "stopping") or not members or any(not m["recording_consent"] for m in members):
                return None
            return {**dict(run), "members": {m["user_id"]: m for m in members}}

    def running(self, room_id, run_id, owner):
        with self.rooms.connect() as db:
            if self._owns_worker(db, owner):
                db.execute("UPDATE room_transcription SET state='running',message='',updated_ms=? WHERE room_id=? AND run_id=? AND worker=? AND state='starting'", (now_ms(), room_id, run_id, owner))

    def fail(self, room_id, run_id, owner, message):
        with self.rooms.connect() as db:
            if self._owns_worker(db, owner) and db.execute("SELECT 1 FROM room_transcription WHERE room_id=? AND run_id=? AND worker=?", (room_id, run_id, owner)).fetchone():
                pause_capture(db, room_id, message, "failed")

    def finish_run(self, room_id, run_id, owner):
        with self.rooms.connect() as db:
            if self._owns_worker(db, owner) and db.execute("SELECT 1 FROM room_transcription WHERE room_id=? AND run_id=? AND worker=? AND state='stopping'", (room_id, run_id, owner)).fetchone():
                pause_capture(db, room_id, "转写已暂停，内容已保留")

    def append(self, room_id, run_id, owner, user_id, track_key, segment_key, start_ms, end_ms, text, finalized):
        text = text.strip()
        if not text or end_ms < start_ms or start_ms < 0:
            return False
        with self.rooms.connect() as db:
            if not self._owns_worker(db, owner):
                return False
            reconcile_capture(db, room_id)
            run = db.execute("SELECT * FROM room_transcription WHERE room_id=? AND run_id=? AND worker=? AND state IN ('starting','running','stopping')", (room_id, run_id, owner)).fetchone()
            member = db.execute("SELECT display_name FROM meeting_room_members WHERE room_id=? AND user_id=? AND left_at IS NULL AND recording_consent=1", (room_id, user_id)).fetchone()
            if not run or not member:
                return False
            if run["state"] == "stopping" and end_ms > run["cutoff_ms"]:
                return False
            previous = db.execute("SELECT * FROM room_transcript_segments WHERE room_id=? AND run_id=? AND track_key=? AND segment_key=?", (room_id, run_id, track_key, segment_key)).fetchone()
            if previous and (previous["finalized"] or (previous["text"] == text and not finalized)):
                return False
            revision = run["revision"] + 1
            db.execute("UPDATE room_transcription SET revision=?,updated_ms=? WHERE room_id=?", (revision, now_ms(), room_id))
            db.execute("""INSERT INTO room_transcript_segments(room_id,run_id,track_key,segment_key,user_id,display_name,start_ms,end_ms,text,finalized,revision)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(room_id,run_id,track_key,segment_key) DO UPDATE SET
                end_ms=excluded.end_ms,text=excluded.text,finalized=excluded.finalized,revision=excluded.revision""",
                (room_id, run_id, track_key, segment_key, user_id, member["display_name"], start_ms, end_ms, text, int(finalized), revision))
            return True

    def request_report(self, user_id, room_id):
        with self.rooms.connect() as db:
            room = self._host(db, room_id, user_id)
            if room["transcription"]["state"] in {"starting", "running", "stopping"}:
                raise HTTPException(409, "请先暂停转写，再生成这段纪要")
            if not worker_available(db):
                raise HTTPException(503, "生成服务暂未就绪")
            finish_segments(db, room_id)
            rows = db.execute("SELECT * FROM room_transcript_segments WHERE room_id=? AND finalized=1 ORDER BY start_ms,id", (room_id,)).fetchall()
            if not rows:
                raise HTTPException(409, "还没有可整理的转录文字")
            revision = db.execute("SELECT revision FROM room_transcription WHERE room_id=?", (room_id,)).fetchone()[0]
            previous = db.execute("SELECT * FROM room_reports WHERE room_id=?", (room_id,)).fetchone()
            if previous and previous["source_revision"] == revision and previous["state"] in {"queued", "generating", "ready"}:
                return {key: previous[key] for key in ("request_id", "state", "source_revision", "text", "message")}
            if previous and previous["state"] in {"queued", "generating"}:
                raise HTTPException(409, "上一份纪要正在整理，请稍候")
            request_id = previous["request_id"] if previous and previous["source_revision"] == revision else uuid.uuid4().hex
            snapshot = f"会议主题：{room['title']}\n来源：智悟本自有会议房间。时间为接收音轨的相对时间，成员名为账号显示名。\n" + "\n".join(
                f"[{timestamp(row['start_ms'])}–{timestamp(row['end_ms'])}] {row['display_name']}：{row['text']}" for row in rows)
            db.execute("""INSERT INTO room_reports(room_id,request_id,state,source_revision,requested_by,snapshot,created_ms,updated_ms)
                VALUES(?,?,'queued',?,?,?,?,?) ON CONFLICT(room_id) DO UPDATE SET request_id=excluded.request_id,
                state='queued',source_revision=excluded.source_revision,requested_by=excluded.requested_by,snapshot=excluded.snapshot,
                text='',message='',worker='',created_ms=excluded.created_ms,updated_ms=excluded.updated_ms""",
                (room_id, request_id, revision, user_id, snapshot, now_ms(), now_ms()))
            return {"request_id": request_id, "state": "queued", "source_revision": revision, "text": "", "message": ""}

    def claim_report(self, room_id, request_id, owner):
        with self.rooms.connect() as db:
            if not self._owns_worker(db, owner):
                return False
            return db.execute("UPDATE room_reports SET state='generating',worker=?,updated_ms=? WHERE room_id=? AND request_id=? AND state='queued'", (owner, now_ms(), room_id, request_id)).rowcount == 1

    def finish_report(self, room_id, request_id, owner, text="", message=""):
        with self.rooms.connect() as db:
            if self._owns_worker(db, owner):
                db.execute("UPDATE room_reports SET state=?,text=?,message=?,updated_ms=? WHERE room_id=? AND request_id=? AND worker=? AND state='generating'",
                    ("ready" if text.strip() else "failed", text.strip(), message, now_ms(), room_id, request_id, owner))

    def release(self, owner):
        with self.rooms.connect() as db:
            if self._owns_worker(db, owner):
                for row in db.execute("SELECT room_id FROM room_transcription WHERE worker=?", (owner,)).fetchall():
                    pause_capture(db, row[0], "转写服务已离线，请稍后继续", "failed")
                db.execute("UPDATE room_reports SET state='failed',message='生成已中断，请重试' WHERE worker=? AND state='generating'", (owner,))
                db.execute("DELETE FROM room_worker WHERE owner=?", (owner,))


def timestamp(value):
    seconds = max(0, value) // 1000
    return f"{seconds // 3600:02d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


def build_workspace_router(path_provider, principal_dependency, report_principal_dependency=None):
    router = APIRouter(prefix="/api/account/rooms", tags=["room-workspace"])

    @router.get("/{room_id}/transcripts")
    def read(room_id: str, after: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=500), principal=Depends(principal_dependency)):
        return JSONResponse(RoomWorkspaceService(path_provider()).read(principal.user_id, room_id, after, limit), headers={"Cache-Control": "no-store"})

    @router.post("/{room_id}/transcription/start")
    def start(room_id: str, principal=Depends(principal_dependency)):
        return RoomWorkspaceService(path_provider()).start(principal.user_id, room_id)

    @router.post("/{room_id}/transcription/pause")
    def pause(room_id: str, principal=Depends(principal_dependency)):
        return RoomWorkspaceService(path_provider()).pause(principal.user_id, room_id)

    @router.post("/{room_id}/report")
    def report(room_id: str, principal=Depends(report_principal_dependency or principal_dependency)):
        return RoomWorkspaceService(path_provider()).request_report(principal.user_id, room_id)

    return router
