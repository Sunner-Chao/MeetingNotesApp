import { openDB, type DBSchema } from "idb";
import type { Meeting } from "../types";

interface RecordingChunk {
  meetingId: string;
  sequence: number;
  blob: Blob;
}

export interface DeletedMeetingRecord {
  id: string;
  deletedAt: number;
}

interface ZhiWuBenDatabase extends DBSchema {
  meetings: {
    key: string;
    value: Meeting;
    indexes: { "by-updated": number };
  };
  recordingChunks: {
    key: [string, number];
    value: RecordingChunk;
    indexes: { "by-meeting": string };
  };
  deletedMeetings: {
    key: string;
    value: DeletedMeetingRecord;
  };
}

const database = openDB<ZhiWuBenDatabase>("zhiwuben-pwa", 2, {
  upgrade(db, oldVersion) {
    if (oldVersion < 1) {
      const meetings = db.createObjectStore("meetings", { keyPath: "id" });
      meetings.createIndex("by-updated", "updatedAt");
      const chunks = db.createObjectStore("recordingChunks", { keyPath: ["meetingId", "sequence"] });
      chunks.createIndex("by-meeting", "meetingId");
    }
    if (oldVersion < 2) db.createObjectStore("deletedMeetings", { keyPath: "id" });
  }
});

export async function listMeetings(): Promise<Meeting[]> {
  const db = await database;
  const meetings = await db.getAllFromIndex("meetings", "by-updated");
  return meetings.sort((left, right) => right.updatedAt - left.updatedAt);
}

export async function saveMeeting(meeting: Meeting): Promise<void> {
  const db = await database;
  await db.put("meetings", meeting);
}

export async function deleteMeetingRecord(meetingId: string): Promise<void> {
  const db = await database;
  const transaction = db.transaction(["meetings", "recordingChunks", "deletedMeetings"], "readwrite");
  await transaction.objectStore("meetings").delete(meetingId);
  const chunkKeys = await transaction.objectStore("recordingChunks").index("by-meeting").getAllKeys(meetingId);
  await Promise.all(chunkKeys.map((key) => transaction.objectStore("recordingChunks").delete(key)));
  await transaction.objectStore("deletedMeetings").put({ id: meetingId, deletedAt: Date.now() });
  await transaction.done;
}

export async function clearMeetings(): Promise<void> {
  const db = await database;
  const transaction = db.transaction(["meetings", "recordingChunks", "deletedMeetings"], "readwrite");
  const meetingIds = await transaction.objectStore("meetings").getAllKeys();
  const deletedAt = Date.now();
  await Promise.all(meetingIds.map((id) => transaction.objectStore("deletedMeetings").put({ id, deletedAt })));
  await transaction.objectStore("meetings").clear();
  await transaction.objectStore("recordingChunks").clear();
  await transaction.done;
}

export async function listDeletedMeetings(): Promise<DeletedMeetingRecord[]> {
  const db = await database;
  return db.getAll("deletedMeetings");
}

export async function acknowledgeDeletedMeeting(meetingId: string): Promise<void> {
  const db = await database;
  await db.delete("deletedMeetings", meetingId);
}

export async function clearRecordingChunks(meetingId: string): Promise<void> {
  const db = await database;
  const transaction = db.transaction("recordingChunks", "readwrite");
  const store = transaction.store;
  const keys = await store.index("by-meeting").getAllKeys(meetingId);
  await Promise.all(keys.map((key) => store.delete(key)));
  await transaction.done;
}

export async function saveRecordingChunk(meetingId: string, sequence: number, blob: Blob): Promise<void> {
  const db = await database;
  await db.put("recordingChunks", { meetingId, sequence, blob });
}

export async function assembleRecording(meetingId: string, fallbackType: string): Promise<Blob | undefined> {
  const db = await database;
  const chunks = await db.getAllFromIndex("recordingChunks", "by-meeting", meetingId);
  if (chunks.length === 0) return undefined;
  chunks.sort((left, right) => left.sequence - right.sequence);
  const mimeType = chunks.find((chunk) => chunk.blob.type)?.blob.type || fallbackType;
  return new Blob(chunks.map((chunk) => chunk.blob), { type: mimeType });
}

export async function recoverInterruptedRecordings(): Promise<Meeting[]> {
  const db = await database;
  const chunks = await db.getAll("recordingChunks");
  const meetingIds = [...new Set(chunks.map((chunk) => chunk.meetingId))];
  const recovered: Meeting[] = [];
  for (const meetingId of meetingIds) {
    const meeting = await db.get("meetings", meetingId);
    const audio = await assembleRecording(meetingId, "audio/webm");
    if (!meeting || !audio || meeting.audio) continue;
    const updated: Meeting = {
      ...meeting,
      audio,
      audioType: audio.type,
      audioName: `recovered-${meetingId}`,
      updatedAt: Date.now()
    };
    await db.put("meetings", updated);
    await clearRecordingChunks(meetingId);
    recovered.push(updated);
  }
  return recovered;
}
