import { NativeModules } from 'react-native';
import {
  clearAppIconSchedule,
  getAppIcon,
  getAppIconState,
  handleAppIconPayload,
  scheduleAppIcon,
  setAppIcon,
} from '../api';
import { resetNativeModuleCache } from '../native';

interface FakeModule {
  getState: jest.Mock;
  setIcon: jest.Mock;
  cancelPending: jest.Mock;
  setSchedule: jest.Mock;
  addListener: jest.Mock;
  removeListeners: jest.Mock;
}

function installFake(state: object = {}): FakeModule {
  const fake: FakeModule = {
    getState: jest.fn(async () => ({
      supported: true,
      current: null,
      icons: ['Dark', 'Holiday'],
      hasPending: false,
      pending: null,
      schedule: [],
      issues: [],
      ...state,
    })),
    setIcon: jest.fn(async (name: string | null) => ({ status: 'pending', icon: name })),
    cancelPending: jest.fn(async () => undefined),
    setSchedule: jest.fn(async () => undefined),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  };
  (NativeModules as { FlexAppIcon?: unknown }).FlexAppIcon = fake;
  resetNativeModuleCache();
  return fake;
}

afterEach(() => {
  delete (NativeModules as { FlexAppIcon?: unknown }).FlexAppIcon;
  resetNativeModuleCache();
});

describe('without the native module', () => {
  it('reports an unsupported state', async () => {
    const state = await getAppIconState();
    expect(state.supported).toBe(false);
    expect(state.issues[0]).toMatch(/not linked/);
  });

  it('rejects changes with E_NOT_LINKED', async () => {
    await expect(setAppIcon('Dark')).rejects.toMatchObject({ code: 'E_NOT_LINKED' });
  });
});

describe('with the native module', () => {
  it('normalizes names before calling native', async () => {
    const fake = installFake();
    await expect(setAppIcon('default')).resolves.toEqual({ status: 'pending', icon: null });
    await setAppIcon(' Dark ');
    expect(fake.setIcon.mock.calls).toEqual([[null], ['Dark']]);
  });

  it('reads the current icon', async () => {
    installFake({ current: 'Holiday' });
    await expect(getAppIcon()).resolves.toBe('Holiday');
  });

  it('merges a scheduled entry into the existing schedule', async () => {
    const fake = installFake({ schedule: [{ icon: 'Dark', at: 100 }, { icon: 'Holiday', at: 300 }] });
    await scheduleAppIcon('default', 200);
    await scheduleAppIcon('Holiday', 100);
    expect(fake.setSchedule.mock.calls[0][0]).toEqual([
      { icon: 'Dark', at: 100 },
      { icon: null, at: 200 },
      { icon: 'Holiday', at: 300 },
    ]);
    expect(fake.setSchedule.mock.calls[1][0]).toEqual([
      { icon: 'Holiday', at: 100 },
      { icon: 'Holiday', at: 300 },
    ]);
    await clearAppIconSchedule();
    expect(fake.setSchedule).toHaveBeenLastCalledWith([]);
  });
});

describe('handleAppIconPayload', () => {
  it('ignores payloads without the key', async () => {
    const fake = installFake();
    await expect(handleAppIconPayload({ title: 'hi' })).resolves.toBeNull();
    await expect(handleAppIconPayload(undefined)).resolves.toBeNull();
    await expect(handleAppIconPayload({ flexAppIcon: 3 })).resolves.toBeNull();
    expect(fake.setIcon).not.toHaveBeenCalled();
  });

  it('sets the icon from the payload', async () => {
    const fake = installFake();
    await expect(handleAppIconPayload({ flexAppIcon: 'Holiday' })).resolves.toEqual({
      status: 'pending',
      icon: 'Holiday',
    });
    await handleAppIconPayload({ flexAppIcon: 'default' });
    await handleAppIconPayload({ icon: 'Dark' }, { key: 'icon' });
    expect(fake.setIcon.mock.calls).toEqual([['Holiday'], [null], ['Dark']]);
  });

  it('schedules future changes and applies past ones', async () => {
    const fake = installFake();
    const future = Date.now() + 60_000;
    await expect(
      handleAppIconPayload({ flexAppIcon: 'Holiday', flexAppIconAt: String(future) }),
    ).resolves.toEqual({ status: 'pending', icon: 'Holiday' });
    expect(fake.setSchedule).toHaveBeenCalledWith([{ icon: 'Holiday', at: future }]);
    expect(fake.setIcon).not.toHaveBeenCalled();

    await handleAppIconPayload({ flexAppIcon: 'Dark', flexAppIconAt: 1000 });
    expect(fake.setIcon).toHaveBeenCalledWith('Dark');
  });
});
