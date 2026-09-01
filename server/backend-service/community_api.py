#!/usr/bin/env python3
"""FastAPI routes for owner-scoped community draft synchronization."""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field

from community_service import (
    CommunityCollectionInput,
    CommunityDraftInput,
    CommunityError,
    CommunityMediaManifestInput,
    CommunityPermissionError,
    CommunityRateLimitError,
    CommunityReportInput,
    CommunityService,
    CommunityWriteDisabledError,
)


def community_writes_enabled_from_env() -> bool:
    value = os.getenv("COMMUNITY_WRITE_ENABLED", "true").strip().lower()
    return value not in {"0", "false", "no", "off"}


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
    destination: str = Field(default="", max_length=120)
    travel_date: str = Field(default="", max_length=10)
    travel_days: int = Field(default=0, ge=0, le=31)
    stage_titles: list[str] = Field(default_factory=list, max_length=50)
    tags: list[str] = Field(default_factory=list, max_length=50)
    pois: list[str] = Field(default_factory=list, max_length=50)


class CommunityModerationPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: str = Field(min_length=1, max_length=20)
    reason: str = Field(default="", max_length=500)


class CommunityReportPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str = Field(min_length=1, max_length=20)
    reason: str = Field(default="", max_length=1000)


class CommunityCommentPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=1000)


class CommunityCommentModerationPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: str = Field(min_length=1, max_length=20)


class CommunityCollectionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=1000)
    destination: str = Field(default="", max_length=120)
    theme: str = Field(default="", max_length=80)
    display_order: int = Field(default=0, ge=0, le=9999)


class CommunityCollectionStatusPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: str = Field(pattern="^(published|unpublished)$")


class CommunityCollectionPostPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    position: int = Field(default=0, ge=0, le=9999)
    curation_note: str = Field(default="", max_length=200)


class CommunityCollectionBatchPostItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    post_id: str = Field(min_length=1, max_length=128)
    position: int = Field(default=0, ge=0, le=9999)
    curation_note: str = Field(default="", max_length=200)


class CommunityCollectionBatchPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[CommunityCollectionBatchPostItem] = Field(min_length=1, max_length=50)


class CommunityCollectionCoverPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    post_id: str | None = Field(default=None, max_length=128)


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
    headers = None
    if isinstance(exc, CommunityRateLimitError):
        headers = {"Retry-After": str(exc.retry_after_seconds)}
    return HTTPException(status_code=exc.status_code, detail=str(exc), headers=headers)


def build_community_router(
    db_path_provider: Callable[[], Path],
    account_principal_dependency: Callable[..., Any],
    write_enabled_provider: Callable[[], bool] | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/api/account/community", tags=["community"])
    is_write_enabled = write_enabled_provider or community_writes_enabled_from_env

    def ensure_user_writes_enabled() -> None:
        if not is_write_enabled():
            raise community_http_error(CommunityWriteDisabledError())

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
        ensure_user_writes_enabled()
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

    @router.put("/drafts/{post_id}")
    @router.patch("/drafts/{post_id}")
    def update_community_draft(
        post_id: str,
        payload: CommunityDraftPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        ensure_user_writes_enabled()
        try:
            return service().update_private_draft(
                principal.user_id,
                post_id,
                CommunityDraftInput(**payload.model_dump()),
            )
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
        ensure_user_writes_enabled()
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
        ensure_user_writes_enabled()
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
        ensure_user_writes_enabled()
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

    @router.post("/posts/{post_id}/report")
    def report_community_post(
        post_id: str,
        payload: CommunityReportPayload,
        response: Response,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            report, created = service().report_post(
                principal.user_id,
                post_id,
                CommunityReportInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        response.status_code = 201 if created else 200
        return report

    @router.get("/posts/{post_id}/interactions")
    def get_community_interactions(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().get_interactions(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/like")
    def toggle_community_like(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        ensure_user_writes_enabled()
        try:
            return service().toggle_like(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/bookmark")
    def toggle_community_bookmark(
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        ensure_user_writes_enabled()
        try:
            return service().toggle_bookmark(principal.user_id, post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/collections/{collection_id}/bookmark")
    def toggle_community_collection_bookmark(
        collection_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        ensure_user_writes_enabled()
        try:
            return service().toggle_collection_bookmark(principal.user_id, collection_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collections/{collection_id}/interaction")
    def get_community_collection_interaction(
        collection_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().get_collection_interaction(principal.user_id, collection_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collection-bookmarks")
    def list_community_collection_bookmarks(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().list_collection_bookmarks(
                principal.user_id,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts/{post_id}/comments")
    def list_account_community_comments(
        post_id: str,
        cursor: str | None = None,
        limit: int = Query(default=50, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().list_comments(
                post_id,
                cursor=cursor,
                limit=limit,
                viewer_user_id=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/posts/{post_id}/comments", status_code=201)
    def create_community_comment(
        post_id: str,
        payload: CommunityCommentPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        ensure_user_writes_enabled()
        try:
            return service().create_comment(principal.user_id, post_id, payload.content)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.delete("/comments/{comment_id}")
    def delete_community_comment(
        comment_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().delete_comment(principal.user_id, comment_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/comments/{comment_id}/report", status_code=201)
    def report_community_comment(
        comment_id: str,
        payload: CommunityReportPayload,
        response: Response,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            report, created = service().report_comment(
                principal.user_id,
                comment_id,
                CommunityReportInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc
        response.status_code = 201 if created else 200
        return report

    @router.get("/bookmarks")
    def list_community_bookmarks(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        try:
            return service().list_bookmarks(
                principal.user_id,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/comment-reports")
    def list_community_comment_reports(
        status: str = Query(default="open"),
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().list_comment_reports(
                status=status,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/comment-reports/{report_id}")
    def resolve_community_comment_report(
        report_id: str,
        payload: CommunityCommentModerationPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().resolve_comment_report(
                report_id,
                decision=payload.decision,
                reviewed_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/operations-summary")
    def get_community_operations_summary(
        hours: int = Query(default=24, ge=1, le=168),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().activity_summary(hours=hours)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/moderation")
    def list_community_moderation_queue(
        status: str = Query(default="pending"),
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().list_moderation_queue(
                status=status,
                cursor=cursor,
                limit=limit,
            )
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

    @router.get("/collections")
    def list_admin_community_collections(
        status: str = Query(default="all"),
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().list_admin_collections(
                status=status,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collection-operations-summary")
    def get_community_collection_operations_summary(
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().collection_operations_summary()
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collection-audit")
    def list_admin_community_collection_audit(
        collection_id: str | None = None,
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().list_collection_audit(
                collection_id=collection_id,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/collections", status_code=201)
    def create_admin_community_collection(
        payload: CommunityCollectionPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().create_collection(
                principal.user_id,
                CommunityCollectionInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collections/{collection_id}")
    def get_admin_community_collection(
        collection_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        try:
            return service().get_admin_collection(collection_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.put("/collections/{collection_id}")
    def update_admin_community_collection(
        collection_id: str,
        payload: CommunityCollectionPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().update_collection(
                collection_id,
                principal.user_id,
                CommunityCollectionInput(**payload.model_dump()),
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.post("/collections/{collection_id}/status")
    def set_admin_community_collection_status(
        collection_id: str,
        payload: CommunityCollectionStatusPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().set_collection_status(
                collection_id,
                status=payload.status,
                updated_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.put("/collections/{collection_id}/cover")
    def set_admin_community_collection_cover(
        collection_id: str,
        payload: CommunityCollectionCoverPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().set_collection_cover(
                collection_id,
                post_id=payload.post_id,
                updated_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.put("/collections/{collection_id}/posts/batch")
    def batch_add_admin_community_collection_posts(
        collection_id: str,
        payload: CommunityCollectionBatchPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().batch_add_collection_posts(
                collection_id,
                [item.model_dump() for item in payload.items],
                added_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.put("/collections/{collection_id}/posts/{post_id}")
    def add_admin_community_collection_post(
        collection_id: str,
        post_id: str,
        payload: CommunityCollectionPostPayload,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().add_collection_post(
                collection_id,
                post_id,
                position=payload.position,
                curation_note=payload.curation_note,
                added_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.delete("/collections/{collection_id}/posts/{post_id}")
    def remove_admin_community_collection_post(
        collection_id: str,
        post_id: str,
        principal: Any = Depends(account_principal_dependency),
    ) -> dict:
        if not bool(getattr(principal, "is_admin", False)):
            raise community_http_error(CommunityPermissionError("需要管理员权限"))
        ensure_user_writes_enabled()
        try:
            return service().remove_collection_post(
                collection_id,
                post_id,
                removed_by=principal.user_id,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    return router


def build_public_community_router(
    db_path_provider: Callable[[], Path],
    write_enabled_provider: Callable[[], bool] | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/api/community", tags=["community"])
    is_write_enabled = write_enabled_provider or community_writes_enabled_from_env

    def service() -> CommunityService:
        current = CommunityService(db_path_provider())
        current.initialize()
        return current

    @router.get("/status")
    def community_status() -> dict:
        return {"read_enabled": True, "write_enabled": bool(is_write_enabled())}

    @router.get("/collections")
    def list_public_community_collections(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        destination: str = Query(default="", max_length=120),
        theme: str = Query(default="", max_length=80),
        sort: str = Query(default="curated", max_length=20),
    ) -> dict:
        try:
            return service().list_public_collections(
                cursor=cursor,
                limit=limit,
                destination=destination,
                theme=theme,
                sort=sort,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collections/{collection_id}")
    def get_public_community_collection(
        collection_id: str,
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
    ) -> dict:
        try:
            return service().get_public_collection(
                collection_id,
                cursor=cursor,
                limit=limit,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/collections/{collection_id}/share")
    def get_public_community_collection_share(collection_id: str) -> dict:
        try:
            return service().get_public_collection_share(collection_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts")
    def list_public_community_posts(
        cursor: str | None = None,
        limit: int = Query(default=20, ge=1, le=50),
        q: str = Query(default="", max_length=100),
        destination: str = Query(default="", max_length=120),
        tag: str = Query(default="", max_length=80),
        poi: str = Query(default="", max_length=80),
        min_days: int = Query(default=0, ge=0, le=31),
        max_days: int = Query(default=0, ge=0, le=31),
        has_media: bool = False,
    ) -> dict:
        try:
            return service().list_public_posts(
                cursor=cursor,
                limit=limit,
                search_query=q,
                destination=destination,
                tag=tag,
                poi=poi,
                min_days=min_days,
                max_days=max_days,
                has_media=has_media,
            )
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts/{post_id}")
    def get_public_community_post(post_id: str) -> dict:
        try:
            return service().get_public_post(post_id)
        except CommunityError as exc:
            raise community_http_error(exc) from exc

    @router.get("/posts/{post_id}/comments")
    def list_public_community_comments(
        post_id: str,
        cursor: str | None = None,
        limit: int = Query(default=50, ge=1, le=50),
    ) -> dict:
        try:
            return service().list_comments(post_id, cursor=cursor, limit=limit)
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
