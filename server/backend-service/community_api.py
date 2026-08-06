#!/usr/bin/env python3
"""FastAPI routes for owner-scoped community draft synchronization."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field

from community_service import CommunityDraftInput, CommunityError, CommunityService


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

    return router
