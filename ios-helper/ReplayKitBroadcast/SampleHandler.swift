import ReplayKit
import CoreMedia
import os

/// Broadcast Upload Extension entry point.
///
/// ReplayKit starts this extension only after the device owner explicitly starts
/// a system-mediated broadcast. This first slice validates lifecycle and owner
/// stop behavior; it intentionally does not claim to transmit WebRTC video yet.
final class SampleHandler: RPBroadcastSampleHandler {
    private let logger = Logger(subsystem: "com.gamepilot.helper.broadcast", category: "ReplayKit")
    private let appGroupIdentifier = "group.REPLACE_WITH_YOUR_APP_GROUP"

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        sharedDefaults?.set(true, forKey: "gamepilot.broadcast.active")
        sharedDefaults?.set(Date().timeIntervalSince1970, forKey: "gamepilot.broadcast.startedAt")
        logger.info("Owner started ReplayKit broadcast")
    }

    override func broadcastPaused() {
        sharedDefaults?.set(false, forKey: "gamepilot.broadcast.active")
        logger.info("ReplayKit broadcast paused by system or owner")
    }

    override func broadcastResumed() {
        sharedDefaults?.set(true, forKey: "gamepilot.broadcast.active")
        logger.info("ReplayKit broadcast resumed")
    }

    override func broadcastFinished() {
        sharedDefaults?.set(false, forKey: "gamepilot.broadcast.active")
        sharedDefaults?.removeObject(forKey: "gamepilot.broadcast.startedAt")
        logger.info("Owner finished ReplayKit broadcast")
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video else { return }
        guard CMSampleBufferDataIsReady(sampleBuffer) else { return }
        // WebRTC video-track integration belongs here in the next implementation stage.
        // Do not persist raw screen frames to shared defaults or disk.
        // Audio and app-audio sample buffers are intentionally not forwarded.
    }

    private var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupIdentifier)
    }
}