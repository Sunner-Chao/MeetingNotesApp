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

        def fake_runner(provider, prompt, attachments, task_root):
            self.calls.append((provider, prompt, attachments, task_root))
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

    def test_claude_stream_error_extracts_result_detail(self):
        output = (
            '{"type":"system","subtype":"init"}\n'
            '{"type":"result","is_error":true,"subtype":"error_during_execution",'
            '"result":"upstream request timed out"}\n'
        )
        self.assertEqual(parse_claude_error(output), "upstream request timed out")

    def test_claude_image_failure_falls_back_to_authorized_codex(self):
        calls = []

        def fallback_runner(provider, prompt, attachments, task_root):
            calls.append(provider)
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
            },
            [IncomingAttachment("meeting.png", "image/png", io.BytesIO(b"png"))],
        )

        self.assertEqual(response["text"], "codex image report")
        self.assertEqual(calls, ["claude-cli", "codex-cli"])
        task = self.gateway.get_task(principal, response["task_id"])
        self.assertEqual(task["provider"], "codex-cli")


if __name__ == "__main__":
    unittest.main()
