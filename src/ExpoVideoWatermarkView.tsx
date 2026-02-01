import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoVideoWatermarkViewProps } from './ExpoVideoWatermark.types';

const NativeView: React.ComponentType<ExpoVideoWatermarkViewProps> =
  requireNativeView('ExpoVideoWatermark');

export default function ExpoVideoWatermarkView(props: ExpoVideoWatermarkViewProps) {
  return <NativeView {...props} />;
}
