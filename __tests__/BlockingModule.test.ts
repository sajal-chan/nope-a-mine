import { NativeModules } from 'react-native';
import { blocking } from '../src/native/BlockingModule';

afterEach(() => {
  delete NativeModules.Blocking;
});

it('forwards configuration reads including effective and editable policy', async () => {
  const snapshot = {
    policy: null,
    activeSession: null,
    effectiveFrom: null,
    changesDeferred: false,
    supportedApps: [],
  };
  NativeModules.Blocking = {
    getScheduleConfiguration: jest.fn().mockResolvedValue(snapshot),
  };
  await expect(blocking.getScheduleConfiguration()).resolves.toEqual(snapshot);
  expect(NativeModules.Blocking.getScheduleConfiguration).toHaveBeenCalledTimes(
    1,
  );
});

it('saves the selected policy without adding JavaScript authorization timestamps', async () => {
  const policy = {
    startTime: '06:00',
    endTime: '09:00',
    weekdays: [1, 2],
    packages: ['com.instagram.android'],
  };
  const snapshot = {
    policy,
    activeSession: null,
    effectiveFrom: '2026-11-02',
    changesDeferred: false,
    supportedApps: [],
  };
  NativeModules.Blocking = {
    saveScheduleConfiguration: jest.fn().mockResolvedValue(snapshot),
  };
  await expect(blocking.saveScheduleConfiguration(policy)).resolves.toEqual(
    snapshot,
  );
  expect(NativeModules.Blocking.saveScheduleConfiguration).toHaveBeenCalledWith(
    policy,
  );
});

it('preserves configuration read and save failures', async () => {
  const error = new Error('Native storage unavailable');
  NativeModules.Blocking = {
    getScheduleConfiguration: jest.fn().mockRejectedValue(error),
    saveScheduleConfiguration: jest.fn().mockRejectedValue(error),
  };
  await expect(blocking.getScheduleConfiguration()).rejects.toBe(error);
  await expect(
    blocking.saveScheduleConfiguration({
      startTime: '06:00',
      endTime: '09:00',
      weekdays: [],
      packages: [],
    }),
  ).rejects.toBe(error);
});

it('forwards authoritative status from Kotlin', async () => {
  const status = {
    enabled: true,
    connected: true,
    blocking: false,
    error: null,
  };
  NativeModules.Blocking = { getStatus: jest.fn().mockResolvedValue(status) };
  await expect(blocking.getStatus()).resolves.toEqual(status);
  expect(NativeModules.Blocking.getStatus).toHaveBeenCalledTimes(1);
});

it('opens native settings only when requested', async () => {
  NativeModules.Blocking = {
    openAccessibilitySettings: jest.fn().mockResolvedValue(undefined),
  };
  await blocking.openAccessibilitySettings();
  expect(
    NativeModules.Blocking.openAccessibilitySettings,
  ).toHaveBeenCalledTimes(1);
});

it('reports an unavailable native bridge explicitly', async () => {
  delete NativeModules.Blocking;
  await expect(blocking.getStatus()).rejects.toThrow(
    'Android blocking module is unavailable',
  );
});

it('preserves native settings failures', async () => {
  NativeModules.Blocking = {
    openAccessibilitySettings: jest
      .fn()
      .mockRejectedValue(new Error('Settings unavailable')),
  };
  await expect(blocking.openAccessibilitySettings()).rejects.toThrow(
    'Settings unavailable',
  );
});
