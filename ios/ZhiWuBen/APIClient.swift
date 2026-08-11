import Foundation

enum APIClientError: LocalizedError {
    case invalidEndpoint
    case server(status: Int, detail: String)
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint:
            return "服务地址格式无效"
        case let .server(status, detail):
            return detail.isEmpty ? "服务请求失败（HTTP \(status)）" : detail
        case .emptyResponse:
            return "服务端没有返回有效内容"
        }
    }
}

struct APIClient {
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared) {
        self.session = session
    }

    func login(endpoint: String, username: String, password: String) async throws -> AuthSession {
        try await authenticate(endpoint: endpoint, path: "auth/login", username: username, password: password)
    }

    func register(endpoint: String, username: String, password: String) async throws -> AuthSession {
        try await authenticate(endpoint: endpoint, path: "auth/register", username: username, password: password)
    }

    func refreshSession(endpoint: String, accessToken: String) async throws -> SessionCredentials {
        let request = try accountRequest(endpoint: endpoint, path: "account/session", token: accessToken)
        return try await send(request)
    }

    func transcribe(
        endpoint: String,
        sttToken: String,
        meetingID: String,
        audioURL: URL
    ) async throws -> String {
        let base = try serviceURL(endpoint)
        let url = base.appendingPathComponent("transcribe")
        let boundary = "Boundary-\(UUID().uuidString)"
        let audio = try Data(contentsOf: audioURL)
        var body = Data()
        body.append("--\(boundary)\r\n".utf8Data)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(audioURL.lastPathComponent)\"\r\n".utf8Data)
        body.append("Content-Type: audio/mp4\r\n\r\n".utf8Data)
        body.append(audio)
        body.append("\r\n--\(boundary)--\r\n".utf8Data)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 60 * 20
        request.setValue("Bearer \(sttToken)", forHTTPHeaderField: "Authorization")
        request.setValue(meetingID, forHTTPHeaderField: "X-Meeting-Id")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let response: TranscriptResponse = try await send(request)
        let text = response.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw APIClientError.emptyResponse }
        return text
    }

    func generateReport(
        endpoint: String,
        agentToken: String,
        provider: String,
        reasoningEffort: String,
        transcript: String,
        template: MeetingTemplate
    ) async throws -> String {
        let requestPayload = AgentRequest(
            provider: provider,
            operation: "generate_report",
            modelReasoningEffort: reasoningEffort,
            effort: reasoningEffort,
            transcript: transcript,
            templateName: template.name,
            templateContent: template.content
        )
        let requestJSON = try encoder.encode(requestPayload)
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".utf8Data)
        body.append("Content-Disposition: form-data; name=\"request\"\r\n".utf8Data)
        body.append("Content-Type: application/json; charset=utf-8\r\n\r\n".utf8Data)
        body.append(requestJSON)
        body.append("\r\n--\(boundary)--\r\n".utf8Data)

        var request = try accountRequest(endpoint: endpoint, path: "agent", token: agentToken)
        request.httpMethod = "POST"
        request.timeoutInterval = 60 * 12
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let response: AgentResponse = try await send(request)
        let report = response.report?.rawContent ?? response.report?.content ?? response.text
        let output = report.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !output.isEmpty else { throw APIClientError.emptyResponse }
        return output
    }

    private func authenticate(
        endpoint: String,
        path: String,
        username: String,
        password: String
    ) async throws -> AuthSession {
        let payload = Credentials(username: username, password: password)
        var request = try accountRequest(endpoint: endpoint, path: path)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(payload)
        return try await send(request)
    }

    private func accountRequest(endpoint: String, path: String, token: String? = nil) throws -> URLRequest {
        var base = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !base.isEmpty else { throw APIClientError.invalidEndpoint }
        if !base.hasSuffix("/api") { base += "/api" }
        guard let url = URL(string: base)?.appendingPathComponent(path) else {
            throw APIClientError.invalidEndpoint
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 40
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func serviceURL(_ endpoint: String) throws -> URL {
        let value = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: value), !value.isEmpty else { throw APIClientError.invalidEndpoint }
        return url
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIClientError.emptyResponse }
        guard (200...299).contains(http.statusCode) else {
            throw APIClientError.server(status: http.statusCode, detail: serverDetail(data))
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIClientError.emptyResponse
        }
    }

    private func serverDetail(_ data: Data) -> String {
        if let envelope = try? decoder.decode(ErrorEnvelope.self, from: data), !envelope.detail.isEmpty {
            return envelope.detail
        }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}

private struct Credentials: Encodable {
    let username: String
    let password: String
}

struct SessionCredentials: Decodable {
    let agentAccessToken: String
    let sttAccessToken: String?
    let expiresAt: Int
    let user: AccountProfile

    enum CodingKeys: String, CodingKey {
        case agentAccessToken = "agent_access_token"
        case sttAccessToken = "stt_access_token"
        case expiresAt = "expires_at"
        case user
    }
}

private struct TranscriptResponse: Decodable {
    let text: String
}

private struct AgentRequest: Encodable {
    let provider: String
    let operation: String
    let modelReasoningEffort: String
    let effort: String
    let transcript: String
    let templateName: String
    let templateContent: String

    enum CodingKeys: String, CodingKey {
        case provider, operation, effort, transcript
        case modelReasoningEffort = "model_reasoning_effort"
        case templateName
        case templateContent
    }
}

private struct AgentResponse: Decodable {
    let text: String
    let report: AgentReport?
}

private struct AgentReport: Decodable {
    let rawContent: String?
    let content: String?

    enum CodingKeys: String, CodingKey {
        case rawContent = "rawContent"
        case content
    }
}

private struct ErrorEnvelope: Decodable {
    let detail: String
}

private extension String {
    var utf8Data: Data { Data(utf8) }
}
