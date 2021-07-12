/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.annotationprocessor;

import android.databinding.tool.CompilerArguments;
import android.databinding.tool.CompilerChef.BindableHolder;
import android.databinding.tool.LibTypes;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.util.GenerationalClassUtil;
import android.databinding.tool.util.L;
import android.databinding.tool.util.LoggedErrorException;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.BRWriter;
import android.databinding.tool.writer.JavaFileWriter;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

// binding app info and library info are necessary to trigger this.
public class ProcessBindable extends ProcessDataBinding.ProcessingStep implements BindableHolder {
    private Intermediate mProperties;
    private HashMap<String, HashSet<String>> mLayoutVariables = new HashMap<>();

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
            CompilerArguments args) {
        if (mProperties == null) {
            mProperties = new IntermediateV1(args.getModulePackage());
            mergeLayoutVariables();
            mLayoutVariables.clear();
            LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
            Elements elementUtils = processingEnv.getElementUtils();
            TypeElement observableType = elementUtils.getTypeElement(libTypes.getObservable());
            Types typeUtils = processingEnv.getTypeUtils();

            for (Element element : AnnotationUtil
                    .getElementsAnnotatedWith(roundEnv, libTypes.getBindableClass())) {
                Element parentElement = element.getEnclosingElement();

                if (parentElement.getKind() == ElementKind.CLASS &&
                    isGeneratedViewBinding(typeUtils, elementUtils, libTypes, parentElement)) {
                    // The Bindable checks which happen below really only pertain to user code, as
                    // we are sure that we did not generate invalid annotations ourselves. Also,
                    // we already generated mProperties for all these classes when parsing their
                    // source layout.xml files.
                    continue;
                }

                try {
                    Element enclosingElement = element.getEnclosingElement();
                    ElementKind kind = enclosingElement.getKind();
                    if (kind != ElementKind.CLASS && kind != ElementKind.INTERFACE) {
                        L.e("Bindable must be on a member field or method. The enclosing type is "
                                        + "%s",
                                enclosingElement.getKind());
                    }
                    TypeElement enclosing = (TypeElement) enclosingElement;
                    if (!typeUtils.isAssignable(enclosing.asType(), observableType.asType())) {
                        L.e("Bindable must be on a member in an Observable class. %s is not "
                                        + "Observable",
                                enclosingElement.getSimpleName());
                    }
                    String name = getPropertyName(element);
                    if (name != null) {
                        Preconditions
                                .checkNotNull(mProperties, "Must receive app / library info before "
                                        + "Bindable fields.");
                        mProperties.addProperty(enclosing.getQualifiedName().toString(), name);
                    }
                } catch (LoggedErrorException e) {
                    // We'll get them later when we do the messages
                }
            }
            GenerationalClassUtil.get().write(mProperties.getPackage(),
                    GenerationalClassUtil.ExtensionFilter.BR, mProperties);
            generateBRClasses(processingEnv, args, mProperties.getPackage());
            return true;
        }
        return false;
    }

    private static boolean isGeneratedViewBinding(Types typeUtils,
                                                  Elements elementUtils,
                                                  LibTypes libTypes,
                                                  Element element) {
        // Note: Ideally, we'd search for the @Generated({ "Android Data Binding "}) annotation to
        // know whether the target class was generated by us or not. However, that annotation was
        // not introduced until Java 8, so the classes we really want to know about
        // (".../generated/.../XyzBinding.java") are not annotated. As a secondary signal, we check
        // if the class inherits from ViewDataBinding, which user-code is not expected to do.
        TypeElement viewDataBinding = elementUtils.getTypeElement(libTypes.getViewDataBinding());
        return typeUtils.isAssignable(element.asType(), viewDataBinding.asType());
    }

    @Override
    public void addVariable(String variableName, String containingClassName) {
        HashSet<String> variableNames = mLayoutVariables.get(containingClassName);
        if (variableNames == null) {
            variableNames = new HashSet<String>();
            mLayoutVariables.put(containingClassName, variableNames);
        }
        variableNames.add(variableName);
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment,
            CompilerArguments args) {
    }

    private void generateBRClasses(
            ProcessingEnvironment processingEnv,
            CompilerArguments compilerArgs,
            String pkg) {
        try {
            CompilerArguments.Type artifactType = compilerArgs.getArtifactType();
            L.d("************* Generating BR file %s. use final: %s", pkg, artifactType.name());
            HashSet<String> properties = new HashSet<>();
            mProperties.captureProperties(properties);
            BindableBag bindableBag = new BindableBag(
                    compilerArgs,
                    getProperties(mProperties),
                    processingEnv);
            final JavaFileWriter writer = getWriter();
            boolean useFinal = compilerArgs.isApp()
                    || compilerArgs.isFeature()
                    || compilerArgs.isTestVariant();
            BRWriter brWriter = new BRWriter(useFinal);
            bindableBag.getToBeGenerated().forEach(brWithValues -> {
                String out = brWriter.write(brWithValues);
                writer.writeToFile(brWithValues.getPkg() + ".BR", out);
            });
            mCallback.onBrWriterReady(
                    bindableBag.getVariableIdLookup(),
                    bindableBag.getWrittenPackages());
        } catch (LoggedErrorException e) {
            // This will be logged later
        }
    }

    private String getPropertyName(Element element) {
        switch (element.getKind()) {
            case FIELD:
                return stripPrefixFromField((VariableElement) element);
            case METHOD:
                return stripPrefixFromMethod((ExecutableElement) element);
            default:
                L.e("@Bindable is not allowed on %s", element.getKind());
                return null;
        }
    }

    private static String stripPrefixFromField(VariableElement element) {
        Name name = element.getSimpleName();
        if (name.length() >= 2) {
            char firstChar = name.charAt(0);
            char secondChar = name.charAt(1);
            if (name.length() > 2 && firstChar == 'm' && secondChar == '_') {
                char thirdChar = name.charAt(2);
                if (Character.isJavaIdentifierStart(thirdChar)) {
                    return "" + Character.toLowerCase(thirdChar) +
                            name.subSequence(3, name.length());
                }
            } else if ((firstChar == 'm' && Character.isUpperCase(secondChar)) ||
                    (firstChar == '_' && Character.isJavaIdentifierStart(secondChar))) {
                return "" + Character.toLowerCase(secondChar) + name.subSequence(2, name.length());
            }
        }
        return name.toString();
    }

    private String stripPrefixFromMethod(ExecutableElement element) {
        Name name = element.getSimpleName();
        CharSequence propertyName;
        if (isGetter(element) || isSetter(element)) {
            propertyName = name.subSequence(3, name.length());
        } else if (isBooleanGetter(element)) {
            propertyName = name.subSequence(2, name.length());
        } else {
            L.e("@Bindable associated with method must follow JavaBeans convention %s", element);
            return null;
        }
        char firstChar = propertyName.charAt(0);
        return "" + Character.toLowerCase(firstChar) +
                propertyName.subSequence(1, propertyName.length());
    }

    private void mergeLayoutVariables() {
        for (String containingClass : mLayoutVariables.keySet()) {
            for (String variable : mLayoutVariables.get(containingClass)) {
                mProperties.addProperty(containingClass, variable);
            }
        }
    }

    private static boolean prefixes(CharSequence sequence, String prefix) {
        boolean prefixes = false;
        if (sequence.length() > prefix.length()) {
            int count = prefix.length();
            prefixes = true;
            for (int i = 0; i < count; i++) {
                if (sequence.charAt(i) != prefix.charAt(i)) {
                    prefixes = false;
                    break;
                }
            }
        }
        return prefixes;
    }

    private static boolean isGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "get") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() != TypeKind.VOID;
    }

    private static boolean isSetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "set") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().size() == 1 &&
                element.getReturnType().getKind() == TypeKind.VOID;
    }

    private static boolean isBooleanGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "is") &&
                Character.isJavaIdentifierStart(name.charAt(2)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() == TypeKind.BOOLEAN;
    }

    interface Intermediate extends Serializable {

        void captureProperties(Set<String> properties);

        void addProperty(String className, String propertyName);

        boolean hasValues();

        String getPackage();
    }

    static class IntermediateV1 implements Serializable, Intermediate {
        private static final long serialVersionUID = 2L;

        private String mPackage;
        private final HashMap<String, HashSet<String>> mProperties = new HashMap<>();

        public IntermediateV1(String aPackage) {
            mPackage = aPackage;
        }

        @Override
        public void captureProperties(Set<String> properties) {
            for (HashSet<String> propertySet : mProperties.values()) {
                properties.addAll(propertySet);
            }
        }

        @Override
        public void addProperty(String className, String propertyName) {
            HashSet<String> properties = mProperties.get(className);
            if (properties == null) {
                properties = new HashSet<String>();
                mProperties.put(className, properties);
            }
            properties.add(propertyName);
        }

        @Override
        public boolean hasValues() {
            return !mProperties.isEmpty();
        }

        @Override
        public String getPackage() {
            return mPackage;
        }
    }

    static Set<String> getProperties(Intermediate intermediate) {
        Set<String> out = new HashSet<>();
        intermediate.captureProperties(out);
        return out;
    }
}
