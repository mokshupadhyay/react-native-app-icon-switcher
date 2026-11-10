package com.flexappicon;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.List;

/** Shared implementation behind the old- and new-architecture modules. */
final class FlexAppIconModuleImpl implements FlexAppIconEngine.Listener {
  static final String NAME = "FlexAppIcon";
  private static final String EVENT = "FlexAppIconChanged";

  private final ReactApplicationContext context;
  private final FlexAppIconEngine engine;
  private boolean invalidated;

  FlexAppIconModuleImpl(ReactApplicationContext context) {
    this.context = context;
    this.engine = FlexAppIconEngine.get(context);
    engine.addListener(this);
  }

  void getState(final Promise promise) {
    engine.post(new Runnable() {
      @Override
      public void run() {
        try {
          promise.resolve(toMap(engine.getState()));
        } catch (RuntimeException error) {
          promise.reject("E_FAILED", error.getMessage(), error);
        }
      }
    });
  }

  void setIcon(@Nullable final String name, final Promise promise) {
    engine.post(new Runnable() {
      @Override
      public void run() {
        try {
          FlexAppIconEngine.SetResult result = engine.setIcon(name);
          WritableMap map = Arguments.createMap();
          map.putString("status", result.status);
          putNullableString(map, "icon", result.icon);
          promise.resolve(map);
        } catch (FlexAppIconException error) {
          promise.reject(error.code, error.getMessage());
        } catch (RuntimeException error) {
          promise.reject("E_FAILED", error.getMessage(), error);
        }
      }
    });
  }

  void cancelPending(final Promise promise) {
    engine.post(new Runnable() {
      @Override
      public void run() {
        engine.cancelPending();
        promise.resolve(null);
      }
    });
  }

  void setSchedule(ReadableArray raw, final Promise promise) {
    final List<FlexAppIconStore.Entry> entries = new ArrayList<>();
    for (int i = 0; i < raw.size(); i++) {
      if (raw.getType(i) != ReadableType.Map) continue;
      ReadableMap item = raw.getMap(i);
      if (item == null || !item.hasKey("at") || item.getType("at") != ReadableType.Number) continue;
      String icon = item.hasKey("icon") && item.getType("icon") == ReadableType.String
          ? item.getString("icon")
          : null;
      if (FlexAppIconAliases.isPrimaryName(icon)) icon = null;
      entries.add(new FlexAppIconStore.Entry(icon, (long) item.getDouble("at")));
    }
    engine.post(new Runnable() {
      @Override
      public void run() {
        try {
          engine.setSchedule(entries);
          promise.resolve(null);
        } catch (RuntimeException error) {
          promise.reject("E_FAILED", error.getMessage(), error);
        }
      }
    });
  }

  void invalidate() {
    if (invalidated) return;
    invalidated = true;
    engine.removeListener(this);
  }

  @Override
  public void onIconChanged(@Nullable String icon, @Nullable String previous) {
    if (invalidated) return;
    WritableMap event = Arguments.createMap();
    putNullableString(event, "icon", icon);
    putNullableString(event, "previous", previous);
    try {
      context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(EVENT, event);
    } catch (RuntimeException ignored) {
      // No React instance is running; JavaScript reads the icon on its next start.
    }
  }

  private static WritableMap toMap(FlexAppIconEngine.State state) {
    WritableMap map = Arguments.createMap();
    map.putBoolean("supported", state.supported);
    putNullableString(map, "current", state.current);
    WritableArray icons = Arguments.createArray();
    for (String icon : state.icons) icons.pushString(icon);
    map.putArray("icons", icons);
    map.putBoolean("hasPending", state.hasPending);
    putNullableString(map, "pending", state.pending);
    WritableArray schedule = Arguments.createArray();
    for (FlexAppIconStore.Entry entry : state.schedule) {
      WritableMap item = Arguments.createMap();
      putNullableString(item, "icon", entry.icon);
      item.putDouble("at", entry.at);
      schedule.pushMap(item);
    }
    map.putArray("schedule", schedule);
    WritableArray issues = Arguments.createArray();
    for (String issue : state.issues) issues.pushString(issue);
    map.putArray("issues", issues);
    return map;
  }

  private static void putNullableString(WritableMap map, String key, @Nullable String value) {
    if (value == null) {
      map.putNull(key);
    } else {
      map.putString(key, value);
    }
  }
}
