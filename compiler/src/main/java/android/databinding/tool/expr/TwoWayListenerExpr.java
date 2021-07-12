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

import android.databinding.tool.InverseBinding;
import android.databinding.tool.reflection.ImportBag;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.writer.KCode;
import android.databinding.tool.writer.LayoutBinderWriterKt;

import java.util.Collections;
import java.util.List;

/**
 * TwoWayListenerExpr is used to set the event listener for a two-way binding expression.
 */
public class TwoWayListenerExpr extends Expr {
    final InverseBinding mInverseBinding;

    public TwoWayListenerExpr(InverseBinding inverseBinding) {
        mInverseBinding = inverseBinding;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        return modelAnalyzer.findClass(modelAnalyzer.libTypes.getInverseBindingListener(),
                ImportBag.EMPTY);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    @Override
    protected KCode generateCode() {
        final String fieldName = LayoutBinderWriterKt.getFieldName(mInverseBinding);
        return new KCode(fieldName);
    }

    @Override
    public void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        // nothing to unbox
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.twoWayListenerExpr(mInverseBinding);
    }

    @Override
    protected String computeUniqueKey() {
        return "event(" + mInverseBinding.getEventAttribute() + ", " +
                System.identityHashCode(mInverseBinding) + ")";
    }

    @Override
    public String getInvertibleError() {
        return "Inverted expressions are already inverted!";
    }

    @Override
    public String toString() {
        return LayoutBinderWriterKt.getFieldName(mInverseBinding.getTarget()) + '(' +
                mInverseBinding.getEventAttribute() + ')';
    }
}
