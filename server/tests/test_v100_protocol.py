"""Protocol regressions independent of CUDA/model downloads."""
import contextlib
import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("v100_protocol_test", ROOT / "v100-service/v100_funasr_server.py")
service = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"funasr": types.SimpleNamespace(AutoModel=object),
                              "torch": types.SimpleNamespace(inference_mode=contextlib.nullcontext,
                                                              cuda=types.SimpleNamespace(is_available=lambda: False))}):
    spec.loader.exec_module(service)


class DelayedModel:
    def generate(self, *, input, cache, is_final, **_):
        if len(input):
            cache["pending"] = cache.get("pending", "") + "声音"
        if is_final:
            return [{"text": cache.pop("pending", "")}]
        return [{"text": ""}]


class V100ProtocolTest(unittest.TestCase):
    def setUp(self):
        service.ACCESS_TOKEN = "protocol-test-only"
        service.ACCOUNT_TOKEN_SECRET = "account-test-only"
        service.online_model = DelayedModel()
        service.offline_model = object()
        service.model_error = ""
        service.active_streams = 0
        service.events.clear()
        self.client = TestClient(service.app)
        self.headers = {"Authorization": "Bearer protocol-test-only"}

    def start(self, ws):
        ws.send_json({"event": "start", "sample_rate": 16000, "channels": 1})
        status = ws.receive_json()
        self.assertEqual(status["stream_provider"], "local")
        self.assertRegex(status["session_id"], r"^[0-9a-f]{32}$")

    def test_android_header_auth_exact_chunk_stop_keeps_delayed_tail(self):
        with self.client.websocket_connect("/ws/transcribe-stream", headers=self.headers) as ws:
            self.start(ws)
            ws.send_bytes(b"\0" * service.CHUNK_SAMPLES * 2)
            ws.send_json({"event": "stop"})
            self.assertEqual(ws.receive_json()["committed_text"], "声音")
            self.assertEqual(ws.receive_json()["text"], "声音")
        self.assertEqual(service.active_streams, 0)

    def test_browser_auth_pause_ignores_audio_and_resume_has_fresh_cache(self):
        with self.client.websocket_connect("/ws/transcribe-stream") as ws:
            ws.send_json({"event": "authenticate", "access_token": "protocol-test-only"})
            self.start(ws)
            ws.send_bytes(b"\0" * service.CHUNK_SAMPLES * 2)
            ws.send_json({"event": "pause"})
            self.assertEqual(ws.receive_json()["text"], "声音")
            self.assertEqual(ws.receive_json()["type"], "status")
            ws.send_bytes(b"\0" * service.CHUNK_SAMPLES * 2)
            ws.send_json({"event": "resume"})
            self.assertEqual(ws.receive_json()["type"], "status")
            ws.send_bytes(b"\0" * 1600)
            ws.send_json({"event": "stop"})
            self.assertEqual(ws.receive_json()["text"], "声音声音")
            self.assertEqual(ws.receive_json()["text"], "声音声音")

    def test_invalid_account_token_is_rejected_and_does_not_reserve_slot(self):
        with self.client.websocket_connect("/ws/transcribe-stream", headers={"Authorization": "Bearer bad"}) as ws:
            self.assertEqual(ws.receive_json()["type"], "error")
        self.assertEqual(service.active_streams, 0)
        self.assertEqual(self.client.get("/debug/stream-events").status_code, 401)

    def test_account_diagnostics_are_owner_scoped(self):
        import time
        from account_stt_token import issue_account_stt_token
        token = issue_account_stt_token("account-test-only", "alice", int(time.time()) + 60)
        service.events.extend([{"owner": "alice", "event": "a"}, {"owner": "bob", "event": "b"}])
        response = self.client.get("/debug/stream-events", headers={"Authorization": "Bearer " + token})
        self.assertEqual(response.json()["events"], [{"event": "a"}])

    def test_upload_capacity_cleans_partial_file(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(service, "TEMP_DIR", directory), patch.object(service, "MAX_UPLOAD_BYTES", 2):
            response = self.client.post("/transcribe", headers=self.headers, files={"file": ("audio.wav", b"1234")})
            self.assertEqual(response.status_code, 413)
            self.assertEqual(list(Path(directory).iterdir()), [])
            self.assertEqual(service.file_requests, 0)


if __name__ == "__main__":
    unittest.main()
