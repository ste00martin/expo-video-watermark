package expo.modules.videowatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.common.C
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
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext

class ExpoVideoWatermarkModule : Module() {
  companion object {
    private const val TAG = "ExpoVideoWatermark"

    /**
     * Check if the device has a hardware H.265/HEVC encoder
     */
    fun hasHevcEncoder(): Boolean {
      return try {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        codecList.codecInfos.any { codecInfo ->
          codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
            type.equals(MimeTypes.VIDEO_H265, ignoreCase = true)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check HEVC encoder support: ${e.message}")
        false
      }
    }

    /**
     * Get device information for debugging
     */
    fun getDeviceInfo(): String {
      return buildString {
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append(", Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append(", Board: ${Build.BOARD}")
        append(", Hardware: ${Build.HARDWARE}")
        append(", SOC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
      }
    }

    /**
     * Get OpenGL ES info (call on GL thread or main thread)
     */
    fun getGLInfo(): String {
      return try {
        val egl = EGLContext.getEGL() as? EGL10
        if (egl != null) {
          val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
          egl.eglInitialize(display, IntArray(2))

          val configAttribs = intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_NONE
          )
          val configs = arrayOfNulls<EGLConfig>(1)
          val numConfigs = IntArray(1)
          egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)

          val vendor = egl.eglQueryString(display, EGL10.EGL_VENDOR) ?: "unknown"
          val version = egl.eglQueryString(display, EGL10.EGL_VERSION) ?: "unknown"
          val extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS) ?: ""

          egl.eglTerminate(display)

          "EGL Vendor: $vendor, Version: $version, Has OES_EGL_image_external: ${extensions.contains("EGL_KHR_image_base")}"
        } else {
          "EGL not available"
        }
      } catch (e: Exception) {
        "GL info error: ${e.message}"
      }
    }

    /**
     * Map ExportException error code to human-readable string
     */
    @OptIn(UnstableApi::class)
    fun getExportErrorCodeName(errorCode: Int): String {
      return when (errorCode) {
        ExportException.ERROR_CODE_UNSPECIFIED -> "ERROR_CODE_UNSPECIFIED"
        ExportException.ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ERROR_CODE_IO_FILE_NOT_FOUND"
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
        ExportException.ERROR_CODE_DECODER_INIT_FAILED -> "ERROR_CODE_DECODER_INIT_FAILED"
        ExportException.ERROR_CODE_DECODING_FAILED -> "ERROR_CODE_DECODING_FAILED"
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "ERROR_CODE_ENCODER_INIT_FAILED"
        ExportException.ERROR_CODE_ENCODING_FAILED -> "ERROR_CODE_ENCODING_FAILED"
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED"
        ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"
        ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> "ERROR_CODE_AUDIO_PROCESSING_FAILED"
        ExportException.ERROR_CODE_MUXING_FAILED -> "ERROR_CODE_MUXING_FAILED"
        ExportException.ERROR_CODE_MUXING_TIMEOUT -> "ERROR_CODE_MUXING_TIMEOUT"
        else -> "UNKNOWN_ERROR_CODE($errorCode)"
      }
    }
  }

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
      Log.d(TAG, "[Step 4] Converting bitmap from ${decodedBitmap.config} to ARGB_8888")
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

    // Log bitmap details for debugging
    val bitmapInfo = buildString {
      append("size=${watermarkBitmap.width}x${watermarkBitmap.height}, ")
      append("config=${watermarkBitmap.config}, ")
      append("byteCount=${watermarkBitmap.byteCount / 1024}KB, ")
      append("hasAlpha=${watermarkBitmap.hasAlpha()}, ")
      append("isPremultiplied=${watermarkBitmap.isPremultiplied}")
    }
    Log.d(TAG, "[Step 4] Watermark bitmap: $bitmapInfo")

    // Step 5: Ensure output directory exists
    val outputFile = File(cleanOutputPath)
    outputFile.parentFile?.mkdirs()

    // Remove existing output file if present
    if (outputFile.exists()) {
      outputFile.delete()
    }

    // Step 6: Get video dimensions and metadata to calculate scale
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

    // Capture additional video metadata for debugging
    val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "unknown"
    val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
    val colorStandard = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD) ?: "unknown"
    val colorTransfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER) ?: "unknown"
    val colorTransferInt = colorTransfer.toIntOrNull()
    // Check for HDR color transfer characteristics. 6 = PQ (ST2084), 7 = HLG. See MediaFormat constants.
    val isHdr = colorTransferInt == 6 || colorTransferInt == 7
    retriever.release()

    // Build comprehensive video info string for debugging
    val videoInfo = buildString {
      append("mime=$mimeType, ")
      append("bitrate=${bitrate / 1000}kbps, ")
      append("duration=${duration}ms, ")
      append("frameRate=$frameRate, ")
      append("colorStandard=$colorStandard, ")
      append("colorTransfer=$colorTransfer (isHdr=$isHdr)")
    }
    Log.d(TAG, "[Step 6] Video metadata: $videoInfo")

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

    // Step 8: Pre-scale watermark bitmap to match video width if needed
    val originalWidth = watermarkBitmap.width
    val originalHeight = watermarkBitmap.height
    val targetWidth = videoWidth.toInt()
    val scale = targetWidth.toFloat() / originalWidth.toFloat()
    val targetHeight = (originalHeight * scale).toInt()

    // Skip scaling if watermark already matches video width
    val scaledWatermark: Bitmap = if (originalWidth == targetWidth) {
      Log.d(TAG, "[Step 8] Watermark already matches video width (${originalWidth}x${originalHeight}), skipping scale")
      watermarkBitmap
    } else {
      Log.d(TAG, "[Step 8] Pre-scaling watermark: ${originalWidth}x${originalHeight} -> ${targetWidth}x${targetHeight} (scale: $scale)")
      try {
        val scaled = Bitmap.createScaledBitmap(watermarkBitmap, targetWidth, targetHeight, true)
        // Recycle original if we created a new scaled version
        if (scaled !== watermarkBitmap) {
          watermarkBitmap.recycle()
        }
        scaled
      } catch (e: Exception) {
        watermarkBitmap.recycle()
        promise.reject("STEP8_SCALE_ERROR", "[Step 8] Failed to scale watermark bitmap: ${e.message}", e)
        return
      }
    }

    // Step 9: Create overlay settings for bottom positioning (no GPU scaling needed)
    // In Media3, coordinates are normalized: (0,0) is center
    // x range [-1, 1] (left to right), y range [-1, 1] (bottom to top)
    val overlaySettings = try {
      StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, -1f)  // Anchor at bottom-center of watermark
        .setBackgroundFrameAnchor(0f, -1f)  // Position at very bottom of video
        .build()
    } catch (e: Exception) {
      scaledWatermark.recycle()
      promise.reject("STEP9_OVERLAY_SETTINGS_ERROR", "[Step 9] Failed to create overlay settings: ${e.message}", e)
      return
    }

    // Step 10: Create the bitmap overlay with pre-scaled bitmap
    val bitmapOverlay = try {
      BitmapOverlay.createStaticBitmapOverlay(
        scaledWatermark,
        overlaySettings
      )
    } catch (e: Exception) {
      scaledWatermark.recycle()
      promise.reject("STEP10_BITMAP_OVERLAY_ERROR", "[Step 10] Failed to create bitmap overlay: ${e.message}", e)
      return
    }

    // Step 11: Create overlay effect with proper typing
    val overlayEffect = try {
      OverlayEffect(ImmutableList.of<TextureOverlay>(bitmapOverlay))
    }
    catch (e: Exception) {
      scaledWatermark.recycle()
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

    // Step 14: Create edited media item (HDR mode is set on Composition, not EditedMediaItem in 1.9.1+)
    val editedMediaItem = try {
      EditedMediaItem.Builder(mediaItem)
        .setEffects(effects)
        .build()
    } catch (e: Exception) {
      scaledWatermark.recycle()
      promise.reject("STEP14_EDITED_MEDIA_ERROR", "[Step 14] Failed to create edited media item: ${e.message}", e)
      return
    }

    // Step 14b: Create composition with EditedMediaItemSequence (required in Media3 1.9.1+)
    val composition = try {
      // Wrap EditedMediaItem in a sequence (must specify track types in 1.9.1+)
      val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
        .addItem(editedMediaItem)
        .build()

      // Build composition with HDR mode if needed
      val compositionBuilder = Composition.Builder(sequence)
      if (isHdr) {
        Log.d(TAG, "[Step 14b] HDR video detected. Applying tone-mapping to composition.")
        compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
      } else {
        Log.d(TAG, "[Step 14b] SDR video detected. No tone-mapping needed.")
      }
      compositionBuilder.build()
    } catch (e: Exception) {
      scaledWatermark.recycle()
      promise.reject("STEP14B_COMPOSITION_ERROR", "[Step 14b] Failed to create composition: ${e.message}", e)
      return
    }
    Log.d(TAG, "[Step 14b] Composition created successfully.")

    // Handler for main thread callbacks
    val mainHandler = Handler(Looper.getMainLooper())

    // Gather device and GL info for debugging (do this before posting to main thread)
    val deviceInfo = getDeviceInfo()
    val glInfo = getGLInfo()
    Log.d(TAG, "[Step 15] Starting transform on device: $deviceInfo")
    Log.d(TAG, "[Step 15] GL info: $glInfo")

    // Step 15: Build and start transformer
    mainHandler.post {
      try {
        val transformer = Transformer.Builder(context)
          // Force H.264 output for maximum compatibility
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              Log.d(TAG, "[Step 15] Transform completed successfully")
              Log.d(TAG, "[Step 15] Export result - durationMillis: ${exportResult.durationMillis}, " +
                "fileSizeBytes: ${exportResult.fileSizeBytes}, " +
                "averageAudioBitrate: ${exportResult.averageAudioBitrate}, " +
                "averageVideoBitrate: ${exportResult.averageVideoBitrate}, " +
                "videoFrameCount: ${exportResult.videoFrameCount}")
              scaledWatermark.recycle()

              // Step 16: Re-encode to H.265 if device supports HEVC encoder
              val supportsHevc = hasHevcEncoder()
              Log.d(TAG, "[Step 16] HEVC encoder support: $supportsHevc")

              if (!supportsHevc) {
                Log.d(TAG, "[Step 16] Skipping H.265 re-encode - no HEVC encoder available")
                promise.resolve("file://$cleanOutputPath")
                return
              }

              // Create temp path for the H.264 watermarked video, rename current output
              val h264File = File(cleanOutputPath)
              val h264TempPath = cleanOutputPath.replace(".mp4", "_h264_temp.mp4")
              val h264TempFile = File(h264TempPath)

              if (!h264File.renameTo(h264TempFile)) {
                Log.e(TAG, "[Step 16] Failed to rename H.264 file for re-encoding, returning H.264 output")
                promise.resolve("file://$cleanOutputPath")
                return
              }

              Log.d(TAG, "[Step 16] Starting H.265 re-encode from: $h264TempPath to: $cleanOutputPath")

              // Build transformer for H.265 re-encoding
              val hevcTransformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .addListener(object : Transformer.Listener {
                  override fun onCompleted(composition: Composition, hevcExportResult: ExportResult) {
                    Log.d(TAG, "[Step 16] H.265 re-encode completed successfully")
                    Log.d(TAG, "[Step 16] Export result - durationMillis: ${hevcExportResult.durationMillis}, " +
                      "fileSizeBytes: ${hevcExportResult.fileSizeBytes}, " +
                      "averageAudioBitrate: ${hevcExportResult.averageAudioBitrate}, " +
                      "averageVideoBitrate: ${hevcExportResult.averageVideoBitrate}, " +
                      "videoFrameCount: ${hevcExportResult.videoFrameCount}")

                    // Calculate compression ratio
                    val h264Size = exportResult.fileSizeBytes
                    val h265Size = hevcExportResult.fileSizeBytes
                    if (h264Size > 0 && h265Size > 0) {
                      val savings = ((h264Size - h265Size) * 100.0 / h264Size)
                      Log.d(TAG, "[Step 16] Size reduction: H.264=${h264Size/1024}KB -> H.265=${h265Size/1024}KB (${String.format("%.1f", savings)}% smaller)")
                    }

                    // Clean up temp H.264 file
                    if (h264TempFile.exists()) {
                      h264TempFile.delete()
                      Log.d(TAG, "[Step 16] Cleaned up temp H.264 file")
                    }

                    promise.resolve("file://$cleanOutputPath")
                  }

                  override fun onError(
                    composition: Composition,
                    hevcExportResult: ExportResult,
                    hevcExportException: ExportException
                  ) {
                    val errorCodeName = getExportErrorCodeName(hevcExportException.errorCode)
                    Log.e(TAG, "[Step 16] H.265 re-encode failed: $errorCodeName - ${hevcExportException.message}")
                    Log.e(TAG, Log.getStackTraceString(hevcExportException))

                    // Restore H.264 file as output on failure
                    if (h264TempFile.exists()) {
                      val outputFile = File(cleanOutputPath)
                      if (outputFile.exists()) {
                        outputFile.delete()
                      }
                      h264TempFile.renameTo(outputFile)
                      Log.d(TAG, "[Step 16] Restored H.264 output after H.265 failure")
                    }

                    // Still resolve with the H.264 version rather than failing completely
                    Log.d(TAG, "[Step 16] Returning H.264 output instead")
                    promise.resolve("file://$cleanOutputPath")
                  }
                })
                .build()

              val hevcMediaItem = MediaItem.fromUri("file://$h264TempPath")
              val hevcEditedMediaItem = EditedMediaItem.Builder(hevcMediaItem).build()
              hevcTransformer.start(hevcEditedMediaItem, cleanOutputPath)
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException
            ) {
              // Build comprehensive error message with all diagnostic info
              val errorCodeName = getExportErrorCodeName(exportException.errorCode)

              val diagnosticInfo = buildString {
                appendLine("=== STEP 15 TRANSFORM ERROR ===")
                appendLine("Error code: ${exportException.errorCode} ($errorCodeName)")
                appendLine("Error message: ${exportException.message ?: "null"}")
                appendLine("Cause: ${exportException.cause?.message ?: "null"}")
                appendLine("Cause class: ${exportException.cause?.javaClass?.name ?: "null"}")
                appendLine()
                appendLine("--- Device Info ---")
                appendLine(deviceInfo)
                appendLine()
                appendLine("--- GL Info ---")
                appendLine(glInfo)
                appendLine()
                appendLine("--- Video Info ---")
                appendLine("Dimensions (raw): ${rawVideoWidth.toInt()}x${rawVideoHeight.toInt()}")
                appendLine("Dimensions (adjusted): ${videoWidth.toInt()}x${videoHeight.toInt()}")
                appendLine("Rotation: $rotation")
                appendLine("Metadata: $videoInfo")
                appendLine("Input path: $cleanVideoPath")
                appendLine()
                appendLine("--- Watermark Info ---")
                appendLine("Original dimensions: ${originalWidth}x${originalHeight}")
                appendLine("Scaled dimensions: ${targetWidth}x${targetHeight}")
                appendLine("Bitmap info: $bitmapInfo")
                appendLine("Scale factor: $scale")
                appendLine()
                appendLine("--- Output Info ---")
                appendLine("Output path: $cleanOutputPath")
                appendLine("Partial export result - durationMillis: ${exportResult.durationMillis}, " +
                  "fileSizeBytes: ${exportResult.fileSizeBytes}")
                appendLine()
                appendLine("--- Full Stack Trace ---")
                append(Log.getStackTraceString(exportException))
              }

              // Log full diagnostics
              Log.e(TAG, diagnosticInfo)

              // Also log any nested causes
              var cause: Throwable? = exportException.cause
              var causeLevel = 1
              while (cause != null) {
                Log.e(TAG, "[Step 15] Cause level $causeLevel: ${cause.javaClass.name}: ${cause.message}")
                Log.e(TAG, Log.getStackTraceString(cause))
                cause = cause.cause
                causeLevel++
              }

              scaledWatermark.recycle()

              // Reject with comprehensive error message
              val errorMessage = "[Step 15] Transform failed - " +
                "ErrorCode: $errorCodeName (${exportException.errorCode}), " +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}), " +
                "Video: ${videoWidth.toInt()}x${videoHeight.toInt()} $mimeType, " +
                "Watermark: ${originalWidth}x${originalHeight} -> ${targetWidth}x${targetHeight}, " +
                "Scale: $scale, " +
                "Message: ${exportException.message ?: "Unknown error"}"

              promise.reject(
                "STEP15_TRANSFORM_ERROR",
                errorMessage,
                exportException
              )
            }
          })
          .build()

        Log.d(TAG, "[Step 15] Transformer built, starting export...")
        transformer.start(composition, cleanOutputPath)
        Log.d(TAG, "[Step 15] Transformer.start() called, waiting for completion...")
      } catch (e: Exception) {
        Log.e(TAG, "[Step 15] Exception building/starting transformer", e)
        Log.e(TAG, "[Step 15] Device info: $deviceInfo")
        Log.e(TAG, "[Step 15] GL info: $glInfo")
        scaledWatermark.recycle()
        promise.reject(
          "STEP15_TRANSFORMER_BUILD_ERROR",
          "[Step 15] Failed to build/start transformer on ${Build.MANUFACTURER} ${Build.MODEL}: ${e.message}",
          e
        )
      }
    }
  }
}
