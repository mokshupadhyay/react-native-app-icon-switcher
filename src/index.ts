export {
  addAppIconChangeListener,
  cancelPendingAppIcon,
  clearAppIconSchedule,
  getAppIcon,
  getAppIconState,
  getAvailableAppIcons,
  getScheduledAppIcons,
  handleAppIconPayload,
  isAppIconChangeSupported,
  resetAppIcon,
  scheduleAppIcon,
  setAppIcon,
  setAppIconSchedule,
} from './api';
export { useAppIcon } from './hooks';
export type { UseAppIconResult } from './hooks';
export type {
  AppIconChangeEvent,
  AppIconErrorCode,
  AppIconName,
  AppIconPayloadOptions,
  AppIconState,
  AppIconSubscription,
  ScheduledAppIcon,
  SetAppIconResult,
  SetAppIconStatus,
} from './types';
