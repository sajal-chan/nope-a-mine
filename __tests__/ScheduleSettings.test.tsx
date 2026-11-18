import React from 'react';
import {
  AppState,
  AppStateStatus,
  Button,
  Text,
  TextInput,
} from 'react-native';
import { act, create, ReactTestRenderer } from 'react-test-renderer';
import App from '../App';
import {
  blocking,
  SchedulePolicy,
  ScheduleSnapshot,
} from '../src/native/BlockingModule';

jest.mock('../src/native/BlockingModule', () => ({
  blocking: {
    getStatus: jest.fn(),
    openAccessibilitySettings: jest.fn(),
    getScheduleConfiguration: jest.fn(),
    saveScheduleConfiguration: jest.fn(),
  },
}));

const read = jest.mocked(blocking.getScheduleConfiguration);
const save = jest.mocked(blocking.saveScheduleConfiguration);
const apps = [
  { packageName: 'com.instagram.android', label: 'Instagram' },
  { packageName: 'com.google.android.youtube', label: 'YouTube' },
  { packageName: 'com.reddit.frontpage', label: 'Reddit' },
];
const policy: SchedulePolicy = {
  startTime: '06:00',
  endTime: '09:00',
  weekdays: [1, 2, 3, 4, 5, 6, 7],
  packages: ['com.instagram.android'],
};
const snapshot = (
  selected: SchedulePolicy | null = null,
): ScheduleSnapshot => ({
  policy: selected,
  activeSession: null,
  effectiveFrom: selected ? '2026-11-18' : null,
  changesDeferred: false,
  supportedApps: apps,
});
let tree: ReactTestRenderer;
let listeners: ((state: AppStateStatus) => void)[];
const text = () =>
  tree.root
    .findAllByType(Text)
    .map(node => React.Children.toArray(node.props.children).join(''))
    .join(' ');
const button = (title: string) =>
  tree.root.findAllByType(Button).find(node => node.props.title === title)!;
const field = (label: string) =>
  tree.root
    .findAllByType(TextInput)
    .find(node => node.props.accessibilityLabel === label)!;
const choice = (label: string) =>
  tree.root.findAll(
    node =>
      node.props.accessibilityRole === 'checkbox' &&
      node.props.accessibilityLabel === label &&
      typeof node.props.onPress === 'function',
  )[0];
const mount = async () => {
  await act(async () => {
    tree = create(<App />);
  });
};
const edit = async (label: string, value: string) => {
  await act(async () => field(label).props.onChangeText(value));
};
const press = async (title: string) => {
  await act(async () => {
    button(title).props.onPress();
  });
};
const toggle = async (label: string) => {
  await act(async () => choice(label).props.onPress());
};
const appState = async (state: AppStateStatus) => {
  await act(async () => listeners.forEach(listener => listener(state)));
};

beforeEach(() => {
  jest.useFakeTimers();
  jest.clearAllMocks();
  listeners = [];
  jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_event, callback) => {
      listeners.push(callback);
      return {
        remove: () => {
          listeners = listeners.filter(listener => listener !== callback);
        },
      };
    });
  jest.mocked(blocking.getStatus).mockResolvedValue({
    enabled: true,
    connected: true,
    blocking: false,
    error: null,
  });
  read.mockResolvedValue(snapshot());
  save.mockImplementation(async selected => snapshot(selected));
});
afterEach(async () => {
  if (tree) await act(async () => tree.unmount());
  jest.clearAllTimers();
  jest.useRealTimers();
  jest.restoreAllMocks();
});

it('loads native choices without saving a default or pretending a schedule already exists', async () => {
  await mount();
  expect(text()).toContain('No schedule saved');
  expect(field('Start time').props.value).toBe('06:00');
  expect(field('End time').props.value).toBe('09:00');
  for (const app of apps)
    expect(choice(app.label).props.accessibilityState.checked).toBe(false);
  expect(save).not.toHaveBeenCalled();
  expect(button('Save schedule').props.disabled).toBe(false);
  expect(text()).not.toContain(
    'Schedule configuration and emergency access are not available',
  );
});

it('reopens saved editable policy while showing the distinct native active session', async () => {
  const edited = {
    ...policy,
    startTime: '07:00',
    endTime: '08:00',
    weekdays: [1, 3],
    packages: ['com.google.android.youtube'],
  };
  read.mockResolvedValue({
    ...snapshot(edited),
    activeSession: { ...policy, date: '2026-11-18' },
    changesDeferred: true,
    effectiveFrom: '2026-11-19',
  });
  await mount();
  expect(field('Start time').props.value).toBe('07:00');
  expect(choice('YouTube').props.accessibilityState.checked).toBe(true);
  expect(choice('Instagram').props.accessibilityState.checked).toBe(false);
  expect(choice('Tuesday').props.accessibilityState.checked).toBe(false);
  expect(text()).toContain('Changes apply from 2026-11-19');
  expect(text()).toContain('Active session: 06:00–09:00');
  expect(text()).toContain('Instagram');
});

it('saves app selection ISO weekdays and edited times without JavaScript deadlines', async () => {
  await mount();
  await toggle('Instagram');
  await toggle('Reddit');
  await toggle('Tuesday');
  await edit('Start time', '07:15');
  await edit('End time', '09:45');
  await press('Save schedule');
  expect(save).toHaveBeenCalledWith({
    startTime: '07:15',
    endTime: '09:45',
    weekdays: [1, 3, 4, 5, 6, 7],
    packages: ['com.instagram.android', 'com.reddit.frontpage'],
  });
  expect(text()).toContain('Schedule saved.');
});

it('rejects malformed equal and overnight windows before asking native storage to save', async () => {
  await mount();
  for (const start of ['7:00', '24:00', '09:00', '10:00']) {
    await edit('Start time', start);
    await press('Save schedule');
    expect(
      tree.root.findAllByProps({ accessibilityRole: 'alert' }).length,
    ).toBeGreaterThan(0);
  }
  expect(save).not.toHaveBeenCalled();
});

it('allows explicit empty selections without implying the current session was cancelled', async () => {
  await mount();
  for (const day of [
    'Monday',
    'Tuesday',
    'Wednesday',
    'Thursday',
    'Friday',
    'Saturday',
    'Sunday',
  ])
    await toggle(day);
  await press('Save schedule');
  expect(save).toHaveBeenCalledWith({
    startTime: '06:00',
    endTime: '09:00',
    weekdays: [],
    packages: [],
  });
  expect(text()).toContain('No future blocking is selected');
});

it('keeps the draft and reports native save failure without a success claim', async () => {
  save.mockRejectedValue(new Error('Disk unavailable'));
  await mount();
  await edit('Start time', '07:30');
  await press('Save schedule');
  expect(text()).toContain('Disk unavailable');
  expect(text()).not.toContain('Schedule saved.');
  expect(field('Start time').props.value).toBe('07:30');
});

it('prevents saving unknown configuration after a load failure and supports retry', async () => {
  read.mockRejectedValueOnce(new Error('Cannot read saved policy'));
  await mount();
  expect(text()).toContain('Cannot read saved policy');
  expect(button('Save schedule').props.disabled).toBe(true);
  await press('Reload schedule');
  expect(button('Save schedule').props.disabled).toBe(false);
  expect(text()).not.toContain('Cannot read saved policy');
});

it('uses the native save response to explain deferral and retain the original active window', async () => {
  save.mockImplementation(async selected => ({
    ...snapshot(selected),
    activeSession: { ...policy, date: '2026-11-18' },
    changesDeferred: true,
    effectiveFrom: '2026-11-19',
  }));
  await mount();
  await toggle('YouTube');
  await edit('End time', '08:00');
  await press('Save schedule');
  expect(text()).toContain('Changes apply from 2026-11-19');
  expect(text()).toContain('Active session: 06:00–09:00');
  expect(field('End time').props.value).toBe('08:00');
});

it('refreshes native state on return and periodically without overwriting unfinished edits', async () => {
  read.mockResolvedValue(snapshot(policy));
  await mount();
  await edit('Start time', '07:30');
  read.mockResolvedValue({
    ...snapshot(policy),
    activeSession: { ...policy, date: '2026-11-18' },
  });
  await appState('active');
  expect(text()).toContain('Active session: 06:00–09:00');
  expect(field('Start time').props.value).toBe('07:30');
  const calls = read.mock.calls.length;
  read.mockResolvedValue(snapshot(policy));
  await act(async () => {
    jest.advanceTimersByTime(5_000);
  });
  expect(read.mock.calls.length).toBeGreaterThan(calls);
  expect(text()).not.toContain('Active session:');
  expect(field('Start time').props.value).toBe('07:30');
});

it('does not poll schedule UI in the background or after unmounting', async () => {
  await mount();
  await appState('background');
  const calls = read.mock.calls.length;
  await act(async () => {
    jest.advanceTimersByTime(15_000);
  });
  expect(read).toHaveBeenCalledTimes(calls);
  await act(async () => tree.unmount());
  await act(async () => {
    jest.advanceTimersByTime(15_000);
  });
  expect(read).toHaveBeenCalledTimes(calls);
  expect(listeners).toHaveLength(0);
});

it('ignores older refresh responses that arrive after a newer native snapshot', async () => {
  await mount();
  let oldResolve: (value: ScheduleSnapshot) => void = () => {};
  read.mockReturnValueOnce(
    new Promise(resolve => {
      oldResolve = resolve;
    }),
  );
  await press('Reload schedule');
  read.mockResolvedValueOnce(
    snapshot({ ...policy, packages: ['com.reddit.frontpage'] }),
  );
  await press('Reload schedule');
  expect(choice('Reddit').props.accessibilityState.checked).toBe(true);
  await act(async () => oldResolve(snapshot(policy)));
  expect(choice('Reddit').props.accessibilityState.checked).toBe(true);
  expect(choice('Instagram').props.accessibilityState.checked).toBe(false);
});

it('prevents duplicate saves and refreshes from racing a pending write', async () => {
  await mount();
  let finish: (value: ScheduleSnapshot) => void = () => {};
  save.mockReturnValueOnce(
    new Promise(resolve => {
      finish = resolve;
    }),
  );
  await act(async () => {
    button('Save schedule').props.onPress();
    button('Save schedule').props.onPress();
  });
  expect(save).toHaveBeenCalledTimes(1);
  expect(button('Save schedule').props.disabled).toBe(true);
  const calls = read.mock.calls.length;
  await appState('active');
  await act(async () => {
    jest.advanceTimersByTime(5_000);
  });
  expect(read).toHaveBeenCalledTimes(calls);
  await act(async () => finish(snapshot()));
  expect(button('Save schedule').props.disabled).toBe(false);
});

it('rapid double toggles preserve the original selection without duplicates', async () => {
  await mount();
  await act(async () => {
    const appPress = choice('Instagram').props.onPress;
    const dayPress = choice('Monday').props.onPress;
    appPress();
    appPress();
    dayPress();
    dayPress();
  });
  await press('Save schedule');
  expect(save).toHaveBeenCalledWith({
    startTime: '06:00',
    endTime: '09:00',
    weekdays: [1, 2, 3, 4, 5, 6, 7],
    packages: [],
  });
});
