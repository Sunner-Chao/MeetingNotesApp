import type { AuthSession, Meeting, RuntimeConfig } from "../types";
import {
  CloudMeetingDeletedError,
  downloadCloudMeetingImage,
  deleteCloudMeeting,
  deleteCloudMeetingImage,
  fetchCloudMeetings,
  listCloudMeetingImages,
  saveCloudMeeting,
  uploadCloudMeetingImage,
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
    deletedImageIds: local?.deletedImageIds ?? [],
    audio: local?.audio,
    audioName: local?.audioName,
    audioType: local?.audioType
  };
}

async function synchronizeMeetingImages(
  config: RuntimeConfig,
  session: AuthSession,
  meeting: Meeting
): Promise<Meeting> {
  const remote = await listCloudMeetingImages(config, session, meeting.id);
  const remoteById = new Map(remote.map((image) => [image.image_id, image]));
  const localById = new Map(meeting.images.map((image) => [image.id, image]));
  const imagesById = new Map(meeting.images.map((image) => [image.id, image]));

  for (const image of meeting.images) {
    const remoteImage = remoteById.get(image.id);
    if (!remoteImage || (image.updatedAt || 0) > remoteImage.updated_at) {
      await uploadCloudMeetingImage(config, session, meeting.id, image);
    }
  }
  for (const remoteImage of remote) {
    if (meeting.deletedImageIds?.includes(remoteImage.image_id)) {
      await deleteCloudMeetingImage(config, session, meeting.id, remoteImage.image_id);
      continue;
    }
    const localImage = localById.get(remoteImage.image_id);
    if (localImage && (localImage.updatedAt || 0) >= remoteImage.updated_at) continue;
    const blob = await downloadCloudMeetingImage(config, session, meeting.id, remoteImage);
    imagesById.set(remoteImage.image_id, {
      id: remoteImage.image_id,
      name: remoteImage.filename,
      type: remoteImage.content_type,
      blob,
      updatedAt: remoteImage.updated_at
    });
  }
  const images = [...imagesById.values()].filter((image) => !meeting.deletedImageIds?.includes(image.id));
  return images.length === meeting.images.length ? meeting : { ...meeting, images };
}

async function removeLocalMeeting(meetingId: string, accountId: string): Promise<void> {
  await deleteMeetingRecord(meetingId, accountId);
  await acknowledgeDeletedMeeting(meetingId, accountId);
}

export async function synchronizeCloudMeetings(
  config: RuntimeConfig,
  session: AuthSession
): Promise<Meeting[]> {
  const accountId = session.user.id;
  const pendingDeletes = await listDeletedMeetings(accountId);
  for (const deletion of pendingDeletes) {
    await deleteCloudMeeting(config, session, deletion.id, deletion.deletedAt);
    await acknowledgeDeletedMeeting(deletion.id, accountId);
  }

  const snapshot = await fetchCloudMeetings(config, session);
  const deleted = new Map(snapshot.deleted.map((item) => [item.meeting_id, item.deleted_at]));
  const remote = new Map(snapshot.meetings.map((meeting) => [meeting.id, meeting]));
  const localMeetings = await listMeetings(accountId);

  for (const local of localMeetings) {
    const deletedAt = deleted.get(local.id);
    if (deletedAt !== undefined && local.updatedAt <= deletedAt) {
      await removeLocalMeeting(local.id, accountId);
      continue;
    }
    const cloud = remote.get(local.id);
    if (!cloud || local.updatedAt > cloud.updated_at) {
      try {
        const saved = await saveCloudMeeting(config, session, local);
        if (saved.updated_at > local.updatedAt) await saveMeeting(fromCloud(saved, local), accountId);
      } catch (error) {
        if (error instanceof CloudMeetingDeletedError) {
          await removeLocalMeeting(local.id, accountId);
          continue;
        }
        throw error;
      }
      remote.delete(local.id);
      continue;
    }
    if (cloud.updated_at > local.updatedAt) await saveMeeting(fromCloud(cloud, local), accountId);
    remote.delete(local.id);
  }

  for (const cloud of remote.values()) await saveMeeting(fromCloud(cloud), accountId);
  const synchronized = await listMeetings(accountId);
  const withImages: Meeting[] = [];
  for (const meeting of synchronized) {
    const enriched = await synchronizeMeetingImages(config, session, meeting);
    const cleaned = enriched.deletedImageIds?.length
      ? { ...enriched, deletedImageIds: undefined }
      : enriched;
    if (cleaned !== meeting) await saveMeeting(cleaned, accountId);
    withImages.push(cleaned);
  }
  return withImages;
}
