import { registerWebModule, NativeModule } from 'expo';

class ExpoVideoWatermarkModule extends NativeModule {
  async watermarkVideo(_videoPath: string, _imagePath: string, _outputPath: string): Promise<string> {
    throw new Error('watermarkVideo is not supported on web');
  }
}

export default registerWebModule(ExpoVideoWatermarkModule, 'ExpoVideoWatermarkModule');
