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

import android.databinding.tool.BindingAdapterCompat;
import android.databinding.tool.BindingMethodsCompat;
import android.databinding.tool.CompilerArguments;
import android.databinding.tool.InverseBindingAdapterCompat;
import android.databinding.tool.InverseBindingMethodsCompat;
import android.databinding.tool.InverseMethodCompat;
import android.databinding.tool.LibTypes;
import android.databinding.tool.UntaggableCompat;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.util.L;
import android.databinding.tool.util.LoggedErrorException;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.util.StringUtils;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;

public class ProcessMethodAdapters extends ProcessDataBinding.ProcessingStep {
    private final static String INVERSE_BINDING_EVENT_ATTR_SUFFIX = "AttrChanged";

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnv,
                                ProcessingEnvironment processingEnvironment,
                                CompilerArguments args) {
        L.d("processing adapters");
        final ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        Preconditions.checkNotNull(modelAnalyzer, "Model analyzer should be"
                + " initialized first");
        SetterStore store = SetterStore.get();
        clearIncrementalClasses(roundEnv, store);

        // 将用@BindingAdapter注释的方法解析，并存储到SetterStore中
        addBindingAdapters(roundEnv, processingEnvironment, store);
        // 将用@BindingMethod注释属性替换的方法解析，并存储到SetterStore中
        addRenamed(roundEnv, store);
        // 将用@BindingConversion注释的方法解析，并存储到SetterStore中
        addConversions(roundEnv, store);
        // 将用@Untaggable注释的类接口等的解析，并存储到SetterStore中
        addUntaggable(roundEnv, store);
        // 将用@InverseBindingAdapter注释的方法等解析，并存储到SetterStore中
        addInverseAdapters(roundEnv, processingEnvironment, store);
        addInverseBindingMethods(roundEnv, store);
        addInverseMethods(roundEnv, processingEnvironment, store);

        try {
            try {
                store.write(args.getModulePackage());
            } catch (IOException e) {
                L.e(e, "Could not write BindingAdapter intermediate file.");
            }
        } catch (LoggedErrorException e) {
            // This will be logged later
        }
        return true;
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
                                 ProcessingEnvironment processingEnvironment,
                                 CompilerArguments args) {

    }

    private void addBindingAdapters(RoundEnvironment roundEnv, ProcessingEnvironment
            processingEnv, SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> adapterAnnotation = libTypes.getBindingAdapterClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, adapterAnnotation)) {
            try {
                if (element.getKind() != ElementKind.METHOD ||
                        !element.getModifiers().contains(Modifier.PUBLIC)) {
                    L.e(element, "@BindingAdapter on invalid element: %s", element);
                    continue;
                }
                BindingAdapterCompat bindingAdapter = BindingAdapterCompat.create(element);

                ExecutableElement executableElement = (ExecutableElement) element;
                List<? extends VariableElement> parameters = executableElement.getParameters();
                if (bindingAdapter.getAttributes().length == 0) {
                    L.e(element, "@BindingAdapter requires at least one attribute. %s",
                            element);
                    continue;
                }

                final boolean takesComponent = takesComponent(executableElement, processingEnv);
                final int startIndex = 1 + (takesComponent ? 1 : 0);
                final int numAttributes = bindingAdapter.getAttributes().length;
                final int numAdditionalArgs = parameters.size() - startIndex;
                if (numAdditionalArgs == (2 * numAttributes)) {
                    // This BindingAdapter takes old and new values. Make sure they are properly ordered
                    Types typeUtils = processingEnv.getTypeUtils();
                    boolean hasParameterError = false;
                    for (int i = startIndex; i < numAttributes + startIndex; i++) {
                        if (!typeUtils.isSameType(parameters.get(i).asType(),
                                parameters.get(i + numAttributes).asType())) {
                            L.e(executableElement,
                                    "BindingAdapter %s: old values should be followed " +
                                            "by new values. Parameter %d must be the same type " +
                                            "as parameter %d.",
                                    executableElement, i + 1, i + numAttributes + 1);
                            hasParameterError = true;
                            break;
                        }
                    }
                    if (hasParameterError) {
                        continue;
                    }
                } else if (numAdditionalArgs != numAttributes) {
                    L.e(element, "@BindingAdapter %s has %d attributes and %d value " +
                                    "parameters. There should be %d or %d value parameters.",
                            executableElement, numAttributes, numAdditionalArgs,
                            numAttributes, numAttributes * 2);
                    continue;
                }
                warnAttributeNamespaces(element, bindingAdapter.getAttributes());
                try {
                    if (numAttributes == 1) {
                        final String attribute = bindingAdapter.getAttributes()[0];
                        store.addBindingAdapter(processingEnv, attribute, executableElement,
                                takesComponent);
                    } else {
                        store.addBindingAdapter(processingEnv, bindingAdapter.getAttributes(),
                                executableElement, takesComponent, bindingAdapter.getRequireAll());
                    }
                } catch (IllegalArgumentException e) {
                    L.e(element, "@BindingAdapter for duplicate View and parameter type: %s",
                            element);
                }
            } catch (LoggedErrorException e) {
                // This will be logged later
            }
        }
    }

    private static boolean takesComponent(ExecutableElement executableElement,
                                          ProcessingEnvironment processingEnvironment) {
        List<? extends VariableElement> parameters = executableElement.getParameters();
        Elements elementUtils = processingEnvironment.getElementUtils();
        TypeMirror viewElement = elementUtils
                .getTypeElement("android.view.View").asType();
        if (parameters.size() < 2) {
            return false; // Validation will fail in the caller
        }
        TypeMirror parameter1 = parameters.get(0).asType();
        Types typeUtils = processingEnvironment.getTypeUtils();
        if (parameter1.getKind() == TypeKind.DECLARED &&
                typeUtils.isAssignable(parameter1, viewElement)) {
            return false; // first parameter is a View
        }
        ModelAnalyzer analyzer = ModelAnalyzer.getInstance();
        if (parameters.size() < 3) {
            TypeMirror viewStubProxy = elementUtils.
                    getTypeElement(analyzer.libTypes.getViewStubProxy()).asType();
            if (!typeUtils.isAssignable(parameter1, viewStubProxy)) {
                L.e(executableElement, "@BindingAdapter %s is applied to a method that has" +
                        " two parameters, the first must be a View type:", executableElement);
            }
            return false;
        }
        TypeMirror parameter2 = parameters.get(1).asType();
        if (typeUtils.isAssignable(parameter2, viewElement)) {
            return true; // second parameter is a View
        }
        L.e(executableElement, "@BindingAdapter %s is applied to a method that doesn't take " +
                        "a View subclass as the first or second parameter. When a BindingAdapter" +
                        " uses a DataBindingComponent, the component parameter is first and the " +
                        "View parameter is second, otherwise the View parameter is first.",
                executableElement);
        return false;
    }

    private static void warnAttributeNamespace(Element element, String attribute) {
        if (attribute.contains(":") && !attribute.startsWith("android:")) {
            L.w(element, "Application namespace for attribute %s will be ignored.", attribute);
        }
    }

    private static void warnAttributeNamespaces(Element element, String[] attributes) {
        for (String attribute : attributes) {
            warnAttributeNamespace(element, attribute);
        }
    }

    private void addRenamed(RoundEnvironment roundEnv, SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> bindingMethodsClass = libTypes.getBindingMethodsClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, bindingMethodsClass)) {
            BindingMethodsCompat bindingMethods = BindingMethodsCompat.create(element);

            for (BindingMethodsCompat.BindingMethodCompat bindingMethod :
                    bindingMethods.getMethods()) {
                try {
                    final String attribute = bindingMethod.getAttribute();
                    final String method = bindingMethod.getMethod();
                    warnAttributeNamespace(element, attribute);
                    String type = bindingMethod.getType();
                    store.addRenamedMethod(attribute, type, method, (TypeElement) element);
                } catch (LoggedErrorException e) {
                    // this will be logged later
                }
            }
        }
    }

    private void addConversions(RoundEnvironment roundEnv, SetterStore store) {
        Class<? extends Annotation> bindingConversionClass = ModelAnalyzer.getInstance()
                .libTypes.getBindingConversionClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, bindingConversionClass)) {
            try {
                if (element.getKind() != ElementKind.METHOD ||
                        !element.getModifiers().contains(Modifier.STATIC) ||
                        !element.getModifiers().contains(Modifier.PUBLIC)) {
                    L.e(element, "@BindingConversion is only allowed on public static " +
                            "methods %s", element);
                    continue;
                }

                ExecutableElement executableElement = (ExecutableElement) element;
                if (executableElement.getParameters().size() != 1) {
                    L.e(element, "@BindingConversion method should have one parameter %s",
                            element);
                    continue;
                }
                if (executableElement.getReturnType().getKind() == TypeKind.VOID) {
                    L.e(element, "@BindingConversion method must return a value %s", element);
                    continue;
                }
                store.addConversionMethod(executableElement);
            } catch (LoggedErrorException e) {
                // this will be logged later
            }
        }
    }

    private void addInverseAdapters(RoundEnvironment roundEnv,
                                    ProcessingEnvironment processingEnv, SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> inverseBindingAdapterClass =
                libTypes.getInverseBindingAdapterClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, inverseBindingAdapterClass)) {
            try {
                if (!element.getModifiers().contains(Modifier.PUBLIC)) {
                    L.e(element,
                            "@InverseBindingAdapter must be associated with a public method");
                    continue;
                }
                ExecutableElement executableElement = (ExecutableElement) element;
                if (executableElement.getReturnType().getKind() == TypeKind.VOID) {
                    L.e(element, "@InverseBindingAdapter must have a non-void return type");
                    continue;
                }
                final InverseBindingAdapterCompat inverseBindingAdapter =
                        InverseBindingAdapterCompat.create(executableElement);
                final String attribute = inverseBindingAdapter.getAttribute();
                warnAttributeNamespace(element, attribute);
                final String event = inverseBindingAdapter.getEvent().isEmpty()
                        ? inverseBindingAdapter.getAttribute() + INVERSE_BINDING_EVENT_ATTR_SUFFIX
                        : inverseBindingAdapter.getEvent();
                warnAttributeNamespace(element, event);
                final boolean takesComponent = takesComponent(executableElement, processingEnv);
                final int expectedArgs = takesComponent ? 2 : 1;
                final int numParameters = executableElement.getParameters().size();
                if (numParameters != expectedArgs) {
                    L.e(element, "@InverseBindingAdapter %s takes %s parameters, but %s "
                            + "parameters were expected", element, numParameters, expectedArgs);
                    continue;
                }
                try {
                    store.addInverseAdapter(processingEnv, attribute, event, executableElement,
                            takesComponent);
                } catch (IllegalArgumentException e) {
                    L.e(element, "@InverseBindingAdapter for duplicate View and parameter "
                            + "type: %s", element);
                }
            } catch (LoggedErrorException e) {
                // This will be logged later
            }
        }
    }

    private void addInverseBindingMethods(RoundEnvironment roundEnv, SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> inverseBindingMethodsClass = libTypes
                .getInverseBindingMethodsClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, inverseBindingMethodsClass)) {
            InverseBindingMethodsCompat bindingMethods =
                    InverseBindingMethodsCompat.create(element);

            for (InverseBindingMethodsCompat.InverseBindingMethodCompat bindingMethod :
                    bindingMethods.getMethods()) {
                try {
                    final String attribute = bindingMethod.getAttribute();
                    final String method = bindingMethod.getMethod();
                    final String event = bindingMethod.getEvent().isEmpty()
                            ? bindingMethod.getAttribute() + INVERSE_BINDING_EVENT_ATTR_SUFFIX
                            : bindingMethod.getEvent();
                    warnAttributeNamespace(element, attribute);
                    warnAttributeNamespace(element, event);
                    String type = bindingMethod.getType();
                    store.addInverseBindingMethod(attribute, event, type, method,
                            (TypeElement) element);
                } catch (LoggedErrorException e) {
                    // This will be logged later
                }
            }
        }
    }

    private void addInverseMethods(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
                                   SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> inverseMethodClass = libTypes.getInverseMethodClass();
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, inverseMethodClass)) {
            try {
                if (!element.getModifiers().contains(Modifier.PUBLIC)) {
                    L.e(element, "@InverseMethods must be associated with a public method");
                    continue;
                }
                ExecutableElement executableElement = (ExecutableElement) element;
                if (executableElement.getReturnType().getKind() == TypeKind.VOID) {
                    L.e(element, "@InverseMethods must have a non-void return type");
                    continue;
                }
                final InverseMethodCompat inverseMethod =
                        InverseMethodCompat.create(executableElement);
                final String target = inverseMethod.getValue();
                if (!StringUtils.isNotBlank(target)) {
                    L.e(element, "@InverseMethod must supply a value containing the name of " +
                            "the method to call when going from View value to bound value");
                    continue;
                }
                if (executableElement.getParameters().isEmpty()) {
                    L.e(element, "@InverseMethods must have at least one parameter.");
                    continue;
                }
                try {
                    ExecutableElement inverse = findInverseOf(processingEnv, executableElement,
                            inverseMethod.getValue());
                    store.addInverseMethod(processingEnv, executableElement, inverse);
                } catch (IllegalArgumentException e) {
                    L.e(element, "%s", e.getMessage());
                }
            } catch (LoggedErrorException e) {
                // This will be logged later
            }
        }
    }

    private ExecutableElement findInverseOf(ProcessingEnvironment env, ExecutableElement method,
                                            String name) throws IllegalArgumentException {
        TypeElement enclosingType = (TypeElement) method.getEnclosingElement();
        List<? extends VariableElement> params = method.getParameters();
        Types typeUtil = env.getTypeUtils();
        for (Element element : env.getElementUtils().getAllMembers(enclosingType)) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement executableElement = (ExecutableElement) element;
                if (!name.equals(executableElement.getSimpleName().toString())) {
                    continue;
                }

                List<? extends VariableElement> checkParams = executableElement.getParameters();
                boolean allTypesMatch = true;
                // now check the parameters
                for (int i = 0; i < params.size() - 1; i++) {
                    TypeMirror expectedType = typeUtil.erasure(params.get(i).asType());
                    TypeMirror foundType = typeUtil.erasure(checkParams.get(i).asType());
                    if (!typeUtil.isSameType(expectedType, foundType)) {
                        allTypesMatch = false;
                        break;
                    }
                }
                if (allTypesMatch) {
                    TypeMirror expectedType = typeUtil.erasure(method.getReturnType());
                    TypeMirror foundType =
                            typeUtil.erasure(checkParams.get(checkParams.size() - 1).asType());
                    allTypesMatch = typeUtil.isSameType(expectedType, foundType);
                    if (allTypesMatch) {
                        // check return type
                        expectedType =
                                typeUtil.erasure(params.get(params.size() - 1).asType());
                        foundType = typeUtil.erasure(executableElement.getReturnType());
                        if (!typeUtil.isSameType(expectedType, foundType)) {
                            throw new IllegalArgumentException(String.format(
                                    "Declared InverseMethod ('%s') does not have the correct " +
                                            "return type. Expected '%s' but was '%s'",
                                    executableElement, expectedType, foundType));
                        }
                    }
                    if (method.getModifiers().contains(Modifier.STATIC) !=
                            executableElement.getModifiers().contains(Modifier.STATIC)) {
                        throw new IllegalArgumentException(String.format(
                                "'%s' declared instance method is different from its " +
                                        "InverseMethod '%s'. Make them both static or instance " +
                                        "methods.", method, executableElement));
                    }
                    if (!executableElement.getModifiers().contains(Modifier.PUBLIC)) {
                        throw new IllegalArgumentException(String.format(
                                "InverseMethod must be declared public '%s'", executableElement));
                    }
                    return executableElement;
                }
            }
        }
        StringBuilder paramStr = new StringBuilder();
        for (int i = 0; i < params.size() - 1; i++) {
            if (i != 0) {
                paramStr.append(", ");
            }
            paramStr.append(params.get(i).asType());
        }
        if (params.size() != 1) {
            paramStr.append(", ");
        }
        paramStr.append(method.getReturnType());
        String staticStr = method.getModifiers().contains(Modifier.STATIC) ? "static " : "";
        TypeMirror returnType = params.get(params.size() - 1).asType();
        throw new IllegalArgumentException(String.format(
                "Could not find inverse method: public %s%s %s(%s)", staticStr, returnType,
                name, paramStr));
    }

    private void addUntaggable(RoundEnvironment roundEnv, SetterStore store) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        Class<? extends Annotation> untaggableClass = libTypes.getUntaggableClass();
        for (Element element : AnnotationUtil.
                getElementsAnnotatedWith(roundEnv, untaggableClass)) {
            try {
                UntaggableCompat untaggable = UntaggableCompat.create(element);
                store.addUntaggableTypes(untaggable.getValue(), (TypeElement) element);
            } catch (LoggedErrorException e) {
                // This will be logged later
            }
        }
    }

    private void clearIncrementalClasses(RoundEnvironment roundEnv, SetterStore store) {
        HashSet<String> classes = new HashSet<>();
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, libTypes.getBindingAdapterClass())) {
            TypeElement containingClass = (TypeElement) element.getEnclosingElement();
            classes.add(containingClass.getQualifiedName().toString());
        }
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, libTypes.getBindingMethodsClass())) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        for (Element element : AnnotationUtil
                .getElementsAnnotatedWith(roundEnv, libTypes.getBindingConversionClass())) {
            classes.add(((TypeElement) element.getEnclosingElement()).getQualifiedName().
                    toString());
        }
        for (Element element : AnnotationUtil.
                getElementsAnnotatedWith(roundEnv, libTypes.getUntaggableClass())) {
            classes.add(((TypeElement) element).getQualifiedName().toString());
        }
        store.clear(classes);
    }
}
