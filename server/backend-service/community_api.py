#!/usr/bin/env python3
"""FastAPI routes for owner-scoped community draft synchronization."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field

from community_service import (
    CommunityDraftInput,
    CommunityError,
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

    return router
