import AVFoundation

@objc public class AudioFromVideoRetriever: NSObject {
    @objc public func extractAudio(videoURL: URL, outputURL: URL, completion: @escaping (URL?, Error?) -> Void) {
        let asset = AVURLAsset(url: videoURL)
        let audioTrack = asset.tracks(withMediaType: .audio).first
        
        guard let audioTrack = audioTrack else {
            let error = NSError(domain: "AudioExtractionError", code: 0, userInfo: [NSLocalizedDescriptionKey: "No audio track found in the video."])
            completion(nil, error)
            return
        }
        
        let composition = AVMutableComposition()
        let audioCompositionTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        
        // Limit duration to 5 minutes (300 seconds)
        let maxDuration = CMTime(seconds: 300, preferredTimescale: 600)
        let timeRangeToUse = CMTimeRange(start: .zero, duration: min(audioTrack.timeRange.duration, maxDuration))
        
        do {
            try audioCompositionTrack?.insertTimeRange(timeRangeToUse, of: audioTrack, at: .zero)
        } catch {
            completion(nil, error)
            return
        }
        
        let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough)
        exportSession?.outputFileType = AVFileType.m4a
        exportSession?.outputURL = outputURL
        exportSession?.timeRange = timeRangeToUse
        exportSession?.fileLengthLimit = Int64(9.9 * 1024 * 1024)
        
        exportSession?.exportAsynchronously {
            if exportSession?.status == .completed {
                completion(outputURL, nil)
            } else if let error = exportSession?.error {
                completion(nil, error)
            }
        }
    }
    
    public func getDataURL(from audioURL: URL) -> String? {
        do {
            let audioData = try Data(contentsOf: audioURL)
            let base64String = audioData.base64EncodedString()
            let mediaType = "audio/mp4" // Set the appropriate media type for your audio file
            let dataURLString = "data:\(mediaType);base64,\(base64String)"
            return dataURLString
        } catch {
            print("Error: \(error)")
            return nil
        }
    }
}
