import { CHANGE_EVENT, getEmitter, getNativeModule } from './native';
import {
  normalizeIconName,
  parseChangeEvent,
  parseResult,
  parseState,
  sortSchedule,
  toTimestamp,
} from './parse';
import type {
  AppIconChangeEvent,
  AppIconName,
  AppIconPayloadOptions,
  AppIconState,
  AppIconSubscription,
  ScheduledAppIcon,
  SetAppIconResult,
} from './types';

const DEFAULT_PAYLOAD_KEY = 'flexAppIcon';

const UNLINKED_STATE: AppIconState = {
  supported: false,
  current: null,
  icons: [],
  pending: null,
  schedule: [],
  issues: ['The FlexAppIcon native module is not linked. Rebuild the app after installing.'],
};

function notLinked(): Error & { code: string } {
  const error = new Error(
    'react-native-app-icon-switcher: the native module is not linked. Rebuild the app ' +
      '(Expo Go and web are not supported).',
  ) as Error & { code: string };
  error.code = 'E_NOT_LINKED';
  return error;
}

function requireModule() {
  const nativeModule = getNativeModule();
  if (!nativeModule) throw notLinked();
  return nativeModule;
}

/** Everything the native side knows: support, icons, pending change, schedule and issues. */
export async function getAppIconState(): Promise<AppIconState> {
  const nativeModule = getNativeModule();
  if (!nativeModule) return { ...UNLINKED_STATE, issues: UNLINKED_STATE.issues.slice() };
  return parseState(await nativeModule.getState());
}

/** The icon the system shows now; `null` for the primary icon. */
export async function getAppIcon(): Promise<AppIconName> {
  return (await getAppIconState()).current;
}

export async function getAvailableAppIcons(): Promise<string[]> {
  return (await getAppIconState()).icons;
}

export async function isAppIconChangeSupported(): Promise<boolean> {
  return (await getAppIconState()).supported;
}

/**
 * Switch to `name`, or to the primary icon with `null`.
 *
 * iOS applies the change at once when the app is active and shows the system
 * alert; otherwise it is applied the next time the app becomes active.
 * Android applies it when the app leaves the foreground, so the running screen
 * is never closed. Calls made while the app is in the background (for example
 * from a push handler) are applied right away when that is safe.
 */
export async function setAppIcon(name: AppIconName): Promise<SetAppIconResult> {
  const icon = normalizeIconName(name);
  return parseResult(await requireModule().setIcon(icon), icon);
}

export function resetAppIcon(): Promise<SetAppIconResult> {
  return setAppIcon(null);
}

/** Drop a change that is still waiting to be applied. */
export async function cancelPendingAppIcon(): Promise<void> {
  await requireModule().cancelPending();
}

export async function getScheduledAppIcons(): Promise<ScheduledAppIcon[]> {
  return (await getAppIconState()).schedule;
}

/**
 * Replace the schedule. Each entry switches the icon once its time has passed:
 * Android does this in the background, iOS the next time the app is active.
 */
export async function setAppIconSchedule(
  entries: ReadonlyArray<{ icon: AppIconName; at: Date | number | string }>,
): Promise<void> {
  const nativeModule = requireModule();
  const normalized = sortSchedule(
    entries.map((entry) => ({ icon: normalizeIconName(entry.icon), at: toTimestamp(entry.at) })),
  );
  await nativeModule.setSchedule(normalized);
}

/** Add one entry to the schedule, replacing any entry at the same time. */
export async function scheduleAppIcon(icon: AppIconName, at: Date | number | string): Promise<void> {
  const time = toTimestamp(at);
  const existing = await getScheduledAppIcons();
  await setAppIconSchedule(existing.filter((entry) => entry.at !== time).concat({ icon, at: time }));
}

export function clearAppIconSchedule(): Promise<void> {
  return setAppIconSchedule([]);
}

/**
 * Called with every applied change while JavaScript is running. Changes applied
 * while the app was not running are visible through `getAppIcon()`.
 */
export function addAppIconChangeListener(
  listener: (event: AppIconChangeEvent) => void,
): AppIconSubscription {
  const emitter = getEmitter();
  if (!emitter) return { remove() {} };
  const subscription = emitter.addListener(CHANGE_EVENT, (raw: unknown) => {
    listener(parseChangeEvent(raw));
  });
  return { remove: () => subscription.remove() };
}

/**
 * Apply an icon change carried in a push notification's data, for example
 * `{ "flexAppIcon": "Holiday" }` or `{ "flexAppIcon": "default" }`. An optional
 * `flexAppIconAt` (Unix milliseconds or ISO date) schedules it instead.
 *
 * Resolves to `null` when the payload has no icon key.
 */
export async function handleAppIconPayload(
  data: { [key: string]: unknown } | null | undefined,
  options: AppIconPayloadOptions = {},
): Promise<SetAppIconResult | null> {
  const key = options.key || DEFAULT_PAYLOAD_KEY;
  if (!data || !(key in data)) return null;
  const raw = data[key];
  if (raw != null && typeof raw !== 'string') return null;
  const icon = normalizeIconName(raw);

  const at = data[`${key}At`];
  if (typeof at === 'number' || (typeof at === 'string' && at.length > 0)) {
    const time = toTimestamp(typeof at === 'string' && /^\d+$/.test(at) ? Number(at) : at);
    if (time > Date.now()) {
      await scheduleAppIcon(icon, time);
      return { status: 'pending', icon };
    }
  }
  return setAppIcon(icon);
}
