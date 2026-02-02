package expo.modules.videowatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class ExpoVideoWatermarkModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.AppContextLost()

  @OptIn(UnstableApi::class)
  override fun definition() = ModuleDefinition {
    Name("ExpoVideoWatermark")

    AsyncFunction("watermarkVideo") { videoPath: String, imagePath: String, outputPath: String, promise: Promise ->
      processWatermark(videoPath, imagePath, outputPath, promise)
    }
  }

  @OptIn(UnstableApi::class)
  private fun processWatermark(
    videoPath: String,
    imagePath: String,
    outputPath: String,
    promise: Promise
  ) {
    // Validate video file exists
    val videoFile = File(videoPath)
    if (!videoFile.exists()) {
      promise.reject("VIDEO_NOT_FOUND", "Video file not found at path: $videoPath", null)
      return
    }

    // Validate image file exists
    val imageFile = File(imagePath)
    if (!imageFile.exists()) {
      promise.reject("IMAGE_NOT_FOUND", "Watermark image not found at path: $imagePath", null)
      return
    }

    // Load the watermark bitmap
    val watermarkBitmap: Bitmap? = BitmapFactory.decodeFile(imagePath)
    if (watermarkBitmap == null) {
      promise.reject("IMAGE_DECODE_ERROR", "Failed to decode image at: $imagePath", null)
      return
    }

    // Ensure output directory exists
    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()

    // Remove existing output file if present
    if (outputFile.exists()) {
      outputFile.delete()
    }

    // Create overlay settings for bottom-right positioning
    // In Media3, coordinates are normalized: (0,0) is center
    // x range [-1, 1] (left to right), y range [-1, 1] (bottom to top)
    val overlaySettings = OverlaySettings.Builder()
      .setOverlayFrameAnchor(1f, -1f)      // Anchor at bottom-right of watermark
      .setBackgroundFrameAnchor(0.85f, -0.85f)  // Position near bottom-right of video with margin
      .build()

    // Create the bitmap overlay
    val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(
      watermarkBitmap,
      overlaySettings
    )

    // Create overlay effect
    val overlayEffect = OverlayEffect(ImmutableList.of(bitmapOverlay))

    // Create effects with video overlay
    val effects = Effects(
      /* audioProcessors= */ listOf(),
      /* videoEffects= */ listOf(overlayEffect)
    )

    // Create media item from video
    val mediaItem = MediaItem.fromUri("file://$videoPath")

    // Create edited media item with effects
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
      .setEffects(effects)
      .build()

    // Handler for main thread callbacks
    val mainHandler = Handler(Looper.getMainLooper())

    // Build and start transformer
    mainHandler.post {
      val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
          override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            watermarkBitmap.recycle()
            promise.resolve(outputPath)
          }

          override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
          ) {
            watermarkBitmap.recycle()
            promise.reject(
              "EXPORT_FAILED",
              "Video export failed: ${exportException.message ?: "Unknown error"}",
              exportException
            )
          }
        })
        .build()

      transformer.start(editedMediaItem, outputPath)
    }
  }
}
