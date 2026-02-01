import * as React from 'react';

import { ExpoVideoWatermarkViewProps } from './ExpoVideoWatermark.types';

export default function ExpoVideoWatermarkView(props: ExpoVideoWatermarkViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
