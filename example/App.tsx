import ExpoVideoWatermark from 'expo-video-watermark';
import { Button, SafeAreaView, Text, View } from 'react-native';
import { Paths } from 'expo-file-system';

export default function App() {
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.header}>Video Watermark Example</Text>
        <Button
          title="Watermark video"
          onPress={async () => {
            try {
              // Replace inputVideoPath and inputImagePath with actual local file paths on device/emulator
              const inputVideoPath = '/path/to/input.mp4';
              const inputImagePath = '/path/to/watermark.png';
              const outputPath = Paths.document.uri + '/watermarked.mp4';

              const result = await ExpoVideoWatermark.watermarkVideo(inputVideoPath, inputImagePath, outputPath);
              alert('Watermarked video written to: ' + result);
            } catch (e: any) {
              alert('Watermark failed: ' + e.message);
            }
          }}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = {
  header: {
    fontSize: 24,
    marginBottom: 20,
  },
  content: {
    margin: 20,
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 20,
  },
  container: {
    flex: 1,
    backgroundColor: '#eee',
  },
};
