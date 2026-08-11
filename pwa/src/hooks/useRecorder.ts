import { useCallback, useEffect, useRef, useState } from "react";
import { assembleRecording, clearRecordingChunks, saveRecordingChunk } from "../lib/db";

interface RecorderResult {
  blob: Blob;
  durationSeconds: number;
  mimeType: string;
}

const MIME_TYPES = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus"];

export function useRecorder(onCompleted: (result: RecorderResult) => Promise<void> | void) {
  const [isRecording, setIsRecording] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [backgroundRisk, setBackgroundRisk] = useState(false);
  const recorderRef = useRef<MediaRecorder>();
  const streamRef = useRef<MediaStream>();
  const wakeLockRef = useRef<WakeLockSentinel>();
  const meetingIdRef = useRef("");
  const sequenceRef = useRef(0);
  const startedAtRef = useRef(0);
  const writeQueueRef = useRef<Promise<void>>(Promise.resolve());
  const startingRef = useRef(false);
  const timerRef = useRef<number>();

  const releaseResources = useCallback(async () => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    timerRef.current = undefined;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = undefined;
    if (wakeLockRef.current && !wakeLockRef.current.released) await wakeLockRef.current.release().catch(() => undefined);
    wakeLockRef.current = undefined;
  }, []);

  const start = useCallback(async (meetingId: string) => {
    if (startingRef.current || recorderRef.current?.state === "recording") return;
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      throw new Error("当前浏览器不支持网页录音");
    }
    startingRef.current = true;
    let stream: MediaStream;
    try {
      await clearRecordingChunks(meetingId);
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
      writeQueueRef.current = writeQueueRef.current.then(() => saveRecordingChunk(meetingId, sequence, event.data));
    };
    recorder.onstop = async () => {
      await writeQueueRef.current;
      const durationSeconds = Math.max(1, Math.round((Date.now() - startedAtRef.current) / 1000));
      const blob = await assembleRecording(meetingId, recorder.mimeType || "audio/webm");
      if (blob) await onCompleted({ blob, durationSeconds, mimeType: blob.type || recorder.mimeType });
      await clearRecordingChunks(meetingId);
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
    timerRef.current = window.setInterval(() => {
      setElapsedSeconds(Math.floor((Date.now() - startedAtRef.current) / 1000));
    }, 500);
    wakeLockRef.current = await navigator.wakeLock?.request("screen").catch(() => undefined);
  }, [onCompleted, releaseResources]);

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

  return { isRecording, elapsedSeconds, backgroundRisk, start, stop };
}
