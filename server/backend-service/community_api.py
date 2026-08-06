#!/usr/bin/env python3
"""FastAPI routes for owner-scoped community draft synchronization."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable
import re

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field

from community_service import (
    CommunityDraftInput,
    CommunityError,
    CommunityMediaManifestInput,
    CommunityPermissionError,
    CommunityService,
)


class CommunityDraftPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    client_snapshot_id: str = Field(min_length=1, max_length=128)
    journey_id: str = Field(min_length=1, max_length=128)
    journey_edition_id: str = Field(min_length=1, max_length=128)
    source_edition_version: int = Field(ge=1)
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=100_000)
    ai_assisted: bool = False
    redacted_coordinate_count: int = Field(default=0, ge=0)
    privacy_reviewed: bool
    rights_confirmed: bool


class CommunityModerationPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: str = Field(min_length=1, max_length=20)
    reason: str = Field(default="", max_length=500)


class CommunityMediaManifestPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    client_media_id: str = Field(min_length=1, max_length=128)
    display_name: str = Field(min_length=1, max_length=200)
    mime_type: str = Field(min_length=1, max_length=40)
    original_bytes: int = Field(gt=0)
    original_sha256: str = Field(min_length=64, max_length=64)
    thumbnail_bytes: int = Field(gt=0)
    thumbnail_sha256: str = Field(min_length=64, max_length=64)


CONTENT_RANGE_PATTERN = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")


def community_http_error(exc: CommunityError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=str(exc))


def build_community_router(
    db_path_provider: Callable[[], Path],
    account_principal_dependency: Callable[..., Any],
) -> APIRouter:
    router = APIRouter(prefix="/api/account/community", tags=["community"])

    def service() -> CommunityService:
        current = CommunityService(db_path_provider())
        current.initialize()
        return current

    @router.post("/drafts")
    def create_community_draft(
        payload: CommunityDraftPayload,
        response: Response,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            post, created = service().create_private_draft(
                principal.user_id,
                CommunityDraftInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        response.status_code = 201 if created else 200
        return post

    @router.get("/drafts/{post_id}")
    def get_community_draft(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().get_post(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts")
    def list_my_community_posts(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().list_owner_posts(
                principal.user_id,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/media-quota")
    def get_community_media_quota(
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().media_quota(principal.user_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts/{post_id}/media")
    def list_community_media(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().list_owner_media(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/media")
    def create_community_media_manifest(
        post_id: str,
        payload: CommunityMediaManifestPayload,
        response: Response,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            media, created = service().create_media_manifest(
                principal.user_id,
                post_id,
                CommunityMediaManifestInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        response.status_code = 201 if created else 200
        return media

    @router.put("/posts/{post_id}/media/{media_id}/{variant}")
    async def upload_community_media_chunk(
        post_id: str,
        media_id: str,
        variant: str,
        request: Request,
        content_range: str | None = Header(default=None, alias="Content-Range"),
        chunk_sha256: str | None = Header(default=None, alias="X-Chunk-SHA256"),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        match = CONTENT_RANGE_PATTERN.fullmatch((content_range or "").strip())
        if match is None:
            raise HTTPException(status_code=400, detail="Content-Range 格式无效")
        if not chunk_sha256:
            raise HTTPException(status_code=400, detail="缺少 X-Chunk-SHA256")
        start, end, total = (int(value) for value in match.groups())
        try:
            return service().append_media_chunk(
                principal.user_id,
                post_id,
                media_id,
                variant,
                start=start,
                end=end,
                total=total,
                data=await request.body(),
                chunk_sha256=chunk_sha256,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/publish")
    def publish_community_post(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().publish(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/withdraw")
    def withdraw_community_post(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().withdraw(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/moderation/{post_id}")
    def moderate_community_post(
        post_id: str,
        payload: CommunityModerationPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().review_post(
                post_id,
                decision=payload.decision,
                reason=payload.reason,
                reviewed_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    return router


def build_public_community_router(db_path_provider: Callable[[], Path]) -> APIRouter:
    router = APIRouter(prefix="/api/community", tags=["community"])

    def service() -> CommunityService:
        current = CommunityService(db_path_provider())
        current.initialize()
        return current

    @router.get("/posts")
    def list_public_community_posts(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
    ) -> dict:
        try:
            return service().list_public_posts(cursor=cursor, limit=limit)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts/{post_id}")
    def get_public_community_post(post_id: str) -> dict:
        try:
            return service().get_public_post(post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/media/{media_id}/thumbnail")
    def get_public_community_media_thumbnail(media_id: str) -> FileResponse:
        try:
            path, mime_type = service().public_media_file(media_id, "thumbnail")
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        return FileResponse(path, media_type=mime_type, headers={"Cache-Control": "public, max-age=3600"})

    @router.get("/media/{media_id}/content")
    def get_public_community_media_content(media_id: str) -> FileResponse:
        try:
            path, mime_type = service().public_media_file(media_id, "original")
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        return FileResponse(path, media_type=mime_type, headers={"Cache-Control": "public, max-age=3600"})

    return router
