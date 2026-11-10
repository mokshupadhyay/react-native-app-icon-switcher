import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { addAppIconChangeListener, getAppIconState, setAppIcon } from './api';
import type { AppIconName, AppIconState, SetAppIconResult } from './types';

export interface UseAppIconResult {
  /** `null` until the first native read completes. */
  state: AppIconState | null;
  setIcon(name: AppIconName): Promise<SetAppIconResult>;
  refresh(): Promise<void>;
}

/**
 * Current icon state, refreshed after every change, every applied change and
 * whenever the app returns to the foreground.
 */
export function useAppIcon(): UseAppIconResult {
  const [state, setState] = useState<AppIconState | null>(null);
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    const next = await getAppIconState();
    if (mounted.current) setState(next);
  }, []);

  const setIcon = useCallback(
    async (name: AppIconName) => {
      try {
        return await setAppIcon(name);
      } finally {
        await refresh().catch(() => undefined);
      }
    },
    [refresh],
  );

  useEffect(() => {
    mounted.current = true;
    refresh().catch(() => undefined);
    const change = addAppIconChangeListener(() => {
      refresh().catch(() => undefined);
    });
    const appState = AppState.addEventListener('change', (status) => {
      if (status === 'active') refresh().catch(() => undefined);
    });
    return () => {
      mounted.current = false;
      change.remove();
      appState.remove();
    };
  }, [refresh]);

  return { state, setIcon, refresh };
}
