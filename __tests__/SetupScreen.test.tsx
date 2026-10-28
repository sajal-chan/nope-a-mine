import React from 'react';
import { AppState, AppStateStatus, Button, Text } from 'react-native';
import { act, create, ReactTestRenderer } from 'react-test-renderer';
import App from '../App';
import { blocking } from '../src/native/BlockingModule';

jest.mock('../src/native/BlockingModule', () => ({
  blocking: { getStatus: jest.fn(), openAccessibilitySettings: jest.fn() },
}));

const status = jest.mocked(blocking.getStatus);
const openSettings = jest.mocked(blocking.openAccessibilitySettings);
let tree: ReactTestRenderer;
const text = () =>
  tree.root
    .findAllByType(Text)
    .map(node => node.props.children)
    .join(' ');
const button = (title: string) =>
  tree.root.findAllByType(Button).find(node => node.props.title === title)!;

beforeEach(() => {
  jest.clearAllMocks();
  jest
    .spyOn(AppState, 'addEventListener')
    .mockReturnValue({ remove: jest.fn() });
  status.mockResolvedValue({
    enabled: false,
    connected: false,
    blocking: false,
    error: null,
  });
  openSettings.mockResolvedValue(undefined);
});
afterEach(async () => {
  if (tree) {
    await act(async () => tree.unmount());
  }
  jest.restoreAllMocks();
});

it('discloses access and requires explicit consent before opening settings', async () => {
  await act(async () => {
    tree = create(<App />);
  });
  expect(text()).toContain('foreground app');
  expect(text()).toContain('No schedules or emergency access');
  expect(openSettings).not.toHaveBeenCalled();
  await act(async () =>
    button('I agree — open accessibility settings').props.onPress(),
  );
  expect(openSettings).toHaveBeenCalledTimes(1);
});

it('distinguishes enabled permission from a connected service', async () => {
  status.mockResolvedValue({
    enabled: true,
    connected: false,
    blocking: false,
    error: null,
  });
  await act(async () => {
    tree = create(<App />);
  });
  expect(text()).toContain('Enabled, service disconnected');
});

it('refreshes native status when returning from Android settings', async () => {
  let listener: (state: AppStateStatus) => void = () => {};
  const remove = jest.fn();
  jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_event, callback) => {
      listener = callback;
      return { remove };
    });
  await act(async () => {
    tree = create(<App />);
  });
  status.mockResolvedValue({
    enabled: true,
    connected: true,
    blocking: false,
    error: null,
  });
  await act(async () => listener('active'));
  expect(text()).toContain('Service connected');
  await act(async () => tree.unmount());
  expect(remove).toHaveBeenCalled();
});

it('reports settings errors rather than silently failing', async () => {
  openSettings.mockRejectedValue(new Error('Settings unavailable'));
  await act(async () => {
    tree = create(<App />);
  });
  await act(async () =>
    button('I agree — open accessibility settings').props.onPress(),
  );
  expect(text()).toContain('Settings unavailable');
});

it('reports status errors without claiming enforcement is active', async () => {
  status.mockRejectedValue(new Error('Native status unavailable'));
  await act(async () => {
    tree = create(<App />);
  });
  expect(text()).toContain('Native status unavailable');
  expect(text()).not.toContain('Service connected');
});
