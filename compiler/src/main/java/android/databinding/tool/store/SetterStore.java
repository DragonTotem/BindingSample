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
package android.databinding.tool.store;

import android.databinding.tool.Context;
import android.databinding.tool.reflection.ImportBag;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.annotation.AnnotationTypeUtil;
import android.databinding.tool.util.GenerationalClassUtil;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.util.StringUtils;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class SetterStore {
    private static final int ASSIGNABLE_CONVERSION = 1;
    private final BindingAdapterStore mStore;
    private final ModelAnalyzer mClassAnalyzer;
    private HashMap<String, List<String>> mInstanceAdapters;
    private final Set<String> mInverseEventAttributes;

    private Comparator<MultiAttributeSetter> COMPARE_MULTI_ATTRIBUTE_SETTERS =
            new Comparator<MultiAttributeSetter>() {
                @Override
                public int compare(MultiAttributeSetter o1, MultiAttributeSetter o2) {
                    if (o1.attributes.length != o2.attributes.length) {
                        return o2.attributes.length - o1.attributes.length;
                    }
                    if (o1.mKey.attributeIndices.size() != o2.mKey.attributeIndices.size()) {
                        return o2.mKey.attributeIndices.size() - o1.mKey.attributeIndices.size();
                    }
                    ModelClass view1 = mClassAnalyzer.findClass(o1.mKey.viewType, null).erasure();
                    ModelClass view2 = mClassAnalyzer.findClass(o2.mKey.viewType, null).erasure();
                    if (!view1.equals(view2)) {
                        if (view1.isAssignableFrom(view2)) {
                            return 1;
                        } else {
                            return -1;
                        }
                    }
                    if (!o1.mKey.attributeIndices.keySet()
                            .equals(o2.mKey.attributeIndices.keySet())) {
                        // order by attribute name
                        Iterator<String> o1Keys = o1.mKey.attributeIndices.keySet().iterator();
                        Iterator<String> o2Keys = o2.mKey.attributeIndices.keySet().iterator();
                        while (o1Keys.hasNext() && o2Keys.hasNext()) {
                            String key1 = o1Keys.next();
                            String key2 = o2Keys.next();
                            int compare = key1.compareTo(key2);
                            if (compare != 0) {
                                return compare;
                            }
                        }
                        Preconditions.check(false,
                                "The sets don't match! That means the keys shouldn't match also");
                    }
                    // Same view type. Same attributes
                    for (String attribute : o1.mKey.attributeIndices.keySet()) {
                        final int index1 = o1.mKey.attributeIndices.get(attribute);
                        final int index2 = o2.mKey.attributeIndices.get(attribute);
                        ModelClass type1 = mClassAnalyzer
                                .findClass(o1.mKey.parameterTypes[index1], null);
                        ModelClass type2 = mClassAnalyzer
                                .findClass(o2.mKey.parameterTypes[index2], null);
                        if (type1.equals(type2)) {
                            continue;
                        }
                        if (o1.mCasts[index1] != null) {
                            if (o2.mCasts[index2] == null) {
                                return 1; // o2 is better
                            } else {
                                continue; // both are casts
                            }
                        } else if (o2.mCasts[index2] != null) {
                            return -1; // o1 is better
                        }
                        if (o1.mConverters[index1] != null) {
                            if (o2.mConverters[index2] == null) {
                                return 1; // o2 is better
                            } else {
                                continue; // both are conversions
                            }
                        } else if (o2.mConverters[index2] != null) {
                            return -1; // o1 is better
                        }

                        if (type1.isPrimitive()) {
                            if (type2.isPrimitive()) {
                                int type1ConversionLevel = ModelMethod
                                        .getImplicitConversionLevel(type1);
                                int type2ConversionLevel = ModelMethod
                                        .getImplicitConversionLevel(type2);
                                return type2ConversionLevel - type1ConversionLevel;
                            } else {
                                // type1 is primitive and has higher priority
                                return -1;
                            }
                        } else if (type2.isPrimitive()) {
                            return 1;
                        }
                        if (type1.isAssignableFrom(type2)) {
                            return 1;
                        } else if (type2.isAssignableFrom(type1)) {
                            return -1;
                        }
                    }
                    // hmmm... same view type, same attributes, same parameter types... ?
                    return 0;
                }
            };

    private SetterStore(ModelAnalyzer modelAnalyzer, BindingAdapterStore store) {
        mClassAnalyzer = modelAnalyzer;
        mStore = store;
        mStore.setAsMainStore();
        mInverseEventAttributes = mStore.collectInverseEvents();
    }

    public static SetterStore get() {
        return Context.getSetterStore();
    }

    public static SetterStore create(ModelAnalyzer modelAnalyzer,
                                     GenerationalClassUtil generationalClassUtil) {
        return load(modelAnalyzer, generationalClassUtil);
    }

    private static SetterStore load(ModelAnalyzer modelAnalyzer,
                                    GenerationalClassUtil generationalClassUtil) {
        List<Intermediate> previousStores = generationalClassUtil
                .load(GenerationalClassUtil.ExtensionFilter.SETTER_STORE,
                        Intermediate.class);
        List<BindingAdapterStore> gsonIntermediates = generationalClassUtil
                .load(GenerationalClassUtil.ExtensionFilter.SETTER_STORE_JSON,
                        BindingAdapterStore.class);
        BindingAdapterStore store = new BindingAdapterStore(previousStores, gsonIntermediates,
                modelAnalyzer.libTypes.getUseAndroidX());
        return new SetterStore(modelAnalyzer, store);
    }

    public void addRenamedMethod(String attribute, String declaringClass, String method,
            TypeElement declaredOn) {
        attribute = stripNamespace(attribute);
        MethodDescription methodDescription = new MethodDescription(
                declaredOn.getQualifiedName().toString(), method);
        mStore.addRenamedMethod(attribute, declaringClass, methodDescription);
    }

    public void addInverseBindingMethod(String attribute, String event, String declaringClass,
            String method, TypeElement declaredOn) {
        attribute = stripNamespace(attribute);
        event = stripNamespace(event);
        InverseDescription methodDescription = new InverseDescription(
                declaredOn.getQualifiedName().toString(), method, event);
        mStore.addInverseBindingMethod(attribute, declaringClass, methodDescription);
    }

    public void addInverseMethod(ProcessingEnvironment processingEnvironment,
            ExecutableElement method, ExecutableElement inverse) {
        InverseMethodDescription from = new InverseMethodDescription(processingEnvironment, method);
        InverseMethodDescription to = new InverseMethodDescription(processingEnvironment, inverse);
        mStore.addInverseMethod(from, to);
    }

    public void addBindingAdapter(ProcessingEnvironment processingEnv, String attribute,
            ExecutableElement bindingMethod, boolean takesComponent) {
        attribute = stripNamespace(attribute);
        L.d("STORE addBindingAdapter %s %s", attribute, bindingMethod);

        List<? extends VariableElement> parameters = bindingMethod.getParameters();
        final int viewIndex = takesComponent ? 1 : 0;
        TypeMirror viewType = eraseType(processingEnv, parameters.get(viewIndex).asType());
        String view = getQualifiedName(viewType);
        TypeMirror parameterType = eraseType(processingEnv, parameters.get(viewIndex + 1).asType());
        String value = getQualifiedName(parameterType);

        AccessorKey key = new AccessorKey(view, value);
        MethodDescription desc = new MethodDescription(bindingMethod, 1, takesComponent);

        mStore.addBindingAdapter(attribute, key, desc);
    }

    public void addInverseAdapter(ProcessingEnvironment processingEnv, String attribute,
            String event, ExecutableElement bindingMethod, boolean takesComponent) {
        attribute = stripNamespace(attribute);
        event = stripNamespace(event);
        L.d("STORE addInverseAdapter %s %s", attribute, bindingMethod);

        List<? extends VariableElement> parameters = bindingMethod.getParameters();
        final int viewIndex = takesComponent ? 1 : 0;
        TypeMirror viewType = eraseType(processingEnv, parameters.get(viewIndex).asType());
        String view = getQualifiedName(viewType);
        TypeMirror returnType = eraseType(processingEnv, bindingMethod.getReturnType());
        String value = getQualifiedName(returnType);

        AccessorKey key = new AccessorKey(view, value);
        InverseDescription desc = new InverseDescription(bindingMethod, event, takesComponent);
        mStore.addInverseBindingAdapter(attribute, key, desc);
    }

    private static TypeMirror eraseType(ProcessingEnvironment processingEnv,
            TypeMirror typeMirror) {
        if (hasTypeVar(typeMirror)) {
            return processingEnv.getTypeUtils().erasure(typeMirror);
        } else {
            return typeMirror;
        }
    }

    private static ModelClass eraseType(ModelClass modelClass) {
        if (hasTypeVar(modelClass)) {
            return modelClass.erasure();
        } else {
            return modelClass;
        }
    }

    private static boolean hasTypeVar(TypeMirror typeMirror) {
        TypeKind kind = typeMirror.getKind();
        if (kind == TypeKind.TYPEVAR) {
            return true;
        } else if (kind == TypeKind.ARRAY) {
            return hasTypeVar(((ArrayType) typeMirror).getComponentType());
        } else if (kind == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) typeMirror;
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
            if (typeArguments == null || typeArguments.isEmpty()) {
                return false;
            }
            for (TypeMirror arg : typeArguments) {
                if (hasTypeVar(arg)) {
                    return true;
                }
            }
            return false;
        } else {
            return false;
        }
    }

    private static boolean hasTypeVar(ModelClass type) {
        if (type.isTypeVar()) {
            return true;
        } else if (type.isArray()) {
            return hasTypeVar(type.getComponentType());
        } else {
            List<ModelClass> typeArguments = type.getTypeArguments();
            if (typeArguments == null) {
                return false;
            }
            for (ModelClass arg : typeArguments) {
                if (hasTypeVar(arg)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void addBindingAdapter(ProcessingEnvironment processingEnv, String[] attributes,
            ExecutableElement bindingMethod, boolean takesComponent, boolean requireAll) {
        L.d("STORE add multi-value BindingAdapter %d %s", attributes.length, bindingMethod);
        MultiValueAdapterKey key = new MultiValueAdapterKey(processingEnv, bindingMethod,
                attributes, takesComponent, requireAll);
        testRepeatedAttributes(key, bindingMethod);
        MethodDescription methodDescription = new MethodDescription(bindingMethod,
                attributes.length, takesComponent);
        mStore.addMultiValueAdapter(key, methodDescription);
    }

    private static void testRepeatedAttributes(MultiValueAdapterKey key, ExecutableElement method) {
        if (key.attributes.length != key.attributeIndices.size()) {
            HashSet<String> names = new HashSet<>();
            for (String attr : key.attributes) {
                if (names.contains(attr)) {
                    L.e(method, "Attribute \"" + attr + "\" is supplied multiple times in " +
                            "BindingAdapter " + method.toString());
                }
                names.add(attr);
            }
        }
    }

    private static String[] stripAttributes(String[] attributes) {
        String[] strippedAttributes = new String[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i] != null) {
                strippedAttributes[i] = stripNamespace(attributes[i]);
            }
        }
        return strippedAttributes;
    }

    public void addUntaggableTypes(String[] typeNames, TypeElement declaredOn) {
        L.d("STORE addUntaggableTypes %s %s", Arrays.toString(typeNames), declaredOn);
        String declaredType = declaredOn.getQualifiedName().toString();
        mStore.addUntaggableType(typeNames, declaredType);
    }

    private static String getQualifiedName(TypeMirror type) {
        final TypeKind kind = type.getKind();
        if (kind == TypeKind.ARRAY) {
            return getQualifiedName(((ArrayType) type).getComponentType()) + "[]";
        } else if (kind == TypeKind.DECLARED && isIncompleteType(type)) {
            DeclaredType declaredType = (DeclaredType) type;
            return declaredType.asElement().toString();
        } else {
            return ((AnnotationTypeUtil)AnnotationTypeUtil.getInstance()).toJava(type);
        }
    }

    private static boolean isIncompleteType(TypeMirror type) {
        final TypeKind kind = type.getKind();
        if (kind == TypeKind.TYPEVAR || kind == TypeKind.WILDCARD) {
            return true;
        } else if (kind == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) type;
            List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
            if (typeArgs == null) {
                return false;
            }
            for (TypeMirror arg : typeArgs) {
                if (isIncompleteType(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addConversionMethod(ExecutableElement conversionMethod) {
        L.d("STORE addConversionMethod %s", conversionMethod);
        List<? extends VariableElement> parameters = conversionMethod.getParameters();
        String fromType = getQualifiedName(parameters.get(0).asType());
        String toType = getQualifiedName(conversionMethod.getReturnType());
        MethodDescription methodDescription = new MethodDescription(conversionMethod, 1, false);
        mStore.addConversionMethod(fromType, toType, methodDescription);
    }

    public void clear(Set<String> classes) {
        mStore.clear(classes);
    }

    public void write(String projectPackage)
            throws IOException {
        Preconditions.checkNotNull(mStore.getCurrentModuleStore(),
                "current module store should not be null");
        GenerationalClassUtil.get().write(
                projectPackage,
                GenerationalClassUtil.ExtensionFilter.SETTER_STORE_JSON,
                mStore.getCurrentModuleStore());
    }

    private static String stripNamespace(String attribute) {
        if (!attribute.startsWith("android:")) {
            int colon = attribute.indexOf(':');
            if (colon >= 0) {
                attribute = attribute.substring(colon + 1);
            }
        }
        return attribute;
    }

    public boolean isTwoWayEventAttribute(String attribute) {
        attribute = stripNamespace(attribute);
        return mInverseEventAttributes.contains(attribute);
    }
    public List<MultiAttributeSetter> getMultiAttributeSetterCalls(String[] attributes,
            ModelClass viewType, ModelClass[] valueType) {
        attributes = stripAttributes(attributes);
        final ArrayList<MultiAttributeSetter> calls = new ArrayList<MultiAttributeSetter>();
        if (viewType != null && viewType.isGeneric()) {
            List<ModelClass> viewGenerics = viewType.getTypeArguments();
            for (int i = 0; i < valueType.length; i++) {
                valueType[i] = eraseType(valueType[i], viewGenerics);
            }
            viewType = viewType.erasure();
        }
        ArrayList<MultiAttributeSetter> matching = getMatchingMultiAttributeSetters(attributes,
                viewType, valueType);
        Collections.sort(matching, COMPARE_MULTI_ATTRIBUTE_SETTERS);
        while (!matching.isEmpty()) {
            MultiAttributeSetter bestMatch = matching.get(0);
            calls.add(bestMatch);
            removeConsumedAttributes(matching, bestMatch.attributes);
        }
        return calls;
    }


    public Map<String, List<String>> getComponentBindingAdapters() {
        ensureInstanceAdapters();
        return mInstanceAdapters;
    }

    private String getBindingAdapterCall(String className) {
        ensureInstanceAdapters();
        final String simpleName = BindingAdapterStore.simpleName(className);
        List<String> adapters = mInstanceAdapters.get(simpleName);
        if (adapters.size() == 1) {
            return "get" + simpleName + "()";
        } else {
            int index = adapters.indexOf(className) + 1;
            return "get" + simpleName + index + "()";
        }
    }

    private void ensureInstanceAdapters() {
        if (mInstanceAdapters == null) {
            mInstanceAdapters = mStore.createInstanceAdapters();
        }
    }

    // Removes all MultiAttributeSetters that require any of the values in attributes
    private static void removeConsumedAttributes(ArrayList<MultiAttributeSetter> matching,
            String[] attributes) {
        for (int i = matching.size() - 1; i >= 0; i--) {
            final MultiAttributeSetter setter = matching.get(i);
            boolean found = false;
            for (String attribute : attributes) {
                if (isInArray(attribute, setter.attributes)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                matching.remove(i);
            }
        }
    }

    // Linear search through the String array for a specific value.
    private static boolean isInArray(String str, String[] array) {
        for (String value : array) {
            if (value.equals(str)) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<MultiAttributeSetter> getMatchingMultiAttributeSetters(String[] attributes,
            ModelClass viewType, ModelClass[] valueType) {
        return mStore.findMultiValueAdapters((adapter, method) -> {
            if (adapter.requireAll && adapter.attributes.length > attributes.length) {
                return null;
            }
            ModelClass viewClass = mClassAnalyzer.findClass(adapter.viewType, null);
            if (viewClass == null) {
                return null;
            }
            if (viewClass.isGeneric()) {
                viewClass = viewClass.erasure();
            }
            if (!viewClass.isAssignableFrom(viewType)) {
                return null;
            }
            return createMultiAttributeSetter(method, attributes, valueType, adapter);
        });
    }

    private MultiAttributeSetter createMultiAttributeSetter(MethodDescription method,
            String[] allAttributes, ModelClass[] attributeValues, MultiValueAdapterKey adapter) {
        int matchingAttributes = 0;
        String[] casts = new String[adapter.attributes.length];
        MethodDescription[] conversions = new MethodDescription[adapter.attributes.length];
        boolean[] supplied = new boolean[adapter.attributes.length];

        for (int i = 0; i < allAttributes.length; i++) {
            Integer index = adapter.attributeIndices.get(allAttributes[i]);
            if (index != null) {
                supplied[index] = true;
                matchingAttributes++;
                final String parameterTypeStr = adapter.parameterTypes[index];
                ModelClass paramClass = mClassAnalyzer.findClass(parameterTypeStr, null);
                if (paramClass == null) {
                    return null;
                }
                final ModelClass parameterType = eraseType(paramClass);
                final ModelClass attributeType = attributeValues[i];
                if (!parameterType.isAssignableFrom(attributeType)) {
                    if (ModelMethod.isBoxingConversion(parameterType, attributeType)) {
                        // automatic boxing is ok
                        continue;
                    } else if (ModelMethod.isImplicitConversion(attributeType, parameterType)) {
                        // implicit conversion is ok
                        continue;
                    }
                    // Look for a converter
                    conversions[index] = getConversionMethod(attributeType, parameterType, null);
                    if (conversions[index] == null) {
                        if (attributeType.isObject()) {
                            // Cast is allowed also
                            casts[index] = parameterTypeStr;
                        } else {
                            // Parameter type mismatch
                            return null;
                        }
                    }
                }
            }
        }

        if ((adapter.requireAll && matchingAttributes != adapter.attributes.length) ||
                matchingAttributes == 0) {
            return null;
        } else {
            return new MultiAttributeSetter(adapter, supplied, method, conversions, casts);
        }
    }

    public SetterCall getSetterCall(String attribute, ModelClass viewType,
                                    ModelClass valueType, ImportBag imports) {
        if (viewType == null) {
            return null;
        }
        @SuppressWarnings("WeakerAccess")
        class BestSetter {
            @Nullable
            ModelClass viewType = null;
            @Nullable
            ModelClass valueType = null;
            @Nullable
            SetterCall setterCall = null;
        }

        viewType = viewType.erasure();
        attribute = stripNamespace(attribute);
        final BestSetter bestSetter = new BestSetter();
        ModelMethod bestSetterMethod = getBestSetter(viewType, valueType, attribute, imports);
        if (bestSetterMethod != null) {
            bestSetter.viewType = bestSetterMethod.getReceiverType();
            bestSetter.valueType = bestSetterMethod.getParameterTypes()[0];
            bestSetter.setterCall = new ModelMethodSetter(bestSetterMethod);
        }

        ModelClass finalViewType = viewType;
        mStore.forEachAdapterMethod(attribute, (key, adapter) -> {
            try {
                ModelClass adapterViewType =
                        mClassAnalyzer.findClass(key.viewType, imports).erasure();
                if (adapterViewType != null && adapterViewType.isAssignableFrom(finalViewType)) {
                    try {
                        L.d("setter parameter type is %s", key.valueType);
                        final ModelClass adapterValueType = eraseType(mClassAnalyzer
                                .findClass(key.valueType, imports));
                        L.d("setter %s takes type %s, compared to %s",
                                adapter.method, adapterValueType.toJavaCode(),
                                valueType.toJavaCode());
                        if (isBetterParameter(valueType, adapterViewType, adapterValueType,
                                bestSetter.viewType, bestSetter.valueType, imports)) {
                            final ModelClass adapterClass = mClassAnalyzer
                                    .findClass(adapter.type, imports);
                            if (adapterClass == null) {
                                // adapter is not in compile classpath, probably in runtime
                                // classpath hence we should ignore it.
                                L.d("ignoring adapter %s because it is not in the" +
                                        " compile classpath.", adapter.type);
                                return null;
                            }
                            bestSetter.viewType = adapterViewType;
                            bestSetter.valueType = adapterValueType;
                            bestSetter.setterCall = new AdapterSetter(adapter, adapterValueType);
                        }
                    } catch (Exception e) {
                        L.e(e, "Unknown class: %s", key.valueType);
                    }
                }
            } catch (Exception e) {
                L.e(e, "Unknown class: %s", key.viewType);
            }
            return null;
        });

        if (bestSetter.setterCall != null) {
            if (valueType.isObject() && bestSetter.valueType.isNullable()) {
                bestSetter.setterCall.setCast(bestSetter.valueType);
            }
            MethodDescription conversionMethod =
                    getConversionMethod(valueType, bestSetter.valueType, imports);
            bestSetter.setterCall.setConverter(conversionMethod);
        }

        if (bestSetter.setterCall == null && viewType.isViewDataBinding()) {
            // if it is a binding, try to find setter on the view class.
            SetterCall rootViewSetter = getSetterCall(
                    attribute,
                    viewType.findInstanceGetter("getRoot").getReturnType(),
                    valueType,
                    imports);
            if (rootViewSetter != null) {
                // replace with a root view getter and return it
                return new ViewBindingRootViewSetterCall(rootViewSetter);
            }
        }
        return bestSetter.setterCall;
    }

    public BindingGetterCall getGetterCall(String attribute, ModelClass viewType,
            ModelClass valueType, ImportBag imports) {
        if (viewType == null) {
            return null;
        } else if (viewType.isViewDataBinding()) {
            return new ViewDataBindingGetterCall(viewType, attribute);
        }

        attribute = stripNamespace(attribute);
        viewType = viewType.erasure();

        InverseMethod bestMethod = getBestGetter(viewType, valueType, attribute, imports);
        ModelClass finalViewType = viewType;
        mStore.forEachInverseAdapterMethod(attribute, (key, inverseDescription) -> {
            try {
                ModelClass adapterViewType = mClassAnalyzer
                        .findClass(key.viewType, imports).erasure();
                if (adapterViewType != null && adapterViewType.isAssignableFrom(finalViewType)) {
                    try {
                        L.d("getter return type is %s", key.valueType);
                        final ModelClass adapterValueType = eraseType(mClassAnalyzer
                                .findClass(key.valueType, imports));
                        L.d("getter %s returns type %s, compared to %s",
                                inverseDescription.method, adapterValueType.toJavaCode(),
                                valueType);
                        if (valueType == null ||
                                isBetterReturn(valueType, adapterViewType, adapterValueType,
                                        bestMethod.viewType, bestMethod.returnType, imports)) {
                            bestMethod.viewType = adapterViewType;
                            bestMethod.returnType = adapterValueType;
                            ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
                            ModelClass listenerType = modelAnalyzer.findClass(
                                    modelAnalyzer.libTypes.getInverseBindingListener(),
                                    ImportBag.EMPTY);
                            BindingSetterCall eventCall = getSetterCall(
                                    inverseDescription.event, finalViewType, listenerType, imports);
                            if (eventCall == null) {
                                List<MultiAttributeSetter> setters =
                                        getMultiAttributeSetterCalls(
                                                new String[]{inverseDescription.event},
                                                finalViewType, new ModelClass[] {listenerType});
                                if (setters.size() != 1) {
                                    L.e("Could not find event '%s' on View type '%s'",
                                            inverseDescription.event,
                                            finalViewType.getCanonicalName());
                                } else {
                                    bestMethod.call = new AdapterGetter(inverseDescription,
                                            setters.get(0), key.valueType);
                                }
                            } else {
                                bestMethod.call = new AdapterGetter(inverseDescription,
                                        eventCall, key.valueType);
                            }
                        }

                    } catch (Exception e) {
                        L.e(e, "Unknown class: %s", key.valueType);
                    }
                }
            } catch (Exception e) {
                L.e(e, "Unknown class: %s", key.viewType);
            }
            return null;
        });
        return bestMethod.call;
    }

    public String getInverseMethod(ModelMethod method) {
        InverseMethodDescription description = new InverseMethodDescription(method);
        return mStore.getInverseMethod(description);
    }

    public boolean isUntaggable(String viewType) {
        return mStore.isUntaggable(viewType);
    }

    private ModelMethod getBestSetter(ModelClass viewType, ModelClass argumentType,
            String attribute, ImportBag imports) {
        if (viewType.isGeneric()) {
            argumentType = eraseType(argumentType, viewType.getTypeArguments());
            viewType = viewType.erasure();
        }
        ModelClass finalViewType = viewType;
        List<String> setterCandidates = mStore.findRenamed(attribute, className -> {
            try {
                ModelClass renamedViewType = mClassAnalyzer.findClass(className, imports);
                if (renamedViewType == null) {
                    return false;
                }
                return renamedViewType.erasure().isAssignableFrom(finalViewType);
            } catch (Exception e) {
                //printMessage(Diagnostic.Kind.NOTE, "Unknown class: " + className);
            }
            return false;
        });
        setterCandidates.add(getSetterName(attribute));
        setterCandidates.add(trimAttributeNamespace(attribute));

        ModelMethod bestMethod = null;
        ModelClass bestParameterType = null;
        List<ModelClass> args = new ArrayList<>();
        args.add(argumentType);
        for (String name : setterCandidates) {
            List<ModelMethod> methods = viewType.getMethods(name, 1);

            for (ModelMethod method : methods) {
                ModelClass[] parameterTypes = method.getParameterTypes();
                ModelClass param = parameterTypes[0];
                ModelClass previousViewType = bestParameterType == null ? null : viewType;
                if (method.isVoid() && isBetterParameter(argumentType, viewType, param,
                        previousViewType, bestParameterType, imports)) {
                    bestParameterType = param;
                    bestMethod = method;
                }
            }
        }
        return bestMethod;
    }

    private InverseMethod getBestGetter(ModelClass viewType, ModelClass valueType,
            String attribute, ImportBag imports) {
        @SuppressWarnings("WeakerAccess")
        class BestSetter {
            @Nullable
            ModelClass returnType = null;
            @Nullable
            InverseDescription description = null;
            @Nullable
            ModelClass viewType = null;
            @Nullable
            ModelMethod method = null;
        }
        if (viewType.isGeneric()) {
            if (valueType != null) {
                valueType = eraseType(valueType, viewType.getTypeArguments());
            }
            viewType = viewType.erasure();
        }
        final BestSetter bestSetter = new BestSetter();

        ModelClass finalViewType = viewType;
        ModelClass finalValueType = valueType;
        mStore.forEachInverseMethod(attribute, (className, inverseDescription) -> {
            try {
                ModelClass methodViewType = mClassAnalyzer.findClass(className, imports);
                if (methodViewType != null
                        && methodViewType.erasure().isAssignableFrom(finalViewType)) {
                    final String name = inverseDescription.method.isEmpty() ?
                            trimAttributeNamespace(attribute) : inverseDescription.method;
                    ModelMethod method = methodViewType.findInstanceGetter(name);
                    ModelClass returnType = method.getReturnType(null); // no parameters
                    if (finalValueType == null || bestSetter.returnType == null ||
                            isBetterReturn(finalValueType, methodViewType, returnType,
                                    bestSetter.viewType, bestSetter.returnType, imports)) {
                        bestSetter.description = inverseDescription;
                        bestSetter.returnType = returnType;
                        bestSetter.viewType = methodViewType;
                        bestSetter.method = method;
                    }
                }
            } catch (Exception e) {
                L.d(e, "Unknown class: " + className);
            }
            return null;
        });

        BindingGetterCall call = null;
        if (bestSetter.description != null) {
            ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
            final ModelClass listenerType = modelAnalyzer.findClass(
                    modelAnalyzer.libTypes.getInverseBindingListener(), ImportBag.EMPTY);
            SetterCall eventSetter = getSetterCall(bestSetter.description.event, viewType,
                    listenerType, imports);
            if (eventSetter == null) {
                List<MultiAttributeSetter> setters = getMultiAttributeSetterCalls(
                        new String[] {bestSetter.description.event}, viewType,
                        new ModelClass[] {listenerType});
                if (setters.size() != 1) {
                    L.e("Could not find event '%s' on View type '%s'",
                            bestSetter.description.event,
                            viewType.getCanonicalName());
                    bestSetter.viewType = null;
                    bestSetter.returnType = null;
                } else {
                    call = new ViewGetterCall(
                            bestSetter.description,
                            bestSetter.method,
                            setters.get(0));
                }
            } else {
                call = new ViewGetterCall(
                        bestSetter.description,
                        bestSetter.method,
                        eventSetter);
            }
        }
        return new InverseMethod(
                call,
                bestSetter.returnType,
                bestSetter.viewType);
    }

    private static ModelClass eraseType(ModelClass type, List<ModelClass> typeParameters) {
        List<ModelClass> typeArguments = type.getTypeArguments();
        if (typeArguments == null || typeParameters == null) {
            return type;
        }
        for (ModelClass arg : typeArguments) {
            if (typeParameters.contains(arg)) {
                return type.erasure();
            }
        }
        return type;
    }

    private static String trimAttributeNamespace(String attribute) {
        final int colonIndex = attribute.indexOf(':');
        return colonIndex == -1 ? attribute : attribute.substring(colonIndex + 1);
    }

    private static String getSetterName(String attribute) {
        return "set" + StringUtils.capitalize(trimAttributeNamespace(attribute));
    }

    /**
     * Checks to see if one parameter is a better fit for a setter (e.g. BindingAdapter) than
     * another for an argument. It is assumed that both view types match the targeted view.
     * <p>
     * Note that this has different priorities than
     * {@link #isBetterReturn(ModelClass, ModelClass, ModelClass, ModelClass, ModelClass, ImportBag)}
     *
     * @param argument The argument type being passed to the setter.
     * @param newViewType The type of the view in the BindingAdapter or setter method.
     * @param newParameter The parameter type of the value in the BindingAdapter or setter method.
     * @param oldViewType The type of the view in the previous matching BindingAdapter or setter
     *                   method.
     * @param oldParameter The parameter type of the value in the previous matching BindingAdapter
     *                    or setter method.
     * @param imports Import in the binding layout file.
     * @return {@code true} when the new BindingAdapter or setter method is a better fit than the
     * previous one or {@code false} if {@code argument} and {@code newParameter} aren't a match
     * or are a worse match.
     */
    private boolean isBetterParameter(@NonNull ModelClass argument,
            @NonNull ModelClass newViewType, @NonNull ModelClass newParameter,
            @Nullable ModelClass oldViewType, @Nullable ModelClass oldParameter,
            @Nullable ImportBag imports) {
        if (oldParameter == null) {
            // just validate that it can be converted
            return calculateConversionPriority(argument, newParameter, imports) >= 0;
        }

        int newConversion = calculateConversionPriority(argument, newParameter, imports);
        if (newConversion < 0) {
            return false; // Doesn't convert
        }

        boolean isSameViewType = oldViewType.equals(newViewType);
        boolean isBetterViewType = oldViewType.isAssignableFrom(newViewType);

        int oldConversion = calculateConversionPriority(argument, oldParameter, imports);
        if (oldConversion == ASSIGNABLE_CONVERSION && newConversion == ASSIGNABLE_CONVERSION) {
            if (isSameViewType) {
                // more specific getter is better
                return oldParameter.isAssignableFrom(newParameter);
            } else {
                return isBetterViewType;
            }
        }

        if (isSameViewType) {
            return newConversion <= oldConversion;
        }

        if (newConversion == oldConversion) {
            return isBetterViewType;
        }

        return newConversion <= oldConversion;
    }

    /**
     * Checks to see if one return type is a better fit for a getter (e.g. InverseBindingAdapter)
     * than another for a method call. It is assumed that both view types match the targeted view.
     * <p>
     * Note that this has different priorities than
     * {@link #isBetterParameter(ModelClass, ModelClass, ModelClass, ModelClass, ModelClass, ImportBag)}
     *
     * @param expected The type that is expected from the getter.
     * @param newViewType The type of the view in the InverseBindingAdapter or getter method.
     * @param newReturnType The return type of the value in the InverseBindingAdapter or getter
     *                      method.
     * @param oldViewType The type of the view in the previous matching InverseBindingAdapter or
     *                    getter method.
     * @param oldReturnType The return type of the value in the previous matching
     *                     InverseBindingAdapter or getter method.
     * @param imports Import in the binding layout file.
     * @return {@code true} when the new InverseBindingAdapter or getter method is a better fit
     * than the previous one or {@code false} if {@code expected} and {@code newReturnType} aren't
     * a match or are a worse match.
     */
    private boolean isBetterReturn(@NonNull ModelClass expected,
            @NonNull ModelClass newViewType, @NonNull ModelClass newReturnType,
            @Nullable ModelClass oldViewType, @Nullable ModelClass oldReturnType,
            @Nullable ImportBag imports) {
        if (oldReturnType == null) {
            // just validate that it can be converted
            return calculateConversionPriority(newReturnType, expected, imports) >= 0;
        }

        int newConversion = calculateConversionPriority(newReturnType, expected, imports);
        if (newConversion < 0) {
            return false; // Doesn't convert
        }

        boolean isSameViewType = oldViewType.equals(newViewType);
        boolean isBetterViewType = oldViewType.isAssignableFrom(newViewType);

        int oldConversion = calculateConversionPriority(oldReturnType, expected, imports);
        if (oldConversion == ASSIGNABLE_CONVERSION && newConversion == ASSIGNABLE_CONVERSION) {
            if (isSameViewType) {
                // more generic getter is better (fairly arbitrary, but consistent)
                return newReturnType.isAssignableFrom(oldReturnType);
            } else {
                return isBetterViewType;
            }
        }

        if (isSameViewType) {
            return newConversion <= oldConversion;
        }

        if (newConversion == oldConversion) {
            return isBetterViewType;
        }

        return newConversion <= oldConversion;
    }

    /**
     * When calling a method, the closer an argument is to the declared parameter, the
     * more likely it is to be matched. This method returns -1 when a {@code from} does not
     * match {@code to}, 0 for an exact match, ASSIGNABLE_CONVERSION when {@code from} is a
     * superclass or interface of {@code to}, and higher values for progressively more inexact
     * matches.
     *
     * @param from The class or interface to attempt to convert from
     * @param to The class or interface to attempt to convert to
     * @param imports The imports used in this binding file
     * @return {@code -1} for no match or greater than or equal to {@code 0} for a possible match,
     * where {@code 0} is an exact match and greater numbers are progressively worse matches.
     */
    private int calculateConversionPriority(@NonNull ModelClass from, @NonNull ModelClass to,
            @Nullable ImportBag imports) {
        if (to.equals(from)) {
            return 0; // exact match
        }
        if (to.isAssignableFrom(from)) {
            return ASSIGNABLE_CONVERSION;
        }
        if (ModelMethod.isBoxingConversion(from, to)) {
            return 2;
        }
        if (ModelMethod.isImplicitConversion(from, to)) {
            // this should be 3 - 9
            return 3 + ModelMethod.getImplicitConversionLevel(to);
        }
        if (getConversionMethod(from, to, imports) != null) {
            return 10;
        }
        if (from.isObject() && !to.isPrimitive()) {
            return 11;
        }
        return -1;
    }

    private MethodDescription getConversionMethod(ModelClass from, ModelClass to,
            ImportBag imports) {
        if (from != null && to != null) {
            if (to.isObject()) {
                return null;
            }
            return mStore.findFirstConversionMethod((fromClassName, conversion) -> {
                try {
                    ModelClass convertFrom = mClassAnalyzer.findClass(fromClassName, imports);
                    if (convertFrom != null && canUseForConversion(from, convertFrom)) {
                        for (String toClassName : conversion.keySet()) {
                            try {
                                ModelClass convertTo = mClassAnalyzer
                                        .findClass(toClassName, imports);
                                if (convertTo != null
                                        && canUseForConversion(convertTo, to)) {
                                    return conversion.get(toClassName);
                                }
                            } catch (Exception e) {
                                L.d(e, "Unknown class: %s", toClassName);
                            }
                        }
                    }
                } catch (Exception e) {
                    L.d(e, "Unknown class: %s", fromClassName);
                }
                return null;
            });
        }
        return null;
    }

    private boolean canUseForConversion(ModelClass from, ModelClass to) {
        if (from.isIncomplete() || to.isIncomplete()) {
            from = from.erasure();
            to = to.erasure();
        }
        return from.equals(to) || ModelMethod.isBoxingConversion(from, to) ||
                to.isAssignableFrom(from);
    }

    private static String createAdapterCall(MethodDescription adapter,
            String componentExpression, String viewExpression, String... args) {
        StringBuilder sb = new StringBuilder();

        if (adapter.isStatic) {
            sb.append(adapter.type);
        } else {
            final SetterStore setterStore = SetterStore.get();
            final String binderCall =  setterStore.getBindingAdapterCall(adapter.type);
            sb.append(componentExpression).append('.').append(binderCall);
        }
        sb.append('.').append(adapter.method).append('(');
        if (adapter.componentClass != null) {
            if (!"DataBindingComponent".equals(adapter.componentClass)) {
                sb.append('(').append(adapter.componentClass).append(") ");
            }
            sb.append(componentExpression).append(", ");
        }
        sb.append(viewExpression);
        for (String arg: args) {
            sb.append(", ").append(arg);
        }
        sb.append(')');
        return sb.toString();
    }

    static class MultiValueAdapterKey implements Serializable,
            Comparable<MultiValueAdapterKey> {
        private static final long serialVersionUID = 1;

        public final String viewType;

        public final String[] attributes;

        public final String[] parameterTypes;

        public final boolean requireAll;

        public final TreeMap<String, Integer> attributeIndices = new TreeMap<>();

        public MultiValueAdapterKey(ProcessingEnvironment processingEnv,
                ExecutableElement method, String[] attributes, boolean takesComponent,
                boolean requireAll) {
            this.attributes = stripAttributes(attributes);
            this.requireAll = requireAll;
            List<? extends VariableElement> parameters = method.getParameters();
            final int argStart = 1 + (takesComponent ? 1 : 0);
            this.viewType = getQualifiedName(eraseType(processingEnv,
                    parameters.get(argStart - 1).asType()));
            this.parameterTypes = new String[attributes.length];
            for (int i = 0; i < attributes.length; i++) {
                TypeMirror typeMirror = eraseType(processingEnv,
                        parameters.get(i + argStart).asType());
                this.parameterTypes[i] = getQualifiedName(typeMirror);
                attributeIndices.put(this.attributes[i], i);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MultiValueAdapterKey)) {
                return false;
            }
            final MultiValueAdapterKey that = (MultiValueAdapterKey) obj;
            if (!this.viewType.equals(that.viewType) ||
                    this.attributes.length != that.attributes.length ||
                    !this.attributeIndices.keySet().equals(that.attributeIndices.keySet())) {
                return false;
            }

            for (int i = 0; i < this.attributes.length; i++) {
                final int thatIndex = that.attributeIndices.get(this.attributes[i]);
                final String thisParameter = parameterTypes[i];
                final String thatParameter = that.parameterTypes[thatIndex];
                if (!thisParameter.equals(thatParameter)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return mergedHashCode(viewType, attributeIndices.keySet());
        }

        @Override
        public int compareTo(@NonNull MultiValueAdapterKey other) {
            int viewTypeCmp = nullableCompare(viewType, other.viewType);
            if (viewTypeCmp != 0) {
                return viewTypeCmp;
            }
            int attrCmp = stringArrayCompare(attributes, other.attributes);
            if (attrCmp != 0) {
                return attrCmp;
            }
            int paramCmp = stringArrayCompare(parameterTypes, other.parameterTypes);
            if (paramCmp != 0) {
                return paramCmp;
            }
            int requireAllCmp = Boolean.compare(requireAll, other.requireAll);
            if (requireAllCmp != 0) {
                return requireAllCmp;
            }

            int attrIndexCmp = Integer.compare(attributeIndices.size(),
                    other.attributeIndices.size());
            if (attrIndexCmp != 0) {
                return attrIndexCmp;
            }
            // compare keys
            Iterator<Map.Entry<String, Integer>> myAttrIndices =
                    attributeIndices.entrySet().iterator();
            Iterator<Map.Entry<String, Integer>> otherAttrIndices =
                    other.attributeIndices.entrySet().iterator();
            while (myAttrIndices.hasNext() && otherAttrIndices.hasNext()) {
                Map.Entry<String, Integer> myNext = myAttrIndices.next();
                Map.Entry<String, Integer> otherNext = otherAttrIndices.next();
                int keyCmp = nullableCompare(myNext.getKey(), otherNext.getKey());
                if (keyCmp != 0) {
                    return keyCmp;
                }
                int valueCmp = nullableCompare(myNext.getValue(), otherNext.getValue());
                if (valueCmp != 0) {
                    return valueCmp;
                }
            }
            return 0;
        }
    }

    private static int nullableCompare(Comparable o1, Comparable o2) {
        if (o1 == null) {
            if (o2 == null) {
                return 0;
            }
            return 1;
        } else if (o2 == null) {
            return -1;
        }
        //noinspection unchecked
        return o1.compareTo(o2);
    }

    private static int stringArrayCompare(String[] array1, String[] array2) {
        if (array1 == null) {
            if (array2 == null) {
                return 0;
            }
            return 1;
        } else if (array2 == null) {
            return -1;
        }
        int lengthCmp = Integer.compare(array1.length, array2.length);
        if (lengthCmp != 0) {
            return lengthCmp;
        }
        for (int i = 0; i < array1.length; i++) {
            int itemCmp = nullableCompare(array1[i], array2[i]);
            if (itemCmp != 0) {
                return itemCmp;
            }
        }
        return 0;
    }
    private static int mergedHashCode(Object... objects) {
        return Arrays.hashCode(objects);
    }

    static class MethodDescription implements Serializable, Comparable<MethodDescription> {

        private static final long serialVersionUID = 1;

        public final String type;

        public final String method;

        public final boolean requiresOldValue;

        public final boolean isStatic;

        public final String componentClass;

        public MethodDescription(String type, String method) {
            this.type = type;
            this.method = method;
            this.requiresOldValue = false;
            this.isStatic = true;
            this.componentClass = null;
            L.d("BINARY created method desc 1 %s %s", type, method );
        }

        public MethodDescription(ExecutableElement method, int numAttributes,
                boolean takesComponent) {
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            this.type = enclosingClass.getQualifiedName().toString();
            this.method = method.getSimpleName().toString();
            final int argStart = 1 + (takesComponent ? 1 : 0);
            this.requiresOldValue = method.getParameters().size() - argStart == numAttributes * 2;
            this.isStatic = method.getModifiers().contains(Modifier.STATIC);
            this.componentClass = takesComponent
                    ? getQualifiedName(method.getParameters().get(0).asType())
                    : null;

            L.d("BINARY created method desc 2 %s %s, %s", type, this.method, method);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MethodDescription)) {
                return false;
            }
            MethodDescription that = (MethodDescription) o;
            return requiresOldValue == that.requiresOldValue &&
                isStatic == that.isStatic &&
                Objects.equals(type, that.type) &&
                Objects.equals(method, that.method) &&
                Objects.equals(componentClass, that.componentClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, method, requiresOldValue, isStatic, componentClass);
        }

        @Override
        public String toString() {
            return type + "#" + method;
        }

        @Override
        public int compareTo(@NonNull MethodDescription other) {
            int typeCmp = nullableCompare(type, other.type);
            if (typeCmp != 0) {
                return typeCmp;
            }
            int methodCmp = nullableCompare(method, other.method);
            if (methodCmp != 0) {
                return methodCmp;
            }

            int oldValueCmp = Boolean.compare(requiresOldValue, other.requiresOldValue);
            if (oldValueCmp != 0) {
                return oldValueCmp;
            }

            int staticCmp = Boolean.compare(isStatic, other.isStatic);
            if (staticCmp != 0) {
                return staticCmp;
            }
            return nullableCompare(componentClass, other.componentClass);
        }
    }

    static class InverseDescription extends MethodDescription {
        private static final long serialVersionUID = 1;

        public final String event;

        public InverseDescription(String type, String method, String event) {
            super(type, method);
            this.event = event;
        }

        public InverseDescription(ExecutableElement method, String event, boolean takesComponent) {
            super(method, 1, takesComponent);
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof InverseDescription)) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            InverseDescription that = (InverseDescription) o;
            return Objects.equals(event, that.event);
        }

        @Override
        public String toString() {
            return type + "#" + method;
        }

        @Override
        public int hashCode() {

            return Objects.hash(super.hashCode(), event);
        }

        @Override
        public int compareTo(@NonNull MethodDescription other) {
            if (other instanceof InverseDescription) {
                int eventCmp = nullableCompare(event, ((InverseDescription) other).event);
                if (eventCmp != 0) {
                    return eventCmp;
                }
            }
            return super.compareTo(other);
        }
    }

    static class AccessorKey implements Serializable, Comparable<AccessorKey> {

        private static final long serialVersionUID = 1;

        public final String viewType;

        public final String valueType;

        public AccessorKey(String viewType, String valueType) {
            this.viewType = viewType;
            this.valueType = valueType;
        }

        @Override
        public int hashCode() {
            return mergedHashCode(viewType, valueType);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AccessorKey) {
                AccessorKey that = (AccessorKey) obj;
                return viewType.equals(that.viewType) && valueType.equals(that.valueType);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return "AK(" + viewType + ", " + valueType + ")";
        }

        @Override
        public int compareTo(@NonNull AccessorKey other) {
            int viewTypeCmp = nullableCompare(viewType, other.viewType);
            if (viewTypeCmp == 0) {
                return nullableCompare(valueType, other.valueType);
            } else {
                return viewTypeCmp;
            }
        }
    }

    static class InverseMethodDescription implements Serializable,
            Comparable<InverseMethodDescription> {
        private static final long serialVersionUID = 0xC00L;

        public final boolean isStatic;
        public final String returnType;
        public final String method;
        public final String[] parameterTypes;
        public final String type;

        public InverseMethodDescription(ProcessingEnvironment env, ExecutableElement method) {
            this.isStatic = method.getModifiers().contains(Modifier.STATIC);
            Types typeUtils = env.getTypeUtils();
            this.returnType = getQualifiedName(typeUtils.erasure(method.getReturnType()));
            this.method = method.getSimpleName().toString();
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();
            this.type = enclosingClass.getQualifiedName().toString();

            List<? extends VariableElement> parameters = method.getParameters();
            this.parameterTypes = new String[parameters.size()];

            for (int i = 0; i < parameters.size(); i++) {
                VariableElement param  = parameters.get(i);
                TypeMirror type = typeUtils.erasure(param.asType());
                this.parameterTypes[i] = getQualifiedName(type);
            }
        }

        public InverseMethodDescription(ModelMethod method) {
            this.isStatic = method.isStatic();
            this.returnType = method.getReturnType().erasure().getCanonicalName();
            this.method = method.getName();
            this.type = method.getReceiverType().getCanonicalName();

            ModelClass[] parameters = method.getParameterTypes();
            this.parameterTypes = new String[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                this.parameterTypes[i] = parameters[i].erasure().getCanonicalName();
            }
        }

        @Override
        public int hashCode() {
            return mergedHashCode(type, isStatic, returnType, method,
                    Arrays.hashCode(parameterTypes));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof InverseMethodDescription) {
                InverseMethodDescription that = (InverseMethodDescription) obj;
                return this.isStatic == that.isStatic &&
                        this.type.equals(that.type) &&
                        this.returnType.equals(that.returnType) &&
                        this.method.equals(that.method) &&
                        Arrays.equals(this.parameterTypes, that.parameterTypes);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (isStatic) {
                sb.append("static ");
            }
            sb.append(returnType)
                    .append(' ')
                    .append(type)
                    .append('.')
                    .append(method)
                    .append('(');
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(parameterTypes[i]);
            }
            sb.append(')');
            return sb.toString();
        }

        @Override
        public int compareTo(@NonNull InverseMethodDescription other) {
            int staticCmp = Boolean.compare(isStatic, other.isStatic);
            if (staticCmp != 0) {
                return staticCmp;
            }
            int returnCmp = nullableCompare(returnType, other.returnType);
            if (returnCmp != 0) {
                return returnCmp;
            }
            int methodCmp = nullableCompare(method, other.method);
            if (methodCmp != 0) {
                return methodCmp;
            }
            int paramCmp = stringArrayCompare(parameterTypes, other.parameterTypes);
            if (paramCmp != 0) {
                return paramCmp;
            }
            return nullableCompare(type, other.type);
        }
    }

    interface Intermediate extends Serializable {
        Intermediate upgrade();
    }

    private static class IntermediateV1 implements Serializable, Intermediate {
        private static final long serialVersionUID = 1;
        public final HashMap<String, HashMap<AccessorKey, MethodDescription>> adapterMethods =
                new HashMap<String, HashMap<AccessorKey, MethodDescription>>();
        public final HashMap<String, HashMap<String, MethodDescription>> renamedMethods =
                new HashMap<String, HashMap<String, MethodDescription>>();
        public final HashMap<String, HashMap<String, MethodDescription>> conversionMethods =
                new HashMap<String, HashMap<String, MethodDescription>>();
        public final HashMap<String, String> untaggableTypes = new HashMap<String, String>();
        public final HashMap<MultiValueAdapterKey, MethodDescription> multiValueAdapters =
                new HashMap<MultiValueAdapterKey, MethodDescription>();

        public IntermediateV1() {
        }

        @Override
        public Intermediate upgrade() {
            IntermediateV2 v2 = new IntermediateV2();
            v2.adapterMethods.putAll(adapterMethods);
            v2.renamedMethods.putAll(renamedMethods);
            v2.conversionMethods.putAll(conversionMethods);
            v2.untaggableTypes.putAll(untaggableTypes);
            v2.multiValueAdapters.putAll(multiValueAdapters);
            return v2.upgrade();
        }
    }

    private static class IntermediateV2 extends IntermediateV1 {
        private static final long serialVersionUID = 0xA45C2EB637E35C07L;
        public final HashMap<String, HashMap<AccessorKey, InverseDescription>> inverseAdapters =
                new HashMap<String, HashMap<AccessorKey, InverseDescription>>();
        public final HashMap<String, HashMap<String, InverseDescription>> inverseMethods =
                new HashMap<String, HashMap<String, InverseDescription>>();

        @Override
        public Intermediate upgrade() {
            IntermediateV3 v3 = new IntermediateV3();
            v3.adapterMethods.putAll(adapterMethods);
            v3.renamedMethods.putAll(renamedMethods);
            v3.conversionMethods.putAll(conversionMethods);
            v3.untaggableTypes.putAll(untaggableTypes);
            v3.multiValueAdapters.putAll(multiValueAdapters);
            v3.inverseAdapters.putAll(inverseAdapters);
            v3.inverseMethods.putAll(inverseMethods);
            return v3.upgrade();
        }
    }

    public static class IntermediateV3 extends IntermediateV2 {
        private static final long serialVersionUID = 0xC00L;
        public final HashMap<InverseMethodDescription, String> twoWayMethods = new HashMap<>();

        @Override
        public Intermediate upgrade() {
            return new BindingAdapterStore(this);
        }
    }

    public static class AdapterSetter extends SetterCall {
        final MethodDescription mAdapter;
        final ModelClass mParameterType;

        public AdapterSetter(MethodDescription adapter, ModelClass parameterType) {
            mAdapter = adapter;
            mParameterType = parameterType;
        }

        @Override
        public String toJavaInternal(String componentExpression, String viewExpression,
                String valueExpression) {
            return createAdapterCall(mAdapter, componentExpression,
                    viewExpression, mCastString + valueExpression);
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String oldValue, String valueExpression) {
            return createAdapterCall(mAdapter, componentExpression,
                    viewExpression, mCastString + oldValue, mCastString + valueExpression);
        }

        @Override
        public int getMinApi() {
            return 1;
        }

        @Override
        public boolean requiresOldValue() {
            return mAdapter.requiresOldValue;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            return new ModelClass[] { mParameterType };
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return mAdapter.isStatic ? null : mAdapter.type;
        }

        @Override
        public String getDescription() {
            return mAdapter.type + "." + mAdapter.method;
        }
    }

    public static class ModelMethodSetter extends SetterCall {
        final ModelMethod mModelMethod;

        public ModelMethodSetter(ModelMethod modelMethod) {
            mModelMethod = modelMethod;
        }

        @Override
        public String toJavaInternal(String componentExpression, String viewExpression,
                String valueExpression) {
            return viewExpression + "." + mModelMethod.getName() + "(" + mCastString +
                    valueExpression + ")";
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String oldValue, String valueExpression) {
            return viewExpression + "." + mModelMethod.getName() + "(" +
                    mCastString + oldValue + ", " + mCastString + valueExpression + ")";
        }

        @Override
        public int getMinApi() {
            return mModelMethod.getMinApi();
        }

        @Override
        public boolean requiresOldValue() {
            return mModelMethod.getParameterTypes().length == 3;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            return new ModelClass[] { mModelMethod.getParameterTypes()[0] };
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return null;
        }

        @Override
        public String getDescription() {
            String args = Arrays.stream(mModelMethod.getParameterTypes())
                    .map(param -> param.toJavaCode())
                    .collect(Collectors.joining(", "));
            return mModelMethod.getReceiverType().toJavaCode() + '.' +
                    mModelMethod.getName() + '(' + args + ')';

        }
    }

    public interface BindingSetterCall {
        String toJava(String componentExpression, String viewExpression,
                String... valueExpressions);

        int getMinApi();

        boolean requiresOldValue();

        ModelClass[] getParameterTypes();

        String getBindingAdapterInstanceClass();

        // A description of the setter method to be used in an error message
        String getDescription();
    }

    public static abstract class SetterCall implements BindingSetterCall {
        private MethodDescription mConverter;
        protected String mCastString = "";

        public SetterCall() {
        }

        public void setConverter(MethodDescription converter) {
            mConverter = converter;
        }

        protected abstract String toJavaInternal(String componentExpression, String viewExpression,
                String converted);

        protected abstract String toJavaInternal(String componentExpression, String viewExpression,
                String oldValue, String converted);

        @Override
        public final String toJava(String componentExpression, String viewExpression,
                String... valueExpression) {
            Preconditions.check(valueExpression.length == 2, "value expressions size must be 2");
            if (requiresOldValue()) {
                return toJavaInternal(componentExpression, viewExpression,
                        convertValue(valueExpression[0]), convertValue(valueExpression[1]));
            } else {
                return toJavaInternal(componentExpression, viewExpression,
                        convertValue(valueExpression[1]));
            }
        }

        protected String convertValue(String valueExpression) {
            return mConverter == null ? valueExpression :
                    mConverter.type + "." + mConverter.method + "(" + valueExpression + ")";
        }

        @Override
        abstract public int getMinApi();

        public void setCast(ModelClass castTo) {
            mCastString = "(" + castTo.toJavaCode() + ") ";
        }
    }

    public static class MultiAttributeSetter implements BindingSetterCall {
        public final String[] attributes;
        private final MethodDescription mAdapter;
        private final MethodDescription[] mConverters;
        private final String[] mCasts;
        private final MultiValueAdapterKey mKey;
        private final boolean[] mSupplied;

        public MultiAttributeSetter(MultiValueAdapterKey key, boolean[] supplied,
                MethodDescription adapter, MethodDescription[] converters, String[] casts) {
            Preconditions.check(converters != null &&
                    converters.length == key.attributes.length &&
                    casts != null && casts.length == key.attributes.length &&
                    supplied.length == key.attributes.length,
                    "invalid arguments to create multi attr setter");
            this.mAdapter = adapter;
            this.mConverters = converters;
            this.mCasts = casts;
            this.mKey = key;
            this.mSupplied = supplied;
            if (key.requireAll) {
                this.attributes = key.attributes;
            } else {
                int numSupplied = 0;
                for (int i = 0; i < mKey.attributes.length; i++) {
                    if (supplied[i]) {
                        numSupplied++;
                    }
                }
                if (numSupplied == key.attributes.length) {
                    this.attributes = key.attributes;
                } else {
                    this.attributes = new String[numSupplied];
                    int attrIndex = 0;
                    for (int i = 0; i < key.attributes.length; i++) {
                        if (supplied[i]) {
                            attributes[attrIndex++] = key.attributes[i];
                        }
                    }
                }
            }
        }

        @Override
        public final String toJava(String componentExpression, String viewExpression,
                String... valueExpressions) {
            Preconditions.check(valueExpressions.length == attributes.length * 2,
                    "MultiAttributeSetter needs %s items, received %s",
                    Arrays.toString(attributes), Arrays.toString(valueExpressions));
            final int numAttrs = mKey.attributes.length;
            String[] args = new String[numAttrs + (requiresOldValue() ? numAttrs : 0)];

            final int startIndex = mAdapter.requiresOldValue ? 0 : numAttrs;
            int attrIndex = mAdapter.requiresOldValue ? 0 : attributes.length;
            final ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
            StringBuilder argBuilder = new StringBuilder();
            final int endIndex = numAttrs * 2;
            for (int i = startIndex; i < endIndex; i++) {
                argBuilder.setLength(0);
                if (!mSupplied[i % numAttrs]) {
                    final String paramType = mKey.parameterTypes[i % numAttrs];
                    final String defaultValue = modelAnalyzer.getDefaultValue(paramType);
                    argBuilder.append('(')
                            .append(paramType)
                            .append(')')
                            .append(defaultValue);
                } else {
                    if (mConverters[i % numAttrs] != null) {
                        final MethodDescription converter = mConverters[i % numAttrs];
                        argBuilder.append(converter.type)
                                .append('.')
                                .append(converter.method)
                                .append('(')
                                .append(valueExpressions[attrIndex])
                                .append(')');
                    } else {
                        if (mCasts[i % numAttrs] != null) {
                            argBuilder.append('(')
                                    .append(mCasts[i % numAttrs])
                                    .append(')');
                        }
                        argBuilder.append(valueExpressions[attrIndex]);
                    }
                    attrIndex++;
                }
                args[i - startIndex] = argBuilder.toString();
            }
            return createAdapterCall(mAdapter, componentExpression, viewExpression, args);
        }

        @Override
        public int getMinApi() {
            return 1;
        }

        @Override
        public boolean requiresOldValue() {
            return mAdapter.requiresOldValue;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            ModelClass[] parameters = new ModelClass[attributes.length];
            String[] paramTypeStrings = mKey.parameterTypes;
            ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
            int attrIndex = 0;
            for (int i = 0; i < mKey.attributes.length; i++) {
                if (mSupplied[i]) {
                    parameters[attrIndex++] = modelAnalyzer.findClass(paramTypeStrings[i], null);
                }
            }
            return parameters;
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return mAdapter.isStatic ? null : mAdapter.type;
        }

        @Override
        public String toString() {
            return "MultiAttributeSetter{" +
                    "attributes=" + Arrays.toString(attributes) +
                    ", mAdapter=" + mAdapter +
                    ", mConverters=" + Arrays.toString(mConverters) +
                    ", mCasts=" + Arrays.toString(mCasts) +
                    ", mKey=" + mKey +
                    '}';
        }

        @Override
        public String getDescription() {
            return mAdapter.type + "." + mAdapter.method;
        }
    }

    public static class ViewDataBindingEventSetter implements BindingSetterCall {

        public ViewDataBindingEventSetter() {
        }

        @Override
        public String toJava(String componentExpression, String viewExpression,
                String... valueExpressions) {
            return "setBindingInverseListener(" + viewExpression + ", " +
                    valueExpressions[0] + ", " + valueExpressions[1] + ")";
        }

        @Override
        public int getMinApi() {
            return 0;
        }

        @Override
        public boolean requiresOldValue() {
            return true;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            ModelClass[] parameterTypes = new ModelClass[1];
            parameterTypes[0] = ModelAnalyzer.getInstance().findClass(
                    "android.databinding.ViewDataBinder.PropertyChangedInverseListener", null);
            return parameterTypes;
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return null;
        }

        @Override
        public String getDescription() {
            return "ViewDataBinding.setBindingInverseListener";
        }
    }

    public interface BindingGetterCall {
        String toJava(String componentExpression, String viewExpression);

        String getGetterType();

        int getMinApi();

        String getBindingAdapterInstanceClass();

        void setBindingAdapterCall(String method);

        BindingSetterCall getEvent();

        String getEventAttribute();
    }

    public static class ViewDataBindingGetterCall implements BindingGetterCall {
        private final String mGetter;
        private final BindingSetterCall mEventSetter;
        private final String mAttribute;
        private final ModelClass mBindingClass;

        public ViewDataBindingGetterCall(ModelClass bindingClass, String attribute) {
            final int colonIndex = attribute.indexOf(':');
            mAttribute = attribute.substring(colonIndex + 1);
            mGetter = "get" + StringUtils.capitalize(mAttribute);
            mEventSetter = new ViewDataBindingEventSetter();
            mBindingClass = bindingClass;
        }

        @Override
        public String toJava(String componentExpression, String viewExpression) {
            return viewExpression + "." + mGetter + "()";
        }

        @Override
        public String getGetterType() {
            return mBindingClass.findInstanceGetter(mGetter).getReturnType().toJavaCode();
        }

        @Override
        public int getMinApi() {
            return 0;
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return null;
        }

        @Override
        public void setBindingAdapterCall(String method) {
        }

        @Override
        public BindingSetterCall getEvent() {
            return mEventSetter;
        }

        @Override
        public String getEventAttribute() {
            return mAttribute;
        }
    }

    public static class ViewGetterCall implements BindingGetterCall {
        private final InverseDescription mInverseDescription;
        private final BindingSetterCall mEventCall;
        private final ModelMethod mMethod;

        public ViewGetterCall(InverseDescription inverseDescription, ModelMethod method,
                BindingSetterCall eventCall) {
            mInverseDescription = inverseDescription;
            mEventCall = eventCall;
            mMethod = method;
        }

        @Override
        public BindingSetterCall getEvent() {
            return mEventCall;
        }

        @Override
        public String getEventAttribute() {
            return mInverseDescription.event;
        }

        @Override
        public String toJava(String componentExpression, String viewExpression) {
            return viewExpression + "." + mMethod.getName() + "()";
        }

        @Override
        public String getGetterType() {
            return mMethod.getReturnType().toJavaCode();
        }

        @Override
        public int getMinApi() {
            return mMethod.getMinApi();
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return null;
        }

        @Override
        public void setBindingAdapterCall(String method) {
        }
    }

    public static class AdapterGetter implements BindingGetterCall {
        private final InverseDescription mInverseDescription;
        private String mBindingAdapterCall;
        private final BindingSetterCall mEventCall;
        private final String mGetterType;

        public AdapterGetter(InverseDescription description, BindingSetterCall eventCall,
                String getterType) {
            mInverseDescription = description;
            mEventCall = eventCall;
            mGetterType = getterType;
        }

        @Override
        public String getGetterType() {
            return mGetterType;
        }

        @Override
        public String toJava(String componentExpression, String viewExpression) {
            StringBuilder sb = new StringBuilder();

            if (mInverseDescription.isStatic) {
                sb.append(mInverseDescription.type);
            } else {
                sb.append(componentExpression).append('.').append(mBindingAdapterCall);
            }
            sb.append('.').append(mInverseDescription.method).append('(');
            if (mInverseDescription.componentClass != null) {
                if (!"DataBindingComponent".equals(mInverseDescription.componentClass)) {
                    sb.append('(').append(mInverseDescription.componentClass).append(") ");
                }
                sb.append(componentExpression).append(", ");
            }
            sb.append(viewExpression).append(')');
            return sb.toString();
        }

        @Override
        public int getMinApi() {
            return 1;
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return mInverseDescription.isStatic ? null : mInverseDescription.type;
        }

        @Override
        public void setBindingAdapterCall(String method) {
            mBindingAdapterCall = method;
        }

        @Override
        public BindingSetterCall getEvent() {
            return mEventCall;
        }

        @Override
        public String getEventAttribute() {
            return mInverseDescription.event;
        }
    }

    private static class InverseMethod {
        public BindingGetterCall call;
        public ModelClass returnType;
        public ModelClass viewType;

        public InverseMethod(BindingGetterCall call, ModelClass returnType, ModelClass viewType) {
            this.call = call;
            this.returnType = returnType;
            this.viewType = viewType;
        }
    }

    /**
     * A ViewBinding can support setters on its root view. This wrapper is injected when include tag
     * has a binding on directly its view.
     */
    private static class ViewBindingRootViewSetterCall extends SetterCall {

        private final SetterCall mWrapped;

        public ViewBindingRootViewSetterCall(SetterCall wrapped) {
            mWrapped = wrapped;
        }

        @Override
        public boolean requiresOldValue() {
            return  mWrapped.requiresOldValue();
        }

        @Override
        public ModelClass[] getParameterTypes() {
            return mWrapped.getParameterTypes();
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return mWrapped.getBindingAdapterInstanceClass();
        }

        @Override
        public String getDescription() {
            return mWrapped.getDescription();
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                                        String converted) {
            return mWrapped.toJavaInternal(componentExpression, viewExpression + ".getRoot()",
                    converted);
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                                        String oldValue, String converted) {
            return mWrapped.toJavaInternal(componentExpression, viewExpression + ".getRoot()",
                    oldValue, converted);
        }

        @Override
        public int getMinApi() {
            return mWrapped.getMinApi();
        }
    }
}
