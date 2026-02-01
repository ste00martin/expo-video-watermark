import { NativeModule, requireNativeModule } from 'expo';

declare class ExpoVideoWatermarkModule extends NativeModule {
  /**
   * Adds a watermark image onto a video and writes the result to `outputPath`.
   * @param videoPath Local filesystem path to the source MP4 video
   * @param imagePath Local filesystem path to the PNG watermark image
   * @param outputPath Local filesystem path where the output MP4 should be written
   * @returns A promise that resolves with the output path on success.
   */
  watermarkVideo(videoPath: string, imagePath: string, outputPath: string): Promise<string>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoVideoWatermarkModule>('ExpoVideoWatermark');
