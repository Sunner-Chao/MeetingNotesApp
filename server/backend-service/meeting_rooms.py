"""Account-scoped room lifecycle. Audio transport is deliberately a separate layer."""
from __future__ import annotations

import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from fastapi.responses import JSONResponse
from room_media import RoomMediaService


class RoomService:
    def __init__(self, path: Path, media: RoomMediaService | None = None):
        self.path = path
        self.media = media or RoomMediaService()

    @contextmanager
    def connect(self, *, write: bool = True):
        db = sqlite3.connect(self.path, timeout=15)
        try:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def initialize(self):
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS meeting_rooms (
                    id TEXT PRIMARY KEY, code TEXT UNIQUE NOT NULL,
                    title TEXT NOT NULL, host_id TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'open',
                    created_at INTEGER NOT NULL, ended_at INTEGER,
                    FOREIGN KEY(host_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS meeting_room_members (
                    room_id TEXT NOT NULL, user_id TEXT NOT NULL,
                    display_name TEXT NOT NULL, joined_at INTEGER NOT NULL,
                    left_at INTEGER, recording_consent INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(room_id, user_id),
                    FOREIGN KEY(room_id) REFERENCES meeting_rooms(id) ON DELETE CASCADE,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS meeting_room_members_user ON meeting_room_members(user_id);
                CREATE TABLE IF NOT EXISTS room_media_cleanup (
                    room_id TEXT NOT NULL, user_id TEXT NOT NULL, event_id TEXT NOT NULL,
                    PRIMARY KEY(room_id, user_id)
                );
                CREATE TRIGGER IF NOT EXISTS room_media_on_room_deleted BEFORE DELETE ON meeting_rooms
                BEGIN
                    INSERT OR REPLACE INTO room_media_cleanup VALUES(OLD.id, '', lower(hex(randomblob(16))));
                END;
                CREATE TRIGGER IF NOT EXISTS room_media_on_member_deleted BEFORE DELETE ON meeting_room_members
                BEGIN
                    INSERT OR REPLACE INTO room_media_cleanup VALUES(OLD.room_id, OLD.user_id, lower(hex(randomblob(16))));
                END;
            """)

    def snapshot(self, db, room_id: str, user_id: str):
        room = db.execute("SELECT * FROM meeting_rooms WHERE id=?", (room_id,)).fetchone()
        member = db.execute("SELECT * FROM meeting_room_members WHERE room_id=? AND user_id=?", (room_id, user_id)).fetchone()
        if room is None or member is None or member["left_at"] is not None:
            raise HTTPException(404, "房间不存在或你尚未加入")
        members = [{**dict(row), "recording_consent": bool(row["recording_consent"])} for row in db.execute(
            "SELECT user_id, display_name, joined_at, left_at, recording_consent FROM meeting_room_members WHERE room_id=? ORDER BY joined_at, user_id",
            (room_id,),
        )]
        active = [m for m in members if m["left_at"] is None]
        return {**dict(room), "members": members,
                "all_recording_consented": bool(active) and all(m["recording_consent"] for m in active),
                "media_ready": self.media.configured}

    def _queue_cleanup(self, db, room_id: str, user_id: str = ""):
        if self.media.configured:
            db.execute("INSERT OR REPLACE INTO room_media_cleanup VALUES(?,?,?)",
                       (room_id, user_id, uuid.uuid4().hex))

    def flush_media_cleanup(self, room_id: str | None = None):
        if not self.media.configured:
            return
        with self.connect(write=False) as db:
            pending = db.execute("SELECT * FROM room_media_cleanup WHERE (? IS NULL OR room_id=?) LIMIT 50",
                                 (room_id, room_id)).fetchall()
        for item in pending:
            try:
                # Serialize cleanup across backend workers and new join requests.
                # Without rechecking under the write lock, a late cleanup could
                # remove a member who has already rejoined with a fresh ticket.
                with self.connect() as db:
                    if not db.execute("SELECT 1 FROM room_media_cleanup WHERE event_id=?", (item["event_id"],)).fetchone():
                        continue
                    if item["user_id"]:
                        self.media.remove(item["room_id"], item["user_id"])
                    else:
                        self.media.end(item["room_id"])
                    db.execute("DELETE FROM room_media_cleanup WHERE event_id=?", (item["event_id"],))
            except HTTPException:
                continue  # Durable retry by the application lifespan worker.

    def media_session(self, user_id: str, room_id: str):
        room = self.get(user_id, room_id)
        if room["state"] != "open":
            raise HTTPException(409, "房间已结束")
        self.flush_media_cleanup(room_id)
        with self.connect(write=False) as db:
            if db.execute("SELECT 1 FROM room_media_cleanup WHERE room_id=?", (room_id,)).fetchone():
                raise HTTPException(503, "上一段通话正在退出，请稍后再加入")
        member = next(m for m in room["members"] if m["user_id"] == user_id)
        result = self.media.connect(room, user_id, member["display_name"])
        # Never issue a token after a concurrent leave/end while CreateRoom was in flight.
        try:
            if self.get(user_id, room_id)["state"] != "open":
                raise HTTPException(409, "房间已结束")
        except HTTPException:
            with self.connect() as db:
                self._queue_cleanup(db, room_id, user_id)
                current = db.execute("SELECT state FROM meeting_rooms WHERE id=?", (room_id,)).fetchone()
                if current is None or current["state"] != "open":
                    self._queue_cleanup(db, room_id)
            self.flush_media_cleanup(room_id)
            raise
        return result

    def media_leave(self, user_id: str, room_id: str):
        self.get(user_id, room_id)
        with self.connect() as db:
            self._queue_cleanup(db, room_id, user_id)
        self.flush_media_cleanup(room_id)
        return {"left": True}

    def create(self, principal: Any, title: str, consent: bool):
        now = int(time.time())
        with self.connect() as db:
            active_count = db.execute("SELECT count(*) FROM meeting_rooms WHERE host_id=? AND state='open'", (principal.user_id,)).fetchone()[0]
            if active_count >= 10:
                raise HTTPException(409, "请先结束已有房间，再创建新会议")
            room_id = uuid.uuid4().hex
            for _ in range(10):
                code = str(secrets.randbelow(900_000_000) + 100_000_000)
                if not db.execute("SELECT 1 FROM meeting_rooms WHERE code=?", (code,)).fetchone():
                    break
            else:
                raise HTTPException(503, "会议号生成失败，请重试")
            db.execute("INSERT INTO meeting_rooms(id,code,title,host_id,created_at) VALUES(?,?,?,?,?)", (room_id, code, title.strip() or "聆听·策划会", principal.user_id, now))
            db.execute("INSERT INTO meeting_room_members(room_id,user_id,display_name,joined_at,recording_consent) VALUES(?,?,?,?,?)", (room_id, principal.user_id, principal.username, now, int(consent)))
            return self.snapshot(db, room_id, principal.user_id)

    def join(self, principal: Any, code: str, consent: bool):
        with self.connect() as db:
            room = db.execute("SELECT * FROM meeting_rooms WHERE code=? AND state='open'", (code,)).fetchone()
            if room is None:
                raise HTTPException(404, "会议号无效或会议已结束")
            db.execute("""INSERT INTO meeting_room_members(room_id,user_id,display_name,joined_at,recording_consent)
                VALUES(?,?,?,?,?) ON CONFLICT(room_id,user_id) DO UPDATE SET display_name=excluded.display_name,
                joined_at=CASE WHEN left_at IS NOT NULL THEN excluded.joined_at ELSE joined_at END,
                left_at=NULL, recording_consent=excluded.recording_consent""",
                (room["id"], principal.user_id, principal.username, int(time.time()), int(consent)))
            return self.snapshot(db, room["id"], principal.user_id)

    def get(self, user_id: str, room_id: str):
        with self.connect(write=False) as db:
            return self.snapshot(db, room_id, user_id)

    def list(self, user_id: str):
        with self.connect(write=False) as db:
            ids = db.execute("""SELECT r.id FROM meeting_rooms r JOIN meeting_room_members m ON r.id=m.room_id
                WHERE m.user_id=? AND m.left_at IS NULL ORDER BY (r.state='open') DESC, r.created_at DESC LIMIT 50""", (user_id,)).fetchall()
            return [self.snapshot(db, row["id"], user_id) for row in ids]

    def update(self, user_id: str, room_id: str, action: str, consent: bool = False):
        with self.connect() as db:
            room = self.snapshot(db, room_id, user_id)
            if action == "end":
                if room["host_id"] != user_id:
                    raise HTTPException(403, "只有主持人可以结束会议")
                db.execute("UPDATE meeting_rooms SET state='ended', ended_at=COALESCE(ended_at,?) WHERE id=?", (int(time.time()), room_id))
                self._queue_cleanup(db, room_id)
            elif action == "leave":
                if room["host_id"] == user_id and room["state"] == "open":
                    raise HTTPException(409, "主持人请先结束会议")
                db.execute("UPDATE meeting_room_members SET left_at=? WHERE room_id=? AND user_id=?", (int(time.time()), room_id, user_id))
                self._queue_cleanup(db, room_id, user_id)
            elif action == "consent":
                if room["state"] != "open":
                    raise HTTPException(409, "会议已结束")
                db.execute("UPDATE meeting_room_members SET recording_consent=? WHERE room_id=? AND user_id=?", (int(consent), room_id, user_id))
            result = {"left": True} if action == "leave" else self.snapshot(db, room_id, user_id)
        self.flush_media_cleanup(room_id)
        return result


class RoomCreate(BaseModel):
    title: str = Field(default="聆听·策划会", max_length=120)
    recording_consent: bool = False


class RoomJoin(BaseModel):
    code: str = Field(pattern=r"^[0-9]{9}$")
    recording_consent: bool = False


class RoomConsent(BaseModel):
    recording_consent: bool


def build_room_router(path_provider: Callable[[], Path], principal_dependency: Callable):
    router = APIRouter(prefix="/api/account/rooms", tags=["meeting-rooms"])

    def service():
        return RoomService(path_provider())

    @router.get("")
    def list_rooms(principal=Depends(principal_dependency)):
        rooms = service()
        return {"rooms": rooms.list(principal.user_id), "media_ready": rooms.media.configured}

    @router.post("", status_code=201)
    def create_room(payload: RoomCreate, principal=Depends(principal_dependency)):
        return service().create(principal, payload.title, payload.recording_consent)

    @router.post("/join")
    def join_room(payload: RoomJoin, principal=Depends(principal_dependency)):
        return service().join(principal, payload.code, payload.recording_consent)

    @router.get("/{room_id}")
    def get_room(room_id: str, principal=Depends(principal_dependency)):
        return service().get(principal.user_id, room_id)

    @router.post("/{room_id}/media-session")
    def media_session(room_id: str, principal=Depends(principal_dependency)):
        return JSONResponse(service().media_session(principal.user_id, room_id),
                            headers={"Cache-Control": "no-store"})

    @router.post("/{room_id}/media-leave")
    def media_leave(room_id: str, principal=Depends(principal_dependency)):
        return service().media_leave(principal.user_id, room_id)

    @router.post("/{room_id}/consent")
    def consent(room_id: str, payload: RoomConsent, principal=Depends(principal_dependency)):
        return service().update(principal.user_id, room_id, "consent", payload.recording_consent)

    @router.post("/{room_id}/leave")
    def leave(room_id: str, principal=Depends(principal_dependency)):
        return service().update(principal.user_id, room_id, "leave")

    @router.post("/{room_id}/end")
    def end(room_id: str, principal=Depends(principal_dependency)):
        return service().update(principal.user_id, room_id, "end")

    return router
