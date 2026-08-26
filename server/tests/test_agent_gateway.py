import io
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend-service"
sys.path.insert(0, str(BACKEND_DIR))

from agent_gateway import (  # noqa: E402
    AgentAuthError,
    AgentGateway,
    AgentInputError,
    AgentPermissionError,
    AgentProviderError,
    AgentQuotaError,
    IncomingAttachment,
    parse_claude_output,
    parse_claude_error,
)


class AgentGatewayTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.calls = []

        def fake_runner(provider, prompt, attachments, task_root, reasoning_effort):
            self.calls.append((provider, prompt, attachments, task_root, reasoning_effort))
            return "agent result"

        self.gateway = AgentGateway(
            db_path=root / "backend.db",
            work_root=root / "tasks",
            bootstrap_token="bootstrap-secret",
            default_request_limit=1,
            allowed_providers={"codex-cli", "claude-cli"},
            max_concurrent=1,
            max_queue=1,
            max_image_bytes=1024,
            max_total_bytes=2048,
            runner=fake_runner,
        )
        self.gateway.initialize()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_initialize_fails_tasks_orphaned_by_service_restart(self):
        principal = self.gateway.authenticate("Bearer bootstrap-secret")
        with self.gateway._connect() as conn:
            conn.execute(
                """
                INSERT INTO agent_tasks (
                    id, token_id, provider, operation, status, attachment_count, created_at
                ) VALUES ('orphan', ?, 'claude-cli', 'generate_report', 'running', 1, 1)
                """,
                (principal.token_id,),
            )

        self.gateway.initialize()

        task = self.gateway.get_task(principal, "orphan")
        self.assertEqual(task["status"], "failed")
        self.assertIn("restarted", task["error"])
        self.assertIsNotNone(task["finished_at"])

    def test_initialize_refunds_reserved_points_for_orphaned_tasks(self):
        account_id = "restart-refund-user"
        with self.gateway._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE users (
                    id TEXT PRIMARY KEY,
                    role TEXT NOT NULL
                );
                CREATE TABLE account_usage_balances (
                    user_id TEXT PRIMARY KEY,
                    ai_credits_used INTEGER NOT NULL DEFAULT 0,
                    points_used INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE account_usage_events (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    unit TEXT NOT NULL,
                    status TEXT NOT NULL,
                    charged INTEGER NOT NULL DEFAULT 0,
                    completed_at INTEGER
                );
                """
            )
            conn.execute("INSERT INTO users (id, role) VALUES (?, 'user')", (account_id,))
            conn.execute(
                "INSERT INTO account_usage_balances (user_id, ai_credits_used, points_used) VALUES (?, 1, 30)",
                (account_id,),
            )
            conn.execute(
                "INSERT INTO account_usage_events (id, user_id, quantity, unit, status, charged) VALUES (?, ?, 30, 'points', 'reserved', 1)",
                ("restart-event", account_id),
            )
            conn.execute(
                """
                INSERT INTO agent_tasks (
                    id, token_id, provider, operation, status, usage_event_id, charged, created_at
                ) VALUES ('restart-task', 'bootstrap', 'codex-cli', 'chat', 'running', 'restart-event', 1, 1)
                """
            )

        self.gateway.initialize()

        with self.gateway._connect() as conn:
            balance = conn.execute(
                "SELECT ai_credits_used, points_used FROM account_usage_balances WHERE user_id = ?",
                (account_id,),
            ).fetchone()
            event = conn.execute(
                "SELECT status, charged FROM account_usage_events WHERE id = 'restart-event'"
            ).fetchone()
        self.assertEqual((balance["ai_credits_used"], balance["points_used"]), (0, 0))
        self.assertEqual((event["status"], event["charged"]), ("refunded", 0))

    def test_authentication_and_quota_are_enforced(self):
        with self.assertRaises(AgentAuthError):
            self.gateway.authenticate("Bearer wrong")

        principal = self.gateway.authenticate("Bearer bootstrap-secret")
        response = self.gateway.execute(
            principal,
            {
                "provider": "codex-cli",
                "operation": "chat",
                "messages": [{"role": "user", "content": "hello"}],
            },
            [],
        )
        self.assertEqual(response["status"], "succeeded")
        self.assertEqual(response["text"], "agent result")
        self.assertEqual(self.gateway.quota(principal)["requests_remaining"], 0)
        self.assertEqual(self.gateway.get_task(principal, response["task_id"])["status"], "succeeded")

        with self.assertRaises(AgentQuotaError):
            self.gateway.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "chat",
                    "messages": [{"role": "user", "content": "again"}],
                },
                [],
            )

    def test_text_limit_is_disabled_by_default_and_can_be_configured(self):
        long_text = "会" * 500_001
        prompt = self.gateway._build_prompt(
            {
                "operation": "generate_report",
                "transcript": long_text,
            }
        )
        self.assertIn(long_text, prompt)

        root = Path(self.temp_dir.name)
        limited = AgentGateway(
            db_path=root / "limited.db",
            work_root=root / "limited-tasks",
            max_text_chars=10,
            runner=lambda *_args: "unused",
        )
        with self.assertRaises(AgentInputError):
            limited._build_prompt(
                {
                    "operation": "generate_report",
                    "transcript": "12345678901",
                }
            )

    def test_visit_template_prompt_preserves_guide_and_trip_constraints(self):
        prompt = self.gateway._build_prompt(
            {
                "operation": "generate_report",
                "templateName": "参观考察（游记）",
                "templateContent": "小红书/携程游记式可读性",
                "transcript": "讲解员介绍展厅，团队记录现场观察。",
                "attachmentManifest": [
                    {
                        "index": 1,
                        "displayName": "entrance.jpg",
                        "capturedAt": 1_754_274_400_000,
                        "locationCapturedAt": 1_754_274_401_000,
                        "latitude": 30.7521,
                        "longitude": 120.7582,
                        "accuracyMeters": 18.5,
                        "locationSource": "gps",
                        "recordingMarkerId": "marker-1",
                        "markerTimestampMs": 72_000,
                        "markerTranscriptAnchor": "随后讲解员介绍\n大殿。",
                    }
                ],
            },
            attachment_count=3,
        )

        self.assertIn("本次共有 3 张图片附件", prompt)
        self.assertIn("导游、讲解员、接待方、受访方和参观者", prompt)
        self.assertIn("小红书/携程游记", prompt)
        self.assertIn("输出面向阅读与分享的图文游记", prompt)
        self.assertIn("不要机械拆成审计表", prompt)
        self.assertIn("每一站只保留一个站点标题", prompt)
        self.assertIn("禁止输出‘时间与点位’", prompt)
        self.assertIn("禁止生成事实与待确认", prompt)
        self.assertIn("没有图片时不要输出空照片章节", prompt)
        self.assertIn("缺失时直接省略对应正文小节", prompt)
        self.assertIn("不得虚构天气、心情、气味、路线、体验或评价", prompt)
        self.assertIn("图片编号不得超过这个范围", prompt)
        self.assertIn("客户端图片附件清单", prompt)
        self.assertIn("entrance.jpg", prompt)
        self.assertIn("1754274400000", prompt)
        self.assertIn("位置辅助=30.752100,120.758200", prompt)
        self.assertIn("精度约 18.5 米", prompt)
        self.assertIn("来源=gps", prompt)
        self.assertIn("录音标记=01:12", prompt)
        self.assertIn("转写锚点=随后讲解员介绍 大殿。", prompt)
        self.assertIn("同一标记绑定多图时按图号连续插入", prompt)
        self.assertIn("不得混入项目管理或工程管理字段", prompt)
        self.assertIn("不得补写或推断职务", prompt)
        self.assertIn("[照片：图 N｜事实型图注]", prompt)
        self.assertIn("每 1-3 个短段落至少安排一张", prompt)
        self.assertIn("轮播内容页", prompt)
        self.assertIn("问题线索页", prompt)
        self.assertIn("### 问题｜具体问题", prompt)
        self.assertIn("不得强行配对或调用网络知识补齐", prompt)
        self.assertIn("田野观察板", prompt)
        self.assertIn("### 整体观察", prompt)
        self.assertNotIn("各不超过 4 行", prompt)

    def test_forum_template_prompt_requests_verified_photo_wall_roster(self):
        prompt = self.gateway._build_prompt(
            {
                "operation": "generate_report",
                "templateName": "论坛会议",
                "templateContent": "论坛信息、主题演讲、圆桌讨论与现场问答",
                "transcript": "主持人周岚邀请城市实验室的林老师作主题演讲。",
            }
        )

        self.assertIn("参会人员名录", prompt)
        self.assertIn("姓名/称谓 | 单位 | 角色", prompt)
        self.assertIn("照片墙通讯录", prompt)
        self.assertIn("不从会议照片推断人物身份", prompt)
        self.assertIn("不输出占位行", prompt)

    def test_attachment_manifest_ignores_invalid_location_values(self):
        prompt = self.gateway._format_attachment_manifest(
            [
                {
                    "displayName": "broken-location.jpg",
                    "latitude": 95.0,
                    "longitude": float("nan"),
                    "accuracyMeters": -1.0,
                    "locationSource": "gps\ninjected",
                }
            ]
        )

        self.assertIn("broken-location.jpg", prompt)
        self.assertNotIn("位置辅助=", prompt)
        self.assertNotIn("injected", prompt)

    def test_default_image_count_is_unlimited_but_optional_cap_still_works(self):
        images_root = Path(self.temp_dir.name) / "images"
        images_root.mkdir()
        twelve = [
            IncomingAttachment(f"site-{index}.jpg", "image/jpeg", io.BytesIO(b"jpeg"))
            for index in range(1, 13)
        ]
        stored = self.gateway._store_attachments(images_root, twelve)
        self.assertEqual(len(stored), 12)
        self.assertEqual(stored[-1].path.name, "12-site-12.jpg")

        nine_root = Path(self.temp_dir.name) / "nine-images"
        nine_root.mkdir()
        capped = AgentGateway(
            db_path=Path(self.temp_dir.name) / "capped.db",
            work_root=Path(self.temp_dir.name) / "capped-tasks",
            max_images=2,
            max_image_bytes=1024,
            max_total_bytes=2048,
            runner=lambda *_args: "unused",
        )
        three = twelve[:3]
        with self.assertRaises(AgentInputError) as context:
            capped._store_attachments(nine_root, three)
        self.assertIn("At most 2 images are allowed", str(context.exception))

    def test_provider_permissions_and_token_metadata(self):
        issued = self.gateway.issue_token("Claude plan", 20, {"claude-cli"}, None)
        self.assertTrue(issued["token"].startswith("mn_agent_"))
        token_list = self.gateway.list_tokens()
        self.assertNotIn("token", next(item for item in token_list if item["id"] == issued["id"]))

        principal = self.gateway.authenticate(f"Bearer {issued['token']}")
        with self.assertRaises(AgentPermissionError):
            self.gateway.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "chat",
                    "messages": [{"role": "user", "content": "hello"}],
                },
                [],
            )
        self.gateway.set_token_enabled(issued["id"], False)
        with self.assertRaises(AgentAuthError):
            self.gateway.authenticate(f"Bearer {issued['token']}")

    def test_image_is_validated_and_passed_to_runner(self):
        issued = self.gateway.issue_token("Image plan", 10, {"codex-cli"}, None)
        principal = self.gateway.authenticate(f"Bearer {issued['token']}")
        response = self.gateway.execute(
            principal,
            {
                "provider": "codex-cli",
                "operation": "generate_report",
                "transcript": "site inspection",
                "templateName": "construction log",
            },
            [IncomingAttachment("site.jpg", "image/jpeg", io.BytesIO(b"jpeg-data"))],
        )
        self.assertEqual(response["status"], "succeeded")
        self.assertEqual(len(self.calls[-1][2]), 1)
        self.assertEqual(self.calls[-1][2][0].display_name, "site.jpg")

        with self.assertRaises(AgentInputError):
            self.gateway.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "chat",
                    "messages": [{"role": "user", "content": "read"}],
                },
                [IncomingAttachment("payload.txt", "text/plain", io.BytesIO(b"bad"))],
            )

    def test_provider_status_accepts_relay_credential_environment(self):
        self.gateway.codex_path = sys.executable
        self.gateway.codex_auth_env = "TEST_RELAY_API_KEY"
        with patch.dict(os.environ, {"TEST_RELAY_API_KEY": "configured"}, clear=False):
            status = self.gateway._provider_status("codex-cli")
        self.assertTrue(status["authenticated"])
        self.assertEqual(status["auth_method"], "environment")
        self.assertEqual(status["model_reasoning_effort"], "medium")

        self.gateway.claude_path = sys.executable
        self.gateway.claude_auth_env = "TEST_RELAY_API_KEY"
        with patch.dict(os.environ, {"TEST_RELAY_API_KEY": "configured"}, clear=False):
            claude_status = self.gateway._provider_status("claude-cli")
        self.assertEqual(claude_status["effort"], "medium")

        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("TEST_RELAY_API_KEY", None)
            status = self.gateway._provider_status("codex-cli")
        self.assertFalse(status["authenticated"])
        self.assertEqual(status["reason"], "credential_env_missing")

    @patch("agent_gateway.subprocess.run")
    def test_codex_exec_reads_service_account_provider_config(self, run_mock):
        run_mock.return_value = SimpleNamespace(returncode=0, stdout="relay result", stderr="")
        task_root = Path(self.temp_dir.name) / "codex-task"
        task_root.mkdir()

        result = self.gateway._run_codex("hello", [], task_root)

        self.assertEqual(result, "relay result")
        command = run_mock.call_args.args[0]
        self.assertNotIn("--ignore-user-config", command)
        self.assertIn("--ephemeral", command)
        reasoning_index = command.index("--config")
        self.assertEqual(command[reasoning_index + 1], 'model_reasoning_effort="medium"')

    @patch("agent_gateway.subprocess.run")
    def test_claude_image_uses_bidirectional_stream_json(self, run_mock):
        run_mock.return_value = SimpleNamespace(
            returncode=0,
            stdout='{"type":"result","result":"image report"}\n',
            stderr="",
        )
        task_root = Path(self.temp_dir.name) / "claude-task"
        task_root.mkdir()
        image = task_root / "meeting.png"
        image.write_bytes(b"png")

        result = self.gateway._run_claude(
            "summarize",
            [SimpleNamespace(path=image, content_type="image/png", display_name="meeting.png")],
            task_root,
        )

        self.assertEqual(result, "image report")
        command = run_mock.call_args.args[0]
        self.assertIn("stream-json", command)
        self.assertEqual(command[command.index("--input-format") + 1], "stream-json")
        self.assertEqual(command[command.index("--output-format") + 1], "stream-json")
        self.assertIn("--no-session-persistence", command)
        self.assertEqual(command[command.index("--effort") + 1], "medium")
        session_id = command[command.index("--session-id") + 1]
        self.assertEqual(str(uuid.UUID(session_id)), session_id)

        self.gateway._run_claude(
            "summarize again",
            [SimpleNamespace(path=image, content_type="image/png", display_name="meeting.png")],
            task_root,
        )
        next_command = run_mock.call_args.args[0]
        next_session_id = next_command[next_command.index("--session-id") + 1]
        self.assertNotEqual(next_session_id, session_id)

    def test_claude_stream_output_extracts_assistant_text(self):
        output = (
            '{"type":"system","subtype":"init"}\n'
            '{"type":"assistant","message":{"content":[{"type":"text","text":"会议纪要"}]}}\n'
        )
        self.assertEqual(parse_claude_output(output), "会议纪要")

    def test_claude_stream_without_final_text_does_not_leak_internal_events(self):
        output = (
            '{"type":"system","subtype":"init","model":"step-3.7-flash"}\n'
            '{"type":"system","subtype":"thinking_tokens","estimated_tokens":16}\n'
            '{"type":"assistant","message":{"content":'
            '[{"type":"thinking","thinking":"internal reasoning"}]}}\n'
            '{"type":"result","subtype":"success","result":""}\n'
        )
        self.assertEqual(parse_claude_output(output), "")

    @patch("agent_gateway.subprocess.run")
    def test_claude_success_without_final_text_is_a_provider_error(self, run_mock):
        run_mock.return_value = SimpleNamespace(
            returncode=0,
            stdout=(
                '{"type":"system","subtype":"init","model":"step-3.7-flash"}\n'
                '{"type":"assistant","message":{"content":'
                '[{"type":"thinking","thinking":"internal reasoning"}]}}\n'
                '{"type":"result","subtype":"success","result":""}\n'
            ),
            stderr="",
        )
        task_root = Path(self.temp_dir.name) / "claude-empty-task"
        task_root.mkdir()

        with self.assertRaisesRegex(AgentProviderError, "completed without final text"):
            self.gateway._run_claude("summarize", [], task_root)

    def test_claude_stream_error_extracts_result_detail(self):
        output = (
            '{"type":"system","subtype":"init"}\n'
            '{"type":"result","is_error":true,"subtype":"error_during_execution",'
            '"result":"upstream request timed out"}\n'
        )
        self.assertEqual(parse_claude_error(output), "upstream request timed out")

    def test_claude_image_failure_falls_back_to_authorized_codex(self):
        calls = []

        def fallback_runner(provider, prompt, attachments, task_root, reasoning_effort):
            calls.append((provider, reasoning_effort))
            if provider == "claude-cli":
                raise AgentProviderError("claude upstream failed")
            return "codex image report"

        self.gateway.runner = fallback_runner
        principal = self.gateway.authenticate("Bearer bootstrap-secret")
        response = self.gateway.execute(
            principal,
            {
                "provider": "claude-cli",
                "operation": "generate_report",
                "transcript": "image meeting",
                "model_reasoning_effort": "high",
                "effort": "low",
            },
            [IncomingAttachment("meeting.png", "image/png", io.BytesIO(b"png"))],
        )

        self.assertEqual(response["text"], "codex image report")
        self.assertEqual(calls, [("claude-cli", "low"), ("codex-cli", "high")])
        task = self.gateway.get_task(principal, response["task_id"])
        self.assertEqual(task["provider"], "codex-cli")

    def test_rejects_provider_specific_effort_values(self):
        principal = self.gateway.authenticate("Bearer bootstrap-secret")
        with self.assertRaisesRegex(AgentInputError, "model_reasoning_effort"):
            self.gateway.execute(
                principal,
                {
                    "provider": "codex-cli",
                    "operation": "chat",
                    "model_reasoning_effort": "max",
                    "messages": [{"role": "user", "content": "hello"}],
                },
                [],
            )


if __name__ == "__main__":
    unittest.main()
