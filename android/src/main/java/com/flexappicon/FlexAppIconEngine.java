package com.flexappicon;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide icon switcher. Every method that reads or changes state runs on
 * the main thread.
 *
 * Disabling the alias a task was launched from makes the system finish that
 * task, so a switch is only applied once none of the app's activities is
 * started, the screen is on (a locked phone must return to the app), and no
 * other app's activity (a browser tab, the camera) sits on top of the app's
 * task waiting to return a result.
 */
final class FlexAppIconEngine implements Application.ActivityLifecycleCallbacks {
  static final String TAG = "FlexAppIcon";

  interface Listener {
    void onIconChanged(@Nullable String icon, @Nullable String previous);
  }

  static final class SetResult {
    final String status;
    @Nullable final String icon;

    SetResult(String status, @Nullable String icon) {
      this.status = status;
      this.icon = icon;
    }
  }

  static final class State {
    boolean supported;
    @Nullable String current;
    List<String> icons = new ArrayList<>();
    boolean hasPending;
    @Nullable String pending;
    List<FlexAppIconStore.Entry> schedule = new ArrayList<>();
    List<String> issues = new ArrayList<>();
  }

  private enum Blocker { NONE, ACTIVITY_STARTED, FOREGROUND, SCREEN_OFF, FOREIGN_ACTIVITY_ON_TOP }

  /** Lets activity transitions and the system's process state settle before switching. */
  private static final long APPLY_DELAY_MS = 700;
  private static final long RETRY_DELAY_MS = 500;
  private static final int FOREGROUND_RETRIES = 8;

  private static volatile FlexAppIconEngine instance;

  static FlexAppIconEngine get(Context context) {
    FlexAppIconEngine engine = instance;
    if (engine != null) return engine;
    synchronized (FlexAppIconEngine.class) {
      if (instance == null) instance = new FlexAppIconEngine((Application) context.getApplicationContext());
      return instance;
    }
  }

  private final Application app;
  private final FlexAppIconStore store;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  private final Runnable evaluateRunnable = new Runnable() {
    @Override
    public void run() {
      evaluate();
    }
  };

  /** True when callbacks were registered before any activity existed, so `started` is exact. */
  private volatile boolean exactTracking;
  private boolean stoppedWhileScreenOff;
  private int foregroundRetries;
  @Nullable private BroadcastReceiver screenReceiver;

  private FlexAppIconEngine(Application app) {
    this.app = app;
    this.store = new FlexAppIconStore(app);
    app.registerActivityLifecycleCallbacks(this);
  }

  /** Called by the init provider, which runs before `Application.onCreate`. */
  void onProcessStart() {
    exactTracking = true;
    post(new Runnable() {
      @Override
      public void run() {
        armAlarm();
        scheduleEvaluation(APPLY_DELAY_MS);
      }
    });
  }

  void addListener(Listener listener) {
    listeners.addIfAbsent(listener);
  }

  void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  void post(Runnable runnable) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      runnable.run();
    } else {
      main.post(runnable);
    }
  }

  // Main thread only below this line.

  State getState() {
    promoteDueSchedule();
    FlexAppIconAliases config = FlexAppIconAliases.read(app);
    State state = new State();
    state.supported = !config.aliases.isEmpty() && config.blockingIssue == null;
    state.current = config.current != null ? config.current.publicName() : null;
    state.icons = config.alternateNames();
    state.hasPending = store.hasPending();
    state.pending = store.pendingIcon();
    state.schedule = store.schedule();
    state.issues = new ArrayList<>(config.issues);
    if (config.aliases.isEmpty()) {
      state.issues.add("No launcher <activity-alias> found. Move MainActivity's LAUNCHER intent filter "
          + "into one <activity-alias> per icon.");
    }
    return state;
  }

  SetResult setIcon(@Nullable String requested) throws FlexAppIconException {
    FlexAppIconAliases config = FlexAppIconAliases.read(app);
    if (config.aliases.isEmpty()) {
      throw new FlexAppIconException("E_NOT_SUPPORTED",
          "No launcher <activity-alias> found in the AndroidManifest. See the setup guide.");
    }
    if (config.blockingIssue != null) {
      throw new FlexAppIconException("E_CONFIG", config.blockingIssue);
    }
    FlexAppIconAliases.Alias target = config.find(requested);
    if (target == null) {
      throw new FlexAppIconException("E_UNKNOWN_ICON",
          "No icon named \"" + requested + "\". Available: " + config.alternateNames());
    }

    String icon = target.publicName();
    if (target == config.current && isOnlyEnabled(config, target)) {
      store.clearPending();
      return new SetResult("unchanged", icon);
    }

    store.setPending(icon);
    if (blocker() == Blocker.NONE) {
      apply(config, target);
      return new SetResult("applied", icon);
    }
    foregroundRetries = FOREGROUND_RETRIES;
    if (started.isEmpty()) scheduleEvaluation(APPLY_DELAY_MS);
    return new SetResult("pending", icon);
  }

  void cancelPending() {
    store.clearPending();
    setScreenWatch(false);
  }

  void setSchedule(List<FlexAppIconStore.Entry> entries) {
    store.setSchedule(entries);
    promoteDueSchedule();
    armAlarm();
    if (store.hasPending() && started.isEmpty()) scheduleEvaluation(APPLY_DELAY_MS);
  }

  /** The alarm for the next schedule entry fired. */
  void onAlarm() {
    foregroundRetries = FOREGROUND_RETRIES;
    evaluate();
  }

  private void scheduleEvaluation(long delayMs) {
    main.removeCallbacks(evaluateRunnable);
    main.postDelayed(evaluateRunnable, delayMs);
  }

  private void evaluate() {
    promoteDueSchedule();
    armAlarm();
    if (!store.hasPending()) {
      setScreenWatch(false);
      return;
    }
    Blocker blocker = blocker();
    switch (blocker) {
      case NONE:
        setScreenWatch(false);
        applyPending();
        break;
      case SCREEN_OFF:
        setScreenWatch(true);
        break;
      case FOREGROUND:
        // The system's process state can trail the last onStop by a moment.
        if (foregroundRetries-- > 0) main.postDelayed(evaluateRunnable, RETRY_DELAY_MS);
        break;
      default:
        // Retried by the next activity stop.
        break;
    }
  }

  private Blocker blocker() {
    if (!started.isEmpty()) return Blocker.ACTIVITY_STARTED;
    if (!exactTracking && isProcessInForeground()) return Blocker.FOREGROUND;
    if (stoppedWhileScreenOff && !isInteractive()) return Blocker.SCREEN_OFF;
    if (hasForeignActivityOnTop()) return Blocker.FOREIGN_ACTIVITY_ON_TOP;
    return Blocker.NONE;
  }

  private void applyPending() {
    String name = store.pendingIcon();
    FlexAppIconAliases config = FlexAppIconAliases.read(app);
    FlexAppIconAliases.Alias target = config.find(name);
    if (config.blockingIssue != null || target == null) {
      Log.w(TAG, "Dropping pending icon \"" + name + "\": "
          + (config.blockingIssue != null ? config.blockingIssue : "no such icon"));
      store.clearPending();
      return;
    }
    if (target == config.current && isOnlyEnabled(config, target)) {
      store.clearPending();
      return;
    }
    apply(config, target);
  }

  private void apply(FlexAppIconAliases config, FlexAppIconAliases.Alias target) {
    String previous = config.current != null ? config.current.publicName() : null;
    PackageManager pm = app.getPackageManager();

    // The target is enabled first so that the app always has a launcher entry.
    List<ComponentName> components = new ArrayList<>();
    List<Integer> states = new ArrayList<>();
    addChange(pm, components, states, target.component, desiredState(target, target));
    for (FlexAppIconAliases.Alias alias : config.aliases) {
      if (alias != target) addChange(pm, components, states, alias.component, desiredState(alias, target));
    }

    try {
      if (Build.VERSION.SDK_INT >= 33) {
        List<PackageManager.ComponentEnabledSetting> settings = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
          settings.add(new PackageManager.ComponentEnabledSetting(
              components.get(i), states.get(i), PackageManager.DONT_KILL_APP));
        }
        if (!settings.isEmpty()) pm.setComponentEnabledSettings(settings);
      } else {
        for (int i = 0; i < components.size(); i++) {
          pm.setComponentEnabledSetting(components.get(i), states.get(i), PackageManager.DONT_KILL_APP);
        }
      }
    } catch (RuntimeException error) {
      // Keep the request so that the next safe moment retries it.
      Log.e(TAG, "Could not switch to " + target.component.getClassName(), error);
      return;
    }

    store.clearPending();
    String icon = target.publicName();
    Log.i(TAG, "Switched app icon to " + (icon == null ? "the primary icon" : icon));
    for (Listener listener : listeners) {
      try {
        listener.onIconChanged(icon, previous);
      } catch (RuntimeException error) {
        Log.w(TAG, "Icon change listener failed", error);
      }
    }
  }

  /**
   * Alternates are disabled in the manifest, so they are turned off by going
   * back to their default state; only the primary alias is explicitly disabled.
   */
  private static int desiredState(FlexAppIconAliases.Alias alias, FlexAppIconAliases.Alias target) {
    if (alias == target) {
      return alias.primary
          ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
          : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
    return alias.primary
        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
  }

  private static void addChange(
      PackageManager pm, List<ComponentName> components, List<Integer> states,
      ComponentName component, int state) {
    int currentState;
    try {
      currentState = pm.getComponentEnabledSetting(component);
    } catch (RuntimeException error) {
      currentState = -1;
    }
    if (currentState == state) return;
    components.add(component);
    states.add(state);
  }

  private static boolean isOnlyEnabled(FlexAppIconAliases config, FlexAppIconAliases.Alias target) {
    for (FlexAppIconAliases.Alias alias : config.aliases) {
      if (alias != target && alias.enabledNow) return false;
    }
    return target.enabledNow;
  }

  /** Moves the latest due schedule entry into the pending slot. */
  private void promoteDueSchedule() {
    List<FlexAppIconStore.Entry> entries = store.schedule();
    if (entries.isEmpty()) return;
    long now = System.currentTimeMillis();
    List<FlexAppIconStore.Entry> remaining = new ArrayList<>();
    FlexAppIconStore.Entry due = null;
    for (FlexAppIconStore.Entry entry : entries) {
      if (entry.at <= now) {
        due = entry;
      } else {
        remaining.add(entry);
      }
    }
    if (due == null) return;
    store.setSchedule(remaining);
    store.setPending(due.icon);
  }

  private void armAlarm() {
    AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) return;
    PendingIntent intent = alarmIntent();
    List<FlexAppIconStore.Entry> entries = store.schedule();
    try {
      if (entries.isEmpty()) {
        alarms.cancel(intent);
      } else {
        // Inexact: needs no exact-alarm permission, and a launcher icon need not change to the second.
        alarms.set(AlarmManager.RTC, entries.get(0).at, intent);
      }
    } catch (RuntimeException error) {
      Log.w(TAG, "Could not arm the icon schedule alarm", error);
    }
  }

  private PendingIntent alarmIntent() {
    Intent intent = new Intent(app, FlexAppIconAlarmReceiver.class);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getBroadcast(app, 0, intent, flags);
  }

  private boolean isInteractive() {
    PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    return power == null || power.isInteractive();
  }

  private static boolean isProcessInForeground() {
    ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
    ActivityManager.getMyMemoryState(info);
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        || info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
  }

  private boolean hasForeignActivityOnTop() {
    if (Build.VERSION.SDK_INT < 23) return false;
    ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
    if (manager == null) return false;
    try {
      for (ActivityManager.AppTask task : manager.getAppTasks()) {
        ComponentName top = task.getTaskInfo().topActivity;
        if (top != null && !app.getPackageName().equals(top.getPackageName())) return true;
      }
    } catch (RuntimeException ignored) {
      // A task can disappear while it is being inspected.
    }
    return false;
  }

  private void setScreenWatch(boolean watch) {
    if (watch == (screenReceiver != null)) return;
    if (!watch) {
      try {
        app.unregisterReceiver(screenReceiver);
      } catch (RuntimeException ignored) {
        // Already unregistered.
      }
      screenReceiver = null;
      return;
    }
    screenReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        foregroundRetries = FOREGROUND_RETRIES;
        scheduleEvaluation(APPLY_DELAY_MS);
      }
    };
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_SCREEN_ON);
    filter.addAction(Intent.ACTION_USER_PRESENT);
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        app.registerReceiver(screenReceiver, filter);
      }
    } catch (RuntimeException error) {
      screenReceiver = null;
      Log.w(TAG, "Could not watch the screen state", error);
    }
  }

  @Override
  public void onActivityStarted(@NonNull Activity activity) {
    started.add(activity);
    stoppedWhileScreenOff = false;
    main.removeCallbacks(evaluateRunnable);
  }

  @Override
  public void onActivityStopped(@NonNull Activity activity) {
    started.remove(activity);
    if (!started.isEmpty()) return;
    stoppedWhileScreenOff = !isInteractive();
    if (store.hasPending() || !store.schedule().isEmpty()) {
      foregroundRetries = FOREGROUND_RETRIES;
      scheduleEvaluation(APPLY_DELAY_MS);
    }
  }

  @Override
  public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

  @Override
  public void onActivityResumed(@NonNull Activity activity) {}

  @Override
  public void onActivityPaused(@NonNull Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

  @Override
  public void onActivityDestroyed(@NonNull Activity activity) {
    started.remove(activity);
  }
}
