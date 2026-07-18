from __future__ import annotations

import asyncio
import sys
import threading
import time
import unittest
from pathlib import Path


STT_SERVICE_DIR = Path(__file__).resolve().parents[1] / "stt-service"
sys.path.insert(0, str(STT_SERVICE_DIR))

from inference_scheduler import InferenceQueueFullError, InferenceScheduler


class InferenceSchedulerTests(unittest.IsolatedAsyncioTestCase):
    async def test_runs_up_to_configured_concurrency(self) -> None:
        scheduler = InferenceScheduler(max_concurrency=2, max_queue=8)
        active = 0
        peak_active = 0
        lock = threading.Lock()

        def work(value: int) -> int:
            nonlocal active, peak_active
            with lock:
                active += 1
                peak_active = max(peak_active, active)
            time.sleep(0.05)
            with lock:
                active -= 1
            return value * 2

        try:
            results = await asyncio.gather(
                *(scheduler.run(work, value, label="test") for value in range(6))
            )
        finally:
            await scheduler.close()

        self.assertEqual(results, [0, 2, 4, 6, 8, 10])
        self.assertEqual(peak_active, 2)
        self.assertEqual(scheduler.stats()["peak_active"], 2)

    async def test_rejects_when_waiting_queue_is_full(self) -> None:
        scheduler = InferenceScheduler(max_concurrency=1, max_queue=1)
        started = threading.Event()
        release = threading.Event()

        def blocking_work() -> str:
            started.set()
            release.wait(timeout=2)
            return "first"

        first = asyncio.create_task(scheduler.run(blocking_work, label="first"))
        await asyncio.to_thread(started.wait, 1)
        second = asyncio.create_task(scheduler.run(lambda: "second", label="second"))
        await asyncio.sleep(0.02)

        try:
            with self.assertRaises(InferenceQueueFullError):
                await scheduler.run(lambda: "third", label="third")
        finally:
            release.set()

        self.assertEqual(await first, "first")
        self.assertEqual(await second, "second")
        self.assertEqual(scheduler.stats()["rejected"], 1)
        await scheduler.close()

    async def test_accepted_job_finishes_after_caller_cancellation(self) -> None:
        scheduler = InferenceScheduler(max_concurrency=1, max_queue=2)
        started = threading.Event()
        release = threading.Event()

        def work() -> str:
            started.set()
            release.wait(timeout=2)
            return "done"

        caller = asyncio.create_task(scheduler.run(work, label="cancelled-caller"))
        await asyncio.to_thread(started.wait, 1)
        caller.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await caller
        release.set()
        await scheduler.wait_idle(timeout_sec=1)

        self.assertEqual(scheduler.stats()["completed"], 1)
        await scheduler.close()


if __name__ == "__main__":
    unittest.main()
