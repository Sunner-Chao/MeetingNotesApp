"""Transport-neutral mono PCM boundary shared by RTC and future device adapters.

Device SDKs must decode/resample outside this module. Account identity is bound by
the authenticated session; hardware cannot assert another speaker's identity.
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class PcmFrame:
    data: bytes = field(repr=False)
    sequence: int
    end_ms: int
    sample_rate: int = 16000
    channels: int = 1

    def __post_init__(self):
        if self.sample_rate != 16000 or self.channels != 1:
            raise ValueError("Audio adapter must supply 16 kHz mono PCM16LE")
        if not self.data or len(self.data) % 2 or self.sequence < 0 or self.end_ms < 0:
            raise ValueError("Invalid PCM frame")

    @property
    def duration_ms(self):
        return len(self.data) / 32


class AudioClock:
    """Map decoder sample offsets to session time, retaining mute/disconnect gaps."""
    def __init__(self):
        self.sent_ms = 0.0
        self.points = []
        self.sequence = -1

    def push(self, frame: PcmFrame):
        if frame.sequence <= self.sequence:
            raise ValueError("Audio frames must be delivered once, in sequence")
        self.sequence = frame.sequence
        start_ms = frame.end_ms - frame.duration_ms
        predicted = self.points[-1][1] + self.sent_ms - self.points[-1][0] if self.points else start_ms
        if not self.points or abs(predicted - start_ms) > 200:
            self.points.append((self.sent_ms, start_ms))
        self.sent_ms += frame.duration_ms

    def map(self, audio_ms):
        if not self.points:
            return 0
        while len(self.points) > 1 and self.points[1][0] < audio_ms:
            self.points.pop(0)
        offset, session_ms = self.points[0]
        return max(0, int(session_ms + audio_ms - offset))
