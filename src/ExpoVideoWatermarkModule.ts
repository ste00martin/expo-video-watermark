import { NativeModule, requireNativeModule } from 'expo';

import { ExpoVideoWatermarkModuleEvents } from './ExpoVideoWatermark.types';

declare class ExpoVideoWatermarkModule extends NativeModule<ExpoVideoWatermarkModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoVideoWatermarkModule>('ExpoVideoWatermark');
