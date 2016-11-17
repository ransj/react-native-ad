/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.react.processing;

import com.facebook.infer.annotation.Assertions;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 *
 * This annotation processor crawls subclasses of BaseJavaModule and finds their
 * exported methods with the @ReactMethod or @ReactSyncHook annotation. It generates a class
 * per native module. This class contains methods to retrieve description of all
 * methods and a way to invoke methods without reflection.
 * @author ransj
 */
public class ReactNativeModuleProcessor extends AbstractProcessor {
  private static final ClassName METHOD_DESCRIPTION = ClassName.get("com.facebook.react.cxxbridge", "JavaModuleWrapper", "MethodDescriptor");
  private static final ClassName JAVA_MODULE_WRAPPER = ClassName.get("com.facebook.react.cxxbridge", "JavaModuleWrapper");
  private static final ClassName NATIVE_MODULE_HELPER = ClassName.get("com.facebook.react.cxxbridge", "JavaModuleWrapper", "AbstractModuleHelper");
  private static final ClassName NATIVE_MODULE_PROVIDER = ClassName.get("com.facebook.react.cxxbridge", "JavaModuleWrapper", "ModuleProvider");
  private static final ClassName CATALYSTINSTANCE = ClassName.get("com.facebook.react.bridge", "CatalystInstance");
  private static final ClassName EXECUTORTOKEN = ClassName.get("com.facebook.react.bridge", "ExecutorToken");
  private static final ClassName READABLENATIVEARRAY = ClassName.get("com.facebook.react.bridge", "ReadableNativeArray");
  private static final ClassName BASE_JAVA_MODULE = ClassName.get("com.facebook.react.bridge", "BaseJavaModule");
  private static final ClassName NATIVE_ARGUMENTS_PARSE_EXCEPTION = ClassName.get("com.facebook.react.bridge", "NativeArgumentsParseException");
  private static final ClassName CALLBACKIMP = ClassName.get("com.facebook.react.bridge", "CallbackImpl");
  private static final ClassName PROMISEIMP = ClassName.get("com.facebook.react.bridge", "PromiseImpl");
  private static final TypeName GET_METHOD_DESCRIPTIONS_LIST_TYPE =
    ParameterizedTypeName.get(ClassName.get(List.class), METHOD_DESCRIPTION);
  private final Map<String, ClassInfo> mClasses;

  private static final String FIELD_NAME_BASE_JAVA_MODULE = "mNativeModule";

  @SuppressFieldNotInitialized
  private Filer mFiler;
  @SuppressFieldNotInitialized
  private Messager mMessager;
  @SuppressFieldNotInitialized
  private Elements mElements;
  @SuppressFieldNotInitialized
  private Types mTypes;

  public ReactNativeModuleProcessor() {
    mClasses = new HashMap<>();
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnvironment) {
    super.init(processingEnvironment);
    mFiler = processingEnvironment.getFiler();
    mMessager = processingEnvironment.getMessager();
    mTypes = processingEnvironment.getTypeUtils();
    mElements = processingEnvironment.getElementUtils();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    mClasses.clear();
    for (TypeElement te : annotations) {
      ClassName annotationName = ClassName.get(te);
      boolean isSyncHook = "ReactMethod".equals(annotationName.simpleName()) ? false : true;
      for (Element ele : roundEnv.getElementsAnnotatedWith(te)) {
        ClassName clsName = ClassName.get((TypeElement) ele.getEnclosingElement());
        String pkgName = clsName.packageName();
        String simpleName = clsName.simpleName();
        String key = pkgName + simpleName;
        ClassInfo clsInfo = mClasses.get(key);
        if (clsInfo == null) {
          clsInfo = new ClassInfo(pkgName, simpleName);
          mClasses.put(key, clsInfo);
        }
        clsInfo.addMethod(new MethodInfo((ExecutableElement) ele, isSyncHook, mTypes, mElements));
      }
    }
    int count = 0;
    List<ClassInfo> classInfos = new ArrayList<>();
    for (ClassInfo info : mClasses.values()) {
      writeToFile(info, count++);
      classInfos.add(info);
    }
    if (!classInfos.isEmpty()) {
      writeModuleProvider(classInfos);
    }
    return true;
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> set = new HashSet<>();
    set.add("com.facebook.react.bridge.ReactMethod");
    set.add("com.facebook.react.bridge.ReactSyncHook");
    return set;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.RELEASE_7;
  }

  private void writeToFile(ClassInfo classInfo, int index) {
    String clsName = JAVA_MODULE_WRAPPER.simpleName() + "$"+index;
    ClassName className = ClassName.get(classInfo.mPkgName, classInfo.mClsName);
    TypeSpec holderClass = TypeSpec.classBuilder(clsName)
      .superclass(NATIVE_MODULE_HELPER)
      .addModifiers(PUBLIC)
      .addField(className, FIELD_NAME_BASE_JAVA_MODULE, Modifier.PRIVATE)
      .addMethod(generateHelperConstructor(className))
      .addMethod(generateMethodGetMethodDescriptors(classInfo.mMethods))
      .addMethod(generateMethodNewGetMethodDescriptors(classInfo.mMethods))
      .addMethod(generateMethodInvoke(classInfo.mMethods))
      .build();
    JavaFile javaFile = JavaFile.builder(JAVA_MODULE_WRAPPER.packageName(), holderClass)
      .addFileComment("Generated by " + getClass().getName())
      .build();

    try {
      javaFile.writeTo(mFiler);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private MethodSpec generateHelperConstructor(ClassName className) {
    MethodSpec method = MethodSpec.constructorBuilder()
      .addModifiers(Modifier.PUBLIC)
      .addParameter(className, "module")
      .addStatement("mNativeModule = $L", "module")
      .build();
    return method;
  }

  private MethodSpec generateMethodGetMethodDescriptors(List<MethodInfo> methodInfos) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.addStatement("$T $L = new $T<>()", GET_METHOD_DESCRIPTIONS_LIST_TYPE, "list", ArrayList.class);
    for (int i = 0, len = methodInfos.size(); i < len; i++) {
      MethodInfo methodInfo = methodInfos.get(i);
      builder.add("// method $L \n", methodInfo.mName);
      builder.addStatement("$T $L = new $T()", METHOD_DESCRIPTION, methodInfo.mName, METHOD_DESCRIPTION);
      builder.addStatement("$L.name = $S", methodInfo.mName, methodInfo.mName);
      builder.addStatement("$L.type = $T.$L", methodInfo.mName, BASE_JAVA_MODULE, methodInfo.mMethodType);
//      builder.addStatement("$L.signature = $S", methodInfo.mName, methodInfo.mSignature);
//      builder.addStatement("checkMethodSignature($L, $L)", FIELD_NAME_BASE_JAVA_MODULE, methodInfo.mName);
      builder.addStatement("$L.add($L)", "list", methodInfo.mName);
    }
    builder.addStatement("return $L", "list");
    MethodSpec method = MethodSpec.methodBuilder("getMethodDescriptors")
      .addAnnotation(Override.class)
      .addModifiers(Modifier.PUBLIC)
      .returns(GET_METHOD_DESCRIPTIONS_LIST_TYPE)
      .addCode(builder.build())
      .build();
    return method;
  }

  private MethodSpec generateMethodNewGetMethodDescriptors(List<MethodInfo> methodInfos) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.addStatement("$T $L = new $T<>()", GET_METHOD_DESCRIPTIONS_LIST_TYPE, "list", ArrayList.class);
    for (int i = 0, len = methodInfos.size(); i < len; i++) {
      MethodInfo methodInfo = methodInfos.get(i);
      builder.add("// method $L \n", methodInfo.mName);
      builder.addStatement("$T $L = new $T()", METHOD_DESCRIPTION, methodInfo.mName, METHOD_DESCRIPTION);
      builder.addStatement("$L.name = $S", methodInfo.mName, methodInfo.mName);
      builder.addStatement("$L.type = $T.$L", methodInfo.mName, BASE_JAVA_MODULE, methodInfo.getMethodType());
      builder.addStatement("$L.signature = $S", methodInfo.mName, methodInfo.mSignature);
      builder.addStatement("checkMethodSignature($L, $L)", FIELD_NAME_BASE_JAVA_MODULE, methodInfo.mName);
      builder.addStatement("$L.add($L)", "list", methodInfo.mName);
    }
    builder.addStatement("return $L", "list");
    MethodSpec method = MethodSpec.methodBuilder("newGetMethodDescriptors")
      .addAnnotation(Override.class)
      .addModifiers(Modifier.PUBLIC)
      .returns(GET_METHOD_DESCRIPTIONS_LIST_TYPE)
      .addCode(builder.build())
      .build();
    return method;
  }

  private MethodSpec generateMethodInvoke(List<MethodInfo> methodInfos) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.add("switch( $L ) {\n", "methodId");
    int size = methodInfos.size();
    for (int i = 0; i < size; i++) {
      MethodInfo methodInfo = methodInfos.get(i);
      builder.indent().add("case $L:\n", i);
      builder.add(methodInfo.createInvokeMethod());
      builder.unindent().add("break;\n").unindent();
    }
    builder.add("}\n");
    MethodSpec method = MethodSpec.methodBuilder("invoke")
      .addAnnotation(Override.class)
      .addModifiers(Modifier.PUBLIC)
      .addParameter(CATALYSTINSTANCE, "catalystInstance")
      .addParameter(EXECUTORTOKEN, "executorToken")
      .addParameter(int.class, "methodId")
      .addParameter(READABLENATIVEARRAY, "parameters")
      .addCode(builder.build())
      .build();
    return method;
  }

  private void writeModuleProvider(List<ClassInfo> classInfos) {
    CodeBlock.Builder builder = CodeBlock.builder();
    for (int i = 0, len = classInfos.size(); i < len; i++) {
      ClassInfo info = classInfos.get(i);
      builder.add("if ($L instanceof $T) {\n", "module", ClassName.get(info.mPkgName, info.mClsName));
      builder.indent().addStatement("return new $T(($T)$L)",
        ClassName.get(JAVA_MODULE_WRAPPER.packageName(), JAVA_MODULE_WRAPPER.simpleName() + "$" + i),
        ClassName.get(info.mPkgName, info.mClsName),
        "module");
      builder.unindent();
      if(i < len -1){
        builder.add("} else ");
      }
    }
    builder.add("}\n");
    builder.addStatement("return null");
    MethodSpec methodSpec = MethodSpec.methodBuilder("getModuleHelper")
      .addModifiers(PUBLIC)
      .addAnnotation(Override.class)
      .addParameter(BASE_JAVA_MODULE, "module")
      .returns(NATIVE_MODULE_HELPER)
      .addCode(builder.build())
      .build();
    String clsName = JAVA_MODULE_WRAPPER.simpleName() + "$CoreModuleProvider";
    TypeSpec holderClass = TypeSpec.classBuilder(clsName)
      .addSuperinterface(NATIVE_MODULE_PROVIDER)
      .addMethod(methodSpec)
      .build();
    JavaFile javaFile = JavaFile.builder(JAVA_MODULE_WRAPPER.packageName(), holderClass)
      .addFileComment("Generated by " + getClass().getName())
      .build();

    try {
      javaFile.writeTo(mFiler);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private class ClassInfo {
    private String mPkgName;
    private String mClsName;
    private List<MethodInfo> mMethods = new ArrayList<>();

    public ClassInfo(String pkgName, String className) {
      mPkgName = pkgName;
      mClsName = className;
    }

    public void addMethod(MethodInfo method) {
      mMethods.add(method);
    }
  }

  private static class MethodInfo {
    static final String TYPE_RESULT_ASYNC = "METHOD_TYPE_ASYNC";
    static final String TYPE_RESULT_PROMISE = "METHOD_TYPE_PROMISE";
    static final String TYPE_RESULT_SYNC = "METHOD_TYPE_SYNC";
    String mSignature;
    String mName;
    private List<? extends VariableElement> mParameters;
    private int mParametersNum;
    private boolean mIsSyncHook;
    private String mMethodType = TYPE_RESULT_ASYNC;

    public MethodInfo(ExecutableElement executableElement, boolean isSyncHook, Types types, Elements elements) {
      mIsSyncHook = isSyncHook;
      mParameters = executableElement.getParameters();
      mParametersNum = mParameters.size();
      mSignature = buildSignature(mParameters, types, elements);
      mName = executableElement.getSimpleName().toString();
    }

    private String buildSignature(List<? extends VariableElement> paramTypes, Types types, Elements elements) {
      int size = paramTypes.size();
      StringBuilder builder = new StringBuilder(size);
      builder.append("v.");
      for (int i = 0; i < size; i++) {
        VariableElement parameter = paramTypes.get(i);
        TypeMirror mirror = parameter.asType();
        if (types.isSubtype(mirror, elements.getTypeElement(Promise.class.getName()).asType())) {
          Assertions.assertCondition(
            i == size - 1, "Promise must be used as last parameter only");
          mMethodType = TYPE_RESULT_PROMISE;
          mParametersNum++;
        } else if (types.isSubtype(mirror, elements.getTypeElement(EXECUTORTOKEN.packageName() + "." + EXECUTORTOKEN.simpleName()).asType())) {
          mParametersNum--;
        }
        builder.append(paramTypeToChar(parameter));
      }

      return builder.toString();
    }

    private char paramTypeToChar(VariableElement parameter) {
      TypeName indexType = TypeName.get(parameter.asType());
      char tryCommon = commonTypeToChar(indexType);
      if (tryCommon != '\0') {
        return tryCommon;
      }
      if (ClassName.get(parameter.asType()).equals(EXECUTORTOKEN)) {
        return 'T';
      } else if (indexType.equals(TypeName.get(Callback.class))) {
        return 'X';
      } else if (indexType.equals(TypeName.get(Promise.class))) {
        return 'P';
      } else if (indexType.equals(TypeName.get(ReadableMap.class))) {
        return 'M';
      } else if (indexType.equals(TypeName.get(ReadableArray.class))) {
        return 'A';
      } else {
        throw new RuntimeException(
          "Got unknown param class: " + indexType.toString());
      }
    }

    private char commonTypeToChar(TypeName indexType) {
      if (indexType.equals(TypeName.get(boolean.class))) {
        return 'z';
      } else if (indexType.equals(TypeName.get(Boolean.class))) {
        return 'Z';
      } else if (indexType.equals(TypeName.get(int.class))) {
        return 'i';
      } else if (indexType.equals(TypeName.get(Integer.class))) {
        return 'I';
      } else if (indexType.equals(TypeName.get(double.class))) {
        return 'd';
      } else if (indexType.equals(TypeName.get(Double.class))) {
        return 'D';
      } else if (indexType.equals(TypeName.get(float.class))) {
        return 'f';
      } else if (indexType.equals(TypeName.get(Float.class))) {
        return 'F';
      } else if (indexType.equals(TypeName.get(String.class))) {
        return 'S';
      } else {
        return '\0';
      }
    }

    public CodeBlock createInvokeMethod() {
      CodeBlock.Builder builder = CodeBlock.builder();
      builder.indent().add("if($L != $L.size()) {\n", mParametersNum, "parameters");
      builder.indent().add("throw new $T($L.getClass().getName()+\".\"+$S+\" got \"+$L.toString()+\"arguments, except \"+$L);\n", NATIVE_ARGUMENTS_PARSE_EXCEPTION, FIELD_NAME_BASE_JAVA_MODULE, mName, "parameters", mParametersNum);
      builder.unindent().add("}\n");
      builder.add("$L.$L(", FIELD_NAME_BASE_JAVA_MODULE, mName);
      int offset = 0;
      for (int i = 0, len = mParameters.size(); i < len; i++) {
        TypeName indexType = TypeName.get(mParameters.get(i).asType());
        if ((ClassName.get(mParameters.get(i).asType()).equals(EXECUTORTOKEN))) {
          builder.add("$L", "executorToken");
          builder.add(", ");
          continue;
        } else if (indexType.equals(TypeName.get(boolean.class)) || indexType.equals(TypeName.get(Boolean.class))) {
          builder.add("$L.getBoolean($L)", "parameters", offset);
        } else if (indexType.equals(TypeName.get(int.class)) || indexType.equals(TypeName.get(Integer.class))) {
          builder.add("$L.getInt($L)", "parameters", offset);
        } else if (indexType.equals(TypeName.get(double.class)) || indexType.equals(TypeName.get(Double.class))) {
          builder.add("$L.getDouble($L)", "parameters", offset);
        } else if (indexType.equals(TypeName.get(float.class)) || indexType.equals(TypeName.get(Float.class))) {
          builder.add("$L.getFloat($L)", "parameters", offset);
        } else if (indexType.equals(TypeName.get(String.class))) {
          builder.add("$L.getString($L)", "parameters", offset);
        } else if (indexType.equals(ClassName.get(Callback.class))) {
          builder.add("createCallback($L, $L, $L, $L)", "catalystInstance", "executorToken", offset, "parameters");
        } else if (indexType.equals(TypeName.get(Promise.class))) {
          builder.add("createPromise($L, $L, $L, $L)", "catalystInstance", "executorToken", offset, "parameters");
        } else if (indexType.equals(TypeName.get(ReadableMap.class))) {
          builder.add("$L.getMap($L)", "parameters", offset);
        } else if (indexType.equals(TypeName.get(ReadableArray.class))) {
          builder.add("$L.getArray($L)", "parameters", offset);
        } else {
          throw new RuntimeException("can not parse parameters in " + mName + ", index " + i + ", type " + indexType.toString() + ", " + ClassName.get(mParameters.get(i).asType()).toString());
        }
        if (i < len - 1) {
          builder.add(", ");
        }
        offset++;
      }
      builder.add(");\n");
      return builder.build();
    }

    private String getMethodType() {
      return mIsSyncHook ? TYPE_RESULT_SYNC : mMethodType;
    }
  }
}
