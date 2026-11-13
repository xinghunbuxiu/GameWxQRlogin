import { useEffect, useRef, useState } from "react";
import { CheckCircle2, Copy, Link2, LoaderCircle, MonitorPlay, RefreshCw, ShieldCheck, VideoOff } from "lucide-react";

type CloudDevice = {
  id: string;
  display_name: string;
  platform: "android" | "ios";
  status: "online" | "offline" | "busy";
  last_seen_at: string | null;
};
type DeviceListResponse = { devices: CloudDevice[] };
type InviteResponse = { inviteId: string; inviteToken: string; expiresInDays: number };
type SessionResponse = { sessionId: string; status: string; expiresInSeconds: number };
type TicketResponse = { ticket: string };
type SignalPayload =
  | { kind: "offer" | "answer"; data: string }
  | { kind: "ice"; data: { candidate: string; sdpMid?: string | null; sdpMLineIndex?: number | null } };

export default function RemoteSessionPanel() {
  const [serverUrl, setServerUrl] = useState("https://remote.example.com");
  const [jwt, setJwt] = useState("");
  const [devices, setDevices] = useState<CloudDevice[]>([]);
  const [selectedDevice, setSelectedDevice] = useState("");
  const [inviteToken, setInviteToken] = useState("");
  const [createdInvite, setCreatedInvite] = useState<InviteResponse | null>(null);
  const [reason, setReason] = useState("远程协助查看设备屏幕");
  const [status, setStatus] = useState("请配置云端地址并使用账号令牌登录。");
  const [busy, setBusy] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [connected, setConnected] = useState(false);
  const [streamReady, setStreamReady] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const pendingIceRef = useRef<RTCIceCandidateInit[]>([]);
  const sessionRef = useRef<string | null>(null);

  const baseUrl = () => {
    const value = serverUrl.trim().replace(/\/+$/, "");
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash) {
      throw new Error("云端地址必须是有效的 HTTPS URL");
    }
    return value;
  };

  const api = async <T,>(path: string, method = "GET", body?: unknown): Promise<T> => {
    const base = baseUrl();
    const response = await fetch(base + path, {
      method,
      redirect: "error",
      headers: {
        Authorization: "Bearer " + jwt.trim(),
        ...(body === undefined ? {} : { "Content-Type": "application/json" })
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error ?? ("HTTP " + response.status));
    return result as T;
  };

  const loadDevices = async () => {
    setBusy(true);
    try {
      const result = await api<DeviceListResponse>("/v1/devices");
      setDevices(result.devices);
      if (!selectedDevice && result.devices.length) setSelectedDevice(result.devices[0].id);
      setStatus("已读取 " + result.devices.length + " 台本人设备。");
    } catch (error) {
      setStatus("读取设备失败：" + String(error));
    } finally {
      setBusy(false);
    }
  };

  const createInvite = async () => {
    if (!selectedDevice) return;
    setBusy(true);
    setCreatedInvite(null);
    try {
      const result = await api<InviteResponse>("/v1/devices/" + encodeURIComponent(selectedDevice) + "/invites", "POST", { expiresInDays: 7 });
      setCreatedInvite(result);
      setStatus("邀请已创建。将令牌安全发送给远程协助者；持有令牌仍不能绕过设备主人审批。");
    } catch (error) {
      setStatus("创建邀请失败：" + String(error));
    } finally {
      setBusy(false);
    }
  };

  const sendSignal = (payload: SignalPayload) => {
    const socket = socketRef.current;
    const id = sessionRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN || !id) throw new Error("信令连接尚未就绪");
    socket.send(JSON.stringify({ type: "signal", sessionId: id, payload }));
  };

  const closeMedia = () => {
    const peer = peerRef.current;
    peerRef.current = null;
    if (peer) {
      peer.ontrack = null;
      peer.onicecandidate = null;
      peer.close();
    }
    if (videoRef.current) videoRef.current.srcObject = null;
    pendingIceRef.current = [];
    setConnected(false);
    setStreamReady(false);
  };

  const stopSession = async (notifyServer: boolean) => {
    const id = sessionRef.current;
    if (notifyServer && id) {
      try { await api("/v1/sessions/" + id + "/end", "POST", {}); }
      catch (error) { setStatus("结束会话时服务器返回：" + String(error)); }
    }
    closeMedia();
    const socket = socketRef.current;
    socketRef.current = null;
    if (socket) socket.close(1000, "viewer ended session");
    sessionRef.current = null;
    setSessionId(null);
    setStatus("远程会话已结束。");
  };

  const startViewerPeer = async (id: string, socket: WebSocket) => {
    const peer = new RTCPeerConnection({
      iceServers: [{ urls: ["stun:stun.l.google.com:19302"] }]
    });
    peerRef.current = peer;
    peer.addTransceiver("video", { direction: "recvonly" });
    peer.ontrack = event => {
      const stream = event.streams[0];
      if (videoRef.current && stream) {
        videoRef.current.srcObject = stream;
        void videoRef.current.play().catch(() => undefined);
        setStreamReady(true);
      }
    };
    peer.onicecandidate = event => {
      if (!event.candidate) return;
      try {
        sendSignal({
          kind: "ice",
          data: {
            candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex
          }
        });
      } catch (error) {
        setStatus("发送 ICE 失败：" + String(error));
      }
    };
    peer.onconnectionstatechange = () => {
      if (peer.connectionState === "connected") {
        setConnected(true);
        setStatus("WebRTC 已连接，正在接收设备画面。");
      } else if (peer.connectionState === "failed" || peer.connectionState === "disconnected") {
        setConnected(false);
        setStatus("WebRTC 连接状态：" + peer.connectionState);
      }
    };

    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    sendSignal({ kind: "offer", data: offer.sdp ?? "" });
    setStatus("设备主人已批准，正在协商 WebRTC 媒体通道…");
  };

  const handleSignal = async (message: Record<string, unknown>) => {
    const payload = message.payload as SignalPayload | undefined;
    const peer = peerRef.current;
    if (!payload || !peer) return;
    if (payload.kind === "answer") {
      await peer.setRemoteDescription({ type: "answer", sdp: payload.data });
      for (const candidate of pendingIceRef.current) await peer.addIceCandidate(candidate);
      pendingIceRef.current = [];
    } else if (payload.kind === "ice") {
      const candidate: RTCIceCandidateInit = payload.data;
      if (peer.remoteDescription) await peer.addIceCandidate(candidate);
      else pendingIceRef.current.push(candidate);
    }
  };

  const requestSession = async () => {
    if (!inviteToken.trim()) {
      setStatus("请先输入设备主人提供的邀请令牌。");
      return;
    }
    setBusy(true);
    try {
      const result = await api<SessionResponse>("/v1/sessions", "POST", {
        inviteToken: inviteToken.trim(),
        reason: reason.trim() || "远程查看设备屏幕"
      });
      sessionRef.current = result.sessionId;
      setSessionId(result.sessionId);
      setStatus("连接申请已发送，等待设备主人确认。申请将在 90 秒后过期。");

      const ticket = await api<TicketResponse>("/v1/realtime/ticket", "POST", {});
      const wsUrl = new URL(baseUrl());
      wsUrl.protocol = "wss:";
      wsUrl.pathname = wsUrl.pathname.replace(/\/+$/, "") + "/v1/realtime";
      wsUrl.search = new URLSearchParams({ ticket: ticket.ticket }).toString();
      const socket = new WebSocket(wsUrl.toString());
      socketRef.current = socket;
      socket.onmessage = event => {
        void (async () => {
          try {
            const message = JSON.parse(String(event.data)) as Record<string, unknown>;
            if (message.type === "session.approved") {
              await startViewerPeer(result.sessionId, socket);
            } else if (message.type === "session.rejected") {
              setStatus("设备主人拒绝了本次连接。");
              await stopSession(false);
            } else if (message.type === "signal") {
              await handleSignal(message);
            } else if (message.type === "session.ended") {
              await stopSession(false);
            } else if (message.type === "error") {
              setStatus("信令错误：" + String(message.message ?? message.code ?? "未知错误"));
            }
          } catch (error) {
            setStatus("处理远程信令失败：" + String(error));
          }
        })();
      };
      socket.onerror = () => setStatus("WebSocket 信令连接发生错误。");
      socket.onclose = () => {
        if (sessionRef.current) setStatus("信令连接已关闭。");
      };
    } catch (error) {
      setStatus("申请远程连接失败：" + String(error));
      sessionRef.current = null;
      setSessionId(null);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => () => {
    closeMedia();
    socketRef.current?.close();
  }, []);

  return <div className="remote-workspace">
    <div className="remote-column">
      <div className="panel remote-panel">
        <div className="panel-title"><div><h2>云端身份与设备</h2><p>通过云端邀请连接，不依赖同一局域网或 USB。</p></div><ShieldCheck size={19}/></div>
        <label>云端 HTTPS 地址</label>
        <input value={serverUrl} onChange={event => setServerUrl(event.target.value)} placeholder="https://remote.example.com" />
        <label>账号访问令牌（OIDC JWT）</label>
        <input value={jwt} onChange={event => setJwt(event.target.value)} type="password" autoComplete="off" placeholder="粘贴身份服务签发的访问令牌" />
        <button className="secondary" disabled={busy || !jwt.trim()} onClick={() => void loadDevices()}>{busy ? <LoaderCircle size={14} className="spin"/> : <RefreshCw size={14}/>} 加载本人设备</button>
        {devices.length > 0 && <>
          <label>选择本人设备并创建邀请</label>
          <select value={selectedDevice} onChange={event => setSelectedDevice(event.target.value)}>
            {devices.map(device => <option key={device.id} value={device.id}>{device.display_name} · {device.platform} · {device.status}</option>)}
          </select>
          <button className="secondary" disabled={busy || !selectedDevice} onClick={() => void createInvite()}><Link2 size={14}/> 创建 7 天邀请</button>
        </>}
        {createdInvite && <div className="invite-token"><b>分享令牌（只显示本次）</b><code>{createdInvite.inviteToken}</code><button className="text-button" onClick={() => void navigator.clipboard?.writeText(createdInvite.inviteToken)}><Copy size={13}/> 复制邀请令牌</button><small>邀请 ID：{createdInvite.inviteId} · 有效期 {createdInvite.expiresInDays} 天</small></div>}
      </div>
      <div className="panel remote-panel">
        <div className="panel-title"><div><h2>申请远程连接</h2><p>连接请求必须由设备主人在手机端逐次批准。</p></div><MonitorPlay size={19}/></div>
        <label>设备主人提供的邀请令牌</label>
        <input value={inviteToken} onChange={event => setInviteToken(event.target.value)} type="password" autoComplete="off" placeholder="gp_inv_…" />
        <label>连接原因</label>
        <input value={reason} onChange={event => setReason(event.target.value)} maxLength={240}/>
        {!sessionId ? <button className="primary" disabled={busy || !jwt.trim() || !inviteToken.trim()} onClick={() => void requestSession()}>{busy ? <LoaderCircle size={14} className="spin"/> : <Link2 size={14}/>} 申请连接</button> : <button className="secondary" onClick={() => void stopSession(true)}>结束远程会话</button>}
        <div className="remote-status"><span className={connected ? "status-dot online" : "status-dot offline"}/>{status}</div>
      </div>
    </div>
    <div className="panel remote-video-panel">
      <div className="panel-title"><div><h2>远程画面</h2><p>{connected ? "WebRTC P2P 会话" : "等待设备主人批准与媒体协商"}</p></div>{connected && <span className="state-pill ready"><CheckCircle2 size={13}/> 已连接</span>}</div>
      <div className="remote-video-stage">
        <video ref={videoRef} autoPlay playsInline muted />
        {!streamReady && <div className="remote-video-empty"><VideoOff size={30}/><b>暂无远程画面</b><span>主人批准后将开始 WebRTC 媒体协商。</span></div>}
      </div>
      <div className="remote-video-footer"><span>信令：WSS</span><span>媒体：WebRTC / DTLS-SRTP</span><span>网络：STUN 优先，TURN 待接入</span></div>
    </div>
  </div>;
}
