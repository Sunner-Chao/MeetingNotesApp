import { openDB, type DBSchema } from "idb";
import type { Meeting } from "../types";

interface RecordingChunk {
  accountId: string;
  meetingId: string;
  sequence: number;
  blob: Blob;
}

export interface DeletedMeetingRecord {
  accountId: string;
  id: string;
  deletedAt: number;
}

export const GUEST_ACCOUNT_ID = "guest";

export interface ScopedMeeting extends Meeting {
  accountId: string;
}

interface ZhiWuBenDatabase extends DBSchema {
  // Legacy stores remain read-only orphaned data after the v3 upgrade. They
  // are intentionally excluded from all accessors so they cannot cross an
  // account boundary.
  meetings: {
    key: string;
    value: Meeting;
    indexes: { "by-updated": number };
  };
  recordingChunks: {
    key: [string, number];
    value: Omit<RecordingChunk, "accountId">;
    indexes: { "by-meeting": string };
  };
  deletedMeetings: {
    key: string;
    value: Omit<DeletedMeetingRecord, "accountId">;
  };
  meetings_v3: {
    key: [string, string];
    value: ScopedMeeting;
    indexes: { "by-account-updated": [string, number] };
  };
  recordingChunks_v3: {
    key: [string, string, number];
    value: RecordingChunk;
    indexes: { "by-account-meeting": [string, string] };
  };
  deletedMeetings_v3: {
    key: [string, string];
    value: DeletedMeetingRecord;
  };
}

const database = openDB<ZhiWuBenDatabase>("zhiwuben-pwa", 3, {
  upgrade(db, oldVersion) {
    // v3 introduces compound keys with an account scope. Legacy stores are
    // retained temporarily and copied to the guest scope after opening.
    const meetings = db.createObjectStore("meetings_v3", { keyPath: ["accountId", "id"] });
    meetings.createIndex("by-account-updated", ["accountId", "updatedAt"]);
    const chunks = db.createObjectStore("recordingChunks_v3", { keyPath: ["accountId", "meetingId", "sequence"] });
    chunks.createIndex("by-account-meeting", ["accountId", "meetingId"]);
    const deleted = db.createObjectStore("deletedMeetings_v3", { keyPath: ["accountId", "id"] });

    // Do not migrate or delete legacy records in the version-change callback;
    // the normal transaction below performs that work after the database opens.
  }
});

const legacyMigration = database.then(async (db) => {
  if (!db.objectStoreNames.contains("meetings")) return;
  const names = (["meetings", "recordingChunks", "deletedMeetings"] as const)
    .filter((name) => db.objectStoreNames.contains(name));
  const transaction = db.transaction([...names, "meetings_v3", "recordingChunks_v3", "deletedMeetings_v3"], "readwrite");
  if (names.includes("meetings")) {
    const legacyStore = transaction.objectStore("meetings");
    const records = await legacyStore.getAll();
    await Promise.all(records.map((value) => transaction.objectStore("meetings_v3").put({ ...value, accountId: GUEST_ACCOUNT_ID })));
    await legacyStore.clear();
  }
  if (names.includes("recordingChunks")) {
    const legacyStore = transaction.objectStore("recordingChunks");
    const records = await legacyStore.getAll();
    await Promise.all(records.map((value) => transaction.objectStore("recordingChunks_v3").put({ ...value, accountId: GUEST_ACCOUNT_ID })));
    await legacyStore.clear();
  }
  if (names.includes("deletedMeetings")) {
    const legacyStore = transaction.objectStore("deletedMeetings");
    const records = await legacyStore.getAll();
    await Promise.all(records.map((value) => transaction.objectStore("deletedMeetings_v3").put({ ...value, accountId: GUEST_ACCOUNT_ID })));
    await legacyStore.clear();
  }
  await transaction.done;
});

async function openScopedDatabase() {
  await legacyMigration;
  return database;
}

function scopedKey(accountId: string, meetingId: string): [string, string] {
  return [accountId || GUEST_ACCOUNT_ID, meetingId];
}

export async function claimLegacyGuestData(accountId: string): Promise<void> {
  const scope = accountId.trim();
  if (!scope || scope === GUEST_ACCOUNT_ID) return;
  const db = await openScopedDatabase();
  const transaction = db.transaction(["meetings_v3", "recordingChunks_v3", "deletedMeetings_v3"], "readwrite");
  const meetingStore = transaction.objectStore("meetings_v3");
  const chunkStore = transaction.objectStore("recordingChunks_v3");
  const deletionStore = transaction.objectStore("deletedMeetings_v3");
  const [meetings, chunks, deletions] = await Promise.all([
    meetingStore.getAll(),
    chunkStore.getAll(),
    deletionStore.getAll()
  ]);
  for (const meeting of meetings.filter((item) => item.accountId === GUEST_ACCOUNT_ID)) {
    const existing = await meetingStore.get(scopedKey(scope, meeting.id));
    if (!existing || existing.updatedAt < meeting.updatedAt) {
      await meetingStore.put({ ...meeting, accountId: scope });
    }
    await meetingStore.delete(scopedKey(GUEST_ACCOUNT_ID, meeting.id));
  }
  for (const chunk of chunks.filter((item) => item.accountId === GUEST_ACCOUNT_ID)) {
    await chunkStore.put({ ...chunk, accountId: scope });
    await chunkStore.delete([GUEST_ACCOUNT_ID, chunk.meetingId, chunk.sequence]);
  }
  for (const deletion of deletions.filter((item) => item.accountId === GUEST_ACCOUNT_ID)) {
    const existing = await deletionStore.get(scopedKey(scope, deletion.id));
    if (!existing || existing.deletedAt < deletion.deletedAt) {
      await deletionStore.put({ ...deletion, accountId: scope });
    }
    await deletionStore.delete(scopedKey(GUEST_ACCOUNT_ID, deletion.id));
  }
  await transaction.done;
}

export async function listMeetings(accountId = GUEST_ACCOUNT_ID): Promise<Meeting[]> {
  const db = await openScopedDatabase();
  const meetings = await db.getAll("meetings_v3");
  return meetings
    .filter((meeting) => meeting.accountId === (accountId || GUEST_ACCOUNT_ID))
    .sort((left, right) => right.updatedAt - left.updatedAt);
}

export async function saveMeeting(meeting: Meeting, accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const db = await openScopedDatabase();
  await db.put("meetings_v3", { ...meeting, accountId: accountId || GUEST_ACCOUNT_ID });
}

export async function deleteMeetingRecord(meetingId: string, accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const scope = accountId || GUEST_ACCOUNT_ID;
  const db = await openScopedDatabase();
  const transaction = db.transaction(["meetings_v3", "recordingChunks_v3", "deletedMeetings_v3"], "readwrite");
  await transaction.objectStore("meetings_v3").delete(scopedKey(scope, meetingId));
  const chunkKeys = await transaction.objectStore("recordingChunks_v3").index("by-account-meeting").getAllKeys([scope, meetingId]);
  await Promise.all(chunkKeys.map((key) => transaction.objectStore("recordingChunks_v3").delete(key)));
  await transaction.objectStore("deletedMeetings_v3").put({ accountId: scope, id: meetingId, deletedAt: Date.now() });
  await transaction.done;
}

export async function clearMeetings(accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const scope = accountId || GUEST_ACCOUNT_ID;
  const db = await openScopedDatabase();
  const transaction = db.transaction(["meetings_v3", "recordingChunks_v3", "deletedMeetings_v3"], "readwrite");
  const meetings = await transaction.objectStore("meetings_v3").getAll();
  const deletedAt = Date.now();
  await Promise.all(meetings.filter((meeting) => meeting.accountId === scope)
    .map((meeting) => transaction.objectStore("deletedMeetings_v3").put({ accountId: scope, id: meeting.id, deletedAt })));
  const chunkKeys = await transaction.objectStore("recordingChunks_v3").index("by-account-meeting").getAllKeys(IDBKeyRange.bound([scope, ""], [scope, "\uffff"]));
  await Promise.all(chunkKeys.map((key) => transaction.objectStore("recordingChunks_v3").delete(key)));
  await Promise.all(meetings.filter((meeting) => meeting.accountId === scope)
    .map((meeting) => transaction.objectStore("meetings_v3").delete([scope, meeting.id])));
  await transaction.done;
}

export async function listDeletedMeetings(accountId = GUEST_ACCOUNT_ID): Promise<DeletedMeetingRecord[]> {
  const db = await openScopedDatabase();
  const records = await db.getAll("deletedMeetings_v3");
  return records.filter((record) => record.accountId === (accountId || GUEST_ACCOUNT_ID));
}

export async function acknowledgeDeletedMeeting(meetingId: string, accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const db = await openScopedDatabase();
  await db.delete("deletedMeetings_v3", scopedKey(accountId || GUEST_ACCOUNT_ID, meetingId));
}

export async function clearRecordingChunks(meetingId: string, accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const scope = accountId || GUEST_ACCOUNT_ID;
  const db = await openScopedDatabase();
  const transaction = db.transaction("recordingChunks_v3", "readwrite");
  const store = transaction.store;
  const keys = await store.index("by-account-meeting").getAllKeys([scope, meetingId]);
  await Promise.all(keys.map((key) => store.delete(key)));
  await transaction.done;
}

export async function saveRecordingChunk(meetingId: string, sequence: number, blob: Blob, accountId = GUEST_ACCOUNT_ID): Promise<void> {
  const db = await openScopedDatabase();
  const scope = accountId || GUEST_ACCOUNT_ID;
  await db.put("recordingChunks_v3", { accountId: scope, meetingId, sequence, blob });
}

export async function assembleRecording(meetingId: string, fallbackType: string, accountId = GUEST_ACCOUNT_ID): Promise<Blob | undefined> {
  const scope = accountId || GUEST_ACCOUNT_ID;
  const db = await openScopedDatabase();
  const chunks = await db.getAllFromIndex("recordingChunks_v3", "by-account-meeting", [scope, meetingId]);
  if (chunks.length === 0) return undefined;
  chunks.sort((left, right) => left.sequence - right.sequence);
  const mimeType = chunks.find((chunk) => chunk.blob.type)?.blob.type || fallbackType;
  return new Blob(chunks.map((chunk) => chunk.blob), { type: mimeType });
}

export async function recoverInterruptedRecordings(accountId = GUEST_ACCOUNT_ID): Promise<Meeting[]> {
  const scope = accountId || GUEST_ACCOUNT_ID;
  const db = await openScopedDatabase();
  const chunks = (await db.getAll("recordingChunks_v3")).filter((chunk) => chunk.accountId === scope);
  const meetingIds = [...new Set(chunks.map((chunk) => chunk.meetingId))];
  const recovered: Meeting[] = [];
  for (const meetingId of meetingIds) {
    const meeting = await db.get("meetings_v3", scopedKey(scope, meetingId));
    const audio = await assembleRecording(meetingId, "audio/webm", scope);
    if (!meeting || !audio || meeting.audio) continue;
    const updated: Meeting = {
      ...meeting,
      audio,
      audioType: audio.type,
      audioName: `recovered-${meetingId}`,
      updatedAt: Date.now()
    };
    await db.put("meetings_v3", { ...updated, accountId: scope });
    await clearRecordingChunks(meetingId, scope);
    recovered.push(updated);
  }
  return recovered;
}
