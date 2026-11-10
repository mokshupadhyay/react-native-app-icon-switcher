import type {
  AppIconChangeEvent,
  AppIconName,
  AppIconState,
  ScheduledAppIcon,
  SetAppIconResult,
  SetAppIconStatus,
} from './types';

type Raw = { [key: string]: unknown };

const STATUSES: readonly SetAppIconStatus[] = ['applied', 'pending', 'unchanged'];

function isObject(value: unknown): value is Raw {
  return typeof value === 'object' && value !== null;
}

function iconName(value: unknown): AppIconName {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/** `null`, `''` and `'default'` in any case all select the primary icon. */
export function normalizeIconName(name: AppIconName | undefined): AppIconName {
  if (name == null) return null;
  const trimmed = String(name).trim();
  if (trimmed.length === 0 || trimmed.toLowerCase() === 'default') return null;
  return trimmed;
}

export function toTimestamp(at: Date | number | string): number {
  const time = at instanceof Date ? at.getTime() : typeof at === 'number' ? at : Date.parse(at);
  if (!isFinite(time)) throw new TypeError(`Invalid schedule time: ${String(at)}`);
  return Math.round(time);
}

function parseScheduleEntry(raw: unknown): ScheduledAppIcon | null {
  if (!isObject(raw) || typeof raw.at !== 'number' || !isFinite(raw.at)) return null;
  return { icon: iconName(raw.icon), at: raw.at };
}

export function sortSchedule(entries: ScheduledAppIcon[]): ScheduledAppIcon[] {
  return entries.slice().sort((a, b) => a.at - b.at);
}

export function parseState(raw: unknown): AppIconState {
  const state: Raw = isObject(raw) ? raw : {};
  const strings = (value: unknown): string[] =>
    Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
  const schedule = Array.isArray(state.schedule)
    ? state.schedule.map(parseScheduleEntry).filter((entry): entry is ScheduledAppIcon => entry !== null)
    : [];
  return {
    supported: state.supported === true,
    current: iconName(state.current),
    icons: strings(state.icons),
    pending: state.hasPending === true ? { icon: iconName(state.pending) } : null,
    schedule: sortSchedule(schedule),
    issues: strings(state.issues),
  };
}

export function parseResult(raw: unknown, requested: AppIconName): SetAppIconResult {
  const result: Raw = isObject(raw) ? raw : {};
  const status = STATUSES.find((candidate) => candidate === result.status);
  return {
    status: status || 'pending',
    icon: 'icon' in result ? iconName(result.icon) : requested,
  };
}

export function parseChangeEvent(raw: unknown): AppIconChangeEvent {
  const event: Raw = isObject(raw) ? raw : {};
  return { icon: iconName(event.icon), previous: iconName(event.previous) };
}
