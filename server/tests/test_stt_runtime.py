import hashlib
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace


STT_SERVICE_DIR = Path(__file__).resolve().parents[1] / "stt-service"
sys.path.insert(0, str(STT_SERVICE_DIR))

import stt_server as stt  # noqa: E402


class SttRuntimeTest(unittest.TestCase):
    def test_release_version_is_exposed(self) -> None:
        self.assertEqual(stt.SERVER_VERSION, "1.1.11")
        self.assertTrue(stt.SERVER_RELEASE.startswith("1.1.11"))

    def test_cpu_streaming_profile_is_bounded(self) -> None:
        self.assertLessEqual(stt.STREAM_MAX_SNAPSHOT_SEC, 8)
        self.assertGreaterEqual(stt.STREAM_BUFFER_SEC, stt.STREAM_MAX_SNAPSHOT_SEC)
        self.assertLessEqual(stt.STREAM_OVERLAP_SEC, stt.STREAM_MAX_SNAPSHOT_SEC / 2)
        self.assertEqual(stt.STREAM_STEP_SEC, 4)
        self.assertEqual(stt.STREAM_BEAM_SIZE, 1)
        self.assertEqual(stt.STREAM_FINAL_COMPAT_MIN_AUDIO_SEC, 2)
        self.assertEqual(stt.STT_STREAM_MODEL, "small")
        self.assertEqual(stt.STREAM_MIN_CONFIDENCE, -0.90)
        self.assertEqual(stt.STREAM_MAX_NO_SPEECH_PROB, 0.35)
        self.assertEqual(stt.STT_FINAL_RETRY_MIN_CHARS, 8)

    def test_stream_merge_keeps_non_overlapping_updates(self) -> None:
        self.assertEqual(stt.merge_transcript_text("第一项", "第二项"), "第一项 第二项")

    def test_transcripts_are_normalized_to_simplified_chinese(self) -> None:
        self.assertEqual(stt.normalize_preview_text("聽得到嗎？價格是一塊"), "听得到吗？价格是一块")

    def test_final_transcription_response_can_hold_normalized_text(self) -> None:
        response = stt.TranscribeResponse(
            text=stt.normalize_preview_text("會議記錄與價格"),
            language="zh",
        )
        self.assertEqual(response.text, "会议记录与价格")

    def test_stream_filter_rejects_noise_and_unsettled_tail(self) -> None:
        text, accepted, rejected = stt.filter_stream_segments(
            [
                {"start": 0.2, "end": 1.2, "text": "稳定内容", "avg_logprob": -0.4, "no_speech_prob": 0.1},
                {"start": 1.3, "end": 2.0, "text": "低置信内容", "avg_logprob": -1.2, "no_speech_prob": 0.1},
                {"start": 4.2, "end": 5.0, "text": "窗口尾部", "avg_logprob": -0.3, "no_speech_prob": 0.1},
            ],
            min_confidence=-0.75,
            max_no_speech_prob=0.35,
            settled_before_sec=4.0,
        )
        self.assertEqual(text, "稳定内容")
        self.assertEqual(len(accepted), 1)
        self.assertEqual({item["reject_reason"] for item in rejected}, {"low_confidence", "unstable_tail"})

    def test_stream_fallback_is_preview_only_after_repeated_rejection(self) -> None:
        self.assertEqual(stt.fallback_stream_preview("临时内容", 1, 2), "")
        self.assertEqual(stt.fallback_stream_preview("臨時內容", 2, 2), "临时内容")
        self.assertEqual(stt.fallback_stream_preview("字幕制作人Zither Harp", 3, 2), "")

    def test_stream_and_final_share_the_same_faster_whisper_strategy(self) -> None:
        calls = []

        class FakeModel:
            def transcribe(self, _file_path, **kwargs):
                calls.append(kwargs)
                return [
                    SimpleNamespace(
                        start=0.0,
                        end=2.0,
                        text="完整会议内容已经超过八个字",
                        avg_logprob=-0.2,
                        no_speech_prob=0.05,
                    )
                ], SimpleNamespace(language="zh")

        result = stt.transcribe_faster_whisper_file(FakeModel(), "sample.wav")

        self.assertEqual(result["strategy"], "final-compatible")
        self.assertEqual(len(calls), 1)
        self.assertEqual(
            calls[0],
            {
                "beam_size": 5,
                "vad_filter": True,
                "language": "zh",
                "condition_on_previous_text": False,
            },
        )

    def test_revisable_preview_uses_beam_one_without_final_retry(self) -> None:
        calls = []

        class FakeModel:
            def transcribe(self, _file_path, **kwargs):
                calls.append(kwargs)
                return [
                    SimpleNamespace(
                        start=0.0,
                        end=1.0,
                        text="实时预览",
                        avg_logprob=-0.2,
                        no_speech_prob=0.05,
                    )
                ], SimpleNamespace(language="zh")

        result = stt.transcribe_faster_whisper_preview_file(FakeModel(), "sample.wav")

        self.assertEqual(result["strategy"], "revisable-preview")
        self.assertEqual(len(calls), 1)
        self.assertEqual(
            calls[0],
            {
                "beam_size": 1,
                "vad_filter": True,
                "language": "zh",
                "condition_on_previous_text": False,
            },
        )

    def test_stream_preview_gate_rejects_unstable_low_confidence_text(self) -> None:
        selected, mode, similarity, accepted, rejected = stt.select_stream_preview(
            "本轮可能误识别",
            "上一轮完全不同",
            [
                {
                    "start": 0.0,
                    "end": 2.0,
                    "text": "本轮可能误识别",
                    "avg_logprob": -1.1,
                    "no_speech_prob": 0.7,
                }
            ],
        )
        self.assertEqual(selected, "")
        self.assertEqual(mode, "held")
        self.assertLess(similarity, 0.7)
        self.assertEqual(accepted, [])
        self.assertEqual(len(rejected), 1)

    def test_stream_preview_gate_accepts_confident_or_stable_text(self) -> None:
        confident, confident_mode, *_ = stt.select_stream_preview(
            "会议决定下周复查",
            "",
            [
                {
                    "start": 0.0,
                    "end": 2.0,
                    "text": "会议决定下周复查",
                    "avg_logprob": -0.2,
                    "no_speech_prob": 0.05,
                }
            ],
        )
        stable, stable_mode, similarity, *_ = stt.select_stream_preview(
            "会议决定下周复查。",
            "会议决定下周复查",
            [
                {
                    "start": 0.0,
                    "end": 2.0,
                    "text": "会议决定下周复查。",
                    "avg_logprob": -1.1,
                    "no_speech_prob": 0.7,
                }
            ],
        )
        self.assertEqual(confident, "会议决定下周复查")
        self.assertEqual(confident_mode, "confidence")
        self.assertEqual(stable, "会议决定下周复查。")
        self.assertEqual(stable_mode, "stable")
        self.assertGreaterEqual(similarity, 0.7)

    def test_stream_preview_gate_marks_short_confident_fragments_revisable(self) -> None:
        selected, mode, *_ = stt.select_stream_preview(
            "好的",
            "",
            [
                {
                    "start": 0.0,
                    "end": 0.8,
                    "text": "好的",
                    "avg_logprob": -0.1,
                    "no_speech_prob": 0.01,
                }
            ],
        )
        self.assertEqual(selected, "好的")
        self.assertEqual(mode, "provisional")

    def test_sha256_file_streams_expected_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.bin"
            path.write_bytes(b"meeting-notes")
            expected = hashlib.sha256(b"meeting-notes").hexdigest()
            self.assertEqual(stt.sha256_file(path), expected)

    def test_temp_cleanup_removes_only_stale_service_files(self) -> None:
        previous_dir = stt.STT_TEMP_DIR
        previous_age = stt.STT_TEMP_MAX_AGE_SEC
        try:
            with tempfile.TemporaryDirectory() as directory:
                stt.STT_TEMP_DIR = Path(directory)
                stt.STT_TEMP_MAX_AGE_SEC = 60
                stale = stt.STT_TEMP_DIR / f"{stt.STT_TEMP_PREFIX}stale.wav"
                fresh = stt.STT_TEMP_DIR / f"{stt.STT_TEMP_PREFIX}fresh.wav"
                unrelated = stt.STT_TEMP_DIR / "other.tmp"
                for path in (stale, fresh, unrelated):
                    path.write_bytes(b"x")
                old = time.time() - 120
                os.utime(stale, (old, old))
                os.utime(unrelated, (old, old))

                self.assertEqual(stt.cleanup_stale_temp_files(), 1)
                self.assertFalse(stale.exists())
                self.assertTrue(fresh.exists())
                self.assertTrue(unrelated.exists())
        finally:
            stt.STT_TEMP_DIR = previous_dir
            stt.STT_TEMP_MAX_AGE_SEC = previous_age


class SttRuntimeSecurityTest(unittest.IsolatedAsyncioTestCase):
    async def test_required_token_cannot_be_empty(self) -> None:
        previous_required = stt.STT_REQUIRE_API_TOKEN
        previous_token = stt.STT_API_TOKEN
        try:
            stt.STT_REQUIRE_API_TOKEN = True
            stt.STT_API_TOKEN = ""
            with self.assertRaisesRegex(RuntimeError, "STT_API_TOKEN is required"):
                async with stt.app_lifespan(stt.app):
                    pass
        finally:
            stt.STT_REQUIRE_API_TOKEN = previous_required
            stt.STT_API_TOKEN = previous_token


if __name__ == "__main__":
    unittest.main()
