package com.flexappicon;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;

/** Legacy bridge module with the same surface as the TurboModule spec. */
public class FlexAppIconModule extends ReactContextBaseJavaModule {
  public static final String NAME = FlexAppIconModuleImpl.NAME;

  private final FlexAppIconModuleImpl impl;

  public FlexAppIconModule(ReactApplicationContext context) {
    super(context);
    impl = new FlexAppIconModuleImpl(context);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void getState(Promise promise) {
    impl.getState(promise);
  }

  @ReactMethod
  public void setIcon(@Nullable String name, Promise promise) {
    impl.setIcon(name, promise);
  }

  @ReactMethod
  public void cancelPending(Promise promise) {
    impl.cancelPending(promise);
  }

  @ReactMethod
  public void setSchedule(ReadableArray entries, Promise promise) {
    impl.setSchedule(entries, promise);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Required by NativeEventEmitter; events are emitted unconditionally.
  }

  @ReactMethod
  public void removeListeners(double count) {
    // Required by NativeEventEmitter.
  }

  @Override
  public void invalidate() {
    impl.invalidate();
  }
}
