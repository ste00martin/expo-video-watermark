import { registerWebModule, NativeModule } from 'expo';

import { ExpoVideoWatermarkModuleEvents } from './ExpoVideoWatermark.types';

class ExpoVideoWatermarkModule extends NativeModule<ExpoVideoWatermarkModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoVideoWatermarkModule, 'ExpoVideoWatermarkModule');
