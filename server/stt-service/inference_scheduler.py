"""Bounded, observable scheduling for blocking STT inference calls."""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from time import monotonic
from typing import Any, Callable


class InferenceQueueFullError(RuntimeError):
    """Raised when the bounded inference queue cannot accept another job."""


class InferenceQueuePausedError(RuntimeError):
    """Raised while model maintenance has paused admission."""


@dataclass(slots=True)
class _InferenceJob:
    callback: Callable[..., Any]
    args: tuple[Any, ...]
    future: asyncio.Future[Any]
    label: str
    queued_at: float


class InferenceScheduler:
    """FIFO queue feeding a fixed number of blocking inference workers."""

    def __init__(self, max_concurrency: int, max_queue: int) -> None:
        if max_concurrency < 1:
            raise ValueError("max_concurrency must be at least 1")
        if max_queue < 1:
            raise ValueError("max_queue must be at least 1")

        self.max_concurrency = max_concurrency
        self.max_queue = max_queue
        self._queue: asyncio.Queue[_InferenceJob] = asyncio.Queue(maxsize=max_queue)
        self._executor = ThreadPoolExecutor(
            max_workers=max_concurrency,
            thread_name_prefix="stt-inference",
        )
        self._workers: list[asyncio.Task[None]] = []
        self._start_lock = asyncio.Lock()
        self._accepting = True
        self._active = 0
        self._submitted = 0
        self._completed = 0
        self._failed = 0
        self._rejected = 0
        self._peak_active = 0
        self._peak_queued = 0
        self._total_queue_wait_sec = 0.0

    async def start(self) -> None:
        if self._workers:
            return
        async with self._start_lock:
            if not self._workers:
                self._workers = [
                    asyncio.create_task(self._worker(index), name=f"stt-worker-{index}")
                    for index in range(self.max_concurrency)
                ]

    async def run(self, callback: Callable[..., Any], *args: Any, label: str = "inference") -> Any:
        await self.start()
        if not self._accepting:
            self._rejected += 1
            raise InferenceQueuePausedError("STT model maintenance is in progress")

        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()
        job = _InferenceJob(
            callback=callback,
            args=args,
            future=future,
            label=label,
            queued_at=monotonic(),
        )
        try:
            self._queue.put_nowait(job)
        except asyncio.QueueFull as exc:
            self._rejected += 1
            raise InferenceQueueFullError("STT inference queue is full") from exc

        self._submitted += 1
        self._peak_queued = max(self._peak_queued, self._queue.qsize())
        # Keep accepted inference jobs alive when an HTTP/WebSocket caller
        # disconnects so worker-owned temporary resources can be finalized.
        try:
            return await asyncio.shield(future)
        except asyncio.CancelledError:
            def consume_result(completed: asyncio.Future[Any]) -> None:
                if not completed.cancelled():
                    completed.exception()

            future.add_done_callback(consume_result)
            raise

    async def _worker(self, index: int) -> None:
        del index
        loop = asyncio.get_running_loop()
        while True:
            job = await self._queue.get()
            try:
                self._total_queue_wait_sec += monotonic() - job.queued_at
                self._active += 1
                self._peak_active = max(self._peak_active, self._active)
                try:
                    result = await loop.run_in_executor(
                        self._executor,
                        job.callback,
                        *job.args,
                    )
                except Exception as exc:
                    self._failed += 1
                    if not job.future.done():
                        job.future.set_exception(exc)
                else:
                    self._completed += 1
                    if not job.future.done():
                        job.future.set_result(result)
                finally:
                    self._active -= 1
            finally:
                self._queue.task_done()

    def pause(self) -> None:
        self._accepting = False

    def resume(self) -> None:
        self._accepting = True

    async def wait_idle(self, timeout_sec: float | None = None) -> None:
        waiter = self._queue.join()
        if timeout_sec is None:
            await waiter
        else:
            await asyncio.wait_for(waiter, timeout=timeout_sec)

    async def close(self) -> None:
        self._accepting = False
        await self._queue.join()
        workers = list(self._workers)
        self._workers.clear()
        for worker in workers:
            worker.cancel()
        await asyncio.gather(*workers, return_exceptions=True)
        self._executor.shutdown(wait=True, cancel_futures=True)

    def stats(self) -> dict[str, Any]:
        average_wait_ms = (
            self._total_queue_wait_sec * 1000 / self._completed
            if self._completed
            else 0.0
        )
        return {
            "accepting": self._accepting,
            "max_concurrency": self.max_concurrency,
            "max_queue": self.max_queue,
            "active": self._active,
            "queued": self._queue.qsize(),
            "submitted": self._submitted,
            "completed": self._completed,
            "failed": self._failed,
            "rejected": self._rejected,
            "peak_active": self._peak_active,
            "peak_queued": self._peak_queued,
            "average_queue_wait_ms": round(average_wait_ms, 2),
        }
