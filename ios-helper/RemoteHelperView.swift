import SwiftUI
import ReplayKit
import UIKit
import Foundation

private struct PendingRemoteSession: Identifiable {
    let id: String
    let requesterID: String
    let reason: String
}

struct RemoteHelperView: View {
    @State private var serverURL = "https://remote.example.com"
    @State private var displayName = UIDevice.current.name
    @State private var ownerJWT = ""
    @State private var deviceID = ""
    @State private var status = "尚未注册云端设备"
    @State private var credential: RemoteDeviceCredential?
    @State private var socket: URLSessionWebSocketTask?
    @State private var receiveTask: Task<Void, Never>?
    @State private var pendingSession: PendingRemoteSession?
    @State private var showBroadcastPicker = false

    var body: some View {
        Form {
            Section("设备接入") {
                TextField("云端 HTTPS 地址", text: $serverURL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("设备名称", text: $displayName)
                SecureField("OIDC 访问令牌", text: $ownerJWT)
                Button("注册本设备") { Task { await registerDevice() } }
                if !deviceID.isEmpty { Text("设备 ID：\(deviceID)").font(.caption).textSelection(.enabled) }
            }
            Section("远程连接") {
                Button("发送在线心跳") { Task { await heartbeat() } }.disabled(credential == nil)
                Button(socket == nil ? "连接云端，等待申请" : "断开云端连接") {
                    if socket == nil { Task { await connectSignaling() } } else { disconnectSignaling() }
                }.disabled(credential == nil)
                if showBroadcastPicker {
                    BroadcastPickerView(extensionBundleIdentifier: "com.example.GamePilotBroadcast")
                        .frame(height: 54)
                    Text("请点击系统广播按钮，主动开始屏幕共享。停止广播即可结束采集。").font(.caption)
                }
            }
            Section("状态") { Text(status).font(.footnote).textSelection(.enabled) }
        }
        .navigationTitle("GamePilot Helper")
        .alert(item: $pendingSession) { request in
            Alert(
                title: Text("远程连接申请"),
                message: Text("申请账号：\(request.requesterID)\n申请原因：\(request.reason)\n\n是否允许本次连接？"),
                primaryButton: .default(Text("允许本次连接")) { Task { await decide(request, approve: true) } },
                secondaryButton: .cancel(Text("拒绝")) { Task { await decide(request, approve: false) } }
            )
        }
        .task { loadCredential() }
        .onDisappear { disconnectSignaling() }
    }

    @MainActor private func registerDevice() async {
        do {
            let result = try await RemoteAPIClient.register(serverURL: serverURL, ownerJWT: ownerJWT, displayName: displayName)
            let saved = RemoteDeviceCredential(serverURL: serverURL, deviceID: result.deviceId, deviceToken: result.deviceToken)
            try RemoteDeviceCredentialStore.save(saved)
            credential = saved
            deviceID = saved.deviceID
            ownerJWT = ""
            status = "设备注册成功，凭证已保存到 iOS Keychain。"
        } catch { status = "注册失败：\(error.localizedDescription)" }
    }

    @MainActor private func loadCredential() {
        do {
            credential = try RemoteDeviceCredentialStore.load()
            deviceID = credential?.deviceID ?? ""
            if credential != nil { status = "已读取 Keychain 中的设备凭证。" }
        } catch { status = "读取设备凭证失败：\(error.localizedDescription)" }
    }

    @MainActor private func heartbeat() async {
        guard let credential else { return }
        do { try await RemoteAPIClient.heartbeat(credential); status = "云端已确认设备在线。" }
        catch { status = "心跳失败：\(error.localizedDescription)" }
    }

    @MainActor private func connectSignaling() async {
        guard let credential else { return }
        do {
            let ticket = try await RemoteAPIClient.ticket(credential)
            guard var components = URLComponents(string: credential.serverURL), components.scheme == "https" else { throw URLError(.badURL) }
            components.scheme = "wss"
            components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/v1/realtime"
            components.queryItems = [URLQueryItem(name: "ticket", value: ticket.ticket)]
            guard let url = components.url else { throw URLError(.badURL) }
            let task = URLSession.shared.webSocketTask(with: url)
            socket = task
            task.resume()
            status = "已连接云端，等待设备主人审批请求。"
            receiveTask = Task { await receiveMessages(from: task) }
        } catch { status = "信令连接失败：\(error.localizedDescription)" }
    }

    private func receiveMessages(from task: URLSessionWebSocketTask) async {
        while !Task.isCancelled {
            do {
                let message = try await task.receive()
                let data: Data
                switch message {
                case .string(let text): data = Data(text.utf8)
                case .data(let bytes): data = bytes
                @unknown default: continue
                }
                guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                if object["type"] as? String == "session.requested",
                   let sessionID = object["sessionId"] as? String {
                    let reason = object["reason"] as? String ?? "远程用户请求连接"
                    let requesterID = object["requesterId"] as? String ?? "未知申请账号"
                    await MainActor.run { pendingSession = PendingRemoteSession(id: sessionID, requesterID: requesterID, reason: reason) }
                }
            } catch {
                await MainActor.run { status = "云端信令已断开：\(error.localizedDescription)"; socket = nil }
                break
            }
        }
    }

    @MainActor private func decide(_ request: PendingRemoteSession, approve: Bool) async {
        guard let credential else { return }
        do {
            try await RemoteAPIClient.decide(credential, sessionID: request.id, approve: approve)
            if approve {
                showBroadcastPicker = true
                status = "已批准本次会话。请使用下方系统广播按钮主动开始 ReplayKit 屏幕共享；目前 WebRTC 视频轨道尚未接入。"
            } else { status = "已拒绝本次连接申请。" }
        } catch { status = "提交审批失败：\(error.localizedDescription)" }
    }

    private func disconnectSignaling() {
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
    }
}

private struct BroadcastPickerView: UIViewRepresentable {
    let extensionBundleIdentifier: String
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = extensionBundleIdentifier
        picker.showsMicrophoneButton = false
        return picker
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}