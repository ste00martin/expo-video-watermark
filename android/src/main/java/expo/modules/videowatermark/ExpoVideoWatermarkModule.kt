package expo.modules.videowatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
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
    // Strip file:// prefix if present
    val cleanVideoPath = videoPath.removePrefix("file://")
    val cleanImagePath = imagePath.removePrefix("file://")
    val cleanOutputPath = outputPath.removePrefix("file://")

    // Validate video file exists
    val videoFile = File(cleanVideoPath)
    if (!videoFile.exists()) {
      promise.reject("VIDEO_NOT_FOUND", "Video file not found at path: $cleanVideoPath", null)
      return
    }

    // Validate image file exists
    val imageFile = File(cleanImagePath)
    if (!imageFile.exists()) {
      promise.reject("IMAGE_NOT_FOUND", "Watermark image not found at path: $cleanImagePath", null)
      return
    }

    // Load the watermark bitmap
    val watermarkBitmap: Bitmap? = BitmapFactory.decodeFile(cleanImagePath)
    if (watermarkBitmap == null) {
      promise.reject("IMAGE_DECODE_ERROR", "Failed to decode image at: $cleanImagePath", null)
      return
    }

    // Ensure output directory exists
    val outputFile = File(cleanOutputPath)
    outputFile.parentFile?.mkdirs()

    // Remove existing output file if present
    if (outputFile.exists()) {
      outputFile.delete()
    }

    // Get video dimensions to calculate scale
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(cleanVideoPath)
    } catch (e: Exception) {
      promise.reject("VIDEO_METADATA_ERROR", "Failed to read video metadata: ${e.message}", e)
      return
    }

    val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
    val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
    retriever.release()

    if (videoWidth <= 0 || videoHeight <= 0) {
      promise.reject("VIDEO_METADATA_ERROR", "Failed to get video dimensions", null)
      return
    }

    // Calculate scale to make watermark span full video width, maintaining aspect ratio
    val watermarkWidth = watermarkBitmap.width.toFloat()
    val scale = videoWidth / watermarkWidth

    // Create overlay settings for full-width bottom positioning
    // In Media3, coordinates are normalized: (0,0) is center
    // x range [-1, 1] (left to right), y range [-1, 1] (bottom to top)
    val overlaySettings = StaticOverlaySettings.Builder()
      .setScale(scale, scale)  // Scale uniformly to match video width
      .setOverlayFrameAnchor(0f, -1f)  // Anchor at bottom-center of watermark
      .setBackgroundFrameAnchor(0f, -1f)  // Position at very bottom of video
      .build()

    // Create the bitmap overlay with settings
    val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(
      watermarkBitmap,
      overlaySettings
    )

    // Create overlay effect with proper typing
    val overlayEffect = OverlayEffect(ImmutableList.of<TextureOverlay>(bitmapOverlay))

    // Create effects with video overlay
    val effects = Effects(
      /* audioProcessors= */ listOf(),
      /* videoEffects= */ listOf(overlayEffect)
    )

    // Create media item from video
    val mediaItem = MediaItem.fromUri("file://$cleanVideoPath")

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
            promise.resolve(cleanOutputPath)
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

      transformer.start(editedMediaItem, cleanOutputPath)
    }
  }
}
