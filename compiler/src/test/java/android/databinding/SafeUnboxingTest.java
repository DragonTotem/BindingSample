/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.databinding;

import android.databinding.tool.CompilerChef;
import android.databinding.tool.LayoutBinder;
import android.databinding.tool.MockLayoutBinder;
import android.databinding.tool.expr.BitShiftExpr;
import android.databinding.tool.expr.BracketExpr;
import android.databinding.tool.expr.CastExpr;
import android.databinding.tool.expr.ComparisonExpr;
import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.expr.MathExpr;
import android.databinding.tool.expr.MethodCallExpr;
import android.databinding.tool.expr.TernaryExpr;
import android.databinding.tool.expr.UnaryExpr;
import android.databinding.tool.reflection.InjectedClass;
import android.databinding.tool.reflection.InjectedMethod;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.java.JavaAnalyzer;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Map;

@org.junit.Ignore("b/175036746")
public class SafeUnboxingTest {
    ExprModel mExprModel;
    LayoutBinder mLayoutBinder;

    @Before
    public void setUp() {
        JavaAnalyzer.initForTests();
        mLayoutBinder = new MockLayoutBinder();
        mExprModel = mLayoutBinder.getModel();
        createFakeViewDataBinding();
    }

    private void createFakeViewDataBinding() {
        InjectedClass injectedClass = new InjectedClass("androidx.databinding.ViewDataBinding",
                "java.lang.Object");
        for (Map.Entry<? extends Class<?>, Class<?>> entry : ModelClass.BOX_MAPPING.entrySet()) {
            injectedClass.addMethod(new InjectedMethod(injectedClass, true,
                    ExprModel.SAFE_UNBOX_METHOD_NAME, null, entry.getKey().getCanonicalName(),
                    entry.getValue().getCanonicalName()));
        }

        ModelAnalyzer analyzer = ModelAnalyzer.getInstance();
        analyzer.injectClass(injectedClass);
    }

    @Test
    public void testBitShift() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, false);
        BitShiftExpr shift = parse("a << 3");
        Expr originalLeft = shift.getLeft();
        Expr originalRight = shift.getRight();
        mExprModel.seal();
        assertSingleCast(shift, shift.getLeft(), originalLeft, "int", "int");
        assertThat(shift.getRight(), is(originalRight));
    }

    @Test
    public void testBitShiftRight() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, false);
        BitShiftExpr shift = parse("1 << a");
        Expr originalLeft = shift.getLeft();
        Expr originalRight = shift.getRight();
        mExprModel.seal();
        assertSingleCast(shift, shift.getRight(), originalRight, "int", "int");
        assertThat(shift.getLeft(), is(originalLeft));
    }

    @Test
    public void testBitShiftNoCast() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, false);
        BitShiftExpr shift = parse("a << 3");
        Expr originalLeft = shift.getLeft();
        mExprModel.seal();
        assertThat(shift.getLeft(), is(originalLeft));
    }

    @Test
    public void testBracket() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("arr", "char[]", null, false);
        BracketExpr bracket = parse("arr[a]");
        Expr originalArg = bracket.getArg();
        mExprModel.seal();
        assertSingleCast(bracket, bracket.getArg(), originalArg, "char", "int");
    }

    @Test
    public void testBracketNoCast() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("arr", "char[]", null, false);
        BracketExpr bracket = parse("arr[a]");
        Expr originalArg = bracket.getArg();
        mExprModel.seal();
        assertThat(bracket.getArg(), is(originalArg));
    }

    @Test
    public void testBracketNoCastString() {
        mLayoutBinder.addVariable("a", String.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("arr", "java.util.Map", null, false);
        BracketExpr bracket = parse("arr[a]");
        Expr originalArg = bracket.getArg();
        mExprModel.seal();
        assertThat(bracket.getArg(), is(originalArg));
    }

    @Test
    public void testCastExpr() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        CastExpr castExpr = parse("(char)a");
        Expr original = castExpr.getCastExpr();
        mExprModel.seal();
        assertSingleCast(castExpr, castExpr.getCastExpr(), original, "char", "int");
    }

    @Test
    public void testCastExprNoCast() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        CastExpr castExpr = parse("(java.lang.Character)a");
        Expr original = castExpr.getCastExpr();
        mExprModel.seal();
        assertThat(castExpr.getCastExpr(), is(original));
    }

    @Test
    public void testComparison() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a > b");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertSingleCast(comparison, comparison.getLeft(), left, "boolean", "int");
        assertThat(comparison.getRight(), is(right));
    }

    @Test
    public void testComparisonNoCast() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a > b");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertThat(comparison.getLeft(), is(left));
        assertThat(comparison.getRight(), is(right));
    }

    @Test
    public void testComparisonCastBoth() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Integer.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a > b");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertSingleCast(comparison, comparison.getLeft(), left, "boolean", "int");
        assertSingleCast(comparison, comparison.getRight(), right, "boolean", "int");
    }

    @Test
    public void testComparisonNoCast2() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Integer.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a == b");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertThat(comparison.getLeft(), is(left));
        assertThat(comparison.getRight(), is(right));
    }

    @Test
    public void testComparisonNoCast3() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a == null");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertThat(comparison.getLeft(), is(left));
        assertThat(comparison.getRight(), is(right));
    }

    @Test
    public void testComparisonCastForPrimitive() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        ComparisonExpr comparison = parse("a == 5");
        Expr left = comparison.getLeft();
        Expr right = comparison.getRight();
        mExprModel.seal();
        assertSingleCast(comparison, comparison.getLeft(), left, "boolean", "int");
        assertThat(comparison.getRight(), is(right));
    }

    @Test
    public void testMathExpr() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a + 3");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertSingleCast(mathExpr, mathExpr.getLeft(), left, "int", "int");
        assertThat(mathExpr.getRight(), is(right));
    }

    @Test
    public void testMathExpr2() {
        mLayoutBinder.addVariable("a", float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Float.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a / b");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertThat(mathExpr.getLeft(), is(left));
        assertSingleCast(mathExpr, mathExpr.getRight(), right, "float", "float");
    }

    @Test
    public void testMathExpr3() {
        mLayoutBinder.addVariable("a", Character.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Character.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a / b");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertSingleCast(mathExpr, mathExpr.getLeft(), left, "char", "char");
        assertSingleCast(mathExpr, mathExpr.getRight(), right, "char", "char");
    }

    @Test
    public void testMathExprNoCast() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", String.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a + b");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertThat(mathExpr.getLeft(), is(left));
        assertThat(mathExpr.getRight(), is(right));
    }

    @Test
    public void testMathExprNoCast2() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", String.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a + b");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertThat(mathExpr.getLeft(), is(left));
        assertThat(mathExpr.getRight(), is(right));
    }

    @Test
    public void testMathExprNoCast3() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        MathExpr mathExpr = parse("a + b");
        Expr left = mathExpr.getLeft();
        Expr right = mathExpr.getRight();
        mExprModel.seal();
        assertThat(mathExpr.getLeft(), is(left));
        assertThat(mathExpr.getRight(), is(right));
    }

    @Test
    public void testMethodCall() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("foo", "com.bar.Foo", null, true);
        InjectedClass injectedClass = new InjectedClass("com.bar.Foo", "java.lang.Object");
        InjectedMethod injectedMethod = new InjectedMethod(injectedClass, false, "doIt", null,
                "boolean", "int", "float");
        injectedClass.addMethod(injectedMethod);
        ModelAnalyzer.getInstance().injectClass(injectedClass);
        MethodCallExpr methodCallExpr = parse("foo.doIt(a, b)");
        Expr arg0 = methodCallExpr.getArgs().get(0);
        Expr arg1 = methodCallExpr.getArgs().get(1);
        mExprModel.seal();
        assertSingleCast(methodCallExpr, methodCallExpr.getArgs().get(0), arg0, "boolean", "int");
        assertSingleCast(methodCallExpr, methodCallExpr.getArgs().get(1), arg1, "boolean", "float");
    }

    @Test
    public void testMethodCall2() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("foo", "com.bar.Foo", null, true);
        InjectedClass injectedClass = new InjectedClass("com.bar.Foo", "java.lang.Object");
        InjectedMethod injectedMethod = new InjectedMethod(injectedClass, false, "doIt", null,
                "boolean", "int", "float");
        injectedClass.addMethod(injectedMethod);
        ModelAnalyzer.getInstance().injectClass(injectedClass);
        MethodCallExpr methodCallExpr = parse("foo.doIt(a, b)");
        Expr arg0 = methodCallExpr.getArgs().get(0);
        Expr arg1 = methodCallExpr.getArgs().get(1);
        mExprModel.seal();
        assertSingleCast(methodCallExpr, methodCallExpr.getArgs().get(0), arg0, "boolean", "int");
        assertThat(methodCallExpr.getArgs().get(1), is(arg1));
    }

    @Test
    public void testMethodCall3() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("foo", "com.bar.Foo", null, true);
        InjectedClass injectedClass = new InjectedClass("com.bar.Foo", "java.lang.Object");
        InjectedMethod injectedMethod = new InjectedMethod(injectedClass, false, "doIt", null,
                "boolean", "int", "float");
        injectedClass.addMethod(injectedMethod);
        ModelAnalyzer.getInstance().injectClass(injectedClass);
        MethodCallExpr methodCallExpr = parse("foo.doIt(a, b)");
        Expr arg0 = methodCallExpr.getArgs().get(0);
        Expr arg1 = methodCallExpr.getArgs().get(1);
        mExprModel.seal();
        assertThat(methodCallExpr.getArgs().get(0), is(arg0));
        assertSingleCast(methodCallExpr, methodCallExpr.getArgs().get(1), arg1, "boolean", "float");
    }

    @Test
    public void testMethodCallNoCast() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("foo", "com.bar.Foo", null, true);
        InjectedClass injectedClass = new InjectedClass("com.bar.Foo", "java.lang.Object");
        InjectedMethod injectedMethod = new InjectedMethod(injectedClass, false, "doIt", null,
                "boolean", "int", "float");
        injectedClass.addMethod(injectedMethod);
        ModelAnalyzer.getInstance().injectClass(injectedClass);
        MethodCallExpr methodCallExpr = parse("foo.doIt(a, b)");
        Expr arg0 = methodCallExpr.getArgs().get(0);
        Expr arg1 = methodCallExpr.getArgs().get(1);
        mExprModel.seal();
        assertThat(methodCallExpr.getArgs().get(0), is(arg0));
        assertThat(methodCallExpr.getArgs().get(1), is(arg1));
    }

    @Test
    public void testTernary() {
        mLayoutBinder.addVariable("a", Boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", Boolean.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertSingleCast(ternaryExpr, ternaryExpr.getPred(), pred, "java.lang.Boolean", "boolean");
        assertThat(ternaryExpr.getIfTrue(), is(ifTrue));
        assertThat(ternaryExpr.getIfFalse(), is(ifFalse));
    }

    @Test
    public void testTernary2() {
        mLayoutBinder.addVariable("a", Boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", Integer.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertSingleCast(ternaryExpr, ternaryExpr.getPred(), pred, "int", "boolean");
        assertThat(ternaryExpr.getIfTrue(), is(ifTrue));
        assertSingleCast(ternaryExpr, ternaryExpr.getIfFalse(), ifFalse, "int", "int");
    }

    @Test
    public void testTernary3() {
        mLayoutBinder.addVariable("a", Boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", float.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertSingleCast(ternaryExpr, ternaryExpr.getPred(), pred, "float", "boolean");
        assertSingleCast(ternaryExpr, ternaryExpr.getIfTrue(), ifTrue, "float", "float");
        assertThat(ternaryExpr.getIfFalse(), is(ifFalse));
    }

    @Test
    public void testTernary4() {
        mLayoutBinder.addVariable("a", boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Float.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", float.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertThat(ternaryExpr.getPred(), is(pred));
        assertSingleCast(ternaryExpr, ternaryExpr.getIfTrue(), ifTrue, "float", "float");
        assertThat(ternaryExpr.getIfFalse(), is(ifFalse));
    }

    @Test
    public void testTernary5() {
        mLayoutBinder.addVariable("a", boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", Integer.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertThat(ternaryExpr.getPred(), is(pred));
        assertThat(ternaryExpr.getIfTrue(), is(ifTrue));
        assertSingleCast(ternaryExpr, ternaryExpr.getIfFalse(), ifFalse, "int", "int");
    }

    @Test
    public void testTernaryNoCast() {
        mLayoutBinder.addVariable("a", boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", int.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", int.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertThat(ternaryExpr.getPred(), is(pred));
        assertThat(ternaryExpr.getIfTrue(), is(ifTrue));
        assertThat(ternaryExpr.getIfFalse(), is(ifFalse));
    }

    @Test
    public void testTernaryNoCast2() {
        mLayoutBinder.addVariable("a", boolean.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("b", Integer.class.getCanonicalName(), null, true);
        mLayoutBinder.addVariable("c", Integer.class.getCanonicalName(), null, true);
        TernaryExpr ternaryExpr = parse("a ? b : c");
        Expr pred = ternaryExpr.getPred();
        Expr ifTrue = ternaryExpr.getIfTrue();
        Expr ifFalse = ternaryExpr.getIfFalse();
        mExprModel.seal();
        assertThat(ternaryExpr.getPred(), is(pred));
        assertThat(ternaryExpr.getIfTrue(), is(ifTrue));
        assertThat(ternaryExpr.getIfFalse(), is(ifFalse));
    }

    @Test
    public void testUnary() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        UnaryExpr unaryExpr = parse("~a");
        Expr expr = unaryExpr.getExpr();
        mExprModel.seal();
        assertSingleCast(unaryExpr, unaryExpr.getExpr(), expr, "int", "int");
    }

    @Test
    public void testUnaryNoCast() {
        mLayoutBinder.addVariable("a", int.class.getCanonicalName(), null, true);
        UnaryExpr unaryExpr = parse("~a");
        Expr expr = unaryExpr.getExpr();
        mExprModel.seal();
        assertThat(unaryExpr.getExpr(), is(expr));
    }

    @Test
    public void testSafeUnboxSubChild() {
        mLayoutBinder.addVariable("a", Integer.class.getCanonicalName(), null, true);
        MathExpr expr = parse("3 + ( 5 / a)");
        Expr right = expr.getRight();
        assertThat(right, instanceOf(MathExpr.class));
        MathExpr mathExp = (MathExpr) right;
        Expr original = mathExp.getRight();
        mExprModel.seal();
        assertSingleCast(mathExp, mathExp.getRight(), original, "int", "int");
    }

    private void assertSingleCast(Expr owner, Expr replacement, Expr original, String ownerReturn,
                                  String replacementReturn) {
        assertThat(owner.getResolvedType().getCanonicalName(), is(ownerReturn));
        assertThat(replacement, instanceOf(MethodCallExpr.class));
        MethodCallExpr methodCallExpr = (MethodCallExpr) replacement;
        assertThat(methodCallExpr.getResolvedType().getCanonicalName(), is(replacementReturn));
        assertThat(methodCallExpr.getArgs().get(0), is(original));
        assertThat(methodCallExpr.getArgs().size(), is(1));
    }

    private <T extends Expr> T parse(String input) {
        final Expr parsed = mLayoutBinder.parse(input, null, null);
        return (T) parsed;
    }
}
