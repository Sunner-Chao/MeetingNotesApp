import SwiftUI
import UniformTypeIdentifiers

struct MeetingEditorView: View {
    @EnvironmentObject private var store: AppStore
    let meetingID: String

    @StateObject private var recorder = AudioRecorder()
    @State private var title = ""
    @State private var templateName = MeetingTemplate.projectManagement.name
    @State private var transcript = ""
    @State private var showImporter = false
    @State private var showDeleteConfirmation = false

    private var currentMeeting: Meeting? { store.meeting(id: meetingID) }

    var body: some View {
        Group {
            if let meeting = currentMeeting {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        templateSection(meeting: meeting)
                        recordingSection(meeting: meeting)
                        transcriptSection(meeting: meeting)
                        reportSection(meeting: meeting)
                    }
                    .padding(16)
                }
                .navigationTitle(title.isEmpty ? meeting.title : title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("保存修改") { saveDraft() }
                            Button("删除会议", role: .destructive) { showDeleteConfirmation = true }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
                .confirmationDialog("删除这条会议记录？", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
                    Button("删除", role: .destructive) { store.deleteMeeting(id: meetingID) }
                }
                .fileImporter(
                    isPresented: $showImporter,
                    allowedContentTypes: [.plainText, .text],
                    allowsMultipleSelection: false
                ) { result in
                    importText(result)
                }
                .onAppear { load(meeting) }
                .onDisappear {
                    if recorder.isRecording { _ = recorder.stop() }
                }
            } else {
                ContentUnavailableView("会议不存在", systemImage: "exclamationmark.triangle")
            }
        }
    }

    private func templateSection(meeting: Meeting) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("纪要模板", systemImage: "doc.text.fill")
                .font(.headline)
            Picker("纪要模板", selection: $templateName) {
                ForEach(MeetingTemplate.all) { template in
                    Text("\(template.name) · \(template.subtitle)").tag(template.name)
                }
            }
            .pickerStyle(.menu)
            Text(MeetingTemplate.named(templateName).subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Divider()
            TextField("会议名称", text: $title)
                .textFieldStyle(.roundedBorder)
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func recordingSection(meeting: Meeting) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("会议音频", systemImage: "waveform")
                    .font(.headline)
                Spacer()
                if let fileName = meeting.audioFileName, let url = LocalStorage.audioURL(fileName: fileName) {
                    ShareLink(item: url) {
                        Label("分享", systemImage: "square.and.arrow.up")
                    }
                    .font(.subheadline)
                }
            }
            HStack(spacing: 12) {
                Image(systemName: recorder.isRecording ? "record.circle.fill" : "mic.circle.fill")
                    .font(.system(size: 42))
                    .foregroundStyle(recorder.isRecording ? Color.red : Color.accentColor)
                VStack(alignment: .leading, spacing: 4) {
                    Text(recorder.isRecording ? "正在录音" : (meeting.audioFileName == nil ? "尚未录音" : "音频已保存"))
                        .font(.headline)
                    Text(formatDuration(recorder.isRecording ? recorder.elapsedSeconds : meeting.durationSeconds))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            Button {
                toggleRecording(meeting)
            } label: {
                Label(recorder.isRecording ? "结束录音" : "开始录音", systemImage: recorder.isRecording ? "stop.fill" : "mic.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(recorder.isRecording ? .red : .accentColor)
            .disabled(store.isWorking)

            if meeting.audioFileName != nil {
                Button {
                    saveDraft()
                    Task {
                        await store.refreshSession()
                        await store.transcribe(meetingID: meetingID)
                        reloadFromStore()
                    }
                } label: {
                    HStack {
                        if store.isWorking { ProgressView() }
                        Label("生成最终转录", systemImage: "text.quote")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(store.isWorking || recorder.isRecording)
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func transcriptSection(meeting: Meeting) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("转写内容", systemImage: "text.alignleft")
                    .font(.headline)
                Spacer()
                Button("导入文本") { showImporter = true }
                    .font(.subheadline)
            }
            TextEditor(text: $transcript)
                .frame(minHeight: 220)
                .padding(8)
                .background(Color(uiColor: .tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            HStack {
                Text("\(transcript.count) 字")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("保存转写") { saveDraft() }
                    .font(.subheadline)
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func reportSection(meeting: Meeting) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("会议纪要", systemImage: "sparkles")
                    .font(.headline)
                Spacer()
                Text("智能体 · 小Woo")
                    .font(.caption)
                    .foregroundStyle(.tint)
            }
            Button {
                saveDraft()
                Task {
                    await store.refreshSession()
                    await store.generateReport(meetingID: meetingID)
                    reloadFromStore()
                }
            } label: {
                HStack {
                    if store.isWorking { ProgressView().tint(.white) }
                    Label("生成会议纪要", systemImage: "wand.and.stars")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(store.isWorking || transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || recorder.isRecording)

            if let report = meeting.report, !report.isEmpty {
                Text(report)
                    .textSelection(.enabled)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Color(uiColor: .tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
                ShareLink(item: report) {
                    Label("分享纪要文本", systemImage: "square.and.arrow.up")
                }
                .font(.subheadline)
            } else {
                Text("转写确认后，由小Woo按所选模板整理会议纪要。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func load(_ meeting: Meeting) {
        title = meeting.title
        templateName = meeting.templateName
        transcript = meeting.transcript
    }

    private func reloadFromStore() {
        if let meeting = store.meeting(id: meetingID) { load(meeting) }
    }

    private func saveDraft() {
        guard var meeting = currentMeeting else { return }
        meeting.title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        meeting.templateName = templateName
        meeting.transcript = transcript
        store.saveMeeting(meeting)
    }

    private func toggleRecording(_ meeting: Meeting) {
        if recorder.isRecording {
            let duration = recorder.stop()
            var updated = meeting
            updated.durationSeconds = duration
            store.saveMeeting(updated)
            reloadFromStore()
            return
        }
        Task {
            do {
                let fileName = try await recorder.start(meetingID: meetingID)
                var updated = meeting
                updated.audioFileName = fileName
                updated.durationSeconds = 0
                store.saveMeeting(updated)
            } catch {
                store.statusMessage = error.localizedDescription
            }
        }
    }

    private func importText(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let granted = url.startAccessingSecurityScopedResource()
            defer { if granted { url.stopAccessingSecurityScopedResource() } }
            transcript = try String(contentsOf: url, encoding: .utf8)
            saveDraft()
        } catch {
            store.statusMessage = "导入文本失败：\(error.localizedDescription)"
        }
    }

    private func formatDuration(_ value: TimeInterval) -> String {
        let total = Int(value.rounded(.down))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}
