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

import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.scopes.LocationScopeProvider;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.RecursionTracker;
import android.databinding.tool.reflection.RecursiveResolutionStack;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.store.Location;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.KCode;
import android.databinding.tool.writer.LayoutBinderWriterKt;

import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract public class Expr implements VersionProvider, LocationScopeProvider {
    public static final int NO_ID = -1;
    protected List<Expr> mChildren = new ArrayList<Expr>();

    // any expression that refers to this. Useful if this expr is duplicate and being replaced
    private List<Expr> mParents = new ArrayList<Expr>();

    private Boolean mIsDynamic;

    private ModelClass mResolvedType;

    private String mUniqueKey;

    private List<Dependency> mDependencies;

    private List<Dependency> mDependants = new ArrayList<Dependency>();

    private int mId = NO_ID;

    private int mRequirementId = NO_ID;

    private int mVersion = 0;

    // means this expression can directly be invalidated by the user
    private boolean mCanBeInvalidated = false;

    // temporary variable used when recursively unboxing children. This allows us to decide
    // whether we should re-consider the return type or not
    private boolean mUnboxedAChild = false;

    @Nullable
    private List<Location> mLocations = new ArrayList<Location>();

    /**
     * This set denotes the times when this expression is invalid.
     * If it is an Identifier expression, it is its index
     * If it is a composite expression, it is the union of invalid flags of its descendants
     */
    private BitSet mInvalidFlags;

    /**
     * Set when this expression is registered to a model
     */
    private ExprModel mModel;

    /**
     * This set denotes the times when this expression must be read.
     *
     * It is the union of invalidation flags of all of its non-conditional dependants.
     */
    BitSet mShouldReadFlags;

    BitSet mReadSoFar = new BitSet();// i've read this variable for these flags

    /**
     * calculated on initialization, assuming all conditionals are true
     */
    BitSet mShouldReadWithConditionals;

    /**
     * Marks this expression such that it is the final result of an expression evaluation.
     * <p>
     * An expression may lose this property if it is wrapped or used via a multi-arg-adapter
     * but we never unset it.
     */
    private boolean mIsBindingExpression = false;

    /**
     * Used by generators when this expression is resolved.
     */
    private boolean mRead;
    private boolean mIsUsed = false;
    private boolean mIsUsedInCallback = false;
    private boolean mUnwrapObservableFields = true;

    // used to prevent infinite loops when resolving recursive data structures
    private static RecursiveResolutionStack sResolveTypeStack = new RecursiveResolutionStack();

    Expr(Iterable<Expr> children) {
        for (Expr expr : children) {
            mChildren.add(expr);
        }
        addParents();
    }

    Expr(Expr... children) {
        Collections.addAll(mChildren, children);
        addParents();
    }

    public int getId() {
        Preconditions.check(mId != NO_ID, "if getId is called on an expression, it should have"
                + " an id: %s", this);
        return mId;
    }

    public void setId(int id) {
        Preconditions.check(mId == NO_ID, "ID is already set on %s", this);
        mId = id;
    }

    public void addLocation(Location location) {
        mLocations.add(location);
    }

    public List<Location> getLocations() {
        return mLocations;
    }

    public ExprModel getModel() {
        return mModel;
    }

    public BitSet getInvalidFlags() {
        if (mInvalidFlags == null) {
            mInvalidFlags = resolveInvalidFlags();
        }
        return mInvalidFlags;
    }

    private BitSet resolveInvalidFlags() {
        BitSet bitSet = (BitSet) mModel.getInvalidateAnyBitSet().clone();
        if (mCanBeInvalidated) {
            bitSet.set(getId(), true);
        }
        for (Dependency dependency : getDependencies()) {
            // TODO optional optimization: do not invalidate for conditional flags
            bitSet.or(dependency.getOther().getInvalidFlags());
        }
        return bitSet;
    }

    public void markAsBindingExpression() {
        mIsBindingExpression = true;
    }
    public boolean isBindingExpression() {
        return mIsBindingExpression;
    }

    public boolean canBeEvaluatedToAVariable() {
        return true; // anything except arg/return expr can be evaluated to a variable
    }

    public boolean isObservable() {
        return getResolvedType().isObservable();
    }

    public String getUpdateRegistrationCall(int id, String value) {
        if (!isObservable()) {
            L.e("The expression isn't observable!");
        }
        String lastParams = id + ", " + value + ");";
        if (getResolvedType().isLiveData()) {
            return "updateLiveDataRegistration(" + lastParams;
        }
        if (getResolvedType().isStateFlow()) {
            return "androidx.databinding.ViewDataBindingKtx."
                    + "updateStateFlowRegistration(this, " + lastParams;
        }
        return "updateRegistration(" + lastParams;
    }

    public void setUnwrapObservableFields(boolean unwrapObservableFields) {
        mUnwrapObservableFields = unwrapObservableFields;
    }

    public Expr resolveListeners(ModelClass valueType, Expr parent) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            Expr child = mChildren.get(i);
            child.resolveListeners(valueType, this);
        }
        resetResolvedType();
        return this;
    }

    /**
     * Tries to create a safe unbox method for the given expression.
     * <p>
     * Sometimes, the child might be just any object (especially returned from bracket expressions where any value type
     * might be unknown). In this case, child stays instact.
     * @param model ExprModel
     * @param child The child that will be replaced with a safe unbox call.
     */
    public void safeUnboxChild(ExprModel model, Expr child) {
        if (child.getResolvedType().unbox() == child.getResolvedType()) {
            return;
        }
        mUnboxedAChild = true;
        int index = getChildren().indexOf(child);
        child.getParents().remove(this);
        getChildren().set(index, model.safeUnbox(child));
    }

    public Expr resolveTwoWayExpressions(Expr parent) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final Expr child = mChildren.get(i);
            child.resolveTwoWayExpressions(this);
        }
        return this;
    }

    protected void resetResolvedType() {
        mResolvedType = null;
    }

    public BitSet getShouldReadFlags() {
        if (mShouldReadFlags == null) {
            getShouldReadFlagsWithConditionals();
            mShouldReadFlags = resolveShouldReadFlags();
        }
        return mShouldReadFlags;
    }

    public BitSet getShouldReadFlagsWithConditionals() {
        if (mShouldReadWithConditionals == null) {
            mShouldReadWithConditionals = resolveShouldReadWithConditionals();
        }
        return mShouldReadWithConditionals;
    }

    public void setModel(ExprModel model) {
        mModel = model;
    }

    private BitSet resolveShouldReadWithConditionals() {
        // ensure we have invalid flags
        BitSet bitSet = new BitSet();
        // if i'm invalid, that DOES NOT mean i should be read :/.
        if (isBindingExpression()) {
            bitSet.or(getInvalidFlags());
        }

        for (Dependency dependency : getDependants()) {
            if (dependency.getCondition() == null) {
                bitSet.or(dependency.getDependant().getShouldReadFlagsWithConditionals());
            } else {
                bitSet.set(dependency.getDependant()
                        .getRequirementFlagIndex(dependency.getExpectedOutput()));
            }
        }
        return bitSet;
    }

    private BitSet resolveShouldReadFlags() {
        // ensure we have invalid flags
        BitSet bitSet = new BitSet();
        if (isRead()) {
            return bitSet;
        }
        if (isBindingExpression()) {
            bitSet.or(getInvalidFlags());
        }
        for (Dependency dependency : getDependants()) {
            final boolean isUnreadElevated = isUnreadElevated(dependency);
            if (dependency.isConditional()) {
                continue; // will be resolved later when conditional is elevated
            }
            if (isUnreadElevated) {
                bitSet.set(dependency.getDependant()
                        .getRequirementFlagIndex(dependency.getExpectedOutput()));
            } else {
                bitSet.or(dependency.getDependant().getShouldReadFlags());
            }
        }
        bitSet.and(mShouldReadWithConditionals);
        bitSet.andNot(mReadSoFar);
        return bitSet;
    }

    private static boolean isUnreadElevated(Dependency input) {
        return input.isElevated() && !input.getDependant().isRead();
    }
    private void addParents() {
        for (Expr expr : mChildren) {
            expr.mParents.add(this);
        }
    }

    public void onSwappedWith(Expr existing) {
        for (Expr child : mChildren) {
            child.onParentSwapped(this, existing);
        }
    }

    private void onParentSwapped(Expr oldParent, Expr newParent) {
        Preconditions.check(mParents.remove(oldParent), "trying to remove non-existent parent %s"
                + " from %s", oldParent, mParents);
        mParents.add(newParent);
    }

    public List<Expr> getChildren() {
        return mChildren;
    }

    public List<Expr> getParents() {
        return mParents;
    }

    /**
     * Whether the result of this expression can change or not.
     *
     * For example, 3 + 5 can not change vs 3 + x may change.
     *
     * Default implementations checks children and returns true if any of them returns true
     *
     * @return True if the result of this expression may change due to variables
     */
    public boolean isDynamic() {
        if (mIsDynamic == null) {
            mIsDynamic = isAnyChildDynamic();
        }
        return mIsDynamic;
    }

    private boolean isAnyChildDynamic() {
        for (Expr expr : mChildren) {
            if (expr.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    public final ModelClass getResolvedType() {
        if (mResolvedType != null) {
            return mResolvedType;
        }
        try {
            Scope.enter(this);
            mResolvedType = sResolveTypeStack.visit(
                    this,
                    currentType -> {
                        if (mUnwrapObservableFields) {
                            unwrapObservableFieldChildren();
                            mUnwrapObservableFields = false;
                        }
                        return resolveType(ModelAnalyzer.getInstance());
                    },
                    recursedType -> {
                        // solve without unwrapping observables
                        return resolveType(ModelAnalyzer.getInstance());
                    }
            );
            if (mResolvedType == null) {
                L.e(ErrorMessages.CANNOT_RESOLVE_TYPE, this);
            }
        } finally {
            Scope.exit();
        }
        return mResolvedType;
    }

    public final List<ExecutionPath> toExecutionPath(ExecutionPath path) {
        List<ExecutionPath> paths = new ArrayList<ExecutionPath>();
        paths.add(path);
        return toExecutionPath(paths);
    }

    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        if (getChildren().isEmpty()) {
            return addJustMeToExecutionPath(paths);
        } else {
            return toExecutionPathInOrder(paths, getChildren());
        }

    }

    @NotNull
    protected final List<ExecutionPath> addJustMeToExecutionPath(List<ExecutionPath> paths) {
        List<ExecutionPath> result = new ArrayList<ExecutionPath>();
        for (ExecutionPath path : paths) {
            result.add(path.addPath(this));
        }
        return result;
    }

    @SuppressWarnings("Duplicates")
    protected final List<ExecutionPath> toExecutionPathInOrder(List<ExecutionPath> paths,
            Expr... order) {
        List<ExecutionPath> executionPaths = paths;
        for (Expr anOrder : order) {
            executionPaths = anOrder.toExecutionPath(executionPaths);
        }
        List<ExecutionPath> result = new ArrayList<ExecutionPath>(paths.size());
        for (ExecutionPath path : executionPaths) {
            result.add(path.addPath(this));
        }
        return result;
    }

    @SuppressWarnings("Duplicates")
    protected final List<ExecutionPath> toExecutionPathInOrder(List<ExecutionPath> paths,
            List<Expr> order) {
        List<ExecutionPath> executionPaths = paths;
        for (Expr expr : order) {
            executionPaths = expr.toExecutionPath(executionPaths);
        }
        List<ExecutionPath> result = new ArrayList<ExecutionPath>(paths.size());
        for (ExecutionPath path : executionPaths) {
            result.add(path.addPath(this));
        }
        return result;
    }

    abstract protected ModelClass resolveType(ModelAnalyzer modelAnalyzer);

    abstract protected List<Dependency> constructDependencies();

    /**
     * Creates a dependency for each dynamic child. Should work for any expression besides
     * conditionals.
     */
    protected List<Dependency> constructDynamicChildrenDependencies() {
        List<Dependency> dependencies = new ArrayList<Dependency>();
        for (Expr node : mChildren) {
            if (!node.isDynamic()) {
                continue;
            }
            dependencies.add(new Dependency(this, node));
        }
        return dependencies;
    }

    public final List<Dependency> getDependencies() {
        if (mDependencies == null) {
            mDependencies = constructDependencies();
        }
        return mDependencies;
    }

    void addDependant(Dependency dependency) {
        mDependants.add(dependency);
    }

    public List<Dependency> getDependants() {
        return mDependants;
    }

    protected static final String KEY_JOIN = "~";
    protected static final String KEY_JOIN_START = "(";
    protected static final String KEY_JOIN_END = ")";
    protected static final String KEY_START = "@";
    protected static final String KEY_END = "#";

    /**
     * Returns a unique string key that can identify this expression.
     *
     * It must take into account any dependencies
     *
     * @return A unique identifier for this expression
     */
    public final String getUniqueKey() {
        if (mUniqueKey == null) {
            String uniqueKey = computeUniqueKey();
            Preconditions.checkNotNull(uniqueKey,
                    "you must override computeUniqueKey to return non-null String");
            Preconditions.check(!uniqueKey.trim().isEmpty(),
                    "you must override computeUniqueKey to return a non-empty String");
            mUniqueKey = KEY_START + uniqueKey + KEY_END;
        }
        return mUniqueKey;
    }

    protected abstract String computeUniqueKey();

    public void enableDirectInvalidation() {
        mCanBeInvalidated = true;
    }

    public boolean canBeInvalidated() {
        return mCanBeInvalidated;
    }

    public void trimShouldReadFlags(BitSet bitSet) {
        mShouldReadFlags.andNot(bitSet);
    }

    public boolean isConditional() {
        return false;
    }

    public int getRequirementId() {
        return mRequirementId;
    }

    public void setRequirementId(int requirementId) {
        mRequirementId = requirementId;
    }

    /**
     * This is called w/ a dependency of mine.
     * Base method should thr
     */
    public int getRequirementFlagIndex(boolean expectedOutput) {
        Preconditions.check(mRequirementId != NO_ID, "If this is an expression w/ conditional"
                + " dependencies, it must be assigned a requirement ID. %s", this);
        return expectedOutput ? mRequirementId + 1 : mRequirementId;
    }

    public boolean hasId() {
        return mId != NO_ID;
    }

    public void markFlagsAsRead(BitSet flags) {
        mReadSoFar.or(flags);
    }

    public boolean isRead() {
        return mRead;
    }

    public boolean considerElevatingConditionals(Expr justRead) {
        boolean elevated = false;
        for (Dependency dependency : mDependencies) {
            if (dependency.isConditional() && dependency.getCondition() == justRead) {
                dependency.elevate();
                elevated = true;
            }
        }
        return elevated;
    }

    public void invalidateReadFlags() {
        mShouldReadFlags = null;
        mVersion ++;
    }

    @Override
    public int getVersion() {
        return mVersion;
    }

    public boolean hasNestedCannotRead() {
        if (isRead()) {
            return false;
        }
        if (getShouldReadFlags().isEmpty()) {
            return true;
        }
        for (Dependency dependency : getDependencies()) {
            if (hasNestedCannotRead(dependency)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNestedCannotRead(Dependency input) {
        return input.isConditional() || input.getOther().hasNestedCannotRead();
    }

    public boolean markAsReadIfDone() {
        if (mRead) {
            return false;
        }
        // TODO avoid clone, we can calculate this iteratively
        BitSet clone = (BitSet) mShouldReadWithConditionals.clone();

        clone.andNot(mReadSoFar);
        mRead = clone.isEmpty();

        if (!mRead && !mReadSoFar.isEmpty()) {
            // check if remaining dependencies can be satisfied w/ existing values
            // for predicate flags, this expr may already be calculated to get the predicate
            // to detect them, traverse them later on, see which flags should be calculated to calculate
            // them. If any of them is completely covered w/ our non-conditional flags, no reason
            // to add them to the list since we'll already be calculated due to our non-conditional
            // flags
            boolean allCovered = true;
            for (int i = clone.nextSetBit(0); i != -1; i = clone.nextSetBit(i + 1)) {
                final Expr expr = mModel.findFlagExpression(i);
                if (expr == null) {
                    continue;
                }
                if (!expr.isConditional()) {
                    allCovered = false;
                    break;
                }
                final BitSet readForConditional = (BitSet) expr
                        .getShouldReadFlagsWithConditionals().clone();

                // FIXME: this does not do full traversal so misses some cases
                // to calculate that conditional, i should've read /readForConditional/ flags
                // if my read-so-far bits cover that; that means i would've already
                // read myself
                readForConditional.andNot(mReadSoFar);
                if (!readForConditional.isEmpty()) {
                    allCovered = false;
                    break;
                }
            }
            mRead = allCovered;
        }
        if (mRead) {
            mShouldReadFlags = null; // if we've been marked as read, clear should read flags
        }
        return mRead;
    }

    BitSet mConditionalFlags;

    private BitSet findConditionalFlags() {
        Preconditions.check(isConditional(), "should not call this on a non-conditional expr");
        if (mConditionalFlags == null) {
            mConditionalFlags = new BitSet();
            resolveConditionalFlags(mConditionalFlags);
        }
        return mConditionalFlags;
    }

    private void resolveConditionalFlags(BitSet flags) {
        flags.or(getPredicateInvalidFlags());
        // if i have only 1 dependency which is conditional, traverse it as well
        if (getDependants().size() == 1) {
            final Dependency dependency = getDependants().get(0);
            if (dependency.getCondition() != null) {
                flags.or(dependency.getDependant().findConditionalFlags());
                flags.set(dependency.getDependant()
                        .getRequirementFlagIndex(dependency.getExpectedOutput()));
            }
        }
    }


    @Override
    public String toString() {
        return "[" + getClass().getSimpleName() + ":" + getUniqueKey() + "]";
    }

    public BitSet getReadSoFar() {
        return mReadSoFar;
    }

    private Node mCalculationPaths = null;

    /**
     * All flag paths that will result in calculation of this expression.
     */
    protected Node getAllCalculationPaths() {
        if (mCalculationPaths == null) {
            Node node = new Node();
            if (isConditional()) {
                node.mBitSet.or(getPredicateInvalidFlags());
            } else {
                node.mBitSet.or(getInvalidFlags());
            }
            for (Dependency dependency : getDependants()) {
                final Expr dependant = dependency.getDependant();
                if (dependency.getCondition() != null) {
                    Node cond = new Node();
                    cond.setConditionFlag(
                            dependant.getRequirementFlagIndex(dependency.getExpectedOutput()));
                    cond.mParents.add(dependant.getAllCalculationPaths());
                    node.mParents.add(cond);
                } else {
                    node.mParents.add(dependant.getAllCalculationPaths());
                }
            }
            mCalculationPaths = node;
        }
        return mCalculationPaths;
    }

    public String getDefaultValue() {
        return ModelAnalyzer.getInstance().getDefaultValue(getResolvedType().toJavaCode());
    }

    protected BitSet getPredicateInvalidFlags() {
        throw new IllegalStateException(
                "must override getPredicateInvalidFlags in " + getClass().getSimpleName());
    }

    /**
     * Used by code generation
     */
    public boolean shouldReadNow(final List<Expr> justRead) {
        if (getShouldReadFlags().isEmpty()) {
            return false;
        }
        for (Dependency input : getDependencies()) {
            boolean dependencyReady = input.getOther().isRead() || (justRead != null &&
                    justRead.contains(input.getOther()));
            if(!dependencyReady) {
                return false;
            }
        }
        return true;
    }

    public boolean isEqualityCheck() {
        return false;
    }

    public void markAsUsed() {
        mIsUsed = true;
        for (Expr child : getChildren()) {
            child.markAsUsed();
        }
    }

    public void markAsUsedInCallback() {
        mIsUsedInCallback = true;
        for (Expr child : getChildren()) {
            child.markAsUsedInCallback();
        }
    }

    public boolean isIsUsedInCallback() {
        return mIsUsedInCallback;
    }

    public boolean isUsed() {
        return mIsUsed;
    }

    public void updateExpr(ModelAnalyzer modelAnalyzer) {
        final Map<String, Expr> exprMap = mModel.getExprMap();
        for (int i = mParents.size() - 1; i >= 0; i--) {
            final Expr parent = mParents.get(i);
            if (exprMap.get(parent.getUniqueKey()) != parent) {
                mParents.remove(i);
            }
        }
        for (Expr child : mChildren) {
            child.updateExpr(modelAnalyzer);
        }
    }

    protected static String join(List<?> vals) {
        if (vals == null || vals.isEmpty()) {
            return "";
        }
        return join(vals.stream());
    }

    protected static String join(Object... vals) {
        if (vals == null || vals.length == 0) {
            return "";
        }
        return join(Arrays.stream(vals));
    }

    private static String join(Stream<?> vals) {
        return vals.map(val -> (val instanceof Expr ? ((Expr)val).getUniqueKey() : val.toString()))
                .collect(Collectors.joining(KEY_JOIN, KEY_JOIN_START, KEY_JOIN_END));
    }

    protected String asPackage() {
        return null;
    }

    @Override
    public List<Location> provideScopeLocation() {
        return mLocations;
    }

    public KCode toCode() {
        if (isDynamic()) {
            return new KCode(LayoutBinderWriterKt.scopedName(this));
        }
        return generateCode();
    }

    public KCode toFullCode() {
        return generateCode();
    }

    protected abstract KCode generateCode();

    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        throw new IllegalStateException("expression does not support two-way binding");
    }

    public abstract Expr cloneToModel(ExprModel model);

    protected static List<Expr> cloneToModel(ExprModel model, List<Expr> exprs) {
        ArrayList<Expr> clones = new ArrayList<Expr>();
        for (Expr expr : exprs) {
            clones.add(expr.cloneToModel(model));
        }
        return clones;
    }

    public void assertIsInvertible() {
        final String errorMessage = getInvertibleError();
        if (errorMessage != null) {
            L.e(ErrorMessages.EXPRESSION_NOT_INVERTIBLE, toFullCode().generate(),
                    errorMessage);
        }
    }

    /**
     * @return The reason the expression wasn't invertible or null if it was invertible.
     */
    protected abstract String getInvertibleError();

    /**
     * This expression is the predicate for 1 or more ternary expressions.
     */
    public boolean hasConditionalDependant() {
        for (Dependency dependency : getDependants()) {
            Expr dependant = dependency.getDependant();
            if (dependant.isConditional() && dependant instanceof TernaryExpr) {
                TernaryExpr ternary = (TernaryExpr) dependant;
                return ternary.getPred() == this;
            }
        }
        return false;
    }

    public final boolean recursivelyInjectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        getResolvedType();
        try {
            Scope.enter(this);
            mUnboxedAChild = false;
            for (int i = getChildren().size() - 1; i >= 0; i--) {
                Expr child = getChildren().get(i);
                child.recursivelyInjectSafeUnboxing(modelAnalyzer, model);
                mUnboxedAChild |= child.mUnboxedAChild;
            }
            if (mUnboxedAChild) {
                // reset our resolved type since it may be depending on children
                resetResolvedType();
                getResolvedType();
                mUnboxedAChild = false;
            }
            injectSafeUnboxing(modelAnalyzer, model);
            if (mUnboxedAChild) {
                // re calculate
                resetResolvedType();
                getResolvedType();
            }
        } finally {
            Scope.exit();
        }
        return mUnboxedAChild;
    }

    public Expr unwrapObservableField() {
        final RecursionTracker<ModelClass> recursionTracker = new RecursionTracker<>(recursed -> {
            if (recursed.isObservable()) {
                L.e(ErrorMessages.RECURSIVE_OBSERVABLE, recursed);
            } else {
                L.w("Observable field resolved into another observable, skipping resolution. %s", recursed);
            }
            return Unit.INSTANCE;
        });

        Expr expr = this;
        String simpleGetterName;
        while ((simpleGetterName = expr.getResolvedType().getObservableGetterName()) != null
                && recursionTracker.pushIfNew(expr.getResolvedType())) {
            Expr unwrapped = mModel.methodCall(expr, simpleGetterName, Collections.EMPTY_LIST);
            mModel.bindingExpr(unwrapped);
            unwrapped.setUnwrapObservableFields(false);
            expr = unwrapped;
        }
        return expr;
    }

    /**
     * Iterates through all children and expands all ObservableFields to call "get()" on them
     * instead.
     */
    protected void unwrapObservableFieldChildren() {
        for (int i = 0; i < mChildren.size(); i++) {
            unwrapChildTo(i, null);
        }
    }

    /**
     * Unwraps an observable field for a specific child.
     *
     * @param childIndex The index into mChildren of the child to unwrap
     * @param type The expected type or null if the child should be fully unwrapped.
     */
    protected void unwrapChildTo(int childIndex, @Nullable ModelClass type) {
        final RecursionTracker<ModelClass> recursionTracker = new RecursionTracker<>(recursed -> {
            if (recursed.isObservable()) {
                L.e(ErrorMessages.RECURSIVE_OBSERVABLE, this);
            } else {
                L.d("Recursed while resolving %s, will stop resolution.", recursed);
            }
            return Unit.INSTANCE;
        });
        final Expr child = mChildren.get(childIndex);
        Expr unwrapped = null;
        Expr expr = child;
        String simpleGetterName;
        while ((simpleGetterName = expr.getResolvedType().getObservableGetterName()) != null
                && recursionTracker.pushIfNew(expr.getResolvedType())
                && shouldUnwrap(type, expr.getResolvedType())) {
            unwrapped = mModel.methodCall(expr, simpleGetterName, Collections.EMPTY_LIST);
            unwrapped.setUnwrapObservableFields(false);
            expr = unwrapped;

        }
        if (unwrapped != null && unwrapped != this) {
            child.getParents().remove(this);
            unwrapped.getParents().add(this);
            mChildren.set(childIndex, unwrapped);
        }
    }

    private static boolean shouldUnwrap(ModelClass from, ModelClass to) {
        return (to == null || to.isObject() || !to.isAssignableFrom(from))
                && !ModelMethod.isImplicitConversion(from, to);
    }

    /**
     * Called after experiment model is sealed to avoid NPE problems caused by boxed primitives.
     */
    abstract protected void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model);

    static class Node {

        BitSet mBitSet = new BitSet();
        List<Node> mParents = new ArrayList<Node>();
        int mConditionFlag = -1;

        public boolean areAllPathsSatisfied(BitSet readSoFar) {
            if (mConditionFlag != -1) {
                return readSoFar.get(mConditionFlag)
                        || mParents.get(0).areAllPathsSatisfied(readSoFar);
            } else {
                final BitSet myBitsClone = (BitSet) mBitSet.clone();
                myBitsClone.andNot(readSoFar);
                if (!myBitsClone.isEmpty()) {
                    // read so far does not cover all of my invalidation. The only way I could be
                    // covered is that I only have 1 conditional dependent which is covered by this.
                    if (mParents.size() == 1 && mParents.get(0).mConditionFlag != -1) {
                        return mParents.get(0).areAllPathsSatisfied(readSoFar);
                    }
                    return false;
                }
                if (mParents.isEmpty()) {
                    return true;
                }
                for (Node parent : mParents) {
                    if (!parent.areAllPathsSatisfied(readSoFar)) {
                        return false;
                    }
                }
                return true;
            }
        }

        public void setConditionFlag(int requirementFlagIndex) {
            mConditionFlag = requirementFlagIndex;
        }
    }
}
