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

import android.databinding.tool.Binding;
import android.databinding.tool.BindingTarget;
import android.databinding.tool.InverseBinding;
import android.databinding.tool.ext.ExtKt;
import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.reflection.Callable;
import android.databinding.tool.reflection.Callable.Type;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.store.SetterStore.BindingGetterCall;
import android.databinding.tool.util.BrNameUtil;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.util.StringUtils;
import android.databinding.tool.writer.KCode;
import com.google.common.collect.Lists;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FieldAccessExpr extends MethodBaseExpr {
    // notification name for the field. Important when we map this to a method w/ different name
    String mBrName;
    Callable mGetter;
    boolean mIsListener;
    boolean mIsViewAttributeAccess;
    boolean mIsMap;
    FieldAccessExpr[] mDependencies;
    HashSet<FieldAccessExpr> mTransitiveDependencies = new HashSet<>();
    HashSet<FieldAccessExpr> mDependents = new HashSet<>();

    FieldAccessExpr(Expr parent, String name) {
        super(parent, name);
        mName = name;
    }

    public Callable getGetter() {
        if (mGetter == null) {
            getResolvedType();
        }
        return mGetter;
    }

    @Override
    protected void resetResolvedType() {
        super.resetResolvedType();
        mGetter = null;
    }

    @Override
    public String getInvertibleError() {
        if (getGetter() == null) {
            return "Listeners do not support two-way binding";
        }
        if (mGetter.setterName == null && mGetter.type == Type.METHOD) {
            return "Two-way binding cannot resolve a setter for " + getResolvedType().toJavaCode() +
                    " property '" + mName + "'";
        }
        if (!mGetter.isDynamic()) {
            return "Cannot change a final field in " + getResolvedType().toJavaCode() +
                    " property " + mName;
        }
        return null;
    }

    @Override
    public void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        // nothing to unbox
    }

    public int getMinApi() {
        return mGetter == null ? 0 : mGetter.getMinApi();
    }

    @Override
    public boolean isDynamic() {
        if (mGetter == null) {
            getResolvedType();
        }
        if (mGetter == null || mGetter.type == Type.METHOD) {
            return true;
        }
        // if it is static final, gone
        if (getTarget().isDynamic()) {
            // if owner is dynamic, then we can be dynamic unless we are static final
            return !mGetter.isStatic() || mGetter.isDynamic();
        }

        if (mIsViewAttributeAccess) {
            return true; // must be able to invalidate this
        }

        // if owner is NOT dynamic, we can be dynamic if an only if getter is dynamic
        return mGetter.isDynamic();
    }

    public boolean hasBindableAnnotations() {
        return mGetter != null && mGetter.canBeInvalidated();
    }

    @Override
    public Expr resolveListeners(ModelClass listener, Expr parent) {
        final ModelClass targetType = getTarget().getResolvedType();
        if (getGetter() == null && (listener == null || !mIsListener) && !mIsMap) {
            L.e("Could not resolve %s.%s as an accessor or listener on the attribute.",
                    targetType.getCanonicalName(), mName);
            return this;
        }
        try {
            Expr listenerExpr = resolveListenersAsMethodReference(listener, parent);
            L.w("Method references using '.' is deprecated. Instead of '%s', use '%s::%s'",
                    toString(), getTarget(), getName());
            return listenerExpr;
        } catch (IllegalStateException e) {
            if (getGetter() == null && !mIsMap) {
                L.e("%s", e.getMessage());
            }
            return this;
        }
    }

    @Override
    protected String computeUniqueKey() {
        if (mName.isEmpty()) {
            return join(getTarget(), ".get-()");
        } else {
            return join(getTarget(), '.', mName);
        }
    }

    public String getBrName() {
        if (mIsListener) {
            return null;
        }
        try {
            Scope.enter(this);
            Preconditions.checkNotNull(mGetter, "cannot get br name before resolving the getter");
            return mBrName;
        } finally {
            Scope.exit();
        }
    }

    /**
     * @return The list of all properties that dirty this expression. This will also contain the
     * the BR identifier for this property if there is one. If the property is not bindable,
     * it will contain an empty list.
     */
    public String[] getDirtyingProperties() {
        String[] names;
        if (mDependencies != null) {
            names = mTransitiveDependencies.stream()
                    .map(dep -> dep.getBrName())
                    .filter(brName -> brName != null)
                    .toArray(size -> new String[size]);
        } else {
            String br = getBrName();
            if (br == null) {
                names = new String[0];
            } else {
                names = new String[] { br };
            }
        }
        return names;
    }

    private void resolveDependencies() {
        if (mGetter != null && mGetter.canBeInvalidated()) {
            if (mGetter.type == Type.FIELD) {
                if (mGetter.bindableAnnotation != null &&
                        mGetter.bindableAnnotation.getDependencies().length != 0) {
                    L.e("Bindable annotation with property names is only supported on methods. " +
                                    "Field '%s.%s' has @Bindable(\"%s\")",
                            getTarget().getResolvedType().toJavaCode(), mGetter.name,
                            StringUtils.join(mGetter.bindableAnnotation.getDependencies(), "\", \""));
                }
            } else if (mGetter.method != null && mGetter.canBeInvalidated() &&
                    mGetter.bindableAnnotation != null) {
                try {
                    Scope.enter(this);
                    String[] dependencyArray = mGetter.bindableAnnotation.getDependencies();

                    Expr target = getTarget();
                    ModelClass resolvedType = target.getResolvedType();
                    L.d("resolving %s. Resolved class type: %s", this, resolvedType);

                    boolean isStatic = target instanceof StaticIdentifierExpr;
                    for (String dependency : dependencyArray) {
                        Callable getter = resolvedType.findGetterOrField(dependency, isStatic);
                        if (getter == null) {
                            L.e("Could not find dependent property '%s' referenced in "
                                            + "@Bindable annotation on %s.%s",
                                    dependency,
                                    mGetter.method.getDeclaringClass().toJavaCode(),
                                    mGetter.method.getName());
                        } else if (!getter.canBeInvalidated() && !getter.resolvedType.isObservableField()) {
                            L.e("The dependent property '%s' referenced in @Bindable "
                                            + "annotation on %s.%s must be annotated with "
                                            + "@Bindable",
                                    dependency,
                                    mGetter.method.getDeclaringClass().toJavaCode(),
                                    mGetter.method.getName());
                        }
                    }
                    mDependencies = new FieldAccessExpr[dependencyArray.length];
                    for (int i = 0; i < dependencyArray.length; i++) {
                        mDependencies[i] = getModel().field(target, dependencyArray[i]);
                        mDependencies[i].getResolvedType(); // force dependency resolution
                        // Make sure we listen for changes
                        getModel().bindingExpr(mDependencies[i]);
                    }

                    resolveDependencies(mTransitiveDependencies);
                    for (FieldAccessExpr expr : mTransitiveDependencies) {
                        expr.addBindableDependent(this);
                    }
                    mTransitiveDependencies.add(this);

                } finally {
                    Scope.exit();
                }
            }
        }
    }

    private void resolveDependencies(HashSet<FieldAccessExpr> dependencies) {
        if (mDependencies != null) {
            for (FieldAccessExpr expr : mDependencies) {
                if (!dependencies.contains(expr)) {
                    dependencies.add(expr);
                    expr.resolveDependencies(dependencies);
                }
            }
        }
    }

    private void addBindableDependent(FieldAccessExpr expr) {
        if (expr != this && !mDependents.contains(expr)) {
            mDependents.add(expr);
            for (FieldAccessExpr dep : mTransitiveDependencies) {
                dep.addBindableDependent(expr);
            }
        }
    }

    public Set<FieldAccessExpr> getBindableDependents() {
        return mDependents;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if (mIsListener) {
            return modelAnalyzer.findClass(Object.class);
        }
        if (mGetter == null) {
            Expr target = getTarget();
            mIsMap = target.getResolvedType().isMap();
            boolean isStatic = target instanceof StaticIdentifierExpr;
            ModelClass resolvedType = target.getResolvedType();
            L.d("resolving %s. Resolved class type: %s", this, resolvedType);

            mGetter = resolvedType.findGetterOrField(mName, isStatic);

            if (mGetter == null) {
                mIsListener = !resolvedType.findMethods(mName, isStatic).isEmpty();
                if (!mIsListener && !mIsMap) {
                    L.e("Could not find accessor %s.%s", resolvedType.getCanonicalName(), mName);
                } else if (mIsMap) {
                    return target.getResolvedType().getComponentType();
                } else {
                    return modelAnalyzer.findClass(Object.class);
                }
            }

            if (mGetter.isStatic() && !isStatic) {
                // found a static method on an instance. register a new one
                replaceStaticIdentifier(resolvedType);
                target = getTarget();
            }

            if (hasBindableAnnotations()) {
                mBrName = ExtKt.br(BrNameUtil.brKey(mGetter));
            }
            resolveDependencies();
        }
        return mGetter.resolvedType;
    }

    protected void replaceStaticIdentifier(ModelClass staticIdentifierType) {
        getTarget().getParents().remove(this);
        getChildren().remove(getTarget());
        StaticIdentifierExpr staticId = getModel().staticIdentifierFor(staticIdentifierType);
        getChildren().add(staticId);
        staticId.getParents().add(this);
    }

    @Override
    public Expr resolveTwoWayExpressions(Expr parent) {
        final Expr child = getTarget();
        if (!(child instanceof ViewFieldExpr)) {
            // fallback to default logic to check sub expressions
            return super.resolveTwoWayExpressions(parent);
        }
        final ViewFieldExpr expr = (ViewFieldExpr) child;
        final BindingTarget bindingTarget = expr.getBindingTarget();

        // This is a binding to a View's attribute, so look for matching attribute
        // on that View's BindingTarget. If there is an expression, we simply replace
        // the binding with that binding expression.
        for (Binding binding : bindingTarget.getBindings()) {
            if (attributeMatchesName(binding.getName(), mName)) {
                final Expr replacement = binding.getExpr().cloneToModel(getModel());
                replaceExpression(parent, replacement);
                return replacement;
            }
        }

        // There was no binding expression to bind to. This should be a two-way binding.
        // This is a synthesized two-way binding because we must capture the events from
        // the View and change the value when the target View's attribute changes.
        final SetterStore setterStore = SetterStore.get();
        final ModelClass targetClass = expr.getResolvedType();
        BindingGetterCall getter = setterStore.getGetterCall(mName, targetClass, null, null);
        if (getter == null) {
            getter = setterStore.getGetterCall("android:" + mName, targetClass, null, null);
            if (getter == null) {
                L.e("Could not resolve the two-way binding attribute '%s' on type '%s'",
                        mName, targetClass);
            }
        }
        InverseBinding inverseBinding = null;
        for (Binding binding : bindingTarget.getBindings()) {
            final Expr testExpr = binding.getExpr();
            if (testExpr instanceof TwoWayListenerExpr &&
                    getter.getEventAttribute().equals(binding.getName())) {
                inverseBinding = ((TwoWayListenerExpr) testExpr).mInverseBinding;
                break;
            }
        }
        if (inverseBinding == null) {
            inverseBinding = bindingTarget.addInverseBinding(mName, getter);
        }
        inverseBinding.addChainedExpression(this);
        mIsViewAttributeAccess = true;
        enableDirectInvalidation();
        return this;
    }

    private static boolean attributeMatchesName(String attribute, String field) {
        int colonIndex = attribute.indexOf(':');
        return attribute.substring(colonIndex + 1).equals(field);
    }

    private void replaceExpression(Expr parent, Expr replacement) {
        if (parent != null) {
            List<Expr> children = parent.getChildren();
            int index;
            while ((index = children.indexOf(this)) >= 0) {
                children.set(index, replacement);
                replacement.getParents().add(parent);
            }
            while (getParents().remove(parent)) {
                // just remove all copies of parent.
            }
        }
        if (getParents().isEmpty()) {
            getModel().removeExpr(this);
        }
    }

    @Override
    protected String asPackage() {
        String parentPackage = getTarget().asPackage();
        return parentPackage == null ? null : parentPackage + "." + mName;
    }

    @Override
    protected KCode generateCode() {
        // once we can deprecate using Field.access for callbacks, we can get rid of this since
        // it will be detected when resolve type is run.
        Callable getter = getGetter();
        if (!mIsMap) {
            Preconditions.checkNotNull(getter, ErrorMessages.CANNOT_RESOLVE_TYPE, this);
        }
        KCode code = new KCode()
                .app("", getTarget().toCode()).app(".");
        if (getter == null && mIsMap) {
            return code.app("get(\"").app(mName).app("\")");
        } else if (getter.type == Callable.Type.FIELD) {
            return code.app(getter.name);
        } else {
            return code.app(getter.name).app("()");
        }
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        Expr castExpr = model.castExpr(getResolvedType().toJavaCode(), value);
        Expr target = getTarget().cloneToModel(model);
        Expr result;
        if (mName.isEmpty()) {
            result = model.methodCall(target, "set", Lists.newArrayList(castExpr));
            result.setUnwrapObservableFields(false);
        } else if (getGetter().type == Callable.Type.FIELD) {
            result = model.assignment(target, mName, castExpr);
        } else {
            result = model.methodCall(target, mGetter.setterName, Lists.newArrayList(castExpr));
        }
        return result;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        final Expr clonedTarget = getTarget().cloneToModel(model);
        return model.field(clonedTarget, mName);
    }

    @Override
    public String toString() {
        String name = mName.isEmpty() ? "get()" : mName;
        return getTarget().toString() + '.' + name;
    }
}
