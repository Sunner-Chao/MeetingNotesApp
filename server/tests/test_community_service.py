from __future__ import annotations

import sys
import hashlib
import sqlite3
import struct
import tempfile
import unittest
import zlib
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from community_service import (
    CommunityConflictError,
    CommunityDraftInput,
    CommunityError,
    CommunityMediaManifestInput,
    CommunityNotFoundError,
    CommunityPermissionError,
    CommunityRateLimitError,
    CommunityReportInput,
    CommunityService,
)


class CommunityServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "community.db"
        self.owner_id = "community-owner"
        self.other_id = "community-other"
        self.third_id = "community-third"
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("CREATE TABLE users (id TEXT PRIMARY KEY)")
            conn.executemany(
                "INSERT INTO users(id) VALUES (?)",
                ((self.owner_id,), (self.other_id,), (self.third_id,)),
            )
            conn.commit()
        finally:
            conn.close()
        self.service = CommunityService(self.db_path)
        self.service.initialize()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    @staticmethod
    def snapshot(**overrides) -> CommunityDraftInput:
        values = {
            "client_snapshot_id": "snapshot-01",
            "journey_id": "journey-01",
            "journey_edition_id": "edition-01",
            "source_edition_version": 2,
            "title": "园区研学第一日",
            "content": "上午参观能源站，下午完成小组复盘。",
            "ai_assisted": True,
            "redacted_coordinate_count": 1,
            "privacy_reviewed": True,
            "rights_confirmed": True,
        }
        values.update(overrides)
        return CommunityDraftInput(**values)

    @staticmethod
    def png_with_metadata(note: str) -> bytes:
        def chunk(kind: bytes, value: bytes) -> bytes:
            return (
                struct.pack(">I", len(value)) + kind + value +
                struct.pack(">I", zlib.crc32(kind + value) & 0xFFFFFFFF)
            )

        return (
            b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) +
            chunk(b"tEXt", f"location={note}".encode("utf-8")) +
            chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00")) +
            chunk(b"IEND", b"")
        )

    @staticmethod
    def media_manifest(client_media_id: str, original: bytes, thumbnail: bytes) -> CommunityMediaManifestInput:
        return CommunityMediaManifestInput(
            client_media_id=client_media_id,
            display_name="园区观察.png",
            mime_type="image/png",
            original_bytes=len(original),
            original_sha256=hashlib.sha256(original).hexdigest(),
            thumbnail_bytes=len(thumbnail),
            thumbnail_sha256=hashlib.sha256(thumbnail).hexdigest(),
        )

    def test_create_is_idempotent_and_rejects_key_reuse_with_changed_content(self) -> None:
        created, was_created = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )
        repeated, was_repeated_created = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )

        self.assertTrue(was_created)
        self.assertFalse(was_repeated_created)
        self.assertEqual(created["id"], repeated["id"])
        self.assertEqual(created["status"], "private_draft")
        with self.assertRaises(CommunityConflictError):
            self.service.create_private_draft(
                self.owner_id,
                self.snapshot(content="相同幂等键下的另一份正文"),
            )

    def test_owner_isolation_publish_and_terminal_withdrawal(self) -> None:
        created, _ = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(),
        )

        with self.assertRaises(CommunityNotFoundError):
            self.service.get_post(self.other_id, created["id"])
        published = self.service.publish(self.owner_id, created["id"])
        self.assertEqual(published["status"], "published")
        self.assertEqual(published["moderation_status"], "pending")
        self.assertIsNotNone(published["published_at"])
        self.assertEqual(
            self.service.publish(self.owner_id, created["id"])["published_at"],
            published["published_at"],
        )

        withdrawn = self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(withdrawn["status"], "withdrawn")
        self.assertEqual(
            self.service.withdraw(self.owner_id, created["id"])["withdrawn_at"],
            withdrawn["withdrawn_at"],
        )
        with self.assertRaises(CommunityConflictError):
            self.service.publish(self.owner_id, created["id"])

    def test_rejects_unreviewed_or_precisely_located_content(self) -> None:
        invalid_snapshots = (
            self.snapshot(privacy_reviewed=False),
            self.snapshot(rights_confirmed=False),
            self.snapshot(content="集合点坐标：31.230416, 121.473701"),
            self.snapshot(content="latitude: 31.230416，地点已确认"),
        )
        for snapshot in invalid_snapshots:
            with self.subTest(snapshot=snapshot):
                with self.assertRaises(CommunityError):
                    self.service.create_private_draft(self.owner_id, snapshot)

    def test_public_visibility_requires_approval_and_withdrawal_removes_it(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])

        self.assertEqual(self.service.list_public_posts()["items"], [])
        with self.assertRaises(CommunityNotFoundError):
            self.service.get_public_post(created["id"])

        approved = self.service.review_post(
            created["id"],
            decision="approved",
            reason="",
            reviewed_by="reviewer-01",
        )
        self.assertEqual(approved["review"]["status"], "approved")
        self.assertEqual(
            self.service.get_post(self.owner_id, created["id"])["review"]["status"],
            "approved",
        )
        public = self.service.list_public_posts()
        self.assertEqual([item["id"] for item in public["items"]], [created["id"]])
        self.assertEqual(public["items"][0]["author_label"], "研学同行者")
        self.assertNotIn("journey_id", public["items"][0])

        self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(self.service.list_public_posts()["items"], [])

    def test_rejected_post_stays_private_and_owner_list_includes_review(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        with self.assertRaises(CommunityError):
            self.service.review_post(
                created["id"],
                decision="rejected",
                reason="",
                reviewed_by="reviewer-01",
            )
        reviewed = self.service.review_post(
            created["id"],
            decision="rejected",
            reason="需去除未授权人物信息",
            reviewed_by="reviewer-01",
        )
        self.assertEqual(reviewed["review"]["status"], "rejected")
        self.assertEqual(self.service.list_public_posts()["items"], [])
        mine = self.service.list_owner_posts(self.owner_id)
        self.assertEqual(mine["items"][0]["review"]["reason"], "需去除未授权人物信息")
        self.assertEqual(self.service.list_owner_posts(self.other_id)["items"], [])

    def test_public_search_filters_confirmed_index_metadata(self) -> None:
        first, _ = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(
                destination="上海",
                travel_date="2026-08-06",
                travel_days=2,
                stage_titles=("能源站参观", "小组复盘"),
                tags=("科普", "团队学习"),
                pois=("上海科技馆",),
            ),
        )
        self.service.publish(self.owner_id, first["id"])
        self.service.review_post(first["id"], decision="approved", reason="", reviewed_by="reviewer-01")

        second, _ = self.service.create_private_draft(
            self.owner_id,
            self.snapshot(
                client_snapshot_id="snapshot-02",
                destination="苏州",
                travel_days=1,
                tags=("园林",),
                pois=("拙政园",),
            ),
        )
        self.service.publish(self.owner_id, second["id"])
        self.service.review_post(second["id"], decision="approved", reason="", reviewed_by="reviewer-01")

        filtered = self.service.list_public_posts(
            search_query="科技馆",
            destination="上海",
            tag="科普",
            poi="上海科技馆",
            min_days=2,
            max_days=2,
        )
        self.assertEqual([item["id"] for item in filtered["items"]], [first["id"]])
        item = filtered["items"][0]
        self.assertEqual(item["destination"], "上海")
        self.assertEqual(item["travel_days"], 2)
        self.assertEqual(item["stages"], ["能源站参观", "小组复盘"])
        self.assertEqual(sorted(item["tags"]), sorted(["科普", "团队学习"]))
        self.assertEqual(item["pois"], ["上海科技馆"])
        self.assertIn("上海", filtered["facets"]["destinations"])
        self.assertIn("拙政园", filtered["facets"]["pois"])
        self.assertEqual(
            self.service.list_public_posts(has_media=True)["items"],
            [],
        )

    def test_public_cursor_pagination_is_stable_and_preserves_filter_scope(self) -> None:
        created_ids = []
        for index in range(3):
            created, _ = self.service.create_private_draft(
                self.owner_id,
                self.snapshot(
                    client_snapshot_id=f"pagination-{index}",
                    destination="上海",
                    tags=("科普",),
                ),
            )
            self.service.publish(self.owner_id, created["id"])
            self.service.review_post(
                created["id"],
                decision="approved",
                reason="",
                reviewed_by="reviewer-01",
            )
            created_ids.append(created["id"])

        first_page = self.service.list_public_posts(
            limit=2,
            destination="上海",
            tag="科普",
        )
        self.assertEqual(len(first_page["items"]), 2)
        self.assertIsNotNone(first_page["next_cursor"])

        second_page = self.service.list_public_posts(
            limit=2,
            cursor=first_page["next_cursor"],
            destination="上海",
            tag="科普",
        )
        first_ids = [item["id"] for item in first_page["items"]]
        second_ids = [item["id"] for item in second_page["items"]]
        self.assertTrue(set(first_ids).issubset(set(created_ids)))
        self.assertTrue(set(second_ids).issubset(set(created_ids)))
        self.assertTrue(set(first_ids).isdisjoint(second_ids))
        self.assertEqual(len(second_ids), 1)
        self.assertIsNone(second_page["next_cursor"])

        moderation_first = self.service.list_moderation_queue(status="all", limit=2)
        moderation_second = self.service.list_moderation_queue(
            status="all",
            cursor=moderation_first["next_cursor"],
            limit=2,
        )
        moderation_first_ids = {item["id"] for item in moderation_first["items"]}
        moderation_second_ids = {item["id"] for item in moderation_second["items"]}
        self.assertEqual(len(moderation_first_ids), 2)
        self.assertEqual(len(moderation_second_ids), 1)
        self.assertTrue(moderation_first_ids.isdisjoint(moderation_second_ids))

    def test_interactions_are_idempotent_scoped_and_comment_deletion_is_soft(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        self.service.review_post(
            created["id"],
            decision="approved",
            reason="",
            reviewed_by=self.other_id,
        )

        liked = self.service.toggle_like(self.owner_id, created["id"])
        self.assertTrue(liked["liked"])
        self.assertEqual(liked["like_count"], 1)
        unliked = self.service.toggle_like(self.owner_id, created["id"])
        self.assertFalse(unliked["liked"])
        self.assertEqual(unliked["like_count"], 0)

        bookmarked = self.service.toggle_bookmark(self.owner_id, created["id"])
        self.assertTrue(bookmarked["bookmarked"])
        self.assertTrue(self.service.get_interactions(self.owner_id, created["id"])["bookmarked"])

        own_comment = self.service.create_comment(self.owner_id, created["id"], "讲解很清楚")
        other_comment = self.service.create_comment(self.other_id, created["id"], "适合团队参观")
        comments = self.service.list_comments(created["id"], viewer_user_id=self.owner_id)
        by_id = {item["id"]: item for item in comments["items"]}
        self.assertTrue(by_id[own_comment["id"]]["can_delete"])
        self.assertFalse(by_id[other_comment["id"]]["can_delete"])
        self.assertEqual(self.service.get_interactions(self.owner_id, created["id"])["comment_count"], 2)

        self.assertEqual(
            self.service.delete_comment(self.owner_id, own_comment["id"])["status"],
            "deleted",
        )
        remaining = self.service.list_comments(created["id"])
        self.assertEqual([item["id"] for item in remaining["items"]], [other_comment["id"]])

        withdrawn = self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(withdrawn["status"], "withdrawn")
        with self.assertRaises(CommunityNotFoundError):
            self.service.toggle_like(self.owner_id, created["id"])

    def test_action_rate_limits_persist_and_summary_remains_aggregate_only(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        self.service.review_post(
            created["id"],
            decision="approved",
            reason="",
            reviewed_by=self.other_id,
        )
        now = [1_786_000_000_000]
        limited_service = CommunityService(
            self.db_path,
            action_rate_limits={"comment": (2, 60)},
            now_ms_provider=lambda: now[0],
        )
        limited_service.initialize()

        limited_service.create_comment(self.owner_id, created["id"], "第一条")
        limited_service.create_comment(self.owner_id, created["id"], "第二条")
        with self.assertRaises(CommunityRateLimitError) as raised:
            limited_service.create_comment(self.owner_id, created["id"], "第三条")
        self.assertGreaterEqual(raised.exception.retry_after_seconds, 1)
        self.assertLessEqual(raised.exception.retry_after_seconds, 60)

        summary = limited_service.activity_summary(hours=1)
        self.assertEqual(summary["allowed_action_count"], 2)
        self.assertEqual(summary["limited_action_count"], 1)
        self.assertNotIn("user_id", summary)
        with limited_service._connect() as conn:
            metric_columns = {
                row[1]
                for row in conn.execute("PRAGMA table_info(community_activity_metrics)").fetchall()
            }
        self.assertNotIn("user_id", metric_columns)

        now[0] += 60_000
        limited_service.create_comment(self.owner_id, created["id"], "新窗口")
        refreshed = limited_service.activity_summary(hours=1)
        self.assertEqual(refreshed["allowed_action_count"], 3)
        self.assertEqual(refreshed["limited_action_count"], 1)

    def test_bookmarks_and_comment_reports_have_public_gates_and_admin_resolution(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        self.service.review_post(
            created["id"],
            decision="approved",
            reason="",
            reviewed_by=self.other_id,
        )
        self.service.toggle_bookmark(self.owner_id, created["id"])
        bookmarks = self.service.list_bookmarks(self.owner_id)
        self.assertEqual([item["id"] for item in bookmarks["items"]], [created["id"]])
        self.assertNotIn("user_id", bookmarks["items"][0])

        comment = self.service.create_comment(self.owner_id, created["id"], "评论内容")
        report, created_report = self.service.report_comment(
            self.other_id,
            comment["id"],
            CommunityReportInput(category="spam", reason="无关推广"),
        )
        repeated, repeated_created = self.service.report_comment(
            self.other_id,
            comment["id"],
            CommunityReportInput(category="privacy", reason="重复提交"),
        )
        self.assertTrue(created_report)
        self.assertFalse(repeated_created)
        self.assertEqual(report["id"], repeated["id"])
        second_report, _ = self.service.report_comment(
            self.third_id,
            comment["id"],
            CommunityReportInput(category="privacy", reason="可能含位置"),
        )
        queue = self.service.list_comment_reports()
        self.assertEqual(len(queue["items"]), 2)
        self.assertTrue(all(item["comment_id"] == comment["id"] for item in queue["items"]))
        self.assertTrue(all("reporter_user_id" not in item for item in queue["items"]))
        report_first = self.service.list_comment_reports(limit=1)
        report_second = self.service.list_comment_reports(
            cursor=report_first["next_cursor"],
            limit=1,
        )
        self.assertIsNotNone(report_first["next_cursor"])
        self.assertNotEqual(report_first["items"][0]["id"], report_second["items"][0]["id"])
        self.assertIsNone(report_second["next_cursor"])

        resolved = self.service.resolve_comment_report(
            report["id"],
            decision="delete",
            reviewed_by=self.other_id,
        )
        self.assertEqual(resolved["status"], "resolved")
        self.assertEqual(self.service.list_comments(created["id"])["items"], [])
        self.assertEqual(self.service.list_comment_reports()["items"], [])
        with self.service._connect() as conn:
            audit_rows = conn.execute(
                "SELECT id, resolution, reviewed_by, reviewed_at "
                "FROM community_comment_reports WHERE comment_id = ? ORDER BY id",
                (comment["id"],),
            ).fetchall()
        self.assertEqual({row["id"] for row in audit_rows}, {report["id"], second_report["id"]})
        self.assertTrue(all(row["resolution"] == "delete" for row in audit_rows))
        self.assertTrue(all(row["reviewed_by"] == self.other_id for row in audit_rows))
        self.assertTrue(all(row["reviewed_at"] is not None for row in audit_rows))
        self.service.withdraw(self.owner_id, created["id"])
        self.assertEqual(self.service.list_bookmarks(self.owner_id)["items"], [])

    def test_resumable_media_is_sanitized_and_public_only_after_review(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        original = self.png_with_metadata("31.230416,121.473701")
        thumbnail = self.png_with_metadata("thumbnail")
        manifest = self.media_manifest("media-01", original, thumbnail)
        media, was_created = self.service.create_media_manifest(
            self.owner_id,
            created["id"],
            manifest,
        )
        repeated, was_repeated = self.service.create_media_manifest(
            self.owner_id,
            created["id"],
            manifest,
        )
        self.assertTrue(was_created)
        self.assertFalse(was_repeated)
        self.assertEqual(media["id"], repeated["id"])

        split = len(original) // 2
        self.service.append_media_chunk(
            self.owner_id,
            created["id"],
            media["id"],
            "original",
            start=0,
            end=split - 1,
            total=len(original),
            data=original[:split],
            chunk_sha256=hashlib.sha256(original[:split]).hexdigest(),
        )
        with self.assertRaises(CommunityConflictError):
            self.service.append_media_chunk(
                self.owner_id,
                created["id"],
                media["id"],
                "original",
                start=0,
                end=split - 1,
                total=len(original),
                data=original[:split],
                chunk_sha256=hashlib.sha256(original[:split]).hexdigest(),
            )
        self.service.append_media_chunk(
            self.owner_id,
            created["id"],
            media["id"],
            "original",
            start=split,
            end=len(original) - 1,
            total=len(original),
            data=original[split:],
            chunk_sha256=hashlib.sha256(original[split:]).hexdigest(),
        )
        self.service.append_media_chunk(
            self.owner_id,
            created["id"],
            media["id"],
            "thumbnail",
            start=0,
            end=len(thumbnail) - 1,
            total=len(thumbnail),
            data=thumbnail,
            chunk_sha256=hashlib.sha256(thumbnail).hexdigest(),
        )
        owner_media = self.service.list_owner_media(self.owner_id, created["id"])
        self.assertEqual(owner_media["items"][0]["status"], "ready")
        self.assertGreater(owner_media["quota"]["used_bytes"], 0)
        with self.assertRaises(CommunityNotFoundError):
            self.service.public_media_file(media["id"], "original")

        self.service.review_post(
            created["id"], decision="approved", reason="", reviewed_by="reviewer-01"
        )
        public_path, mime_type = self.service.public_media_file(media["id"], "original")
        self.assertEqual(mime_type, "image/png")
        self.assertTrue(public_path.is_file())
        self.assertNotIn(b"tEXt", public_path.read_bytes())
        public_post = self.service.get_public_post(created["id"])
        self.assertEqual(public_post["media"][0]["id"], media["id"])

    def test_media_quota_is_byte_based_not_a_fixed_image_count(self) -> None:
        limited = CommunityService(
            self.db_path,
            media_root=Path(self.temp_dir.name) / "limited-media",
            media_quota_bytes=100,
            media_max_asset_bytes=100,
            media_max_thumbnail_bytes=100,
        )
        limited.initialize()
        created, _ = limited.create_private_draft(self.owner_id, self.snapshot(client_snapshot_id="quota-snapshot"))
        original = self.png_with_metadata("one")
        thumbnail = self.png_with_metadata("two")
        with self.assertRaises(CommunityConflictError):
            limited.create_media_manifest(
                self.owner_id,
                created["id"],
                self.media_manifest("quota-media", original, thumbnail),
            )

    def test_reports_are_anonymous_idempotent_and_close_after_review(self) -> None:
        created, _ = self.service.create_private_draft(self.owner_id, self.snapshot())
        self.service.publish(self.owner_id, created["id"])
        with self.assertRaises(CommunityNotFoundError):
            self.service.report_post(
                self.other_id,
                created["id"],
                CommunityReportInput(category="spam", reason="待审核内容"),
            )
        self.service.review_post(
            created["id"], decision="approved", reason="", reviewed_by="reviewer-01"
        )

        with self.assertRaises(CommunityPermissionError):
            self.service.report_post(
                self.owner_id,
                created["id"],
                CommunityReportInput(category="spam"),
            )
        with self.assertRaises(CommunityError):
            self.service.report_post(
                self.other_id,
                created["id"],
                CommunityReportInput(category="invalid"),
            )
        with self.assertRaises(CommunityError):
            self.service.report_post(
                self.other_id,
                created["id"],
                CommunityReportInput(category="other", reason="x" * 1001),
            )

        report, first = self.service.report_post(
            self.other_id,
            created["id"],
            CommunityReportInput(category="privacy", reason="公开内容包含不应出现的信息"),
        )
        repeated, second = self.service.report_post(
            self.other_id,
            created["id"],
            CommunityReportInput(category="spam", reason="重复提交"),
        )
        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(report["id"], repeated["id"])
        self.assertNotIn("reporter_user_id", report)

        queue = self.service.list_moderation_queue(status="reported")
        self.assertEqual(queue["items"][0]["open_report_count"], 1)
        self.assertEqual(queue["items"][0]["reports"][0]["category"], "privacy")
        self.assertNotIn("reporter_user_id", queue["items"][0]["reports"][0])

        self.service.review_post(
            created["id"],
            decision="rejected",
            reason="请补充隐私处理说明",
            reviewed_by="reviewer-01",
        )
        self.assertEqual(self.service.list_moderation_queue(status="reported")["items"], [])
        with self.service._connect() as conn:
            status = conn.execute(
                "SELECT status FROM community_reports WHERE id = ?", (report["id"],)
            ).fetchone()["status"]
        self.assertEqual(status, "resolved")


if __name__ == "__main__":
    unittest.main()
