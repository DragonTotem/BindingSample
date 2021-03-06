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
package android.databinding.tool.expr;

import android.databinding.tool.BindingTarget;
import android.databinding.tool.Context;
import android.databinding.tool.reflection.ImportBag;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.writer.KCode;
import android.databinding.tool.writer.LayoutBinderWriterKt;
import com.google.common.collect.Lists;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.databinding.tool.ext.ExtKt.capitalizeUS;

public class ResourceExpr extends Expr {

    private final static Map<String, String> RESOURCE_TYPE_TO_R_OBJECT;
    public final static Map<String, List<String>> R_OBJECT_TO_RESOURCE_TYPE;
    static {
        RESOURCE_TYPE_TO_R_OBJECT = new HashMap<String, String>();
        RESOURCE_TYPE_TO_R_OBJECT.put("colorStateList", "color");
        RESOURCE_TYPE_TO_R_OBJECT.put("dimenOffset", "dimen");
        RESOURCE_TYPE_TO_R_OBJECT.put("dimenSize", "dimen");
        RESOURCE_TYPE_TO_R_OBJECT.put("intArray", "array");
        RESOURCE_TYPE_TO_R_OBJECT.put("stateListAnimator", "animator");
        RESOURCE_TYPE_TO_R_OBJECT.put("stringArray", "array");
        RESOURCE_TYPE_TO_R_OBJECT.put("text", "string");
        RESOURCE_TYPE_TO_R_OBJECT.put("typedArray", "array");

        // TODO(184833062): clean up so it reads from the above map and reverses it more elegantly
        R_OBJECT_TO_RESOURCE_TYPE = new HashMap<String, List<String>>();
        R_OBJECT_TO_RESOURCE_TYPE.put("color", Lists.newArrayList("colorStateList"));
        R_OBJECT_TO_RESOURCE_TYPE.put("dimen", Lists.newArrayList("dimenOffset", "dimenSize"));
        R_OBJECT_TO_RESOURCE_TYPE.put(
                "array", Lists.newArrayList("intArray", "stringArray", "typedArray"));
        R_OBJECT_TO_RESOURCE_TYPE.put("animator", Lists.newArrayList("stateListAnimator"));
        R_OBJECT_TO_RESOURCE_TYPE.put("string", Lists.newArrayList("text"));
    }
    // lazily initialized
    private Map<String, ModelClass> mResourceToTypeMapping;

    protected final String mPackage;

    protected final String mResourceType;

    protected final String mResourceId;

    protected final BindingTarget mTarget;

    public ResourceExpr(BindingTarget target, String packageName, String resourceType,
            String resourceName, List<Expr> args) {
        super(args);
        mTarget = target;
        mPackage = Context.getResources().getRPackagePrefix(packageName, resourceType, resourceName);
        mResourceType = resourceType;
        mResourceId = resourceName;
    }

    private Map<String, ModelClass> getResourceToTypeMapping(ModelAnalyzer modelAnalyzer) {
        if (mResourceToTypeMapping == null) {
            final ImportBag imports = getModel().getImports();
            mResourceToTypeMapping = new HashMap<String, ModelClass>();
            mResourceToTypeMapping.put("anim", modelAnalyzer.findClass("android.view.animation.Animation",
                            imports));
            mResourceToTypeMapping.put("animator", modelAnalyzer.findClass("android.animation.Animator",
                            imports));
            mResourceToTypeMapping.put("colorStateList",
                            modelAnalyzer.findClass("android.content.res.ColorStateList",
                                    imports));
            mResourceToTypeMapping.put("drawable", modelAnalyzer.findClass("android.graphics.drawable.Drawable",
                            imports));
            mResourceToTypeMapping.put("stateListAnimator",
                            modelAnalyzer.findClass("android.animation.StateListAnimator",
                                    imports));
            mResourceToTypeMapping.put("transition", modelAnalyzer.findClass("android.transition.Transition",
                            imports));
            mResourceToTypeMapping.put("typedArray", modelAnalyzer.findClass("android.content.res.TypedArray",
                            imports));
            mResourceToTypeMapping.put("interpolator",
                            modelAnalyzer.findClass("android.view.animation.Interpolator", imports));
            mResourceToTypeMapping.put("bool", modelAnalyzer.findClass(boolean.class));
            mResourceToTypeMapping.put("color", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("dimenOffset", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("dimenSize", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("id", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("integer", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("layout", modelAnalyzer.findClass(int.class));
            mResourceToTypeMapping.put("dimen", modelAnalyzer.findClass(float.class));
            mResourceToTypeMapping.put("fraction", modelAnalyzer.findClass(float.class));
            mResourceToTypeMapping.put("intArray", modelAnalyzer.findClass(int[].class));
            mResourceToTypeMapping.put("string", modelAnalyzer.findClass(String.class));
            mResourceToTypeMapping.put("stringArray", modelAnalyzer.findClass(String[].class));
            mResourceToTypeMapping.put("text", modelAnalyzer.findClass(CharSequence.class));
        }
        return mResourceToTypeMapping;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        final Map<String, ModelClass> mapping = getResourceToTypeMapping(
                modelAnalyzer);
        final ModelClass modelClass = mapping.get(mResourceType);
        if (modelClass != null) {
            return modelClass;
        }
        if ("plurals".equals(mResourceType)) {
            if (getChildren().isEmpty()) {
                return modelAnalyzer.findClass(int.class);
            } else {
                return modelAnalyzer.findClass(String.class);
            }
        }
        return modelAnalyzer.findClass(mResourceType, getModel().getImports());
    }

    @Override
    public void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        // nothing to unbox
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    @Override
    protected String computeUniqueKey() {
        String view = requiresView() ? LayoutBinderWriterKt.getFieldName(mTarget) : "";
        return join(view, toString(), join(getChildren()));
    }

    @Override
    protected KCode generateCode() {
        return new KCode(toJava());
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        String pkg = mPackage.isEmpty() ? "" : "android";
        return model.resourceExpr(mTarget, pkg, mResourceType, mResourceId,
                cloneToModel(model, getChildren()));
    }

    @Override
    public String getInvertibleError() {
        return "Resources may not be the target of a two-way binding expression: " + this;
    }

    private boolean requiresView() {
        return !mTarget.isBinder() && !("anim".equals(mResourceType) ||
                "animator".equals(mResourceType) ||
                "id".equals(mResourceType) ||
                "interpolator".equals(mResourceType) ||
                "layout".equals(mResourceType) ||
                "stateListAnimator".equals(mResourceType) ||
                "transition".equals(mResourceType));
    }

    public String toJava() {
        final String context = "getRoot().getContext()";
        final String viewName = requiresView() ? LayoutBinderWriterKt.getFieldName(mTarget) :
                "getRoot()";
        final String viewContext = viewName + ".getContext()";
        final String resources = viewName + ".getResources()";
        final String resourceName = mPackage + "R." + getResourceObject() + "." + mResourceId;
        if ("anim".equals(mResourceType)) return "android.view.animation.AnimationUtils.loadAnimation(" + context + ", " + resourceName + ")";
        if ("animator".equals(mResourceType)) return "android.animation.AnimatorInflater.loadAnimator(" + context + ", " + resourceName + ")";
        if ("bool".equals(mResourceType)) return resources + ".getBoolean(" + resourceName + ")";
        if ("color".equals(mResourceType)) return "getColorFromResource(" + viewName + ", " + resourceName + ")";
        if ("colorStateList".equals(mResourceType)) {
            ModelClass appCompatResources = ModelAnalyzer.getInstance().getAppCompatResourcesType();
            if (appCompatResources != null) {
                return appCompatResources.getCanonicalName() + ".getColorStateList(" + viewContext + ", " + resourceName + ")";
            } else {
                return "getColorStateListFromResource(" + viewName + ", " + resourceName + ")";
            }
        }
        if ("dimen".equals(mResourceType)) return resources + ".getDimension(" + resourceName + ")";
        if ("dimenOffset".equals(mResourceType)) return resources + ".getDimensionPixelOffset(" + resourceName + ")";
        if ("dimenSize".equals(mResourceType)) return resources + ".getDimensionPixelSize(" + resourceName + ")";
        if ("drawable".equals(mResourceType)) {
            ModelClass appCompatResources = ModelAnalyzer.getInstance().getAppCompatResourcesType();
            if (appCompatResources != null) {
                return appCompatResources.getCanonicalName() + ".getDrawable(" + viewContext + ", " + resourceName + ")";
            } else {
                return "getDrawableFromResource(" + viewName + ", " + resourceName + ")";
            }
        }
        if ("fraction".equals(mResourceType)) {
            String base = getChildCode(0, "1");
            String pbase = getChildCode(1, "1");
            return resources + ".getFraction(" + resourceName + ", " + base + ", " + pbase + ")";
        }
        if ("id".equals(mResourceType)) return resourceName;
        if ("intArray".equals(mResourceType)) return resources + ".getIntArray(" + resourceName + ")";
        if ("integer".equals(mResourceType)) return resources + ".getInteger(" + resourceName + ")";
        if ("interpolator".equals(mResourceType))  return "android.view.animation.AnimationUtils.loadInterpolator(" + context + ", " + resourceName + ")";
        if ("layout".equals(mResourceType)) return resourceName;
        if ("plurals".equals(mResourceType)) {
            if (getChildren().isEmpty()) {
                return resourceName;
            } else {
                return makeParameterCall(resources, resourceName, "getQuantityString");
            }
        }
        if ("stateListAnimator".equals(mResourceType)) return "android.animation.AnimatorInflater.loadStateListAnimator(" + context + ", " + resourceName + ")";
        if ("string".equals(mResourceType)) return makeParameterCall(resources, resourceName, "getString");
        if ("stringArray".equals(mResourceType)) return resources + ".getStringArray(" + resourceName + ")";
        if ("transition".equals(mResourceType)) return "android.transition.TransitionInflater.from(" + context + ").inflateTransition(" + resourceName + ")";
        if ("text".equals(mResourceType)) return resources + ".getText(" + resourceName + ")";
        if ("typedArray".equals(mResourceType)) return resources + ".obtainTypedArray(" + resourceName + ")";
        final String property = capitalizeUS(mResourceType);
        return resources + ".get" + property + "(" + resourceName + ")";

    }

    private String getChildCode(int childIndex, String defaultValue) {
        if (getChildren().size() <= childIndex) {
            return defaultValue;
        } else {
            return getChildren().get(childIndex).toCode().generate();
        }
    }

    private String makeParameterCall(String resources, String resourceName, String methodCall) {
        StringBuilder sb = new StringBuilder(resources);
        sb.append('.').append(methodCall).append("(").append(resourceName);
        for (Expr expr : getChildren()) {
            sb.append(", ").append(expr.toCode().generate());
        }
        sb.append(")");
        return sb.toString();
    }

    private String getResourceObject() {
        String rFileObject = RESOURCE_TYPE_TO_R_OBJECT.get(mResourceType);
        if (rFileObject == null) {
            rFileObject = mResourceType;
        }
        return rFileObject;
    }

    @Override
    public String toString() {
        if (mPackage == null) {
            return "@" + mResourceType + "/" + mResourceId;
        } else {
            return "@" + "android:" + mResourceType + "/" + mResourceId;
        }
    }
}
