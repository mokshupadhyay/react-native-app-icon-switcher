import * as ReactNative from 'react-native';
import type { Spec } from './specs/NativeFlexAppIcon';

export const CHANGE_EVENT = 'FlexAppIconChanged';

function isTurboModuleRuntime(): boolean {
  const root = globalThis as { __turboModuleProxy?: unknown; RN$Bridgeless?: unknown };
  return root.__turboModuleProxy != null || root.RN$Bridgeless === true;
}

/**
 * Load the TurboModule on the New Architecture and the bridge module on the
 * old one. The spec is required lazily so that a missing module never throws
 * at import time.
 */
function loadNativeModule(): Spec | null {
  if (isTurboModuleRuntime()) {
    try {
      const turboModule = require('./specs/NativeFlexAppIcon').default as Spec | null;
      if (turboModule) return turboModule;
    } catch {
      // Fall through to the bridge lookup.
    }
  }
  const legacy = ReactNative.NativeModules.FlexAppIcon as Spec | undefined;
  return legacy ?? null;
}

let cached: Spec | null | undefined;
let emitter: InstanceType<typeof ReactNative.NativeEventEmitter> | null = null;

export function getNativeModule(): Spec | null {
  if (cached === undefined) cached = loadNativeModule();
  return cached;
}

export function getEmitter(): InstanceType<typeof ReactNative.NativeEventEmitter> | null {
  const nativeModule = getNativeModule();
  if (!nativeModule) return null;
  if (!emitter) emitter = new ReactNative.NativeEventEmitter(nativeModule as never);
  return emitter;
}

/** Test hook: forget the resolved module. */
export function resetNativeModuleCache(): void {
  cached = undefined;
  emitter = null;
}
