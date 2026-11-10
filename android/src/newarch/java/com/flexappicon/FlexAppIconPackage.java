package com.flexappicon;

import androidx.annotation.Nullable;

import com.facebook.react.TurboReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.module.model.ReactModuleInfo;
import com.facebook.react.module.model.ReactModuleInfoProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * `TurboReactPackage` is deprecated in favor of `BaseReactPackage` (0.74+),
 * but it is the base class available on every New Architecture release.
 */
@SuppressWarnings("deprecation")
public class FlexAppIconPackage extends TurboReactPackage {
  @Nullable
  @Override
  public NativeModule getModule(String name, ReactApplicationContext context) {
    return FlexAppIconModule.NAME.equals(name) ? new FlexAppIconModule(context) : null;
  }

  @Override
  public ReactModuleInfoProvider getReactModuleInfoProvider() {
    return new ReactModuleInfoProvider() {
      @Override
      public Map<String, ReactModuleInfo> getReactModuleInfos() {
        Map<String, ReactModuleInfo> infos = new HashMap<>();
        infos.put(FlexAppIconModule.NAME, new ReactModuleInfo(
            FlexAppIconModule.NAME,
            FlexAppIconModule.class.getName(),
            false, // canOverrideExistingModule
            false, // needsEagerInit
            false, // hasConstants
            false, // isCxxModule
            true)); // isTurboModule
        return infos;
      }
    };
  }
}
