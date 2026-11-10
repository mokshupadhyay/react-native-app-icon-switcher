package com.flexappicon;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Native entry points for code that runs without JavaScript, such as a
 * `FirebaseMessagingService` handling a data message while the app is closed.
 * Safe to call from any thread.
 */
public final class FlexAppIcon {
  /** Data key carrying the icon name; `"default"` or `""` selects the primary icon. */
  public static final String PAYLOAD_KEY = "flexAppIcon";

  private FlexAppIcon() {}

  /** Switch to `name` (`null` for the primary icon) at the next safe moment. */
  public static void setIcon(Context context, @Nullable final String name) {
    final FlexAppIconEngine engine = FlexAppIconEngine.get(context);
    engine.post(new Runnable() {
      @Override
      public void run() {
        try {
          engine.setIcon(name);
        } catch (FlexAppIconException error) {
          Log.w(FlexAppIconEngine.TAG, error.code + ": " + error.getMessage());
        }
      }
    });
  }

  /**
   * Handle a push message's data. `flexAppIcon` names the icon; an optional
   * `flexAppIconAt` (Unix milliseconds) in the future schedules it instead.
   *
   * @return whether the data contained an icon change.
   */
  public static boolean handleRemoteMessageData(Context context, @Nullable Map<String, String> data) {
    if (data == null || !data.containsKey(PAYLOAD_KEY)) return false;
    String raw = data.get(PAYLOAD_KEY);
    final String name = FlexAppIconAliases.isPrimaryName(raw) ? null : raw.trim();

    long at = 0;
    String rawAt = data.get(PAYLOAD_KEY + "At");
    if (rawAt != null) {
      try {
        at = Long.parseLong(rawAt.trim());
      } catch (NumberFormatException ignored) {
        Log.w(FlexAppIconEngine.TAG, "Ignoring invalid " + PAYLOAD_KEY + "At: " + rawAt);
      }
    }

    if (at > System.currentTimeMillis()) {
      final long time = at;
      final FlexAppIconEngine engine = FlexAppIconEngine.get(context);
      engine.post(new Runnable() {
        @Override
        public void run() {
          List<FlexAppIconStore.Entry> entries = engine.getState().schedule;
          for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).at == time) entries.remove(i);
          }
          entries.add(new FlexAppIconStore.Entry(name, time));
          engine.setSchedule(entries);
        }
      });
    } else {
      setIcon(context, name);
    }
    return true;
  }

  /** The icon the launcher shows now; `null` for the primary icon. */
  @Nullable
  public static String getCurrentIcon(Context context) {
    FlexAppIconAliases config = FlexAppIconAliases.read(context.getApplicationContext());
    return config.current != null ? config.current.publicName() : null;
  }
}
