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

package android.databinding.tool;

import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.expr.TwoWayListenerExpr;
import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.scopes.LocationScopeProvider;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.store.Location;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.store.SetterStore.BindingGetterCall;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;

import com.android.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BindingTarget implements LocationScopeProvider {
    List<Binding> mBindings = new ArrayList<Binding>();
    List<InverseBinding> mInverseBindings = new ArrayList<InverseBinding>();
    ExprModel mModel;
    ModelClass mResolvedClass;

    // if this target presents itself in multiple layout files with different view types,
    // it receives an interface type and should use it in the getter instead.
    ResourceBundle.BindingTargetBundle mBundle;

    public BindingTarget(ResourceBundle.BindingTargetBundle bundle) {
        mBundle = bundle;
    }

    public boolean isUsed() {
        return mBundle.isUsed();
    }

    public void addBinding(String name, Expr expr) {
        if (SetterStore.get().isTwoWayEventAttribute(name)) {
            L.e(ErrorMessages.TWO_WAY_EVENT_ATTRIBUTE, name);
        }
        mBindings.add(new Binding(this, name, expr));
    }

    public String getInterfaceType() {
        return mBundle.getInterfaceType() == null ? mBundle.getFullClassName() : mBundle.getInterfaceType();
    }

    public InverseBinding addInverseBinding(String name, Expr expr, String bindingClass) {
        final InverseBinding inverseBinding = new InverseBinding(this, name, expr, bindingClass);
        mInverseBindings.add(inverseBinding);
        expr.markAsBindingExpression();
        mBindings.add(new Binding(this, inverseBinding.getEventAttribute(),
                mModel.twoWayListenerExpr(inverseBinding),
                inverseBinding.getEventSetter()));
        return inverseBinding;
    }

    public InverseBinding addInverseBinding(String name, BindingGetterCall call) {
        final InverseBinding inverseBinding = new InverseBinding(this, name, call);
        mInverseBindings.add(inverseBinding);
        TwoWayListenerExpr expr = mModel.twoWayListenerExpr(inverseBinding);
        expr.markAsBindingExpression();
        mBindings.add(new Binding(this, inverseBinding.getEventAttribute(), expr));
        return inverseBinding;
    }

    @Override
    public List<Location> provideScopeLocation() {
        return mBundle.provideScopeLocation();
    }

    public String getId() {
        return mBundle.getId();
    }

    public String getTag() {
        return mBundle.getTag();
    }

    public String getOriginalTag() {
        return mBundle.getOriginalTag();
    }

    public String getViewClass() {
        return mBundle.getFullClassName();
    }

    public ModelClass getResolvedType() {
        if (mResolvedClass == null) {
            mResolvedClass = ModelAnalyzer.getInstance().findClass(mBundle.getFullClassName(),
                    mModel.getImports());
        }
        return mResolvedClass;
    }

    public String getIncludedLayout() {
        return mBundle.getIncludedLayout();
    }

    @Nullable
    public String getIncludedLayoutPackage() {
        return mBundle.getModulePackage();
    }

    /**
     * This will return true for both DataBinding and ViewBinding targets. Check the resolved type
     * if you need to distinguish between the two.
     */
    public boolean isBinder() {
        return mBundle.isBinder();
    }

    public boolean supportsTag() {
        return !SetterStore.get()
                .isUntaggable(mBundle.getFullClassName());
    }

    public List<Binding> getBindings() {
        return mBindings;
    }

    public List<InverseBinding> getInverseBindings() {
        return mInverseBindings;
    }

    public ExprModel getModel() {
        return mModel;
    }

    public void setModel(ExprModel model) {
        mModel = model;
    }

    /**
     * Called after experiment model is sealed to avoid NPE problems caused by boxed primitives.
     */
    public void injectSafeUnboxing(ExprModel exprModel) {
        for (Binding binding : mBindings) {
            binding.injectSafeUnboxing(exprModel);
        }
    }

    public void resolveListeners() {
        for (Binding binding : mBindings) {
            try {
                Scope.enter(binding);
                binding.resolveListeners();
            } finally {
                Scope.exit();
            }
        }
    }

    public void resolveCallbackParams() {
        for (Binding binding : mBindings) {
            try {
                Scope.enter(binding);
                binding.resolveCallbackParams();
            } finally {
                Scope.exit();
            }
        }
    }

    public void resolveTwoWayExpressions() {
        List<Binding> bindings = new ArrayList<>(mBindings);
        for (Binding binding : bindings) {
            try {
                Scope.enter(binding);
                binding.resolveTwoWayExpressions();
            } finally {
                Scope.exit();
            }
        }
    }

    /**
     * Called after BindingTarget is finalized.
     * <p>
     * We traverse all bindings and ask SetterStore to figure out if any can be combined.
     * When N bindings are combined, they are demoted from being a binding expression and a new
     * ArgList expression is added as the new binding expression that depends on others.
     */
    public void resolveMultiSetters() {
        L.d("resolving multi setters for %s", getId());
        final SetterStore setterStore = SetterStore.get();
        String[] attributes = new String[mBindings.size()];
        ModelClass[] types = new ModelClass[mBindings.size()];
        boolean hasObservableFields = false;

        // Start by trying attributes with the ObservableField component
        for (int i = 0; i < mBindings.size(); i ++) {
            Binding binding = mBindings.get(i);
            try {
                Scope.enter(binding);
                attributes[i] = binding.getName();
                final ModelClass type = binding.getExpr().getResolvedType();
                if (type.getObservableGetterName() != null) {
                    hasObservableFields = true;
                    types[i] = binding.getExpr().unwrapObservableField().getResolvedType();
                } else {
                    types[i] = type;
                }
            } finally {
                Scope.exit();
            }
        }
        List<SetterStore.MultiAttributeSetter> multiAttributeSetterCalls = setterStore.
                getMultiAttributeSetterCalls(attributes, getResolvedType(), types);
        List<MergedBinding> merged = createMultiSetters(multiAttributeSetterCalls, hasObservableFields);

        if (hasObservableFields) {
            // Now try the attributes for ObservableField direct bindings
            attributes = new String[mBindings.size()];
            types = new ModelClass[mBindings.size()];
            for (int i = 0; i < mBindings.size(); i++) {
                Binding binding = mBindings.get(i);
                try {
                    Scope.enter(binding);
                    attributes[i] = binding.getName();
                    types[i] = binding.getExpr().getResolvedType();
                } finally {
                    Scope.exit();
                }
            }
            multiAttributeSetterCalls = setterStore.
                    getMultiAttributeSetterCalls(attributes, getResolvedType(), types);
            List<MergedBinding> observableFieldCalls =
                    createMultiSetters(multiAttributeSetterCalls, false);
            merged.addAll(observableFieldCalls);
        }

        mBindings.addAll(merged);
    }

    private List<MergedBinding> createMultiSetters(
            List<SetterStore.MultiAttributeSetter> multiAttributeSetterCalls,
            boolean unwrapObservableFields) {
        final List<MergedBinding> mergeBindings = new ArrayList<>();
        if (multiAttributeSetterCalls.isEmpty()) {
            return mergeBindings;
        }
        final Map<String, Binding> lookup = new HashMap<String, Binding>();
        for (Binding binding : mBindings) {
            String name = binding.getName();
            if (name.startsWith("android:")) {
                lookup.put(name, binding);
            } else {
                int ind = name.indexOf(":");
                if (ind == -1) {
                    lookup.put(name, binding);
                } else {
                    lookup.put(name.substring(ind + 1), binding);
                }
            }
        }
        for (final SetterStore.MultiAttributeSetter setter : multiAttributeSetterCalls) {
            L.d("resolved %s", setter);
            final List<Binding> mergedBindings = new ArrayList<Binding>();
            for (String attribute : setter.attributes) {
                Binding binding = lookup.get(attribute);
                Preconditions.checkNotNull(binding, "cannot find binding for %s", attribute);
                mergedBindings.add(binding);
                if (unwrapObservableFields) {
                    binding.unwrapObservableFieldExpression();
                }
            }

            mBindings.removeAll(mergedBindings);
            MergedBinding mergedBinding = new MergedBinding(getModel(), setter, this,
                    mergedBindings);
            mergeBindings.add(mergedBinding);
        }
        return mergeBindings;
    }
}
