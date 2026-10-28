import { NativeModules } from 'react-native';

export type BlockingStatus = {
  enabled: boolean;
  connected: boolean;
  blocking: boolean;
  error: string | null;
};

export const blocking = {
  async getStatus(): Promise<BlockingStatus> {
    return nativeModule().getStatus();
  },
  async openAccessibilitySettings(): Promise<void> {
    return nativeModule().openAccessibilitySettings();
  },
};

function nativeModule(): {
  getStatus(): Promise<BlockingStatus>;
  openAccessibilitySettings(): Promise<void>;
} {
  const module = NativeModules.Blocking;
  if (!module) {
    throw new Error('Android blocking module is unavailable');
  }
  return module;
}
