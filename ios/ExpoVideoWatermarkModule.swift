import ExpoModulesCore
import AVFoundation
import UIKit

public class ExpoVideoWatermarkModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoVideoWatermark")

    AsyncFunction("watermarkVideo") { (videoPath: String, imagePath: String, outputPath: String, promise: Promise) in
      DispatchQueue.global(qos: .userInitiated).async {
        self.processWatermark(videoPath: videoPath, imagePath: imagePath, outputPath: outputPath, promise: promise)
      }
    }
  }

  private func processWatermark(videoPath: String, imagePath: String, outputPath: String, promise: Promise) {
    // Validate video file exists
    let videoURL = URL(fileURLWithPath: videoPath)
    guard FileManager.default.fileExists(atPath: videoPath) else {
      promise.reject("VIDEO_NOT_FOUND", "Video file not found at path: \(videoPath)")
      return
    }

    // Validate image file exists and load it
    guard FileManager.default.fileExists(atPath: imagePath),
          let watermarkImage = UIImage(contentsOfFile: imagePath) else {
      promise.reject("IMAGE_NOT_FOUND", "Watermark image not found at path: \(imagePath)")
      return
    }

    // Load video asset
    let asset = AVURLAsset(url: videoURL)

    // Get video track
    guard let videoTrack = asset.tracks(withMediaType: .video).first else {
      promise.reject("INVALID_VIDEO", "No video track found in file: \(videoPath)")
      return
    }

    // Create composition
    let composition = AVMutableComposition()

    guard let compositionVideoTrack = composition.addMutableTrack(
      withMediaType: .video,
      preferredTrackID: kCMPersistentTrackID_Invalid
    ) else {
      promise.reject("COMPOSITION_ERROR", "Failed to create video composition track")
      return
    }

    let duration = asset.duration

    do {
      // Insert video track
      try compositionVideoTrack.insertTimeRange(
        CMTimeRange(start: .zero, duration: duration),
        of: videoTrack,
        at: .zero
      )

      // Handle audio track if present
      if let audioTrack = asset.tracks(withMediaType: .audio).first,
         let compositionAudioTrack = composition.addMutableTrack(
           withMediaType: .audio,
           preferredTrackID: kCMPersistentTrackID_Invalid
         ) {
        try compositionAudioTrack.insertTimeRange(
          CMTimeRange(start: .zero, duration: duration),
          of: audioTrack,
          at: .zero
        )
      }
    } catch {
      promise.reject("COMPOSITION_ERROR", "Failed to insert tracks: \(error.localizedDescription)")
      return
    }

    // Get video size accounting for transform
    let videoSize = self.naturalSizeForTrack(videoTrack)

    // Create layer hierarchy for watermark overlay
    let parentLayer = CALayer()
    let videoLayer = CALayer()

    parentLayer.frame = CGRect(origin: .zero, size: videoSize)
    videoLayer.frame = CGRect(origin: .zero, size: videoSize)
    parentLayer.addSublayer(videoLayer)

    // Create watermark layer positioned at bottom-right
    let watermarkLayer = CALayer()
    watermarkLayer.contents = watermarkImage.cgImage

    let padding: CGFloat = 20
    let maxWatermarkWidth = videoSize.width * 0.25
    let maxWatermarkHeight = videoSize.height * 0.25

    var watermarkWidth = watermarkImage.size.width
    var watermarkHeight = watermarkImage.size.height

    // Scale down watermark if too large
    if watermarkWidth > maxWatermarkWidth || watermarkHeight > maxWatermarkHeight {
      let widthRatio = maxWatermarkWidth / watermarkWidth
      let heightRatio = maxWatermarkHeight / watermarkHeight
      let scale = min(widthRatio, heightRatio)
      watermarkWidth *= scale
      watermarkHeight *= scale
    }

    // Position at bottom-center (Core Animation y=0 is bottom)
    watermarkLayer.frame = CGRect(
      x: (videoSize.width - watermarkWidth) / 2,
      y: padding,
      width: watermarkWidth,
      height: watermarkHeight
    )
    watermarkLayer.opacity = 1.0
    parentLayer.addSublayer(watermarkLayer)

    // Create video composition
    let videoComposition = AVMutableVideoComposition()
    videoComposition.renderSize = videoSize
    videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
    videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
      postProcessingAsVideoLayer: videoLayer,
      in: parentLayer
    )

    // Create instruction
    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = CMTimeRange(start: .zero, duration: duration)

    let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideoTrack)

    // Apply transform to handle rotated videos
    let transform = self.transformForTrack(videoTrack)
    layerInstruction.setTransform(transform, at: .zero)

    instruction.layerInstructions = [layerInstruction]
    videoComposition.instructions = [instruction]

    // Setup export
    let outputURL = URL(fileURLWithPath: outputPath)

    // Remove existing file if present
    try? FileManager.default.removeItem(at: outputURL)

    // Create parent directory if needed
    let parentDir = outputURL.deletingLastPathComponent()
    try? FileManager.default.createDirectory(at: parentDir, withIntermediateDirectories: true)

    guard let exportSession = AVAssetExportSession(
      asset: composition,
      presetName: AVAssetExportPresetHighestQuality
    ) else {
      promise.reject("EXPORT_ERROR", "Could not create export session")
      return
    }

    exportSession.outputURL = outputURL
    exportSession.outputFileType = .mp4
    exportSession.videoComposition = videoComposition

    exportSession.exportAsynchronously {
      switch exportSession.status {
      case .completed:
        promise.resolve(outputPath)
      case .failed:
        let errorMessage = exportSession.error?.localizedDescription ?? "Unknown error"
        promise.reject("EXPORT_FAILED", "Video export failed: \(errorMessage)")
      case .cancelled:
        promise.reject("EXPORT_CANCELLED", "Video export was cancelled")
      default:
        promise.reject("EXPORT_ERROR", "Export ended with status: \(exportSession.status.rawValue)")
      }
    }
  }

  // Calculate the natural size accounting for video transform/rotation
  private func naturalSizeForTrack(_ track: AVAssetTrack) -> CGSize {
    let size = track.naturalSize
    let transform = track.preferredTransform

    // Check if video is rotated 90 or 270 degrees
    if abs(transform.a) == 0 && abs(transform.d) == 0 {
      return CGSize(width: size.height, height: size.width)
    }
    return size
  }

  // Get the transform needed to render the video correctly
  private func transformForTrack(_ track: AVAssetTrack) -> CGAffineTransform {
    let size = track.naturalSize
    let transform = track.preferredTransform

    // Handle different rotation cases
    if transform.a == 0 && transform.d == 0 {
      if transform.b == 1.0 && transform.c == -1.0 {
        // 90 degrees rotation
        return CGAffineTransform(translationX: size.height, y: 0).rotated(by: .pi / 2)
      } else if transform.b == -1.0 && transform.c == 1.0 {
        // 270 degrees rotation
        return CGAffineTransform(translationX: 0, y: size.width).rotated(by: -.pi / 2)
      }
    } else if transform.a == -1.0 && transform.d == -1.0 {
      // 180 degrees rotation
      return CGAffineTransform(translationX: size.width, y: size.height).rotated(by: .pi)
    }

    return .identity
  }
}
