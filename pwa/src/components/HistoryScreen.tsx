import { Check, Edit3, Search, Trash2, X } from "lucide-react";
import { useMemo, useState } from "react";
import { templateFor } from "../templates";
import type { Meeting } from "../types";
import { formatMeetingDate } from "../lib/format";

interface HistoryScreenProps {
  meetings: Meeting[];
  onOpen: (meeting: Meeting) => void;
  onRename: (meeting: Meeting, title: string) => void;
  onDelete: (meeting: Meeting) => void;
  onClear: () => void;
}

export function HistoryScreen({ meetings, onOpen, onRename, onDelete, onClear }: HistoryScreenProps) {
  const [query, setQuery] = useState("");
  const [editingId, setEditingId] = useState<string>();
  const [draftTitle, setDraftTitle] = useState("");
  const filtered = useMemo(() => {
    const clean = query.trim().toLowerCase();
    if (!clean) return meetings;
    return meetings.filter((meeting) => `${meeting.title} ${meeting.transcript} ${meeting.report}`.toLowerCase().includes(clean));
  }, [meetings, query]);

  return (
    <div className="screen history-screen">
      <header className="screen-header">
        <div><span className="eyebrow">本机会议库</span><h1>全部记录</h1></div>
        {meetings.length > 0 && <button className="danger-text-button" onClick={onClear}>清空</button>}
      </header>
      <label className="search-field">
        <Search />
        <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题或内容" />
      </label>
      <div className="meeting-list history-list">
        {filtered.map((meeting) => (
          <article className="history-row" key={meeting.id}>
            <button className="history-main" onClick={() => onOpen(meeting)}>
              <span className={`meeting-type type-${meeting.templateKey}`}>{templateFor(meeting.templateKey).name.slice(0, 2)}</span>
              <span className="meeting-copy">
                {editingId === meeting.id ? (
                  <input
                    value={draftTitle}
                    onClick={(event) => event.stopPropagation()}
                    onChange={(event) => setDraftTitle(event.target.value)}
                    autoFocus
                  />
                ) : <strong>{meeting.title}</strong>}
                <small>{formatMeetingDate(meeting.updatedAt)} · {meeting.transcript.length.toLocaleString()} 字</small>
              </span>
            </button>
            <div className="row-tools">
              {editingId === meeting.id ? (
                <>
                  <button className="icon-button" title="保存标题" onClick={() => { onRename(meeting, draftTitle); setEditingId(undefined); }}><Check /></button>
                  <button className="icon-button" title="取消编辑" onClick={() => setEditingId(undefined)}><X /></button>
                </>
              ) : (
                <button className="icon-button" title="编辑标题" onClick={() => { setEditingId(meeting.id); setDraftTitle(meeting.title); }}><Edit3 /></button>
              )}
              <button className="icon-button danger" title="删除会议" onClick={() => onDelete(meeting)}><Trash2 /></button>
            </div>
          </article>
        ))}
        {filtered.length === 0 && <div className="empty-state">没有匹配的会议记录</div>}
      </div>
    </div>
  );
}
