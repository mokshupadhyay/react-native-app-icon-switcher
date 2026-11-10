import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * Native icon switcher. `null` names mean the primary icon. Results and state
 * are plain objects validated by `src/parse.ts`.
 *
 * Applied changes are delivered through `FlexAppIconChanged` on the module's
 * event emitter.
 */
export interface Spec extends TurboModule {
  getState(): Promise<Object>;
  setIcon(name: string | null): Promise<Object>;
  cancelPending(): Promise<void>;
  setSchedule(entries: Array<Object>): Promise<void>;

  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('FlexAppIcon');
