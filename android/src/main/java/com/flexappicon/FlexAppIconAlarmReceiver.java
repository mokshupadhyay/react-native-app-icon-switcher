package com.flexappicon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when the next schedule entry is due; runs even when the app is not running. */
public final class FlexAppIconAlarmReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    FlexAppIconEngine.get(context).onAlarm();
  }
}
