import asyncio
import base64
import contextlib
from contextlib import contextmanager
import hashlib
import io
import json
import os
import sys
import tempfile
import time
import unittest
import hmac
import wave
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from fastapi.testclient import TestClient


STT_SERVICE_DIR = Path(__file__).resolve().parents[1] / "stt-service"
sys.path.insert(0, str(STT_SERVICE_DIR))

import stt_server as stt  # noqa: E402
from agent_gateway import AgentGateway  # noqa: E402
from account_service import AccountConflictError  # noqa: E402
from common.account_stt_token import issue_account_stt_token  # noqa: E402


class SttRuntimeTest(unittest.TestCase):
    def test_context_hint_is_sanitized_and_bounded(self) -> None:
        hint = stt.sanitize_context_hint("  大佛寺\x00\n研学考察  " + "词" * 300)

        self.assertNotIn("\x00", hint)
        self.assertNotIn("\n", hint)
        self.assertTrue(hint.startswith("大佛寺 研学考察"))
        self.assertLessEqual(len(hint), stt.STT_FINAL_CONTEXT_HINT_MAX_CHARS)

    def test_final_audio_enhancement_bypasses_clean_recording(self) -> None:
        quality = stt.FinalAudioQuality(
            noise_floor_dbfs=-62.0,
            speech_level_dbfs=-18.0,
            snr_db=44.0,
            clipping_ratio=0.0,
            duration_seconds=30.0,
        )

        self.assertEqual(stt.final_audio_enhancement_decision(quality), (False, False))

    def test_final_audio_enhancement_handles_noise_and_quiet_speech(self) -> None:
        noisy = stt.FinalAudioQuality(
            noise_floor_dbfs=-36.0,
            speech_level_dbfs=-18.0,
            snr_db=18.0,
            clipping_ratio=0.0,
            duration_seconds=30.0,
        )
        quiet = stt.FinalAudioQuality(
            noise_floor_dbfs=-58.0,
            speech_level_dbfs=-36.0,
            snr_db=22.0,
            clipping_ratio=0.0,
            duration_seconds=30.0,
        )

        self.assertEqual(stt.final_audio_enhancement_decision(noisy), (True, False))
        self.assertEqual(stt.final_audio_enhancement_decision(quiet), (True, True))

    def test_wav_quality_analysis_estimates_noise_and_speech_levels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "quality.wav"
            with wave.open(str(path), "wb") as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(16000)
                quiet = (300).to_bytes(2, "little", signed=True) * 8000
                speech = (8000).to_bytes(2, "little", signed=True) * 8000
                wav_file.writeframes(quiet + speech)

            quality = stt.analyze_wav_quality(path)

        self.assertIsNotNone(quality)
        self.assertLess(quality.noise_floor_dbfs, quality.speech_level_dbfs)
        self.assertGreater(quality.snr_db, 20.0)

    def test_faster_whisper_model_directory_accepts_json_vocabulary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_dir = Path(directory)
            (model_dir / "model.bin").write_bytes(b"0" * 2048)
            (model_dir / "tokenizer.json").write_text("{}", encoding="utf-8")
            (model_dir / "vocabulary.json").write_text("{}", encoding="utf-8")

            self.assertTrue(stt.has_valid_fw_model_dir(model_dir))

    def test_local_stt_admin_requires_basic_auth_and_renders_dashboard(self) -> None:
        with (
            patch.object(stt, "WEB_API_USERNAME", "operator"),
            patch.object(stt, "WEB_API_TOKEN", "web-secret"),
            TestClient(stt.app) as client,
        ):
            unauthorized = client.get("/admin/")
            authorized = client.get("/admin/", auth=("operator", "web-secret"))

        self.assertEqual(unauthorized.status_code, 401)
        self.assertIn("Basic", unauthorized.headers.get("www-authenticate", ""))
        self.assertEqual(authorized.status_code, 200)
        self.assertIn("智悟本 本地 STT", authorized.text)

    def test_local_stt_admin_status_does_not_accept_account_bearer_token(self) -> None:
        with (
            patch.object(stt, "WEB_API_USERNAME", "operator"),
            patch.object(stt, "WEB_API_TOKEN", "web-secret"),
            TestClient(stt.app) as client,
        ):
            bearer = client.get(
                "/admin/api/status",
                headers={"Authorization": "Bearer account-token"},
            )
            basic = client.get("/admin/api/status", auth=("operator", "web-secret"))

        self.assertEqual(bearer.status_code, 401)
        self.assertEqual(basic.status_code, 200)
        self.assertIn("inference", basic.json())

    def test_chunk_merge_ignores_boundary_punctuation_variants(self) -> None:
        self.assertEqual(
            stt.merge_chunk_transcript_text(
                "论坛今天讨论云模型，主持人宣布休会。",
                "主持人宣布休会，下午继续圆桌讨论。",
            ),
            "论坛今天讨论云模型，主持人宣布休会，下午继续圆桌讨论。",
        )

    def test_long_local_audio_is_chunked_and_merged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "long-recording.m4a"
            source.write_bytes(b"source")
            chunks = [
                stt.AudioChunk(root / "chunk-001.wav", 0.0, 1803.0),
                stt.AudioChunk(root / "chunk-002.wav", 1797.0, 1803.0),
                stt.AudioChunk(root / "chunk-003.wav", 3597.0, 3.0),
            ]
            with (
                patch.object(stt, "STT_TEMP_DIR", root),
                patch.object(stt, "STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC", 2700),
                patch.object(stt, "audio_duration_for_tencent_budget", return_value=3600.0),
                patch.object(stt, "create_audio_chunks", return_value=chunks) as create_chunks,
                patch.object(
                    stt,
                    "transcribe_local_single_file",
                    side_effect=[
                        stt.TranscribeResponse(
                            text="开场介绍与议程，主持人宣布休会。", language="zh"
                        ),
                        stt.TranscribeResponse(
                            text="主持人宣布休会，下午继续圆桌讨论。", language="zh"
                        ),
                        stt.TranscribeResponse(text="圆桌讨论形成共识。", language="zh"),
                    ],
                ) as transcribe_chunk,
            ):
                result = stt.transcribe_local_long_audio(str(source), "zh")

        create_chunks.assert_called_once()
        self.assertEqual(transcribe_chunk.call_count, 3)
        self.assertEqual(
            transcribe_chunk.call_args_list[0].args,
            (str(chunks[0].path), "zh", ""),
        )
        self.assertEqual(
            result.text,
            "开场介绍与议程，主持人宣布休会，下午继续圆桌讨论。形成共识。",
        )
        self.assertEqual(result.language, "zh")

    def test_long_local_audio_requires_active_diarization_for_cloud_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "long-recording.wav"
            source.write_bytes(b"source")
            chunk = stt.AudioChunk(root / "chunk-001.wav", 0.0, 3000.0)
            with (
                patch.object(stt, "STT_TEMP_DIR", root),
                patch.object(stt, "STT_LONG_AUDIO_CHUNK_THRESHOLD_SEC", 2700),
                patch.object(stt, "audio_duration_for_tencent_budget", return_value=3000.0),
                patch.object(stt, "create_audio_chunks", return_value=[chunk]),
                patch.object(
                    stt,
                    "transcribe_local_single_file",
                    return_value=stt.TranscribeResponse(
                        text="普通转写",
                        language="zh",
                        segments=[{"start": 0.0, "end": 2.0, "text": "普通转写"}],
                    ),
                ),
                patch.object(
                    stt,
                    "attach_local_speakers",
                    return_value=(
                        [{"start": 0.0, "end": 2.0, "text": "普通转写"}],
                        {"enabled": True, "provider": "local", "active": False},
                    ),
                ),
            ):
                with self.assertRaisesRegex(stt.AudioChunkingError, "本地说话人分离未就绪"):
                    stt.transcribe_local_long_audio(
                        str(source),
                        "zh",
                        speaker_diarization=True,
                    )

    def test_chunked_tencent_transcription_merges_cloud_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.wav"
            first = root / "first.wav"
            second = root / "second.wav"
            source.write_bytes(b"source")
            first.write_bytes(b"first")
            second.write_bytes(b"second")
            chunks = [
                stt.TencentAudioChunk(first, 0.0, 2403.0),
                stt.TencentAudioChunk(second, 2397.0, 1203.0),
            ]

            with (
                patch.object(stt, "TENCENT_ASR_MAX_UPLOAD_MB", 0),
                patch.object(stt, "create_tencent_audio_chunks", return_value=chunks),
                patch.object(
                    stt,
                    "transcribe_with_tencent_flash",
                    side_effect=[
                        ("第一段末尾重叠内容", {"audio_duration": 2403000}),
                        ("重叠内容第二段开始", {"audio_duration": 1203000}),
                    ],
                ) as cloud_transcribe,
            ):
                text, payload = stt.transcribe_with_tencent_flash_chunked(
                    source,
                    "wav",
                    tier=stt.TENCENT_STANDARD_TIER,
                    language="zh",
                    record_usage=False,
                )

        self.assertEqual(text, "第一段末尾重叠内容第二段开始")
        self.assertEqual(cloud_transcribe.call_count, 2)
        self.assertTrue(payload["chunked"])
        self.assertEqual(payload["chunk_count"], 2)
        self.assertEqual(payload["audio_duration"], 3606000)

    def test_chunked_tencent_diarization_preserves_offset_segments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.wav"
            first = root / "first.wav"
            second = root / "second.wav"
            source.write_bytes(b"source")
            first.write_bytes(b"first")
            second.write_bytes(b"second")
            chunks = [
                stt.TencentAudioChunk(first, 0.0, 2403.0),
                stt.TencentAudioChunk(second, 2397.0, 1203.0),
            ]

            with (
                patch.object(stt, "TENCENT_ASR_MAX_UPLOAD_MB", 0),
                patch.object(stt, "create_tencent_audio_chunks", return_value=chunks),
                patch.object(
                    stt,
                    "transcribe_with_tencent_flash",
                    side_effect=[
                        (
                            "说话人 1：第一段",
                            {
                                "audio_duration": 2403000,
                                "segments": [
                                    {"text": "第一段", "start_time": 100, "end_time": 900, "speaker_id": 0}
                                ],
                            },
                        ),
                        (
                            "说话人 2：第二段",
                            {
                                "audio_duration": 1203000,
                                "segments": [
                                    {"text": "第二段", "start_time": 100, "end_time": 900, "speaker_id": 1}
                                ],
                            },
                        ),
                    ],
                ),
            ):
                text, payload = stt.transcribe_with_tencent_flash_chunked(
                    source,
                    "wav",
                    tier=stt.TENCENT_STANDARD_TIER,
                    language="zh",
                    record_usage=False,
                    speaker_diarization=True,
                )

        self.assertEqual(text, "说话人 1：第一段\n说话人 2：第二段")
        self.assertEqual(payload["segments"][1]["start_time"], 2397100.0)

    def test_stt_accepts_valid_account_token_and_rejects_expired_or_tampered_tokens(self) -> None:
        now = int(time.time())
        valid = issue_account_stt_token("shared-secret", "user-1", now + 60)
        expired = issue_account_stt_token("shared-secret", "user-1", now - 1)
        with (
            patch.object(stt, "STT_REQUIRE_API_TOKEN", True),
            patch.object(stt, "STT_API_TOKEN", "service-token"),
            patch.object(stt, "ACCOUNT_TOKEN_SECRET", "shared-secret"),
        ):
            self.assertTrue(stt.is_api_token_valid(f"Bearer {valid}"))
            self.assertTrue(stt.is_api_token_valid("Bearer service-token"))
            self.assertFalse(stt.is_api_token_valid(f"Bearer {expired}"))
            self.assertFalse(stt.is_api_token_valid(f"Bearer {valid}x"))
            self.assertEqual(stt.resolve_api_principal(f"Bearer {valid}").owner_id, "user-1")
            self.assertTrue(stt.resolve_api_principal("Bearer service-token").is_management)

    def test_account_secret_disables_anonymous_access_without_management_token(self) -> None:
        account_token = issue_account_stt_token(
            "shared-secret",
            "user-1",
            int(time.time()) + 60,
        )
        with (
            patch.object(stt, "STT_REQUIRE_API_TOKEN", False),
            patch.object(stt, "STT_API_TOKEN", ""),
            patch.object(stt, "ACCOUNT_TOKEN_SECRET", "shared-secret"),
        ):
            self.assertIsNone(stt.resolve_api_principal(None))
            self.assertIsNone(stt.resolve_api_principal("Bearer invalid"))
            principal = stt.resolve_api_principal(f"Bearer {account_token}")
            self.assertIsNotNone(principal)
            self.assertEqual(principal.owner_id, "user-1")

    def test_tencent_audio_duration_is_parsed_as_milliseconds_with_safe_fallback(self) -> None:
        self.assertEqual(stt.tencent_audio_duration_ms({"audio_duration": "61001"}), 61_001)
        self.assertEqual(
            stt.tencent_audio_duration_ms(
                {"audio_duration": "not-a-number"},
                fallback_seconds=1.25,
            ),
            1_250,
        )
        self.assertEqual(
            stt.tencent_audio_duration_ms(
                {"audio_duration": float("inf")},
                fallback_seconds=2.0,
            ),
            2_000,
        )

    def test_global_model_switch_accepts_only_static_management_token(self) -> None:
        account_token = issue_account_stt_token("shared-secret", "user-1", int(time.time()) + 60)
        with patch.object(stt, "STT_API_TOKEN", "service-management-token"):
            self.assertTrue(stt.is_management_token_valid("Bearer service-management-token"))
            self.assertFalse(stt.is_management_token_valid(f"Bearer {account_token}"))
            self.assertFalse(stt.is_management_token_valid(None))

    def test_release_version_is_exposed(self) -> None:
        expected_version = (STT_SERVICE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()
        self.assertEqual(stt.SERVER_VERSION, expected_version)
        self.assertTrue(stt.SERVER_RELEASE.startswith(expected_version))

    def test_cpu_streaming_profile_is_bounded(self) -> None:
        self.assertLessEqual(stt.STREAM_MAX_SNAPSHOT_SEC, 8)
        self.assertGreaterEqual(stt.STREAM_BUFFER_SEC, stt.STREAM_MAX_SNAPSHOT_SEC)
        self.assertLessEqual(stt.STREAM_OVERLAP_SEC, stt.STREAM_MAX_SNAPSHOT_SEC / 2)
        self.assertEqual(stt.STREAM_STEP_SEC, 4)
        self.assertEqual(stt.STREAM_BEAM_SIZE, 1)
        self.assertEqual(stt.STREAM_FINAL_COMPAT_MIN_AUDIO_SEC, 2)
        self.assertEqual(stt.STT_STREAM_MODEL, "large-v3-turbo")
        self.assertEqual(stt.STREAM_MIN_CONFIDENCE, -0.90)
        self.assertEqual(stt.STREAM_MAX_NO_SPEECH_PROB, 0.35)
        self.assertEqual(stt.STT_FINAL_RETRY_MIN_CHARS, 8)
        self.assertEqual(stt.FINAL_BEAM_SIZE, 5)
        self.assertEqual(stt.STT_FINAL_BATCH_SIZE, 1)

    def test_stream_preview_starts_at_minimum_then_uses_steady_step(self) -> None:
        min_bytes = 2 * 16000 * 2
        step_bytes = 4 * 16000 * 2

        self.assertEqual(
            stt.stream_required_new_bytes(0, min_bytes, step_bytes),
            min_bytes,
        )
        self.assertEqual(
            stt.stream_required_new_bytes(min_bytes, min_bytes, step_bytes),
            step_bytes,
        )

    def test_stream_merge_keeps_non_overlapping_updates(self) -> None:
        self.assertEqual(stt.merge_transcript_text("第一项", "第二项"), "第一项 第二项")

    def test_stable_preview_promotion_keeps_accumulated_preview(self) -> None:
        accumulated = "第一阶段讨论内容 第二阶段讨论内容 第三阶段讨论内容"
        promoted = stt.promote_revisable_preview(
            committed_text="既有稳定内容",
            active_preview_text=accumulated,
            stable_candidate="第三阶段讨论内容 最终决定",
        )

        self.assertEqual(
            promoted,
            "既有稳定内容 第一阶段讨论内容 第二阶段讨论内容 第三阶段讨论内容 最终决定",
        )

    def test_transcripts_are_normalized_to_simplified_chinese(self) -> None:
        self.assertEqual(stt.normalize_preview_text("聽得到嗎？價格是一塊"), "听得到吗？价格是一块")

    def test_final_punctuation_uses_segment_pauses_only_when_model_has_none(self) -> None:
        segments = [
            {"start": 0.0, "end": 1.0, "text": "第一项"},
            {"start": 1.4, "end": 2.0, "text": "第二项"},
            {"start": 3.1, "end": 4.0, "text": "第三项"},
        ]
        with (
            patch.object(stt, "STT_FINAL_RESTORE_PUNCTUATION", True),
            patch.object(stt, "STT_FINAL_PUNCTUATION_PAUSE_SEC", 0.8),
        ):
            self.assertEqual(
                stt.restore_final_punctuation("第一项 第二项 第三项", segments),
                "第一项，第二项。第三项。",
            )
            self.assertEqual(
                stt.restore_final_punctuation("已有标点，继续讨论", segments),
                "已有标点，继续讨论。",
            )

    def test_english_final_punctuation_uses_english_marks_and_spacing(self) -> None:
        segments = [
            {"start": 0.0, "end": 1.0, "text": "first item"},
            {"start": 1.2, "end": 2.0, "text": "second item"},
            {"start": 3.0, "end": 4.0, "text": "final item"},
        ]
        with (
            patch.object(stt, "STT_FINAL_RESTORE_PUNCTUATION", True),
            patch.object(stt, "STT_FINAL_PUNCTUATION_PAUSE_SEC", 0.8),
        ):
            self.assertEqual(
                stt.restore_final_punctuation(
                    "first item second item final item",
                    segments,
                    "en",
                ),
                "first item, second item. final item.",
            )

    def test_final_prompt_echo_is_removed_without_touching_real_content(self) -> None:
        self.assertEqual(stt.sanitize_final_transcript("请使用规范中文标点。"), "")
        self.assertEqual(
            stt.sanitize_final_transcript(
                "以下是普通话会议记录，请使用规范中文标点。今天讨论项目进度"
            ),
            "今天讨论项目进度",
        )
        self.assertEqual(
            stt.sanitize_final_transcript("今天讨论如何使用规范中文标点这个功能"),
            "今天讨论如何使用规范中文标点这个功能",
        )

    def test_prompt_echo_triggers_unprompted_final_retry(self) -> None:
        calls = []

        class FakeModel:
            def transcribe(self, _file_path, **kwargs):
                calls.append(kwargs)
                text = (
                    "请使用规范中文标点"
                    if len(calls) == 1
                    else "这是实际发言内容"
                )
                return [
                    SimpleNamespace(
                        start=0.0,
                        end=2.0,
                        text=text,
                        avg_logprob=-0.2,
                        no_speech_prob=0.05,
                    )
                ], SimpleNamespace(language="zh")

        with patch.object(
            stt,
            "STT_FINAL_INITIAL_PROMPT",
            "以下是普通话会议记录，请使用规范中文标点。",
        ):
            result = stt.transcribe_faster_whisper_file(FakeModel(), "sample.wav")

        self.assertEqual(result["text"], "这是实际发言内容。")
        self.assertEqual(len(calls), 2)
        self.assertIn("initial_prompt", calls[0])
        self.assertNotIn("initial_prompt", calls[1])

    def test_tencent_usage_summary_aggregates_month_and_applies_warning_thresholds(self) -> None:
        rows = [
            {"BizName": "asr_rt", "Duration": 12_000, "Count": 8},
            {"BizName": "asr_rt", "Duration": 5_200, "Count": 2},
            {"BizName": "asr_rec", "Duration": 2_000, "Count": 3},
        ]
        with (
            patch.object(stt, "TENCENT_REALTIME_MONTHLY_FREE_SEC", 18_000),
            patch.object(stt, "TENCENT_FLASH_MONTHLY_FREE_SEC", 18_000),
        ):
            summary = stt.build_tencent_asr_usage_summary(
                rows,
                datetime(2026, 7, 22, 12, 0, 0),
            )

        realtime, flash = summary["services"]
        self.assertEqual(summary["month"], "2026-07")
        self.assertEqual(summary["warning_level"], "critical")
        self.assertEqual(summary["hybrid_remaining_seconds"], 800)
        self.assertEqual(realtime["used_seconds"], 17_200)
        self.assertEqual(realtime["request_count"], 10)
        self.assertEqual(realtime["remaining_seconds"], 800)
        self.assertEqual(flash["remaining_seconds"], 16_000)

    def test_tencent_usage_summary_marks_low_and_exhausted_quota(self) -> None:
        with (
            patch.object(stt, "TENCENT_REALTIME_MONTHLY_FREE_SEC", 18_000),
            patch.object(stt, "TENCENT_FLASH_MONTHLY_FREE_SEC", 18_000),
        ):
            low = stt.build_tencent_asr_usage_summary(
                [{"BizName": "asr_rt", "Duration": 15_000, "Count": 1}],
                datetime(2026, 7, 22),
            )
            exhausted = stt.build_tencent_asr_usage_summary(
                [{"BizName": "asr_rec", "Duration": 18_001, "Count": 1}],
                datetime(2026, 7, 22),
            )

        self.assertEqual(low["warning_level"], "low")
        self.assertEqual(exhausted["warning_level"], "exhausted")
        self.assertEqual(exhausted["hybrid_remaining_seconds"], 0)

    def test_tencent_usage_summary_keeps_official_and_pending_usage_separate(self) -> None:
        summary = stt.build_tencent_asr_usage_summary(
            [{"BizName": "asr_rec", "Duration": 0, "Count": 0}],
            datetime(2026, 7, 28),
            local_rows=[
                {"business_name": "asr_rec", "duration_seconds": 182, "count": 2}
            ],
        )

        flash = next(item for item in summary["services"] if item["id"] == "flash")
        self.assertEqual(flash["used_seconds"], 0)
        self.assertEqual(flash["request_count"], 0)
        self.assertEqual(flash["pending_local_seconds"], 182)
        self.assertEqual(flash["pending_local_request_count"], 2)
        self.assertFalse(summary["is_estimated"])

    def test_tencent_usage_ledger_persists_and_fills_official_reporting_delay(self) -> None:
        previous_path = stt.TENCENT_ASR_USAGE_LEDGER_PATH
        previous_enabled = stt.TENCENT_ASR_USAGE_LEDGER_ENABLED
        previous_initialized = stt._tencent_usage_ledger_initialized
        try:
            with tempfile.TemporaryDirectory() as directory:
                stt.TENCENT_ASR_USAGE_LEDGER_PATH = Path(directory) / "usage.db"
                stt.TENCENT_ASR_USAGE_LEDGER_ENABLED = True
                stt._tencent_usage_ledger_initialized = False
                stt.synchronize_tencent_usage_ledger(
                    "2026-07",
                    [{"BizName": "asr_rt", "Duration": 100, "Count": 4}],
                )
                stt.record_local_tencent_asr_usage(
                    "asr_rt",
                    2.25,
                    now=datetime(2026, 7, 22, 12, 0, 0),
                )
                local_rows = stt.read_local_tencent_asr_usage("2026-07")
                merged, estimated = stt.merge_tencent_asr_usage_rows(
                    [{"BizName": "asr_rt", "Duration": 100, "Count": 4}],
                    local_rows,
                )

                realtime = next(
                    row for row in merged if row["business_name"] == "asr_rt"
                )
                self.assertEqual(realtime["duration_seconds"], 103)
                self.assertEqual(realtime["count"], 5)
                self.assertTrue(estimated)

                stt.synchronize_tencent_usage_ledger(
                    "2026-07",
                    [{"BizName": "asr_rt", "Duration": 103, "Count": 5}],
                )
                caught_up = stt.read_local_tencent_asr_usage("2026-07")
                caught_up_realtime = next(
                    row for row in caught_up if row["business_name"] == "asr_rt"
                )
                self.assertEqual(caught_up_realtime["duration_seconds"], 103)
        finally:
            stt.TENCENT_ASR_USAGE_LEDGER_PATH = previous_path
            stt.TENCENT_ASR_USAGE_LEDGER_ENABLED = previous_enabled
            stt._tencent_usage_ledger_initialized = previous_initialized
            stt.tencent_usage_cache = None

    def test_tencent_flash_signature_and_response_parsing(self) -> None:
        with (
            patch.object(stt, "TENCENT_ASR_BASE_URL", "https://asr.example/asr/flash/v1"),
            patch.object(stt, "TENCENT_ASR_APP_ID", "123456"),
            patch.object(stt, "TENCENT_ASR_SECRET_ID", "secret-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_KEY", "secret-key"),
            patch.object(stt, "TENCENT_ASR_ENGINE_TYPE", "16k_zh_en"),
        ):
            url, signature = stt.build_tencent_flash_request(
                voice_format="wav",
                timestamp=1700000000,
            )
        parsed = __import__("urllib.parse", fromlist=["urlparse"]).urlparse(url)
        source = f"POST{parsed.netloc}{parsed.path}?{parsed.query}"
        expected = base64.b64encode(
            hmac.new(b"secret-key", source.encode("utf-8"), hashlib.sha1).digest()
        ).decode("ascii")
        self.assertEqual(signature, expected)
        self.assertIn("engine_type=16k_zh_en", url)
        self.assertEqual(
            stt.parse_tencent_flash_response(
                {"code": 0, "flash_result": [{"text": "會議內容。"}]}
            ),
            "会议内容。",
        )

    def test_speaker_rows_use_stable_human_labels_and_merge_adjacent_turns(self) -> None:
        text, rows = stt.parse_tencent_flash_response(
            {
                "code": 0,
                "flash_result": [
                    {"text": "甲先介绍。", "speaker_id": 2},
                    {"text": "继续说明。", "speaker_id": 2},
                    {"text": "乙补充。", "speaker_id": 7},
                ],
            },
            include_speakers=True,
        )

        self.assertEqual(text, "说话人 1：甲先介绍。继续说明。\n说话人 2：乙补充。")
        self.assertEqual([row["speaker_id"] for row in rows], [2, 2, 7])

    def test_tencent_sentence_list_is_flattened_for_diarization(self) -> None:
        text, rows = stt.parse_tencent_flash_response(
            {
                "code": 0,
                "flash_result": [
                    {
                        "sentence_list": [
                            {"text": "甲。", "speaker_id": 0},
                            {"text": "乙。", "speaker_id": 1},
                        ]
                    }
                ],
            },
            include_speakers=True,
        )

        self.assertEqual(text, "说话人 1：甲。\n说话人 2：乙。")
        self.assertEqual(len(rows), 2)

    def test_tencent_diarization_requires_real_speaker_labels(self) -> None:
        with self.assertRaisesRegex(ValueError, "did not return speaker diarization labels"):
            stt.parse_tencent_flash_response(
                {"code": 0, "flash_result": [{"text": "没有说话人标签。"}]},
                include_speakers=True,
            )

    def test_cloud_diarization_metadata_only_activates_for_speaker_ids(self) -> None:
        inactive = stt.cloud_diarization_metadata([{"text": "普通文本"}])
        active = stt.cloud_diarization_metadata(
            [
                {"text": "甲", "speaker_id": 3},
                {"text": "乙", "speaker_id": 7},
                {"text": "甲继续", "speaker_id": 3},
            ]
        )

        self.assertFalse(inactive["active"])
        self.assertEqual(inactive["speaker_count"], 0)
        self.assertTrue(active["active"])
        self.assertEqual(active["speaker_count"], 2)

    def test_local_speaker_attachment_selects_maximum_time_overlap(self) -> None:
        # Keep the test independent from ONNX model loading while exercising
        # the same overlap rule used by production attachment.
        turns = [
            {"start": 0.0, "end": 1.5, "speaker": 0},
            {"start": 1.5, "end": 4.0, "speaker": 1},
        ]
        with patch.object(stt, "diarize_wav_segments", return_value=turns):
            attached, metadata = stt.attach_local_speakers(
                Path("unused.wav"),
                [
                    {"start": 0.0, "end": 2.0, "text": "甲"},
                    {"start": 2.0, "end": 4.0, "text": "乙"},
                ],
            )

        self.assertEqual([row["speaker"] for row in attached], [0, 1])
        self.assertTrue(metadata["active"])
        self.assertEqual(metadata["speaker_count"], 2)

    def test_tencent_realtime_signature_and_transcript_state(self) -> None:
        with (
            patch.object(stt, "TENCENT_REALTIME_ASR_BASE_URL", "wss://asr.example/asr/v2"),
            patch.object(stt, "TENCENT_ASR_APP_ID", "123456"),
            patch.object(stt, "TENCENT_ASR_SECRET_ID", "secret-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_KEY", "secret-key"),
            patch.object(stt, "TENCENT_REALTIME_ASR_ENGINE_TYPE", "16k_zh_en"),
            patch.object(stt, "TENCENT_REALTIME_ASR_SIGNATURE_TTL_SEC", 3600),
        ):
            url, signature_source = stt.build_tencent_realtime_request(
                voice_id="voice-1",
                timestamp=1700000000,
                nonce=1234,
            )
        expected_signature = base64.b64encode(
            hmac.new(b"secret-key", signature_source.encode("utf-8"), hashlib.sha1).digest()
        ).decode("ascii")
        self.assertNotIn("wss://", signature_source)
        self.assertIn("engine_model_type=16k_zh_en", signature_source)
        self.assertIn("signature=", url)
        self.assertIn(__import__("urllib.parse", fromlist=["quote"]).quote(expected_signature, safe=""), url)

        state = stt.TencentRealtimeTranscriptState()
        self.assertEqual(
            state.apply(
                {
                    "code": 0,
                    "result": {"slice_type": 1, "index": 0, "voice_text_str": "討論中"},
                }
            ),
            ("", "讨论中", False),
        )
        self.assertEqual(
            state.apply(
                {
                    "code": 0,
                    "result": {"slice_type": 2, "index": 0, "voice_text_str": "第一项完成"},
                }
            ),
            ("第一项完成", "", False),
        )
        self.assertEqual(state.apply({"code": 0, "final": 1}), ("第一项完成", "", True))

    def test_tencent_standard_tier_selects_configured_english_engines(self) -> None:
        with (
            patch.object(stt, "TENCENT_ASR_BASE_URL", "https://asr.example/asr/flash/v1"),
            patch.object(stt, "TENCENT_REALTIME_ASR_BASE_URL", "wss://asr.example/asr/v2"),
            patch.object(stt, "TENCENT_ASR_APP_ID", "123456"),
            patch.object(stt, "TENCENT_ASR_SECRET_ID", "secret-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_KEY", "secret-key"),
            patch.object(stt, "TENCENT_STANDARD_ASR_ENGINE_TYPE_EN", "16k_en_test"),
            patch.object(stt, "TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE_EN", "16k_en_rt_test"),
        ):
            flash_url, _ = stt.build_tencent_flash_request(
                voice_format="wav",
                tier=stt.TENCENT_STANDARD_TIER,
                language="en",
                timestamp=1700000000,
            )
            _, realtime_source = stt.build_tencent_realtime_request(
                voice_id="voice-en",
                tier=stt.TENCENT_STANDARD_TIER,
                language="en",
                timestamp=1700000000,
                nonce=1234,
            )

        self.assertIn("engine_type=16k_en_test", flash_url)
        self.assertIn("engine_model_type=16k_en_rt_test", realtime_source)

    def test_tencent_legacy_protocol_is_always_mapped_to_standard_tier(self) -> None:
        self.assertEqual(stt.tencent_model_tier("tencent-flash"), stt.TENCENT_STANDARD_TIER)
        self.assertEqual(
            stt.tencent_stream_tier("tencent-realtime"),
            stt.TENCENT_STANDARD_TIER,
        )
        self.assertEqual(
            stt.tencent_tier_config(stt.TENCENT_STANDARD_TIER).flash_engine_type,
            "16k_zh",
        )

    def test_precision_tier_requires_positive_budget_cap(self) -> None:
        with (
            patch.object(stt, "TENCENT_PRECISION_ASR_ENABLED", True),
            patch.object(stt, "TENCENT_PRECISION_MONTHLY_LIMIT_SEC", 0),
            patch.object(stt, "TENCENT_ASR_APP_ID", "app-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_ID", "secret-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_KEY", "secret-key"),
        ):
            self.assertFalse(stt.tencent_asr_configured(stt.TENCENT_PRECISION_TIER))

    def test_standard_tier_has_no_application_budget_gate(self) -> None:
        with (
            patch.object(stt, "TENCENT_STANDARD_ASR_ENABLED", True),
            patch.object(stt, "TENCENT_STANDARD_REALTIME_ASR_ENABLED", True),
            patch.object(stt, "TENCENT_STANDARD_MONTHLY_LIMIT_SEC", 0),
            patch.object(stt, "TENCENT_ASR_APP_ID", "app-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_ID", "secret-id"),
            patch.object(stt, "TENCENT_ASR_SECRET_KEY", "secret-key"),
        ):
            self.assertTrue(stt.tencent_asr_configured(stt.TENCENT_STANDARD_TIER))
            self.assertTrue(stt.tencent_realtime_asr_configured(stt.TENCENT_STANDARD_TIER))
            self.assertFalse(stt.tencent_asr_budget_enforced(stt.TENCENT_STANDARD_TIER))
            self.assertTrue(stt.tencent_asr_budget_enforced(stt.TENCENT_PRECISION_TIER))

    def test_tencent_budget_reservation_caps_and_releases_concurrent_allowance(self) -> None:
        previous_path = stt.TENCENT_ASR_USAGE_LEDGER_PATH
        previous_initialized = stt._tencent_usage_ledger_initialized
        try:
            with tempfile.TemporaryDirectory() as directory:
                stt.TENCENT_ASR_USAGE_LEDGER_PATH = Path(directory) / "budget.db"
                stt._tencent_usage_ledger_initialized = False
                with patch.object(stt, "TENCENT_STANDARD_MONTHLY_LIMIT_SEC", 120):
                    test_now = datetime(2026, 7, 22)
                    first = stt.reserve_tencent_asr_budget(
                        "standard", "asr_rec", 60, now=test_now
                    )
                    summary = stt.tencent_asr_budget_summary(datetime(2026, 7, 22))
                    standard = next(item for item in summary["tiers"] if item["id"] == "standard")
                    recording_file = next(item for item in standard["services"] if item["business_name"] == "asr_rec")
                    self.assertEqual(recording_file["reserved_seconds"], 60)
                    self.assertEqual(recording_file["remaining_seconds"], 60)

                    stt.settle_tencent_asr_budget(first, 30)
                    second = stt.reserve_tencent_asr_budget(
                        "standard", "asr_rec", 60, now=test_now
                    )
                    partial = stt.reserve_tencent_asr_budget(
                        "standard",
                        "asr_rec",
                        90,
                        allow_partial=True,
                        now=test_now,
                    )
                    self.assertEqual(partial.reserved_seconds, 30)
                    with self.assertRaisesRegex(RuntimeError, "monthly budget"):
                        stt.reserve_tencent_asr_budget(
                            "standard", "asr_rec", 1, now=test_now
                        )
                    stt.release_tencent_asr_budget(second)
                    stt.release_tencent_asr_budget(partial)
        finally:
            stt.TENCENT_ASR_USAGE_LEDGER_PATH = previous_path
            stt._tencent_usage_ledger_initialized = previous_initialized

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

        with (
            patch.object(stt, "STT_FINAL_CONDITION_ON_PREVIOUS_TEXT", True),
            patch.object(stt, "STT_FINAL_INITIAL_PROMPT", "中文会议标点提示"),
        ):
            result = stt.transcribe_faster_whisper_file(FakeModel(), "sample.wav")

        self.assertEqual(result["strategy"], "final-compatible")
        self.assertEqual(len(calls), 1)
        self.assertEqual(
            calls[0],
            {
                "beam_size": 5,
                "vad_filter": True,
                "language": "zh",
                "condition_on_previous_text": True,
                "initial_prompt": "中文会议标点提示",
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

    def test_final_transcription_uses_configured_batch_pipeline(self) -> None:
        calls = []

        class FakeBaseModel:
            def transcribe(self, _file_path, **_kwargs):
                raise AssertionError("base model should not handle the primary final decode")

        class FakeBatchPipeline:
            def transcribe(self, _file_path, **kwargs):
                calls.append(kwargs)
                return [
                    SimpleNamespace(
                        start=0.0,
                        end=2.0,
                        text="批处理保持高精度最终转写内容",
                        avg_logprob=-0.2,
                        no_speech_prob=0.05,
                    )
                ], SimpleNamespace(language="zh")

        previous_model = stt.model
        previous_final_model = stt.final_model
        previous_batch_size = stt.STT_FINAL_BATCH_SIZE
        base_model = FakeBaseModel()
        try:
            stt.model = base_model
            stt.final_model = FakeBatchPipeline()
            stt.STT_FINAL_BATCH_SIZE = 4

            result = stt.transcribe_faster_whisper_file(base_model, "sample.wav")

            self.assertEqual(result["text"], "批处理保持高精度最终转写内容。")
            self.assertEqual(calls[0]["beam_size"], stt.FINAL_BEAM_SIZE)
            self.assertEqual(calls[0]["batch_size"], 4)
            self.assertTrue(calls[0]["vad_filter"])
        finally:
            stt.model = previous_model
            stt.final_model = previous_final_model
            stt.STT_FINAL_BATCH_SIZE = previous_batch_size

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

    def test_failed_audio_is_preserved_with_account_and_meeting_context(self) -> None:
        previous_dir = stt.STT_RECOVERY_DIR
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                stt.STT_RECOVERY_DIR = root / "recovery"
                source = root / "upload.wav"
                with wave.open(str(source), "wb") as writer:
                    writer.setnchannels(1)
                    writer.setsampwidth(2)
                    writer.setframerate(16000)
                    writer.writeframes(b"\x00\x00" * 16000)
                manifest = stt.preserve_failed_audio(
                    source,
                    owner_id="admin-1",
                    meeting_id="meeting-1",
                    archive_key="request-1",
                    original_filename="meeting.wav",
                    reason="inference failed",
                )
                self.assertIsNotNone(manifest)
                assert manifest is not None
                self.assertEqual(manifest["owner_id"], "admin-1")
                self.assertEqual(manifest["meeting_id"], "meeting-1")
                self.assertEqual(manifest["archive_key"], "request-1")
                recovery_dir = stt.STT_RECOVERY_DIR / manifest["id"]
                self.assertTrue((recovery_dir / manifest["audio_file"]).is_file())
                self.assertTrue((recovery_dir / f"{manifest['id']}.json").is_file())
        finally:
            stt.STT_RECOVERY_DIR = previous_dir

    def test_audio_archive_is_isolated_by_owner_and_meeting(self) -> None:
        previous = (
            stt.STT_AUDIO_ARCHIVE_ENABLED,
            stt.STT_AUDIO_ARCHIVE_DIR,
            stt.STT_AUDIO_ARCHIVE_RETENTION_DAYS,
            stt.STT_AUDIO_ARCHIVE_MAX_GB,
        )
        try:
            with tempfile.TemporaryDirectory() as directory:
                stt.STT_AUDIO_ARCHIVE_ENABLED = True
                stt.STT_AUDIO_ARCHIVE_DIR = Path(directory) / "archive"
                stt.STT_AUDIO_ARCHIVE_RETENTION_DAYS = 30
                stt.STT_AUDIO_ARCHIVE_MAX_GB = 1
                source = Path(directory) / "recording.wav"
                with wave.open(str(source), "wb") as writer:
                    writer.setnchannels(1)
                    writer.setsampwidth(2)
                    writer.setframerate(16000)
                    writer.writeframes(b"\x00\x00" * 16000)

                archived = stt.archive_audio_file(
                    source,
                    owner_id="user-1",
                    meeting_id="meeting-1",
                    source_kind="stream",
                    original_filename="recording.wav",
                    archive_key="stream-session-1",
                )

                self.assertIsNotNone(archived)
                self.assertEqual(archived["duration_sec"], 1.0)
                same_session = stt.archive_audio_file(
                    source,
                    owner_id="user-1",
                    meeting_id="meeting-1",
                    source_kind="upload",
                    original_filename="recording.wav",
                    archive_key="stream-session-1",
                )
                same_content = stt.archive_audio_file(
                    source,
                    owner_id="user-1",
                    meeting_id="meeting-1",
                    source_kind="upload",
                    original_filename="recording.wav",
                    archive_key="upload-retry-1",
                )
                self.assertEqual(same_session["id"], archived["id"])
                self.assertEqual(same_content["id"], archived["id"])

                longer_source = Path(directory) / "longer-recording.wav"
                with wave.open(str(longer_source), "wb") as writer:
                    writer.setnchannels(1)
                    writer.setsampwidth(2)
                    writer.setframerate(16000)
                    writer.writeframes(b"\x00\x00" * 32000)
                longer = stt.archive_audio_file(
                    longer_source,
                    owner_id="user-1",
                    meeting_id="meeting-1",
                    source_kind="file-upload",
                    original_filename="recording.wav",
                    archive_key="upload-complete",
                )
                self.assertEqual(longer["id"], archived["id"])
                self.assertEqual(longer["duration_sec"], 2.0)
                self.assertEqual(len(stt.list_archived_audio("user-1", "meeting-1")), 1)

                target_dir = stt.archive_owner_dir("user-1") / "meeting-1"
                duplicate_id = "b" * 32
                duplicate_audio = target_dir / f"{duplicate_id}.wav"
                duplicate_audio.write_bytes(source.read_bytes())
                duplicate_metadata = {
                    **archived,
                    "id": duplicate_id,
                    "archive_key": "historical-retry",
                    "created_at": "2099-01-01T00:00:00Z",
                    "audio_file": duplicate_audio.name,
                }
                duplicate_metadata_path = target_dir / f"{duplicate_id}.json"
                duplicate_metadata_path.write_text(
                    json.dumps(duplicate_metadata, ensure_ascii=False),
                    encoding="utf-8",
                )

                self.assertEqual(len(stt.list_archived_audio("user-1", "meeting-1")), 1)
                self.assertEqual(stt.cleanup_audio_archive(), 1)
                self.assertFalse(duplicate_audio.exists())
                self.assertFalse(duplicate_metadata_path.exists())
                self.assertEqual(stt.list_archived_audio("user-2", "meeting-1"), [])
                self.assertIsNotNone(stt.find_archived_audio("user-1", archived["id"]))
                self.assertTrue(stt.delete_archived_audio("user-1", archived["id"]))
                self.assertEqual(stt.list_archived_audio("user-1", "meeting-1"), [])
        finally:
            (
                stt.STT_AUDIO_ARCHIVE_ENABLED,
                stt.STT_AUDIO_ARCHIVE_DIR,
                stt.STT_AUDIO_ARCHIVE_RETENTION_DAYS,
                stt.STT_AUDIO_ARCHIVE_MAX_GB,
            ) = previous


class TencentRealtimeBridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_cloud_admission_timeout_returns_a_recoverable_message(self) -> None:
        async def noop(*_args) -> None:
            return None

        bridge = stt.TencentRealtimeBridge(noop, noop, tier=stt.TENCENT_STANDARD_TIER)
        with (
            patch.object(stt, "tencent_realtime_asr_semaphore", asyncio.Semaphore(0)),
            patch.object(stt, "TENCENT_REALTIME_ASR_ACQUIRE_TIMEOUT_SEC", 0.01),
        ):
            with self.assertRaisesRegex(RuntimeError, "当前并发繁忙"):
                await bridge.start()

    async def test_audio_is_queued_as_fixed_duration_frames(self) -> None:
        async def noop(*_args) -> None:
            return None

        bridge = stt.TencentRealtimeBridge(
            noop,
            noop,
            tier=stt.TENCENT_STANDARD_TIER,
        )
        bridge.connection = object()

        await bridge.feed(b"a" * 4096)
        await bridge.feed(b"b" * 4096)

        self.assertEqual(bridge.audio_queue.qsize(), 1)
        self.assertEqual(len(await bridge.audio_queue.get()), bridge.frame_bytes)
        self.assertEqual(len(bridge.pending_audio), 8192 - bridge.frame_bytes)
        self.assertEqual(bridge.audio_bytes, 8192)

    async def test_short_queue_congestion_applies_backpressure_without_fallback(self) -> None:
        failures: list[str] = []

        async def noop(*_args) -> None:
            return None

        async def on_failure(message: str) -> None:
            failures.append(message)

        bridge = stt.TencentRealtimeBridge(
            noop,
            on_failure,
            tier=stt.TENCENT_STANDARD_TIER,
        )
        bridge.connection = object()
        bridge.audio_queue = asyncio.Queue(maxsize=1)
        bridge.audio_queue.put_nowait(b"busy")

        async def release_queue() -> None:
            await asyncio.sleep(0.01)
            await bridge.audio_queue.get()

        consumer = asyncio.create_task(release_queue())
        with patch.object(stt, "TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC", 0.2):
            await bridge.feed(b"a" * bridge.frame_bytes)
        await consumer

        self.assertFalse(bridge.failed)
        self.assertEqual(failures, [])
        self.assertEqual(await bridge.audio_queue.get(), b"a" * bridge.frame_bytes)

    async def test_finish_does_not_block_when_upstream_queue_stays_full(self) -> None:
        failures: list[str] = []

        async def noop(*_args) -> None:
            return None

        async def on_failure(message: str) -> None:
            failures.append(message)

        class Connection:
            async def close(self) -> None:
                return None

        bridge = stt.TencentRealtimeBridge(
            noop,
            on_failure,
            tier=stt.TENCENT_STANDARD_TIER,
        )
        bridge.connection = Connection()
        bridge.audio_queue = asyncio.Queue(maxsize=1)
        bridge.audio_queue.put_nowait(b"busy")
        bridge.pending_audio.extend(b"tail")
        bridge.sender_task = asyncio.create_task(asyncio.Event().wait())

        with patch.object(stt, "TENCENT_REALTIME_ASR_BACKPRESSURE_TIMEOUT_SEC", 0.01):
            await asyncio.wait_for(bridge.finish(), timeout=0.5)

        self.assertTrue(bridge.failed)
        self.assertEqual(len(failures), 1)
        self.assertIsNone(bridge.connection)
        self.assertTrue(bridge.sender_task.cancelled())


class StreamAdmissionTest(unittest.TestCase):
    def test_owner_limit_prevents_one_account_from_consuming_all_stream_slots(self) -> None:
        previous_sessions = set(stt.active_stream_sessions)
        previous_owners = dict(stt.active_stream_owners)
        try:
            stt.active_stream_sessions.clear()
            stt.active_stream_owners.clear()
            with (
                patch.object(stt, "STT_MAX_STREAMS", 3),
                patch.object(stt, "STT_MAX_STREAMS_PER_OWNER", 1),
            ):
                self.assertIsNone(stt.reserve_stream_session("a", "owner-1"))
                self.assertEqual(stt.reserve_stream_session("b", "owner-1"), "owner")
                self.assertIsNone(stt.reserve_stream_session("c", "owner-2"))
                stt.release_stream_session("a", "owner-1")
                self.assertIsNone(stt.reserve_stream_session("d", "owner-1"))
        finally:
            stt.active_stream_sessions.clear()
            stt.active_stream_sessions.update(previous_sessions)
            stt.active_stream_owners.clear()
            stt.active_stream_owners.update(previous_owners)


class SttRuntimeSecurityTest(unittest.IsolatedAsyncioTestCase):
    async def test_cloud_asr_usage_endpoint_returns_summary_and_maps_failures(self) -> None:
        expected = {"month": "2026-07", "warning_level": "normal", "services": []}
        principal = stt.ApiPrincipal(owner_id="user-1")
        with (
            patch.object(stt, "TENCENT_ASR_USAGE_ENABLED", True),
            patch.object(stt, "tencent_asr_usage_configured", return_value=True),
            patch.object(stt, "fetch_tencent_asr_usage", return_value=expected),
        ):
            self.assertEqual(await stt.managed_cloud_asr_usage(principal), expected)

        with (
            patch.object(stt, "TENCENT_ASR_USAGE_ENABLED", True),
            patch.object(stt, "tencent_asr_usage_configured", return_value=True),
            patch.object(stt, "fetch_tencent_asr_usage", side_effect=ValueError("upstream failed")),
        ):
            with self.assertRaises(stt.HTTPException) as raised:
                await stt.managed_cloud_asr_usage(principal)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertNotIn("secret", str(raised.exception.detail).lower())

    async def test_switching_to_loaded_engine_and_model_is_a_noop(self) -> None:
        previous = (
            stt.stt_engine,
            stt.model_size,
            stt.model,
            stt.stream_model,
            stt.stream_model_error,
        )
        try:
            stt.stt_engine = "faster-whisper"
            stt.model_size = "small"
            stt.model = object()
            stt.stream_model = object()
            stt.stream_model_error = ""
            with (
                patch.object(stt.inference_scheduler, "pause") as pause,
                patch.object(stt, "load_model") as load_model,
                patch.object(stt, "load_stream_model") as load_stream_model,
            ):
                response = await stt.switch_stt(
                    stt.SwitchSTTRequest(engine="faster-whisper", model="small")
                )

            self.assertEqual(response["status"], "ready")
            self.assertTrue(response["unchanged"])
            pause.assert_not_called()
            load_model.assert_not_called()
            load_stream_model.assert_not_called()
        finally:
            (
                stt.stt_engine,
                stt.model_size,
                stt.model,
                stt.stream_model,
                stt.stream_model_error,
            ) = previous

    async def test_stream_recording_can_be_finalized_without_uploading_again(self) -> None:
        session_id = "a" * 32
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stream.wav"
            with wave.open(str(path), "wb") as writer:
                writer.setnchannels(1)
                writer.setsampwidth(2)
                writer.setframerate(16000)
                writer.writeframes(b"\x00\x00" * 1600)
            ready = asyncio.Event()
            ready.set()
            stt.stream_recordings[session_id] = stt.StreamRecording(
                path=path,
                ready=ready,
                created_at=time.time(),
                owner_id="user-1",
                meeting_id="meeting-1",
                audio_bytes=3200,
                language="en",
            )

            async def fake_run_inference(
                callback, file_path, language, context_hint="", *, label
            ):
                self.assertIs(callback, stt.transcribe_spooled_file)
                self.assertEqual(file_path, str(path))
                self.assertEqual(language, "en")
                self.assertEqual(label, "stream-finalize")
                return stt.TranscribeResponse(text="server-side final transcript", language="en")

            with patch.object(stt, "run_inference", side_effect=fake_run_inference):
                response = await stt.transcribe_stream_recording(
                    session_id,
                    stt.ApiPrincipal(owner_id="management", is_management=True),
                )

            self.assertEqual(response.text, "server-side final transcript")
            self.assertNotIn(session_id, stt.stream_recordings)

    async def test_tencent_hybrid_stream_uses_flash_final_and_local_fallback(self) -> None:
        async def create_recording(session_id: str, path: Path) -> None:
            with wave.open(str(path), "wb") as writer:
                writer.setnchannels(1)
                writer.setsampwidth(2)
                writer.setframerate(16000)
                writer.writeframes(b"\x00\x00" * 1600)
            ready = asyncio.Event()
            ready.set()
            stt.stream_recordings[session_id] = stt.StreamRecording(
                path=path,
                ready=ready,
                created_at=time.time(),
                owner_id="user-1",
                meeting_id="meeting-1",
                audio_bytes=3200,
                stream_provider=stt.TENCENT_REALTIME_STREAM_PROVIDER,
                final_provider="tencent-flash",
            )

        with tempfile.TemporaryDirectory() as directory:
            success_session = "b" * 32
            success_path = Path(directory) / "success.wav"
            await create_recording(success_session, success_path)
            with (
                patch.object(stt, "tencent_asr_configured", return_value=True),
                patch.object(
                    stt,
                    "transcribe_with_tencent_flash",
                    return_value=("腾讯极速版最终稿", {"code": 0}),
                ),
            ):
                response = await stt.transcribe_stream_recording(
                    success_session,
                    stt.ApiPrincipal(owner_id="management", is_management=True),
                )
            self.assertEqual(response.text, "腾讯极速版最终稿")
            self.assertFalse(success_path.exists())

            fallback_session = "c" * 32
            fallback_path = Path(directory) / "fallback.wav"
            await create_recording(fallback_session, fallback_path)

            async def fake_run_inference(
                callback, file_path, language, context_hint="", *, label
            ):
                self.assertIs(callback, stt.transcribe_spooled_file)
                self.assertEqual(file_path, str(fallback_path))
                self.assertEqual(language, "zh")
                self.assertEqual(label, "stream-finalize")
                fallback_path.unlink()
                return stt.TranscribeResponse(text="本地兜底最终稿", language="zh")

            with (
                patch.object(stt, "tencent_asr_configured", return_value=True),
                patch.object(
                    stt,
                    "transcribe_with_tencent_flash",
                    side_effect=ValueError("Tencent Cloud ASR error 4004"),
                ),
                patch.object(stt, "run_inference", side_effect=fake_run_inference),
            ):
                response = await stt.transcribe_stream_recording(
                    fallback_session,
                    stt.ApiPrincipal(owner_id="management", is_management=True),
                )
            self.assertEqual(response.text, "本地兜底最终稿")

    async def test_tencent_cloud_upload_uses_local_fallback_when_upstream_fails(self) -> None:
        upload = stt.UploadFile(filename="fallback.wav", file=io.BytesIO(b"RIFFfallback"))

        async def fake_run_inference(
            callback, file_path, language, context_hint="", *, label
        ):
            self.assertIs(callback, stt.transcribe_spooled_file)
            self.assertTrue(Path(file_path).is_file())
            self.assertEqual(language, "zh")
            self.assertEqual(label, "cloud-asr-fallback")
            Path(file_path).unlink()
            return stt.TranscribeResponse(text="本地兜底转写", language="zh")

        with (
            patch.object(stt, "tencent_asr_configured", return_value=True),
            patch.object(
                stt,
                "transcribe_with_tencent_flash",
                side_effect=ValueError("Tencent Cloud ASR returned HTTP 502"),
            ),
            patch.object(stt, "run_inference", side_effect=fake_run_inference),
        ):
            response = await stt.managed_cloud_asr_transcription(
                file=upload,
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=stt.ApiPrincipal(owner_id="management", is_management=True),
                x_meeting_id="",
                x_archive_key="",
            )

        self.assertEqual(response["text"], "本地兜底转写")
        self.assertEqual(response["provider"], "faster-whisper")
        self.assertTrue(response["fallback"])

    async def test_tencent_cloud_oversize_upload_uses_chunked_cloud_transcription(self) -> None:
        upload = stt.UploadFile(filename="long-recording.wav", file=io.BytesIO(b"RIFFfallback"))

        with (
            patch.object(stt, "tencent_asr_configured", return_value=True),
            patch.object(
                stt,
                "transcribe_with_tencent_flash_chunked",
                return_value=(
                    "超大录音分段云转写",
                    {"code": 0, "audio_duration": 3600000, "chunked": True, "chunk_count": 2},
                ),
            ) as cloud_transcribe,
            patch.object(stt, "run_inference") as local_transcribe,
        ):
            response = await stt.managed_cloud_asr_transcription(
                file=upload,
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=stt.ApiPrincipal(owner_id="management", is_management=True),
                x_meeting_id="",
                x_archive_key="",
            )

        cloud_transcribe.assert_called_once()
        local_transcribe.assert_not_called()
        self.assertEqual(response["text"], "超大录音分段云转写")
        self.assertEqual(response["provider"], stt.TENCENT_STANDARD_MODEL)
        self.assertTrue(response["chunked"])
        self.assertEqual(response["chunk_count"], 2)

    async def test_tencent_cloud_chunk_failure_falls_back_to_local_model(self) -> None:
        upload = stt.UploadFile(filename="long-recording.wav", file=io.BytesIO(b"RIFFfallback"))

        async def local_fallback(*_args, **_kwargs):
            return {
                "text": "本地兜底转写",
                "provider": "faster-whisper",
                "language": "zh",
                "fallback": True,
            }

        with (
            patch.object(stt, "tencent_asr_configured", return_value=True),
            patch.object(
                stt,
                "transcribe_with_tencent_flash_chunked",
                side_effect=stt.TencentChunkedTranscriptionError("第 2/2 段云端转写失败"),
            ),
            patch.object(stt, "local_cloud_asr_fallback", side_effect=local_fallback) as local_fallback_call,
        ):
            response = await stt.managed_cloud_asr_transcription(
                file=upload,
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=stt.ApiPrincipal(owner_id="management", is_management=True),
                x_meeting_id="",
                x_archive_key="",
            )

        local_fallback_call.assert_awaited_once()
        self.assertEqual(response["text"], "本地兜底转写")
        self.assertEqual(response["provider"], "faster-whisper")
        self.assertTrue(response["fallback"])

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


class SttBillingIntegrationTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.root = root
        self.db_path = root / "accounts.db"
        plans_path = root / "plans.json"
        plans_path.write_text("[]", encoding="utf-8")
        self.gateway = AgentGateway(
            self.db_path,
            root / "tasks",
            bootstrap_token="billing-agent-token",
        )
        self.gateway.initialize()
        self.account_service = stt.AccountService(
            self.db_path,
            token_secret="billing-account-secret",
            plans_path=plans_path,
            free_points=1_000,
            stt_points_per_minute=10,
        )
        self.account_service.initialize(bootstrap_admin=False)
        session = self.account_service.register("billing_user", "strong-password")
        account_principal = self.account_service.authenticate(
            f"Bearer {session['access_token']}"
        )
        self.account_principal = account_principal
        self.principal = stt.ApiPrincipal(owner_id=account_principal.user_id)

    def tearDown(self) -> None:
        for recording in list(stt.stream_recordings.values()):
            if recording.owner_id == self.principal.owner_id:
                with contextlib.suppress(FileNotFoundError):
                    recording.path.unlink()
        stt.stream_recordings = {
            key: value
            for key, value in stt.stream_recordings.items()
            if value.owner_id != self.principal.owner_id
        }
        self.temp_dir.cleanup()

    @contextmanager
    def billing_environment(self):
        with (
            patch.object(stt, "_account_billing_service", self.account_service),
            patch.object(stt, "ACCOUNT_TOKEN_SECRET", "billing-account-secret"),
            patch.object(stt, "ACCOUNT_DB_PATH", self.db_path),
            patch.object(stt, "STT_TEMP_DIR", self.root / "stt-temp"),
            patch.object(stt, "STT_AUDIO_ARCHIVE_ENABLED", False),
        ):
            yield

    @staticmethod
    async def fake_inference(
        callback, file_path, language, context_hint="", *, label
    ):
        assert callback is stt.transcribe_spooled_file
        Path(file_path).unlink(missing_ok=True)
        return stt.TranscribeResponse(
            text=f"{label}-transcript",
            language=language,
            duration_ms=61_001,
        )

    def _new_upload(self) -> stt.UploadFile:
        return stt.UploadFile(
            filename="recording.wav",
            file=io.BytesIO(b"RIFF" + b"billing-audio"),
        )

    async def test_file_transcription_charges_once_for_idempotent_retry(self) -> None:
        with (
            self.billing_environment(),
            patch.object(stt, "model", object()),
            patch.object(stt, "audio_duration_for_tencent_budget", return_value=61.001),
            patch.object(stt, "archive_audio_file", return_value=None),
            patch.object(stt, "run_inference", side_effect=self.fake_inference),
        ):
            first = await stt.transcribe(
                self._new_upload(),
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-file",
                x_archive_key="",
                x_usage_key="file-retry-01",
            )
            retry = await stt.transcribe(
                self._new_upload(),
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-file",
                x_archive_key="",
                x_usage_key="file-retry-01",
            )

        self.assertIsNotNone(first.usage)
        self.assertEqual(first.usage["id"], retry.usage["id"])
        self.assertEqual(first.duration_ms, 61_001)
        self.assertEqual(self.account_service.usage_summary(self.account_principal)["points_used"], 20)

    async def test_cloud_success_and_local_fallback_each_charge_once(self) -> None:
        cloud_payload = {"code": 0, "audio_duration": "61001"}
        with (
            self.billing_environment(),
            patch.object(stt, "tencent_asr_configured", return_value=True),
            patch.object(stt, "tencent_asr_budget_enforced", return_value=False),
            patch.object(stt, "audio_duration_for_tencent_budget", return_value=61.001),
            patch.object(stt, "archive_audio_file", return_value=None),
            patch.object(
                stt,
                "transcribe_with_tencent_flash_chunked",
                return_value=("云端转写", cloud_payload),
            ),
        ):
            first = await stt.managed_cloud_asr_transcription(
                file=self._new_upload(),
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-cloud",
                x_archive_key="",
                x_usage_key="cloud-success-01",
            )
            retry = await stt.managed_cloud_asr_transcription(
                file=self._new_upload(),
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-cloud",
                x_archive_key="",
                x_usage_key="cloud-success-01",
            )

        self.assertEqual(first["usage"]["id"], retry["usage"]["id"])
        self.assertEqual(first["duration_ms"], 61_001)

        async def fallback_inference(
            callback, file_path, language, context_hint="", *, label
        ):
            self.assertIs(callback, stt.transcribe_spooled_file)
            self.assertEqual(label, "cloud-asr-fallback")
            Path(file_path).unlink(missing_ok=True)
            return stt.TranscribeResponse(text="本地兜底", language=language, duration_ms=0)

        with (
            self.billing_environment(),
            patch.object(stt, "tencent_asr_configured", return_value=True),
            patch.object(stt, "tencent_asr_budget_enforced", return_value=False),
            patch.object(stt, "audio_duration_for_tencent_budget", return_value=61.001),
            patch.object(stt, "archive_audio_file", return_value=None),
            patch.object(
                stt,
                "transcribe_with_tencent_flash_chunked",
                side_effect=ValueError("upstream unavailable"),
            ),
            patch.object(stt, "run_inference", side_effect=fallback_inference),
        ):
            first_fallback = await stt.managed_cloud_asr_transcription(
                file=self._new_upload(),
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-cloud",
                x_archive_key="",
                x_usage_key="cloud-fallback-01",
            )
            retry_fallback = await stt.managed_cloud_asr_transcription(
                file=self._new_upload(),
                model=stt.TENCENT_STANDARD_MODEL,
                language="zh",
                principal=self.principal,
                x_meeting_id="meeting-cloud",
                x_archive_key="",
                x_usage_key="cloud-fallback-01",
            )

        self.assertEqual(first_fallback["usage"]["id"], retry_fallback["usage"]["id"])
        self.assertEqual(first_fallback["duration_ms"], 61_001)
        self.assertEqual(self.account_service.usage_summary(self.account_principal)["points_used"], 40)

    async def test_stream_finalize_charges_once_when_same_session_key_is_retried(self) -> None:
        with self.account_service._connect() as conn:
            conn.execute(
                "UPDATE account_usage_balances SET points_granted = 20, points_used = 0 WHERE user_id = ?",
                (self.account_principal.user_id,),
            )

        def create_recording(session_id: str) -> None:
            path = self.root / f"{session_id}.wav"
            with wave.open(str(path), "wb") as writer:
                writer.setnchannels(1)
                writer.setsampwidth(2)
                writer.setframerate(16_000)
                writer.writeframes(b"\x00\x00" * 1_600)
            ready = asyncio.Event()
            ready.set()
            stt.stream_recordings[session_id] = stt.StreamRecording(
                path=path,
                ready=ready,
                created_at=time.time(),
                owner_id=self.principal.owner_id,
                meeting_id="meeting-stream",
                audio_bytes=3_200,
                language="zh",
            )

        session_id = "d" * 32
        create_recording(session_id)
        with (
            self.billing_environment(),
            patch.object(stt, "audio_duration_for_tencent_budget", return_value=61.001),
            patch.object(stt, "archive_audio_file", return_value=None),
            patch.object(stt, "run_inference", side_effect=self.fake_inference),
        ):
            first = await stt.transcribe_stream_recording(
                session_id,
                self.principal,
                "stream-retry-01",
            )
            create_recording(session_id)
            retry = await stt.transcribe_stream_recording(
                session_id,
                self.principal,
                "stream-retry-01",
            )

        self.assertEqual(first.usage["id"], retry.usage["id"])
        self.assertEqual(self.account_service.usage_summary(self.account_principal)["points_used"], 20)

    def test_cross_account_reuse_of_canonical_usage_key_is_rejected(self) -> None:
        second_session = self.account_service.register("billing_user_two", "strong-password")
        second = self.account_service.authenticate(
            f"Bearer {second_session['access_token']}"
        )
        key = self.account_service.canonical_stt_usage_key(
            self.account_principal.user_id,
            "cross-user-key",
        )
        self.account_service.record_stt_usage_for_user(
            self.account_principal.user_id,
            duration_ms=1_000,
            meeting_id="meeting-cross-user",
            idempotency_key=key,
        )
        with self.assertRaises(AccountConflictError):
            self.account_service.record_stt_usage_for_user(
                second.user_id,
                duration_ms=1_000,
                meeting_id="meeting-cross-user",
                idempotency_key=key,
            )


if __name__ == "__main__":
    unittest.main()
