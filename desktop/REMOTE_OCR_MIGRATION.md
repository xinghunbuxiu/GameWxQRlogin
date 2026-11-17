# GamePilot OCR migration decision

## Status

Accepted for a desktop-side proof of concept. The Android implementation remains in place until the desktop path passes offline, accuracy, coordinate and lifecycle tests.

## Decision

Move OCR inference to the Tauri desktop WebView, behind a replaceable VisionOcrEngine adapter. Android remains responsible for owner-approved MediaProjection capture and WebRTC video transport only.

The first desktop spike will evaluate PP-OCRv5 ONNX inference in a Web Worker using ONNX Runtime Web. The candidate ocr-web project exposes browser/Electron worker inference and line boxes, but it is a small, young project; it is a prototype candidate, not yet an approved production dependency.

References:
- https://github.com/bent2685/ocr-web
- https://github.com/RapidAI/RapidOCR
- PP-OCRv5 model assets are derived from PaddleOCR and documented as Apache-2.0 in the candidate project.

## Target data flow

```text
Android Helper
  MediaProjection -> WebRTC video track
                         |
                         v
Tauri desktop
  RTCPeerConnection -> HTMLVideoElement
                         |
                         v
  Frame sampler (canvas / ImageBitmap, bounded rate)
                         |
                         v
  VisionOcrEngine (Web Worker + ONNX Runtime Web)
                         |
                         v
  VisionResult v1
    - frame sequence and capture timestamp
    - source frame width / height / rotation
    - text, confidence and source-frame pixel bounds
                         |
              +----------+----------+
              |                     |
              v                     v
       OCR text panel        video bounding-box overlay
                                    |
                                    v
                         future scene/action analysis
```

No screenshots, OCR text or model inputs are sent to the cloud signaling server. Recognition stays on the desktop. The desktop must not persist frames by default.

## Why desktop-side OCR

- Removes ML Kit's runtime and recognition work from the Android Helper.
- Avoids a second OCR-specific DataChannel and duplicate frame metadata.
- Centralizes OCR, image detection and later scene understanding beside the desktop automation workflow.
- Allows one desktop to process multiple authorized device sessions, subject to CPU/GPU and memory limits.
- Keeps the Android app focused on capture, connectivity and owner consent.

## Non-negotiable implementation constraints

1. Offline after installation: ONNX models, dictionary and ONNX Runtime WASM assets must be bundled with or installed into the desktop app. Do not depend on jsDelivr/CDN at runtime.
2. Worker isolation: OCR must run off the React UI thread. At most one inference per session may be in flight; drop stale frames rather than queueing an unbounded backlog.
3. Bounded sampling: start at 1–2 frames/second and make the rate configurable. Pause OCR when the session is hidden, stopped or the video is not ready.
4. Coordinate contract: all boxes are expressed in the original decoded video frame's pixel coordinate space, before CSS scaling/cropping. Include frame sequence, dimensions and rotation. Validate portrait, landscape, 90/180/270-degree rotations, object-fit contain and letterboxing before drawing overlays.
5. Lifecycle: create one engine per desktop app/session policy, dispose worker and ONNX sessions on shutdown, and clear pending frame resources when a session ends.
6. Backpressure: never queue frames while inference is busy. A newer frame replaces a pending frame; old results are ignored if their frame sequence is stale.
7. Privacy: OCR is local. Diagnostics may record timings and error classes, but not screenshots or recognized user text by default.
8. Graceful degradation: OCR failure must not stop WebRTC video.
9. Adapter boundary: UI consumes a stable VisionResult v1 protocol and must not depend on a specific OCR vendor's response shape.

## VisionResult v1 (desktop-internal)

```json
{
  "type": "vision.result",
  "version": 1,
  "frameSequence": 1024,
  "capturedAt": 1794900000000,
  "frame": {
    "width": 1080,
    "height": 2400,
    "rotation": 0
  },
  "elements": [
    {
      "type": "text",
      "text": "开始游戏",
      "confidence": 0.98,
      "bounds": [420, 1200, 650, 1280]
    }
  ]
}
```

The protocol is internal to the desktop initially. It is not sent to Android or the cloud.

## Migration sequence

### Phase 0 — preserve current working path

- Keep Android ML Kit and gamepilot-ocr DataChannel unchanged.
- Add desktop OCR as a parallel, feature-flagged path.
- Compare desktop OCR output against Android OCR on the same received video frames.

### Phase 1 — desktop proof of concept

- Bundle the worker, ONNX Runtime WASM, PP-OCRv5 models and dictionary locally.
- Add VisionOcrEngine interface and worker adapter.
- Sample decoded video frames at a bounded rate.
- Display text, confidence, inference latency and source-frame boxes.
- Test offline launch, macOS and Windows build/runtime, Chinese game UI accuracy and memory stability.

### Phase 2 — parity and coordinate validation

- Compare desktop vs Android recognition on representative game screens.
- Verify frame sequence, timestamp, rotation, scaling, cropping and letterbox transforms.
- Confirm stopping a session releases worker, frame and model resources.
- Confirm OCR errors never interrupt video.

### Phase 3 — remove Android OCR

Only after Phase 2 passes:
- Remove RemoteOcrProcessor.
- Remove OCR DataChannel negotiation and handling.
- Remove com.google.mlkit:text-recognition-chinese.
- Restore Android minSdk to 21 only if all remaining dependencies and APIs permit it; otherwise retain the actual dependency minimum.
- Update Android diagnostics and docs.
- Compare release APK sizes before/after; do not infer size savings from Debug artifacts.

### Phase 4 — unified vision capabilities

Add image/template detection and scene understanding through the same VisionResult contract. Keep OCR text recognition separate from semantic decisions and any future authorized input execution.

## Acceptance criteria

- Desktop OCR works with network disconnected after installation.
- Chinese and mixed Chinese/English text are recognized from real game screens.
- UI remains responsive during inference.
- No unbounded frame queue or memory growth during a 30-minute session.
- Bounding boxes align with the displayed video in portrait and landscape.
- Multiple sessions are isolated; stopping one does not stop another.
- Video continues if OCR initialization or inference fails.
- Android APK no longer packages ML Kit only after the desktop path has passed all above tests.
