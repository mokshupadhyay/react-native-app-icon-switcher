package com.flexappicon;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The app's launcher `<activity-alias>` entries, read from PackageManager on
 * every call so the result always reflects the installed manifest and the
 * current component states. Component names come from the system, never from
 * the application id, so build flavors and id suffixes need no configuration.
 */
final class FlexAppIconAliases {
  /** Optional `<meta-data android:name="flexAppIcon" android:value="Name" />` on an alias. */
  static final String META_NAME = "flexAppIcon";

  /** `MATCH_DISABLED_COMPONENTS` (API 24) and `GET_DISABLED_COMPONENTS` share this value. */
  private static final int MATCH_DISABLED = 0x00000200;

  static final class Alias {
    final ComponentName component;
    final String name;
    /** Enabled in the manifest: the icon shown after install. */
    final boolean primary;
    final boolean enabledNow;

    Alias(ComponentName component, String name, boolean primary, boolean enabledNow) {
      this.component = component;
      this.name = name;
      this.primary = primary;
      this.enabledNow = enabledNow;
    }

    /** The name reported to JavaScript; `null` for the primary icon. */
    @Nullable
    String publicName() {
      return primary ? null : name;
    }
  }

  final List<Alias> aliases;
  @Nullable final Alias primary;
  @Nullable final Alias current;
  final List<String> issues;
  /** Set when switching would be unsafe; switching is refused until it is fixed. */
  @Nullable final String blockingIssue;

  private FlexAppIconAliases(
      List<Alias> aliases,
      @Nullable Alias primary,
      @Nullable Alias current,
      List<String> issues,
      @Nullable String blockingIssue) {
    this.aliases = aliases;
    this.primary = primary;
    this.current = current;
    this.issues = issues;
    this.blockingIssue = blockingIssue;
  }

  static boolean isPrimaryName(@Nullable String name) {
    return name == null || name.trim().isEmpty() || "default".equalsIgnoreCase(name.trim());
  }

  /** The alias for `name`; `null`, `""` and `"default"` select the primary icon. */
  @Nullable
  Alias find(@Nullable String name) {
    if (isPrimaryName(name)) return primary;
    String wanted = name.trim();
    for (Alias alias : aliases) {
      if (alias.name.equals(wanted)) return alias;
    }
    for (Alias alias : aliases) {
      if (alias.name.equalsIgnoreCase(wanted)) return alias;
    }
    return null;
  }

  List<String> alternateNames() {
    List<String> names = new ArrayList<>();
    for (Alias alias : aliases) {
      if (!alias.primary) names.add(alias.name);
    }
    return names;
  }

  @SuppressWarnings("deprecation")
  static FlexAppIconAliases read(Context context) {
    PackageManager pm = context.getPackageManager();
    String packageName = context.getPackageName();
    List<String> issues = new ArrayList<>();

    ActivityInfo[] activities = null;
    try {
      PackageInfo info = pm.getPackageInfo(
          packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA | MATCH_DISABLED);
      activities = info.activities;
    } catch (PackageManager.NameNotFoundException | RuntimeException error) {
      issues.add("Could not read the app's activities: " + error);
    }

    Set<String> launchers = new HashSet<>();
    try {
      Intent launcher = new Intent(Intent.ACTION_MAIN)
          .addCategory(Intent.CATEGORY_LAUNCHER)
          .setPackage(packageName);
      for (ResolveInfo resolved : pm.queryIntentActivities(launcher, MATCH_DISABLED)) {
        if (resolved.activityInfo != null) launchers.add(resolved.activityInfo.name);
      }
    } catch (RuntimeException error) {
      issues.add("Could not query launcher activities: " + error);
    }

    List<Alias> aliases = new ArrayList<>();
    Set<String> targets = new HashSet<>();
    Set<String> names = new HashSet<>();
    if (activities != null) {
      for (ActivityInfo activity : activities) {
        if (activity.targetActivity == null) continue;
        String explicitName = activity.metaData != null ? activity.metaData.getString(META_NAME) : null;
        if (!launchers.contains(activity.name) && explicitName == null) continue;

        String name = explicitName != null && !explicitName.trim().isEmpty()
            ? explicitName.trim()
            : deriveName(activity.name);
        if (!names.add(name.toLowerCase())) {
          issues.add("Two launcher aliases share the icon name \"" + name + "\"; rename one with <meta-data android:name=\"flexAppIcon\" />.");
          continue;
        }
        ComponentName component = new ComponentName(packageName, activity.name);
        boolean enabledNow = isEnabled(pm, component, activity.enabled);
        aliases.add(new Alias(component, name, activity.enabled, enabledNow));
        targets.add(activity.targetActivity);
      }
    }

    String blockingIssue = null;
    if (activities != null) {
      for (ActivityInfo activity : activities) {
        if (activity.targetActivity == null
            && launchers.contains(activity.name)
            && targets.contains(activity.name)) {
          blockingIssue = activity.name + " still has the MAIN/LAUNCHER intent filter. Move it to a "
              + "default <activity-alias> so that switching icons never disables the activity itself.";
          issues.add(blockingIssue);
        }
      }
    }

    Alias primary = null;
    int primaryCount = 0;
    for (Alias alias : aliases) {
      if (alias.primary) {
        primary = alias;
        primaryCount++;
      }
    }
    if (!aliases.isEmpty() && primaryCount != 1) {
      String issue = "Exactly one launcher <activity-alias> must be android:enabled=\"true\" in the "
          + "manifest (found " + primaryCount + ").";
      issues.add(issue);
      if (blockingIssue == null) blockingIssue = issue;
      primary = null;
    }

    Alias current = null;
    for (Alias alias : aliases) {
      if (!alias.enabledNow) continue;
      // During a switch the primary can be enabled together with an explicitly enabled alternate.
      if (current == null || (current.primary && !alias.primary)) current = alias;
    }
    if (!aliases.isEmpty() && current == null) {
      issues.add("No launcher alias is enabled; the next icon change repairs this.");
    }

    return new FlexAppIconAliases(aliases, primary, current, issues, blockingIssue);
  }

  /** `.MainActivityHoliday` is `Holiday`, matching react-native-change-icon; `.Holiday` is `Holiday`. */
  static String deriveName(String className) {
    String simple = className.substring(className.lastIndexOf('.') + 1);
    int index = simple.indexOf("MainActivity");
    if (index >= 0) {
      String suffix = simple.substring(index + "MainActivity".length());
      if (!suffix.isEmpty()) return suffix;
    }
    return simple;
  }

  static boolean isEnabled(PackageManager pm, ComponentName component, boolean manifestEnabled) {
    int state;
    try {
      state = pm.getComponentEnabledSetting(component);
    } catch (RuntimeException error) {
      return manifestEnabled;
    }
    switch (state) {
      case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
        return true;
      case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
        return manifestEnabled;
      default:
        return false;
    }
  }
}
