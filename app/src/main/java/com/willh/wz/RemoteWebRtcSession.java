package com.willh.wz;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;

import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android screen sender. The browser/requester is the offerer; this device answers. */
public final class RemoteWebRtcSession {
    public interface Listener {
        void onReady();
        void onConnected();
        void onFailure(String message);
    }

    private static boolean factoryInitialized;
    private final Context context;
    private final String sessionId;
    private final RemoteSignalingClient signaling;
    private final Listener listener;
    private final Intent projectionPermissionData;
    private final int width;
    private final int height;
    private final int fps;
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private ScreenCapturerAndroid screenCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private boolean remoteDescriptionSet;
    private boolean released;

    public RemoteWebRtcSession(Context context, String sessionId, Intent projectionPermissionData,
                               RemoteSignalingClient signaling, Listener listener,
                               int width, int height, int fps) {
        this.context = context.getApplicationContext();
        this.sessionId = sessionId;
        this.projectionPermissionData = projectionPermissionData;
        this.signaling = signaling;
        this.listener = listener;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    public synchronized void start() {
        if (released) return;
        try {
            initializeFactory(context);
            eglBase = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();

            PeerConnection.IceServer stun = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer();
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(Collections.singletonList(stun));
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

            peerConnection = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                @Override public void onAddStream(org.webrtc.MediaStream stream) {}
                @Override public void onRemoveStream(org.webrtc.MediaStream stream) {}
                @Override public void onDataChannel(org.webrtc.DataChannel channel) {}
                @Override public void onRenegotiationNeeded() {}

                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED
                            || state == PeerConnection.IceConnectionState.COMPLETED) {
                        listener.onConnected();
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        listener.onFailure("WebRTC ICE 连接中断：" + state.name());
                    }
                }

                @Override public void onIceCandidate(IceCandidate candidate) {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("candidate", candidate.sdp);
                        data.put("sdpMid", candidate.sdpMid);
                        data.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        JSONObject payload = new JSONObject();
                        payload.put("kind", "ice");
                        payload.put("data", data);
                        if (!signaling.sendSignal(sessionId, payload)) {
                            listener.onFailure("无法发送本地 ICE Candidate");
                        }
                    } catch (Exception error) {
                        listener.onFailure("构造 ICE Candidate 失败");
                    }
                }

                @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) {}
            });
            if (peerConnection == null) throw new IllegalStateException("无法创建 PeerConnection");

            screenCapturer = new ScreenCapturerAndroid(projectionPermissionData, new MediaProjection.Callback() {
                @Override public void onStop() {
                    listener.onFailure("系统屏幕采集已停止");
                }
            });
            videoSource = factory.createVideoSource(screenCapturer.isScreencast());
            surfaceTextureHelper = SurfaceTextureHelper.create("GamePilotScreenCapture", eglBase.getEglBaseContext());
            screenCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoTrack = factory.createVideoTrack("gamepilot-screen-track", videoSource);
            peerConnection.addTrack(videoTrack, Collections.singletonList("gamepilot-screen"));
            screenCapturer.startCapture(width, height, fps);
            listener.onReady();
        } catch (Exception error) {
            listener.onFailure(error.getMessage() == null ? "WebRTC 初始化失败" : error.getMessage());
            release();
        }
    }

    public void handleSignal(JSONObject payload) {
        if (released || peerConnection == null || payload == null) return;
        String kind = payload.optString("kind");
        JSONObject data = payload.optJSONObject("data");
        if ("offer".equals(kind)) {
            String sdp = payload.optString("data", "");
            if (sdp.isEmpty()) {
                listener.onFailure("收到空的 SDP Offer");
                return;
            }
            SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, sdp);
            peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                @Override public void onSetSuccess() {
                    remoteDescriptionSet = true;
                    drainRemoteCandidates();
                    createAnswer();
                }
                @Override public void onSetFailure(String error) {
                    listener.onFailure("设置远端 SDP 失败：" + error);
                }
            }, offer);
        } else if ("ice".equals(kind) && data != null) {
            IceCandidate candidate = new IceCandidate(
                    data.optString("sdpMid", null),
                    data.has("sdpMLineIndex") && !data.isNull("sdpMLineIndex") ? data.optInt("sdpMLineIndex") : 0,
                    data.optString("candidate", "")
            );
            if (candidate.sdp == null || candidate.sdp.isEmpty()) return;
            if (remoteDescriptionSet) peerConnection.addIceCandidate(candidate);
            else pendingRemoteCandidates.add(candidate);
        }
    }

    private void createAnswer() {
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription answer) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject payload = new JSONObject();
                            payload.put("kind", "answer");
                            payload.put("data", answer.description);
                            if (!signaling.sendSignal(sessionId, payload)) listener.onFailure("无法发送 SDP Answer");
                        } catch (Exception error) {
                            listener.onFailure("构造 SDP Answer 失败");
                        }
                    }
                    @Override public void onSetFailure(String error) {
                        listener.onFailure("设置本地 SDP 失败：" + error);
                    }
                }, answer);
            }
            @Override public void onCreateFailure(String error) {
                listener.onFailure("创建 SDP Answer 失败：" + error);
            }
        }, new MediaConstraints());
    }

    private void drainRemoteCandidates() {
        for (IceCandidate candidate : pendingRemoteCandidates) peerConnection.addIceCandidate(candidate);
        pendingRemoteCandidates.clear();
    }

    private static synchronized void initializeFactory(Context context) {
        if (factoryInitialized) return;
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
        );
        factoryInitialized = true;
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        if (screenCapturer != null) {
            try { screenCapturer.stopCapture(); } catch (Exception ignored) { Thread.currentThread().interrupt(); }
            screenCapturer.dispose();
            screenCapturer = null;
        }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection.dispose(); peerConnection = null; }
        if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
    }

    private abstract static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
