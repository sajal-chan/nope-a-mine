import { NativeModules } from 'react-native';

export type BlockingStatus = {
  enabled: boolean;
  connected: boolean;
  blocking: boolean;
  error: string | null;
};

export type SchedulePolicy = {
  startTime: string;
  endTime: string;
  weekdays: number[];
  packages: string[];
};

export type ScheduleSnapshot = {
  policy: SchedulePolicy | null;
  activeSession: (SchedulePolicy & { date: string }) | null;
  effectiveFrom: string | null;
  changesDeferred: boolean;
  supportedApps: { packageName: string; label: string }[];
};

export const blocking = {
  async getScheduleConfiguration(): Promise<ScheduleSnapshot> {
    return nativeModule().getScheduleConfiguration();
  },
  async saveScheduleConfiguration(
    policy: SchedulePolicy,
  ): Promise<ScheduleSnapshot> {
    return nativeModule().saveScheduleConfiguration(policy);
  },
  async getStatus(): Promise<BlockingStatus> {
    return nativeModule().getStatus();
  },
  async openAccessibilitySettings(): Promise<void> {
    return nativeModule().openAccessibilitySettings();
  },
};

function nativeModule(): {
  getScheduleConfiguration(): Promise<ScheduleSnapshot>;
  saveScheduleConfiguration(policy: SchedulePolicy): Promise<ScheduleSnapshot>;
  getStatus(): Promise<BlockingStatus>;
  openAccessibilitySettings(): Promise<void>;
} {
  const module = NativeModules.Blocking;
  if (!module) {
    throw new Error('Android blocking module is unavailable');
  }
  return module;
}
