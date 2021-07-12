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
import android.databinding.tool.CompilerChef;
import android.databinding.tool.Context;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.ScopedException;
import android.databinding.tool.store.GenClassInfoLog;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.AnnotationJavaFileWriter;
import android.databinding.tool.writer.JavaFileWriter;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.util.HashSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.xml.bind.JAXBException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parent annotation processor that dispatches sub steps to ensure execution order.
 * Use initProcessingSteps to add a new step.
 *
 * NOTE: For incremental annotation processing to work correctly, the following list of supported
 * annotation types needs to be exhaustive.
 */
@SupportedAnnotationTypes({
        "androidx.databinding.Bindable",
        "androidx.databinding.BindingAdapter",
        "androidx.databinding.BindingBuildInfo",
        "androidx.databinding.BindingConversion",
        "androidx.databinding.BindingMethod",
        "androidx.databinding.BindingMethods",
        "androidx.databinding.InverseBindingAdapter",
        "androidx.databinding.InverseBindingMethod",
        "androidx.databinding.InverseBindingMethods",
        "androidx.databinding.InverseMethod",
        "androidx.databinding.Untaggable",
        "android.databinding.Bindable",
        "android.databinding.BindingAdapter",
        "android.databinding.BindingBuildInfo",
        "android.databinding.BindingConversion",
        "android.databinding.BindingMethod",
        "android.databinding.BindingMethods",
        "android.databinding.InverseBindingAdapter",
        "android.databinding.InverseBindingMethod",
        "android.databinding.InverseBindingMethods",
        "android.databinding.InverseMethod",
        "android.databinding.Untaggable"
})
public class ProcessDataBinding extends AbstractProcessor {

    private static final String AGGREGATING_ANNOTATION_PROCESSORS_INDICATOR =
        "org.gradle.annotation.processing.aggregating";

    private List<ProcessingStep> mProcessingSteps;
    private CompilerArguments mCompilerArgs;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            return doProcess(roundEnv);
        } finally {
            if (roundEnv.processingOver()) {
                Context.fullClear(processingEnv);
            }
        }
    }

    private boolean doProcess(RoundEnvironment roundEnv) {
            if (mProcessingSteps == null) {
            readArguments();
            initProcessingSteps(processingEnv);
        }
        if (mCompilerArgs == null) {
            return false;
        }
        if (mCompilerArgs.isTestVariant() && !mCompilerArgs.isEnabledForTests() &&
                !mCompilerArgs.isLibrary()) {
            L.d("data binding processor is invoked but not enabled, skipping...");
            return false;
        }
        boolean done = true;
        Context.init(processingEnv, mCompilerArgs);
        for (ProcessingStep step : mProcessingSteps) {
            try {
                done = step.runStep(roundEnv, processingEnv, mCompilerArgs) && done;
            } catch (JAXBException e) {
                L.e(e, "Exception while handling step %s", step);
            }
        }
        if (roundEnv.processingOver()) {
            for (ProcessingStep step : mProcessingSteps) {
                step.onProcessingOver(roundEnv, processingEnv, mCompilerArgs);
            }
        }
        if (roundEnv.processingOver()) {
            Scope.assertNoError();
        }
        return done;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private void initProcessingSteps(ProcessingEnvironment processingEnv) {
        final ProcessBindable processBindable = new ProcessBindable();
        mProcessingSteps = Arrays.asList(
                new ProcessMethodAdapters(),
                new ProcessExpressions(),
                processBindable
        );
        Callback dataBinderWriterCallback = new Callback() {
            CompilerChef mChef;
            List<String> mModulePackages;
            BindableBag.BRMapping mBRVariableLookup;
            boolean mWrittenMapper = false;

            @Override
            public void onChefReady(
                    @NonNull CompilerChef chef,
                    @Nullable GenClassInfoLog classInfoLog) {
                Preconditions.checkNull(mChef, "Cannot set compiler chef twice");
                chef.addBRVariables(processBindable);
                mChef = chef;
                considerWritingMapper();
            }

            private void considerWritingMapper() {
                if (mWrittenMapper || mChef == null || mBRVariableLookup == null) {
                    return;
                }
                boolean justLibrary = mCompilerArgs.isLibrary()
                        && !mCompilerArgs.isTestVariant();
                if (justLibrary && !mCompilerArgs.isEnableV2()) {
                    return;
                }
                mWrittenMapper = true;
                mChef.writeDataBinderMapper(processingEnv, mCompilerArgs, mBRVariableLookup,
                        mModulePackages);
            }

            @Override
            public void onBrWriterReady(BindableBag.BRMapping brWithValues, List<String> brPackages) {
                Preconditions.checkNull(mBRVariableLookup, "Cannot set br writer twice");
                mBRVariableLookup = brWithValues;
                mModulePackages = brPackages;
                considerWritingMapper();
            }
        };
        AnnotationJavaFileWriter javaFileWriter = new AnnotationJavaFileWriter(processingEnv);
        for (ProcessingStep step : mProcessingSteps) {
            step.mJavaFileWriter = javaFileWriter;
            step.mCallback = dataBinderWriterCallback;
        }
    }

    /**
     * use this instead of init method so that we won't become a problem when data binding happens
     * to be in annotation processor classpath by chance
     */
    private synchronized void readArguments() {
        if (mCompilerArgs != null) {
            return;
        }

        try {
            mCompilerArgs = CompilerArguments.readFromOptions(processingEnv.getOptions());
            L.setDebugLog(mCompilerArgs.getEnableDebugLogs());
            L.d("processor args: %s", mCompilerArgs);
            ScopedException.encodeOutput(mCompilerArgs.getPrintEncodedErrorLogs());
        } catch (Throwable t) {
            String allParam = processingEnv.getOptions().entrySet().stream().map(
                    (entry) -> entry.getKey() + " : " + entry.getValue())
                    .collect(Collectors.joining("\n"));
            throw new RuntimeException("Failed to parse data binding compiler options. Params:\n"
                    + allParam, t);
        }
    }

    @Override
    public Set<String> getSupportedOptions() {
        Set<String> supportedOptions = new HashSet<>(CompilerArguments.ALL_PARAMS);

        // In addition to the regular supported options above, we also need to add an option to tell
        // Gradle that this is an aggregating annotation processor (if the incremental flag is
        // enabled).
        // Note that when this method is called, we don't know whether data binding will actually be
        // invoked later or not. Since the data binding options may not exist in the processing
        // environment, we need to check whether the options exist first.
        if (processingEnv.getOptions().containsKey(CompilerArguments.PARAM_INCREMENTAL)) {
            readArguments();
            if (mCompilerArgs.getIncremental()) {
                supportedOptions.add(AGGREGATING_ANNOTATION_PROCESSORS_INDICATOR);
            }
        }

        return supportedOptions;
    }

    /**
     * To ensure execution order and binding build information, we use processing steps.
     */
    public abstract static class ProcessingStep {
        private boolean mDone;
        private JavaFileWriter mJavaFileWriter;
        Callback mCallback;

        protected JavaFileWriter getWriter() {
            return mJavaFileWriter;
        }

        private boolean runStep(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                CompilerArguments args) throws JAXBException {
            if (mDone) {
                return true;
            }
            mDone = onHandleStep(roundEnvironment, processingEnvironment, args);
            return mDone;
        }

        /**
         * Invoked in each annotation processing step.
         *
         * @return True if it is done and should never be invoked again.
         */
        abstract public boolean onHandleStep(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                CompilerArguments args) throws JAXBException;

        /**
         * Invoked when processing is done. A good place to generate the output if the
         * processor requires multiple steps.
         */
        abstract public void onProcessingOver(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                CompilerArguments args);
    }

    interface Callback {
        void onChefReady(CompilerChef chef, GenClassInfoLog classInfoLog);
        void onBrWriterReady(BindableBag.BRMapping brWithValues, List<String> brPackages);
    }
}
