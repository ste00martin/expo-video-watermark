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

    // Step 1: Validate video file exists
    val videoFile = File(cleanVideoPath)
    if (!videoFile.exists()) {
      promise.reject("STEP1_VIDEO_NOT_FOUND", "[Step 1] Video file not found at path: $cleanVideoPath", null)
      return
    }

    // Step 2: Validate image file exists
    val imageFile = File(cleanImagePath)
    if (!imageFile.exists()) {
      promise.reject("STEP2_IMAGE_NOT_FOUND", "[Step 2] Watermark image not found at path: $cleanImagePath", null)
      return
    }

    // Step 3: Load the watermark bitmap with ARGB_8888 config for GPU compatibility
    val options = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decodedBitmap: Bitmap? = BitmapFactory.decodeFile(cleanImagePath, options)
    if (decodedBitmap == null) {
      promise.reject("STEP3_IMAGE_DECODE_ERROR", "[Step 3] Failed to decode image at: $cleanImagePath", null)
      return
    }

    // Step 4: Ensure bitmap is in ARGB_8888 format (required for Media3 GPU processing)
    val watermarkBitmap: Bitmap = if (decodedBitmap.config != Bitmap.Config.ARGB_8888) {
      val converted = decodedBitmap.copy(Bitmap.Config.ARGB_8888, false)
      decodedBitmap.recycle()
      if (converted == null) {
        promise.reject("STEP4_IMAGE_CONVERT_ERROR", "[Step 4] Failed to convert image to ARGB_8888 format", null)
        return
      }
      converted
    } else {
      decodedBitmap
    }

    // Step 5: Ensure output directory exists
    val outputFile = File(cleanOutputPath)
    outputFile.parentFile?.mkdirs()

    // Remove existing output file if present
    if (outputFile.exists()) {
      outputFile.delete()
    }

    // Step 6: Get video dimensions to calculate scale
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(cleanVideoPath)
    } catch (e: Exception) {
      watermarkBitmap.recycle()
      promise.reject("STEP6_VIDEO_METADATA_ERROR", "[Step 6] Failed to read video metadata: ${e.message}", e)
      return
    }

    val rawVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
    val rawVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    retriever.release()

    if (rawVideoWidth <= 0 || rawVideoHeight <= 0) {
      watermarkBitmap.recycle()
      promise.reject("STEP6_VIDEO_DIMENSIONS_ERROR", "[Step 6] Failed to get video dimensions (width=$rawVideoWidth, height=$rawVideoHeight)", null)
      return
    }

    // Step 7: Account for video rotation - swap dimensions if rotated 90 or 270 degrees
    val (videoWidth, videoHeight) = if (rotation == 90 || rotation == 270) {
      rawVideoHeight to rawVideoWidth
    } else {
      rawVideoWidth to rawVideoHeight
    }

    // Step 8: Calculate scale to make watermark span full video width, maintaining aspect ratio
    val watermarkWidth = watermarkBitmap.width.toFloat()
    val watermarkHeight = watermarkBitmap.height.toFloat()
    val scale = videoWidth / watermarkWidth

    // Step 9: Create overlay settings for full-width bottom positioning
    // In Media3, coordinates are normalized: (0,0) is center
    // x range [-1, 1] (left to right), y range [-1, 1] (bottom to top)
    val overlaySettings = try {
      StaticOverlaySettings.Builder()
        .setScale(scale, scale)  // Scale uniformly to match video width
        .setOverlayFrameAnchor(0f, -1f)  // Anchor at bottom-center of watermark
        .setBackgroundFrameAnchor(0f, -1f)  // Position at very bottom of video
        .build()
    } catch (e: Exception) {
      watermarkBitmap.recycle()
      promise.reject("STEP9_OVERLAY_SETTINGS_ERROR", "[Step 9] Failed to create overlay settings: ${e.message}", e)
      return
    }

    // Step 10: Create the bitmap overlay with settings
    val bitmapOverlay = try {
      BitmapOverlay.createStaticBitmapOverlay(
        watermarkBitmap,
        overlaySettings
      )
    } catch (e: Exception) {
      watermarkBitmap.recycle()
      promise.reject("STEP10_BITMAP_OVERLAY_ERROR", "[Step 10] Failed to create bitmap overlay: ${e.message}", e)
      return
    }

    // Step 11: Create overlay effect with proper typing
    val overlayEffect = try {
      OverlayEffect(ImmutableList.of<TextureOverlay>(bitmapOverlay))
    } catch (e: Exception) {
      watermarkBitmap.recycle()
      promise.reject("STEP11_OVERLAY_EFFECT_ERROR", "[Step 11] Failed to create overlay effect: ${e.message}", e)
      return
    }

    // Step 12: Create effects with video overlay
    val effects = Effects(
      /* audioProcessors= */ listOf(),
      /* videoEffects= */ listOf(overlayEffect)
    )

    // Step 13: Create media item from video
    val mediaItem = MediaItem.fromUri("file://$cleanVideoPath")

    // Step 14: Create edited media item with effects
    val editedMediaItem = try {
      EditedMediaItem.Builder(mediaItem)
        .setEffects(effects)
        .build()
    } catch (e: Exception) {
      watermarkBitmap.recycle()
      promise.reject("STEP14_EDITED_MEDIA_ERROR", "[Step 14] Failed to create edited media item: ${e.message}", e)
      return
    }

    // Handler for main thread callbacks
    val mainHandler = Handler(Looper.getMainLooper())

    // Step 15: Build and start transformer
    mainHandler.post {
      try {
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
                "STEP15_TRANSFORM_ERROR",
                "[Step 15] Video transform failed (video: ${videoWidth.toInt()}x${videoHeight.toInt()}, rotation: $rotation, watermark: ${watermarkWidth.toInt()}x${watermarkHeight.toInt()}, scale: $scale): ${exportException.message ?: "Unknown error"}",
                exportException
              )
            }
          })
          .build()

        transformer.start(editedMediaItem, cleanOutputPath)
      } catch (e: Exception) {
        watermarkBitmap.recycle()
        promise.reject("STEP15_TRANSFORMER_BUILD_ERROR", "[Step 15] Failed to build/start transformer: ${e.message}", e)
      }
    }
  }
}
