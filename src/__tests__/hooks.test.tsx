import * as React from 'react';
import { Text } from 'react-native';
import { act, render, screen } from '@testing-library/react-native';
import { NativeModules } from 'react-native';
import { useAppIcon } from '../hooks';
import { resetNativeModuleCache } from '../native';
import type { UseAppIconResult } from '../hooks';

let current: string | null = null;

beforeEach(() => {
  current = null;
  (NativeModules as { FlexAppIcon?: unknown }).FlexAppIcon = {
    getState: jest.fn(async () => ({
      supported: true,
      current,
      icons: ['Dark'],
      hasPending: false,
      pending: null,
      schedule: [],
      issues: [],
    })),
    setIcon: jest.fn(async (name: string | null) => {
      current = name;
      return { status: 'applied', icon: name };
    }),
    cancelPending: jest.fn(),
    setSchedule: jest.fn(),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  };
  resetNativeModuleCache();
});

afterEach(() => {
  delete (NativeModules as { FlexAppIcon?: unknown }).FlexAppIcon;
  resetNativeModuleCache();
});

it('loads the state and refreshes after a change', async () => {
  let api: UseAppIconResult | undefined;
  function Probe() {
    api = useAppIcon();
    return <Text>{api.state ? `icon:${api.state.current ?? 'primary'}` : 'loading'}</Text>;
  }

  await render(<Probe />);
  expect(await screen.findByText('icon:primary')).toBeTruthy();

  await act(async () => {
    await api!.setIcon('Dark');
  });
  expect(screen.getByText('icon:Dark')).toBeTruthy();
});
