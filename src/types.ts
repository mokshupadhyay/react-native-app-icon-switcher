/**
 * An icon name: the name of an alternate icon, or `null` for the primary
 * icon. The name `'default'` (any case) also means the primary icon.
 */
export type AppIconName = string | null;

export interface ScheduledAppIcon {
  /** Icon to switch to; `null` for the primary icon. */
  icon: AppIconName;
  /** Unix time in milliseconds. */
  at: number;
}

export interface AppIconState {
  /** Whether this build can switch icons at all. */
  supported: boolean;
  /** The icon the system shows now; `null` for the primary icon. */
  current: AppIconName;
  /** Alternate icon names configured in the build, without the primary icon. */
  icons: string[];
  /** A requested icon that is waiting for a safe moment to be applied. */
  pending: { icon: AppIconName } | null;
  /** Future switches, in time order. */
  schedule: ScheduledAppIcon[];
  /** Configuration problems found in the native project. */
  issues: string[];
}

/**
 * - `applied`: the system now shows the icon.
 * - `pending`: the icon is stored and applied at the next safe moment: when the
 *   app leaves the foreground on Android, or becomes active on iOS.
 * - `unchanged`: the icon was already showing; any pending change is cancelled.
 */
export type SetAppIconStatus = 'applied' | 'pending' | 'unchanged';

export interface SetAppIconResult {
  status: SetAppIconStatus;
  icon: AppIconName;
}

export interface AppIconChangeEvent {
  icon: AppIconName;
  previous: AppIconName;
}

export interface AppIconSubscription {
  remove(): void;
}

export interface AppIconPayloadOptions {
  /** Key holding the icon name. Defaults to `'flexAppIcon'`. */
  key?: string;
}

/**
 * Error codes on rejected promises (`error.code`):
 * - `E_NOT_LINKED`: the native module is missing (Expo Go, web, or no rebuild).
 * - `E_NOT_SUPPORTED`: the device or build cannot switch icons.
 * - `E_UNKNOWN_ICON`: no icon with this name is configured.
 * - `E_CONFIG`: the native project setup prevents a safe switch; see `issues`.
 * - `E_FAILED`: the system refused the change.
 */
export type AppIconErrorCode =
  | 'E_NOT_LINKED'
  | 'E_NOT_SUPPORTED'
  | 'E_UNKNOWN_ICON'
  | 'E_CONFIG'
  | 'E_FAILED';
