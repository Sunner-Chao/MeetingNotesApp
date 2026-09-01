import { useCallback, useEffect, useRef, useState } from "react";
import { assembleRecording, clearRecordingChunks, saveRecordingChunk } from "../lib/db";
import type { RuntimeConfig } from "../types";

interface RecorderResult {
  blob: Blob;
  durationSeconds: number;
  mimeType: string;
}

export interface LiveTranscriptUpdate {
  text: string;
  committedText?: string;
  previewText?: string;
}

interface RecorderOptions {
  config?: RuntimeConfig;
  sttAccessToken?: string | null;
  contextHint?: string;
  onPartialText?: (update: LiveTranscriptUpdate) => void;
}

const MIME_TYPES = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus"];

function buildStreamingUrl(config?: RuntimeConfig): string | undefined {
  if (typeof window === "undefined") return undefined;
  const rawBase = config?.apiBase?.trim() || window.location.origin;
  const base = new URL(rawBase, window.location.origin);
  const basePath = base.pathname.replace(/\/+$/, "").replace(/\/api$/, "");
  base.protocol = base.protocol === "https:" ? "wss:" : "ws:";
  base.pathname = `${basePath}/ws/transcribe-stream`;
  base.search = "";
  return base.toString();
}

function pcm16FromFloat32(input: Float32Array): ArrayBuffer {
  const output = new Int16Array(input.length);
  for (let index = 0; index < input.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, input[index]));
    output[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
  }
  return output.buffer;
}

export function useRecorder(
  accountId: string,
  onCompleted: (result: RecorderResult) => Promise<void> | void,
  options: RecorderOptions = {}
) {
  const { config, sttAccessToken, contextHint, onPartialText } = options;
  const [isRecording, setIsRecording] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [backgroundRisk, setBackgroundRisk] = useState(false);
  const [liveTranscript, setLiveTranscript] = useState("");
  const [liveStatus, setLiveStatus] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const recorderRef = useRef<MediaRecorder>();
  const streamRef = useRef<MediaStream>();
  const wakeLockRef = useRef<WakeLockSentinel>();
  const liveSocketRef = useRef<WebSocket>();
  const audioContextRef = useRef<AudioContext>();
  const audioSourceRef = useRef<MediaStreamAudioSourceNode>();
  const audioProcessorRef = useRef<ScriptProcessorNode>();
  const meetingIdRef = useRef("");
  const sequenceRef = useRef(0);
  const startedAtRef = useRef(0);
  const writeQueueRef = useRef<Promise<void>>(Promise.resolve());
  const startingRef = useRef(false);
  const timerRef = useRef<number>();

  const stopLiveStream = useCallback(() => {
    const socket = liveSocketRef.current;
    liveSocketRef.current = undefined;
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ event: "stop" }));
      socket.close(1000, "recording stopped");
    } else {
      socket?.close();
    }
    audioProcessorRef.current?.disconnect();
    audioProcessorRef.current = undefined;
    audioSourceRef.current?.disconnect();
    audioSourceRef.current = undefined;
    const context = audioContextRef.current;
    audioContextRef.current = undefined;
    if (context && context.state !== "closed") void context.close().catch(() => undefined);
    setLiveStatus("idle");
  }, []);

  const startLiveStream = useCallback(async (meetingId: string, stream: MediaStream) => {
    const url = buildStreamingUrl(config);
    const token = sttAccessToken?.trim();
    if (!url || !token || typeof AudioContext === "undefined" || typeof WebSocket === "undefined") {
      setLiveStatus("idle");
      return;
    }
    stopLiveStream();
    setLiveTranscript("");
    setLiveStatus("connecting");
    const context = new AudioContext();
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(4096, 1, 1);
    const socket = new WebSocket(url);
    liveSocketRef.current = socket;
    audioContextRef.current = context;
    audioSourceRef.current = source;
    audioProcessorRef.current = processor;
    let failed = false;
    socket.onopen = () => {
      socket.send(JSON.stringify({ event: "authenticate", access_token: token }));
      socket.send(JSON.stringify({
        event: "start",
        meeting_id: meetingId,
        sample_rate: context.sampleRate,
        channels: 1,
        language: "zh",
        context_hint: contextHint?.trim() || undefined,
        stream_provider: "local"
      }));
    };
    socket.onmessage = (event) => {
      if (typeof event.data !== "string") return;
      try {
        const payload = JSON.parse(event.data) as { type?: string; text?: string; committed_text?: string; preview_text?: string };
        if (payload.type === "status") {
          setLiveStatus("connected");
          return;
        }
        if (payload.type === "error") {
          failed = true;
          setLiveStatus("error");
          return;
        }
        if (payload.type !== "partial") return;
        const text = payload.text?.trim() || [payload.committed_text, payload.preview_text].filter(Boolean).join(" ").trim();
        if (!text) return;
        setLiveTranscript(text);
        onPartialText?.({ text, committedText: payload.committed_text, previewText: payload.preview_text });
      } catch {
        // Ignore malformed preview events; final transcription remains authoritative.
      }
    };
    socket.onerror = () => {
      failed = true;
      setLiveStatus("error");
    };
    socket.onclose = () => {
      if (liveSocketRef.current === socket) {
        liveSocketRef.current = undefined;
        setLiveStatus(failed ? "error" : "idle");
      }
    };
    processor.onaudioprocess = (event) => {
      if (socket.readyState !== WebSocket.OPEN) return;
      socket.send(pcm16FromFloat32(event.inputBuffer.getChannelData(0)));
    };
    source.connect(processor);
    // ScriptProcessorNodes only run when connected to an output node. Keep the
    // output silent so the microphone is never echoed back to the user.
    const mute = context.createGain();
    mute.gain.value = 0;
    processor.connect(mute);
    mute.connect(context.destination);
    await context.resume().catch(() => undefined);
  }, [config, contextHint, onPartialText, sttAccessToken, stopLiveStream]);

  const releaseResources = useCallback(async () => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    timerRef.current = undefined;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = undefined;
    stopLiveStream();
    if (wakeLockRef.current && !wakeLockRef.current.released) await wakeLockRef.current.release().catch(() => undefined);
    wakeLockRef.current = undefined;
  }, [stopLiveStream]);

  const start = useCallback(async (meetingId: string) => {
    if (startingRef.current || recorderRef.current?.state === "recording") return;
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      throw new Error("当前浏览器不支持网页录音");
    }
    startingRef.current = true;
    let stream: MediaStream;
    try {
      await clearRecordingChunks(meetingId, accountId);
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });
    } finally {
      startingRef.current = false;
    }
    const mimeType = MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type));
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    meetingIdRef.current = meetingId;
    sequenceRef.current = 0;
    writeQueueRef.current = Promise.resolve();
    startedAtRef.current = Date.now();
    recorder.ondataavailable = (event) => {
      if (event.data.size === 0) return;
      const sequence = sequenceRef.current++;
      writeQueueRef.current = writeQueueRef.current.then(() => saveRecordingChunk(meetingId, sequence, event.data, accountId));
    };
    recorder.onstop = async () => {
      stopLiveStream();
      await writeQueueRef.current;
      const durationSeconds = Math.max(1, Math.round((Date.now() - startedAtRef.current) / 1000));
      const blob = await assembleRecording(meetingId, recorder.mimeType || "audio/webm", accountId);
      if (blob) await onCompleted({ blob, durationSeconds, mimeType: blob.type || recorder.mimeType });
      await clearRecordingChunks(meetingId, accountId);
      await releaseResources();
      setIsRecording(false);
    };
    recorder.onerror = () => {
      void releaseResources();
      setIsRecording(false);
    };
    recorderRef.current = recorder;
    streamRef.current = stream;
    setElapsedSeconds(0);
    setBackgroundRisk(false);
    setIsRecording(true);
    recorder.start(1000);
    void startLiveStream(meetingId, stream).catch(() => setLiveStatus("error"));
    timerRef.current = window.setInterval(() => {
      setElapsedSeconds(Math.floor((Date.now() - startedAtRef.current) / 1000));
    }, 500);
    wakeLockRef.current = await navigator.wakeLock?.request("screen").catch(() => undefined);
  }, [accountId, onCompleted, releaseResources, startLiveStream, stopLiveStream]);

  const stop = useCallback(() => {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
  }, []);

  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.hidden && recorderRef.current?.state === "recording") setBackgroundRisk(true);
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, []);

  useEffect(() => () => {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
    void releaseResources();
  }, [releaseResources]);

  return { isRecording, elapsedSeconds, backgroundRisk, liveTranscript, liveStatus, start, stop };
}
