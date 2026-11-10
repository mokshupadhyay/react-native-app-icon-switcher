package com.flexappicon;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pending change and schedule, persisted so that a change requested before the
 * process dies is still applied later. A `null` icon is the primary icon.
 */
final class FlexAppIconStore {
  private static final String FILE = "com.flexappicon";
  private static final String KEY_HAS_PENDING = "pending.has";
  private static final String KEY_PENDING_ICON = "pending.icon";
  private static final String KEY_SCHEDULE = "schedule";

  static final class Entry {
    @Nullable final String icon;
    final long at;

    Entry(@Nullable String icon, long at) {
      this.icon = icon;
      this.at = at;
    }
  }

  private final SharedPreferences prefs;

  FlexAppIconStore(Context context) {
    prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  boolean hasPending() {
    return prefs.getBoolean(KEY_HAS_PENDING, false);
  }

  @Nullable
  String pendingIcon() {
    return prefs.getString(KEY_PENDING_ICON, null);
  }

  void setPending(@Nullable String icon) {
    SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_HAS_PENDING, true);
    if (icon == null) {
      editor.remove(KEY_PENDING_ICON);
    } else {
      editor.putString(KEY_PENDING_ICON, icon);
    }
    editor.apply();
  }

  void clearPending() {
    prefs.edit().remove(KEY_HAS_PENDING).remove(KEY_PENDING_ICON).apply();
  }

  /** Entries in time order. */
  List<Entry> schedule() {
    List<Entry> entries = new ArrayList<>();
    String raw = prefs.getString(KEY_SCHEDULE, null);
    if (raw == null) return entries;
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null || !item.has("at")) continue;
        String icon = item.isNull("icon") ? null : item.optString("icon", null);
        entries.add(new Entry(icon, item.optLong("at")));
      }
    } catch (JSONException ignored) {
      // A corrupt schedule is dropped rather than retried forever.
    }
    sort(entries);
    return entries;
  }

  void setSchedule(List<Entry> entries) {
    List<Entry> sorted = new ArrayList<>(entries);
    sort(sorted);
    if (sorted.isEmpty()) {
      prefs.edit().remove(KEY_SCHEDULE).apply();
      return;
    }
    JSONArray array = new JSONArray();
    try {
      for (Entry entry : sorted) {
        JSONObject item = new JSONObject();
        item.put("icon", entry.icon == null ? JSONObject.NULL : entry.icon);
        item.put("at", entry.at);
        array.put(item);
      }
    } catch (JSONException ignored) {
      // Only thrown for non-finite numbers, which `long` cannot hold.
    }
    prefs.edit().putString(KEY_SCHEDULE, array.toString()).apply();
  }

  private static void sort(List<Entry> entries) {
    Collections.sort(entries, new Comparator<Entry>() {
      @Override
      public int compare(Entry a, Entry b) {
        return a.at < b.at ? -1 : (a.at == b.at ? 0 : 1);
      }
    });
  }
}
