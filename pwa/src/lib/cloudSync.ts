import type { AuthSession, Meeting, RuntimeConfig } from "../types";
import {
  CloudMeetingDeletedError,
  deleteCloudMeeting,
  fetchCloudMeetings,
  saveCloudMeeting,
  type CloudMeeting
} from "./api";
import {
  acknowledgeDeletedMeeting,
  deleteMeetingRecord,
  listDeletedMeetings,
  listMeetings,
  saveMeeting
} from "./db";

function fromCloud(remote: CloudMeeting, local?: Meeting): Meeting {
  return {
    id: remote.id,
    title: remote.title,
    templateKey: remote.template_key,
    createdAt: remote.created_at,
    updatedAt: remote.updated_at,
    durationSeconds: remote.duration_seconds,
    transcript: remote.transcript,
    report: remote.report,
    images: local?.images ?? [],
    audio: local?.audio,
    audioName: local?.audioName,
    audioType: local?.audioType
  };
}

async function removeLocalMeeting(meetingId: string): Promise<void> {
  await deleteMeetingRecord(meetingId);
  await acknowledgeDeletedMeeting(meetingId);
}

export async function synchronizeCloudMeetings(
  config: RuntimeConfig,
  session: AuthSession
): Promise<Meeting[]> {
  const pendingDeletes = await listDeletedMeetings();
  for (const deletion of pendingDeletes) {
    await deleteCloudMeeting(config, session, deletion.id, deletion.deletedAt);
    await acknowledgeDeletedMeeting(deletion.id);
  }

  const snapshot = await fetchCloudMeetings(config, session);
  const deleted = new Map(snapshot.deleted.map((item) => [item.meeting_id, item.deleted_at]));
  const remote = new Map(snapshot.meetings.map((meeting) => [meeting.id, meeting]));
  const localMeetings = await listMeetings();

  for (const local of localMeetings) {
    const deletedAt = deleted.get(local.id);
    if (deletedAt !== undefined && local.updatedAt <= deletedAt) {
      await removeLocalMeeting(local.id);
      continue;
    }
    const cloud = remote.get(local.id);
    if (!cloud || local.updatedAt > cloud.updated_at) {
      try {
        const saved = await saveCloudMeeting(config, session, local);
        if (saved.updated_at > local.updatedAt) await saveMeeting(fromCloud(saved, local));
      } catch (error) {
        if (error instanceof CloudMeetingDeletedError) {
          await removeLocalMeeting(local.id);
          continue;
        }
        throw error;
      }
      remote.delete(local.id);
      continue;
    }
    if (cloud.updated_at > local.updatedAt) await saveMeeting(fromCloud(cloud, local));
    remote.delete(local.id);
  }

  for (const cloud of remote.values()) await saveMeeting(fromCloud(cloud));
  return listMeetings();
}
