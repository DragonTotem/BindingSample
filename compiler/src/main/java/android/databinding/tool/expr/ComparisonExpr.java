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

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.writer.KCode;

import com.android.annotations.NonNull;

import java.util.List;
import java.util.Objects;

public class ComparisonExpr extends Expr {
    final String mOp;
    ComparisonExpr(String op, Expr left, Expr right) {
        super(left, right);
        mOp = op;
    }

    @Override
    protected String computeUniqueKey() {
        return join(getLeft(), mOp, getRight());
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        return modelAnalyzer.loadPrimitive("boolean");
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    public String getOp() {
        return mOp;
    }

    public Expr getLeft() {
        return getChildren().get(0);
    }

    public Expr getRight() {
        return getChildren().get(1);
    }

    @Override
    public boolean isEqualityCheck() {
        return "==".equals(mOp.trim());
    }

    @Override
    protected KCode generateCode() {
        return new KCode()
                .app("(", getLeft().toCode())
                .app(") ")
                .app(getOp())
                .app(" (", getRight().toCode())
                .app(")");
    }

    @Override
    public void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        Expr left = getLeft();
        Expr right = getRight();
        if (left.getResolvedType().isNullable() && right.getResolvedType().isNullable()) {
            if ("==".equals(mOp) || "!=".equals(mOp)) {
                return;
            }
            safeUnboxChild(model, left);
            safeUnboxChild(model, right);
        } else if (left.getResolvedType().isNullable()) {
            safeUnboxChild(model, left);
        } else if (right.getResolvedType().isNullable()) {
            safeUnboxChild(model, right);
        }
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.comparison(mOp, getLeft().cloneToModel(model), getRight().cloneToModel(model));
    }

    @Override
    public String getInvertibleError() {
        return "Comparison operators are not valid as targets of two-way binding";
    }

    @Override
    public String toString() {
        return getLeft().toString() + ' ' + mOp + ' ' + getRight();
    }

    /**
     * Returns true if this expression is a null check for the given expression.
     * e.g. if this expression is a == null then this method returns true if invoked w/ a.
     */
    public boolean isNullCheckFor(@NonNull Expr expr) {
        return "==".equals(mOp) && isNullabilityCheckFor(expr);
    }

    /**
     * Returns true if this expression is a NOT NULL check for the given expression.
     * e.g. if this expression is a != null then this method returns true if invoked w/ a.
     */
    public boolean isNotNullCheckFor(@NonNull Expr expr) {
        return "!=".equals(mOp) && isNullabilityCheckFor(expr);
    }

    private boolean isNullabilityCheckFor(@NonNull Expr expr) {
        if (isNullLiteral(getLeft())) {
            return Objects.equals(expr.getUniqueKey(), getRight().getUniqueKey());
        } else if (isNullLiteral(getRight())) {
            return Objects.equals(expr.getUniqueKey(), getLeft().getUniqueKey());
        }
        return false;
    }

    private static boolean isNullLiteral(Expr expr) {
        final ModelClass type = expr.getResolvedType();
        return (type.isObject() && (expr instanceof SymbolExpr) &&
                "null".equals(((SymbolExpr) expr).getText()));
    }
}
