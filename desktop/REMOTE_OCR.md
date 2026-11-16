# GamePilot Remote OCR (MVP)

## Data path

OCR is computed on the Android device from the same MediaProjection-backed WebRTC video frames already approved by the device owner.

- Android OCR engine: Google ML Kit Chinese Text Recognition.
- Sampling target: at most one frame every 900 ms.
- Transport: the negotiated WebRTC DataChannel named `gamepilot-ocr`.
- DataChannel is created by the desktop requester before its SDP offer; it is encrypted by the WebRTC DTLS-SRTP transport and follows the same P2P/TURN route as the video session.
- Cloud WSS remains limited to authentication, owner approval, session lifecycle and SDP/ICE signaling. OCR payloads do not pass through or persist on the cloud server.
- No screenshot is uploaded, stored or logged by this OCR path.

## Message v1

Each UTF-8 DataChannel message is a JSON object:

```json
{
  "type": "ocr.result",
  "version": 1,
  "sequence": 12,
  "capturedAt": 1794823200000,
  "width": 1080,
  "height": 2400,
  "rotation": 0,
  "blocks": [
    {
      "text": "示例文本",
      "left": 40,
      "top": 120,
      "right": 520,
      "bottom": 188
    }
  ]
}
```

Coordinates are pixel coordinates associated with the captured frame. The frame dimensions and rotation are included so the desktop can map regions to the displayed video. A result can contain at most 80 text blocks and is dropped if its serialized size exceeds 24 KiB.

## Lifecycle and boundaries

- The DataChannel is negotiated only inside the owner-approved remote session.
- OCR starts with the WebRTC screen sender and stops when the capture session is released.
- OCR errors or DataChannel congestion drop OCR results only; they do not interrupt video.
- Ending the remote session closes the peer connection and DataChannel.
- This is a first integration pass. Physical-device accuracy, latency, orientation mapping and TURN-path behavior still require end-to-end validation.
- Android ML Kit Chinese OCR requires Android API 23+, so the helper's minimum SDK is now 23.