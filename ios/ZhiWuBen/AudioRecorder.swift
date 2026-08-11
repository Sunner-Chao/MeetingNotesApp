import AVFoundation
import Combine
import Foundation

@MainActor
final class AudioRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published private(set) var isRecording = false
    @Published private(set) var elapsedSeconds: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var startedAt: Date?

    func start(meetingID: String) async throws -> String {
        guard await requestPermission() else {
            throw AudioRecorderError.permissionDenied
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)

        let fileName = "\(meetingID)-\(Int(Date().timeIntervalSince1970)).m4a"
        let url = try LocalStorage.audioDirectory().appendingPathComponent(fileName)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.delegate = self
        recorder.isMeteringEnabled = true
        guard recorder.record() else { throw AudioRecorderError.startFailed }
        self.recorder = recorder
        self.startedAt = Date()
        self.elapsedSeconds = 0
        self.isRecording = true
        startTimer()
        return fileName
    }

    @discardableResult
    func stop() -> TimeInterval {
        let duration = recorder?.currentTime ?? elapsedSeconds
        recorder?.stop()
        recorder = nil
        timer?.invalidate()
        timer = nil
        startedAt = nil
        elapsedSeconds = duration
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return duration
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let startedAt = self.startedAt else { return }
                self.elapsedSeconds = Date().timeIntervalSince(startedAt)
            }
        }
    }

    private func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}

enum AudioRecorderError: LocalizedError {
    case permissionDenied
    case startFailed

    var errorDescription: String? {
        switch self {
        case .permissionDenied:
            return "请允许智悟本使用麦克风"
        case .startFailed:
            return "无法启动录音"
        }
    }
}
