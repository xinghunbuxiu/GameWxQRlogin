package com.willh.wz;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the already-authorized WebRTC screen frames and sends OCR metadata
 * over the same peer connection. No screenshots are uploaded or persisted.
 */
public final class RemoteOcrProcessor implements VideoSink {
    private static final long MIN_INTERVAL_MS = 900L;
    private static final int MAX_BLOCKS = 80;
    private static final int MAX_MESSAGE_BYTES = 24 * 1024;

    private final Context appContext;
    private final com.google.mlkit.vision.text.TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final AtomicBoolean waitingChannelLogged = new AtomicBoolean(false);
    private final AtomicBoolean channelOpenLogged = new AtomicBoolean(false);
    private final AtomicLong lastFrameAt = new AtomicLong(0L);
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private volatile DataChannel dataChannel;

    public RemoteOcrProcessor(Context context) {
        appContext = context.getApplicationContext();
        RemoteDiagnosticsStore.append(appContext, "ocr.processor.started interval_ms=" + MIN_INTERVAL_MS);
    }

    public void setDataChannel(DataChannel channel) {
        dataChannel = channel;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (released.get()) return;
        DataChannel channel = dataChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) {
            if (waitingChannelLogged.compareAndSet(false, true)) {
                RemoteDiagnosticsStore.append(appContext, "ocr.waiting_data_channel");
            }
            return;
        }
        if (channelOpenLogged.compareAndSet(false, true)) {
            RemoteDiagnosticsStore.append(appContext, "ocr.data_channel.open");
        }
        long now = SystemClock.elapsedRealtime();
        long previous = lastFrameAt.get();
        if (now - previous < MIN_INTERVAL_MS || !lastFrameAt.compareAndSet(previous, now)) return;

        VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
        try {
            int width = i420.getWidth();
            int height = i420.getHeight();
            int rotation = frame.getRotation();
            byte[] nv21 = toNv21(i420);
            InputImage image = InputImage.fromByteArray(
                    nv21, width, height, rotation, InputImage.IMAGE_FORMAT_NV21);
            long frameSequence = sequence.incrementAndGet();
            long capturedAt = System.currentTimeMillis();
            recognizer.process(image)
                    .addOnSuccessListener(result -> sendResult(result, frameSequence, capturedAt,
                            width, height, rotation))
                    .addOnFailureListener(error -> RemoteDiagnosticsStore.append(
                            appContext, "ocr.recognition.failed " + error.getClass().getSimpleName()));
        } catch (Exception error) {
            RemoteDiagnosticsStore.append(appContext, "ocr.frame.failed " + error.getClass().getSimpleName());
            // OCR is an optional side channel; keep the video session alive.
        } finally {
            i420.release();
        }
    }

    private void sendResult(Text result, long frameSequence, long capturedAt,
                            int width, int height, int rotation) {
        if (released.get()) return;
        DataChannel channel = dataChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) return;

        try {
            JSONObject message = new JSONObject();
            message.put("type", "ocr.result");
            message.put("version", 1);
            message.put("sequence", frameSequence);
            message.put("capturedAt", capturedAt);
            message.put("width", width);
            message.put("height", height);
            message.put("rotation", rotation);

            JSONArray blocks = new JSONArray();
            for (Text.TextBlock block : result.getTextBlocks()) {
                if (blocks.length() >= MAX_BLOCKS) break;
                Rect bounds = block.getBoundingBox();
                if (bounds == null) continue;
                JSONObject item = new JSONObject();
                item.put("text", block.getText() == null ? "" : block.getText());
                item.put("left", bounds.left);
                item.put("top", bounds.top);
                item.put("right", bounds.right);
                item.put("bottom", bounds.bottom);
                blocks.put(item);
            }
            message.put("blocks", blocks);

            byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MESSAGE_BYTES) {
                RemoteDiagnosticsStore.append(appContext, "ocr.result.dropped oversized bytes=" + bytes.length);
                return;
            }
            boolean sent = channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
            if (!sent) {
                RemoteDiagnosticsStore.append(appContext, "ocr.result.send_failed sequence=" + frameSequence);
            } else if (frameSequence == 1 || frameSequence % 10 == 0) {
                RemoteDiagnosticsStore.append(appContext, "ocr.result.sent sequence=" + frameSequence
                        + " blocks=" + blocks.length() + " bytes=" + bytes.length);
            }
        } catch (Exception ignored) {
            // Never let an OCR serialization/send error stop the approved video.
        }
    }

    private static byte[] toNv21(VideoFrame.I420Buffer buffer) {
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        int ySize = width * height;
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        byte[] output = new byte[ySize + (2 * chromaWidth * chromaHeight)];
        ByteBuffer y = buffer.getDataY();
        ByteBuffer u = buffer.getDataU();
        ByteBuffer v = buffer.getDataV();
        int strideY = buffer.getStrideY();
        int strideU = buffer.getStrideU();
        int strideV = buffer.getStrideV();

        for (int row = 0; row < height; row++) {
            int source = row * strideY;
            y.position(source);
            y.get(output, row * width, width);
        }

        int offset = ySize;
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                output[offset++] = v.get(row * strideV + col);
                output[offset++] = u.get(row * strideU + col);
            }
        }
        return output;
    }

    public void release() {
        if (!released.compareAndSet(false, true)) return;
        dataChannel = null;
        recognizer.close();
    }
}