/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.cxxbridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.facebook.common.logging.FLog;
import com.facebook.proguard.annotations.DoNotStrip;
import com.facebook.react.bridge.BaseJavaModule;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.CatalystInstance;
import com.facebook.react.bridge.ExecutorToken;
import com.facebook.react.bridge.NativeArray;
import com.facebook.react.bridge.PromiseImpl;
import com.facebook.react.bridge.ReadableNativeArray;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.systrace.Systrace;
import com.facebook.systrace.SystraceMessage;

import static com.facebook.systrace.Systrace.TRACE_TAG_REACT_JAVA_BRIDGE;

/**
 * This is part of the glue which wraps a java BaseJavaModule in a C++
 * NativeModule.  This could all be in C++, but it's android-specific
 * initialization code, and writing it this way is easier to read and means
 * fewer JNI calls.
 */

@DoNotStrip
/* package */ class JavaModuleWrapper {
  @DoNotStrip
  static class MethodDescriptor {
    @DoNotStrip
    Method method;
    @DoNotStrip
    String signature;
    @DoNotStrip
    String name;
    @DoNotStrip
    String type;
  }
  private static ModuleProvider mCoreModuleProvider;
  private static ModuleProvider mCustomModuleProvider;

  private final CatalystInstance mCatalystInstance;
  private final BaseJavaModule mModule;
  private final AbstractModuleHelper mModuleHelper;

  static {
    String clsName = JavaModuleWrapper.class.getName();
    try {
      Class<?> coreHelperCls = Class.forName(clsName + "$CoreModuleProvider");
      //noinspection unchecked
      mCoreModuleProvider = (ModuleProvider) coreHelperCls.newInstance();
    } catch (ClassNotFoundException e) {
      FLog.w("JavaModuleHelper", "Could not find generated core module provider");
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException("Unable to instantiate core module provider ", e);
    }
    try {
      Class<?> customHelperCls = Class.forName(clsName + "$CustomModuleProvider");
      //noinspection unchecked
      mCustomModuleProvider = (ModuleProvider) customHelperCls.newInstance();
    } catch (ClassNotFoundException e) {
      FLog.w("JavaModuleHelper", "Could not find generated custom module provider");
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException("Unable to instantiate custom module provider ", e);
    }
  }

  public JavaModuleWrapper(CatalystInstance catalystinstance, BaseJavaModule module) {
    mCatalystInstance = catalystinstance;
    mModule = module;
    AbstractModuleHelper helper = null;
    if (mCoreModuleProvider != null) {
      helper = mCoreModuleProvider.getModuleHelper(module);
    }
    if (helper == null && mCustomModuleProvider != null) {
      helper = mCustomModuleProvider.getModuleHelper(module);
    }
    mModuleHelper = helper == null ? new FallbackModuleHelper(module) : helper;
  }

  @DoNotStrip
  public BaseJavaModule getModule() {
    return mModule;
  }

  @DoNotStrip
  public String getName() {
    return mModule.getName();
  }

  @DoNotStrip
  public List<MethodDescriptor> getMethodDescriptors() {
    return mModuleHelper.getMethodDescriptors();
  }

  @DoNotStrip
  public List<MethodDescriptor> newGetMethodDescriptors() {
    return mModuleHelper.newGetMethodDescriptors();
  }

  // TODO mhorowitz: make this return NativeMap, which requires moving
  // NativeMap out of OnLoad.
  @DoNotStrip
  public NativeArray getConstants() {
    SystraceMessage.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "Map constants")
      .arg("moduleName", getName())
      .flush();
    Map<String, Object> map = mModule.getConstants();
    Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);

    SystraceMessage.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "WritableNativeMap constants")
      .arg("moduleName", getName())
      .flush();
    WritableNativeMap writableNativeMap;
    try {
      writableNativeMap = Arguments.makeNativeMap(map);
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    }
    WritableNativeArray array = new WritableNativeArray();
    array.pushMap(writableNativeMap);
    return array;
  }

  @DoNotStrip
  public boolean supportsWebWorkers() {
    return mModule.supportsWebWorkers();
  }

  @DoNotStrip
  public void invoke(ExecutorToken token, int methodId, ReadableNativeArray parameters) {
    mModuleHelper.invoke(mCatalystInstance, token, methodId, parameters);
  }

  /**
   * Native module helper, aim to improve performance
   * Created by ransj on 30/09/2016.
   *
   * @author ransj
   */

  public interface ModuleHelper {
    /**
     * @return
     */
    List<MethodDescriptor> getMethodDescriptors();

    /**
     * @return
     */
    List<MethodDescriptor> newGetMethodDescriptors();

    /**
     * @param catalystInstance
     * @param executorToken
     * @param methodId
     * @param parameters
     */
    void invoke(CatalystInstance catalystInstance, ExecutorToken executorToken, int methodId, ReadableNativeArray parameters);
  }

  /**
   * Abstract module helper, aim to improve performance
   * Created by ransj on 30/09/2016.
   *
   * @author ransj
   */

  static abstract class AbstractModuleHelper implements ModuleHelper {
    protected void checkMethodSignature(BaseJavaModule module, MethodDescriptor methodInfo) {
      String methodName = methodInfo.name;
      String signature = methodInfo.signature;
      if (module.supportsWebWorkers() && signature.charAt(2) != 'T') {
        throw new RuntimeException("Module" + module.getName()
          + " supports web workers, but " + methodName
          + " does not take an ExecutorToken as its first parameter.");
      }
      if (signature.contains("T") && !module.supportsWebWorkers()) {
        throw new RuntimeException("Module" + module.getName()
          + " doesn't support web workers, but "
          + methodName + " takes an ExecutorToken.");
      }
    }

    protected com.facebook.react.bridge.CallbackImpl createCallback(
      CatalystInstance catalystinstance, ExecutorToken executorToken, int index, ReadableNativeArray jsArguments) {
      if (jsArguments.isNull(index)) {
        return null;
      } else {
        int id = (int) jsArguments.getDouble(index);
        return new com.facebook.react.bridge.CallbackImpl(catalystinstance, executorToken, id);
      }
    }

    protected PromiseImpl createPromise(
      CatalystInstance catalystinstance, ExecutorToken executorToken, int index, ReadableNativeArray jsArguments) {
      Callback resolve = createCallback(catalystinstance, executorToken, index, jsArguments);
      Callback reject = createCallback(catalystinstance, executorToken, ++index, jsArguments);
      return new PromiseImpl(resolve, reject);
    }
  }

  static interface ModuleProvider {
    public AbstractModuleHelper getModuleHelper(BaseJavaModule module);
  }

  static class FallbackModuleHelper extends AbstractModuleHelper {
    private BaseJavaModule mModule;
    private final ArrayList<BaseJavaModule.JavaMethod> mMethods;


    public FallbackModuleHelper(BaseJavaModule module) {
      mModule = module;
      mMethods = new ArrayList<BaseJavaModule.JavaMethod>();
    }

    @Override
    public List<MethodDescriptor> getMethodDescriptors() {
      ArrayList<MethodDescriptor> descs = new ArrayList<>();

      for (Map.Entry<String, BaseJavaModule.NativeMethod> entry :
        mModule.getMethods().entrySet()) {
        MethodDescriptor md = new MethodDescriptor();
        md.name = entry.getKey();
        md.type = entry.getValue().getType();

        BaseJavaModule.JavaMethod method = (BaseJavaModule.JavaMethod) entry.getValue();
        mMethods.add(method);
        descs.add(md);
      }
      return descs;
    }

    @Override
    public List<MethodDescriptor> newGetMethodDescriptors() {
      ArrayList<MethodDescriptor> descs = new ArrayList<>();

      for (Map.Entry<String, BaseJavaModule.NativeMethod> entry :
        mModule.getMethods().entrySet()) {
        MethodDescriptor md = new MethodDescriptor();
        md.name = entry.getKey();
        md.type = entry.getValue().getType();

        BaseJavaModule.JavaMethod method = (BaseJavaModule.JavaMethod) entry.getValue();
        md.method = method.getMethod();
        md.signature = method.getSignature();
        descs.add(md);
      }

      for (Map.Entry<String, BaseJavaModule.SyncNativeHook> entry :
        mModule.getSyncHooks().entrySet()) {
        MethodDescriptor md = new MethodDescriptor();
        md.name = entry.getKey();
        md.type = BaseJavaModule.METHOD_TYPE_SYNC_HOOK;

        BaseJavaModule.SyncJavaHook method = (BaseJavaModule.SyncJavaHook) entry.getValue();
        md.method = method.getMethod();
        md.signature = method.getSignature();

        descs.add(md);
      }
      return descs;
    }

    @Override
    public void invoke(CatalystInstance catalystInstance, ExecutorToken executorToken, int methodId, ReadableNativeArray parameters) {
      if (mMethods == null || methodId >= mMethods.size()) {
        return;
      }
      mMethods.get(methodId).invoke(catalystInstance, executorToken, parameters);
    }
  }
}
