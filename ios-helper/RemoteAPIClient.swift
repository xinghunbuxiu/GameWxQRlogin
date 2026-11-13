import Foundation

struct RemoteRegistration: Decodable {
    let deviceId: String
    let deviceToken: String
}

struct RemoteTicket: Decodable { let ticket: String }

private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum RemoteAPIClient {
    private static let httpSession = URLSession(
        configuration: .ephemeral,
        delegate: RedirectBlocker(),
        delegateQueue: nil
    )
    static func register(serverURL: String, ownerJWT: String, displayName: String) async throws -> RemoteRegistration {
        let body: [String: Any] = [
            "displayName": displayName,
            "platform": "ios",
            "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev",
            "capabilities": ["device.heartbeat", "session.owner_approval", "screen.broadcast.replaykit"]
        ]
        return try await post(serverURL: serverURL, path: "/v1/devices/register", bearer: ownerJWT, body: body)
    }

    static func ticket(_ credential: RemoteDeviceCredential) async throws -> RemoteTicket {
        try await post(serverURL: credential.serverURL, path: "/v1/realtime/ticket", bearer: credential.deviceToken, body: [:])
    }

    static func decide(_ credential: RemoteDeviceCredential, sessionID: String, approve: Bool) async throws {
        let _: EmptyResponse = try await post(serverURL: credential.serverURL, path: "/v1/sessions/\(sessionID)/decision", bearer: credential.deviceToken, body: ["decision": approve ? "approve" : "reject"])
    }

    static func heartbeat(_ credential: RemoteDeviceCredential) async throws {
        let _: EmptyResponse = try await post(serverURL: credential.serverURL, path: "/v1/devices/heartbeat", bearer: credential.deviceToken, body: ["status": "online"])
    }

    private struct EmptyResponse: Decodable {}

    private static func post<T: Decodable>(serverURL: String, path: String, bearer: String, body: [String: Any]) async throws -> T {
        guard var components = URLComponents(string: serverURL), components.scheme?.lowercased() == "https", components.host != nil, components.user == nil, components.query == nil, components.fragment == nil else {
            throw URLError(.badURL)
        }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path
        guard let url = components.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await httpSession.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? "HTTP_ERROR"
            throw NSError(domain: "GamePilotRemote", code: (response as? HTTPURLResponse)?.statusCode ?? -1, userInfo: [NSLocalizedDescriptionKey: "服务器请求失败：\(code)"])
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}