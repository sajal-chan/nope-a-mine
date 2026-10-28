import { NativeModules } from 'react-native';
import { blocking } from '../src/native/BlockingModule';

afterEach(() => {
  delete NativeModules.Blocking;
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
