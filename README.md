# expo-video-watermark

An Expo native module for adding watermark images to videos on iOS and Android.

## Installation

```bash
npx expo install @stefanmartin/expo-video-watermark
```

## Usage

```typescript
import ExpoVideoWatermark from '@stefanmartin/expo-video-watermark';

// Add a watermark to a video
const outputPath = await ExpoVideoWatermark.watermarkVideo(
  '/path/to/source/video.mp4',
  '/path/to/watermark.png',
  '/path/to/output/video.mp4'
);

console.log('Watermarked video saved to:', outputPath);
```

## API

### `watermarkVideo(videoPath, imagePath, outputPath)`

Adds a watermark image onto a video and writes the result to the specified output path.

**Parameters:**

| Name | Type | Description |
|------|------|-------------|
| `videoPath` | `string` | Local filesystem path to the source MP4 video |
| `imagePath` | `string` | Local filesystem path to the PNG watermark image |
| `outputPath` | `string` | Local filesystem path where the output MP4 should be written |

**Returns:** `Promise<string>` - Resolves with the output path on success.

## Platform Support

| Platform | Supported |
|----------|-----------|
| iOS | Yes |
| Android | Yes |
| Web | No |

## License

MIT
