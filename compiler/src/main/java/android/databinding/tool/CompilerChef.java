/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool;

import android.databinding.annotationprocessor.BindableBag;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.ScopedException;
import android.databinding.tool.reflection.InjectedClass;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.MutableImportBag;
import android.databinding.tool.store.FeatureInfoList;
import android.databinding.tool.store.GenClassInfoLog;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.util.L;
import android.databinding.tool.writer.BindingMapperWriter;
import android.databinding.tool.writer.BindingMapperWriterV2;
import android.databinding.tool.writer.JavaFileWriter;
import android.databinding.tool.writer.MergedBindingMapperWriter;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.collect.Sets;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.jetbrains.annotations.NotNull;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Chef class for compiler.
 *
 * Different build systems can initiate a version of this to handle their work
 */
public class CompilerChef {
    private static final String[] VERSION_CODES = {
            "BASE",                 // 1
            "BASE_1_1",             // 2
            "CUPCAKE",              // 3
            "DONUT",                // 4
            "ECLAIR",               // 5
            "ECLAIRE_0_1",          // 6
            "ECLAIR_MR1",           // 7
            "FROYO",                // 8
            "GINGERBREAD",          // 9
            "GINGERBREAD_MR1",      // 10
            "HONEYCOMB",            // 11
            "HONEYCOMB_MR1",        // 12
            "HONEYCOMB_MR2",        // 13
            "ICE_CREAM_SANDWICH",   // 14
            "ICE_CREAM_SANDWICH_MR1",// 15
            "JELLY_BEAN",           // 16
            "JELLY_BEAN_MR1",       // 17
            "JELLY_BEAN_MR2",       // 18
            "KITKAT",               // 19
            "KITKAT_WATCH",         // 20
            "LOLLIPOP",             // 21
            "LOLLIPOP_MR1",         // 22
            "M",                    // 23
    };
    private JavaFileWriter mFileWriter;
    private ResourceBundle mResourceBundle;
    private DataBinder mDataBinder;
    private boolean mEnableV2;
    // the compiler chef we create for V1 dependencies
    @Nullable
    private CompilerChef mV1CompatChef;

    private CompilerChef() {
    }

    public void setV1CompatChef(CompilerChef v1CompatChef) {
        mV1CompatChef = v1CompatChef;
    }

    public static CompilerChef createChef(
            ResourceBundle bundle,
            JavaFileWriter fileWriter,
            CompilerArguments compilerArgs) {
        CompilerChef chef = new CompilerChef();

        chef.mResourceBundle = bundle;
        chef.mFileWriter = fileWriter;
        chef.mResourceBundle.validateAndRegisterErrors();
        chef.mEnableV2 = compilerArgs.isEnableV2();
        chef.pushClassesToAnalyzer();
        return chef;
    }

    @SuppressWarnings("unused")
    public ResourceBundle getResourceBundle() {
        return mResourceBundle;
    }

    public void ensureDataBinder() {
        if (mDataBinder == null) {
            LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
            mDataBinder = new DataBinder(mResourceBundle, mEnableV2, libTypes);
            mDataBinder.setFileWriter(mFileWriter);
        }
    }

    public boolean hasAnythingToGenerate() {
        L.d("checking if we have anything to generate. bundle size: %s",
                mResourceBundle == null ? -1 : mResourceBundle.getLayoutBundles().size());
        return mResourceBundle != null && mResourceBundle.getLayoutBundles().size() > 0;
    }

    /**
     * Injects ViewDataBinding subclasses to the ModelAnalyzer so that they can be
     * analyzed prior to creation. This is useful for resolving variable setters and
     * View fields during compilation.
     */
    private void pushClassesToAnalyzer() {
        ModelAnalyzer analyzer = ModelAnalyzer.getInstance();
        for (String layoutName : mResourceBundle.getLayoutBundles().keySet()) {
            final HashSet<String> variables = new HashSet();
            final HashSet<String> fields = new HashSet();

            List<ResourceBundle.LayoutFileBundle> bundles =
                    mResourceBundle.getLayoutBundles().get(layoutName);
            final String className = bundles.get(0).getBindingClassPackage() + "."
                    + bundles.get(0).getBindingClassName();
            // inject base class
            InjectedClass bindingClass =
                    new InjectedClass(className, analyzer.libTypes.getViewDataBinding());
            analyzer.injectClass(bindingClass);

            for (ResourceBundle.LayoutFileBundle layoutFileBundle : bundles) {
                final MutableImportBag imports = new MutableImportBag();
                for (ResourceBundle.NameTypeLocation imp : layoutFileBundle.getImports()) {
                    imports.put(imp.name, imp.type);
                }

                for (ResourceBundle.VariableDeclaration variable :
                        layoutFileBundle.getVariables()) {
                    if (variables.add(variable.name)) {
                        bindingClass.addVariable(variable.name, variable.type, imports);
                    }
                }

                for (ResourceBundle.BindingTargetBundle bindingTargetBundle :
                        layoutFileBundle.getBindingTargetBundles()) {
                    if (bindingTargetBundle.getId() != null) {
                        String fieldName = bindingTargetBundle.getId();
                        if (fields.add(fieldName)) {
                            String fieldType = bindingTargetBundle.getInterfaceType();
                            if (fieldType == null) {
                                fieldType = bindingTargetBundle.getFullClassName();
                            }
                            bindingClass.addField(fieldName, fieldType);
                        }
                    }
                }
                // inject implementation
                if (mEnableV2 || bundles.size() > 1) {
                    // Add the implementation class
                    final String implName =
                            layoutFileBundle.getBindingClassPackage() + "."
                            + layoutFileBundle.createImplClassNameWithConfig();
                    analyzer.injectClass(new InjectedClass(implName, className));
                }
            }
        }
    }

    public void writeDataBinderMapper(
            ProcessingEnvironment processingEnv,
            CompilerArguments compilerArgs,
            BindableBag.BRMapping brValueLookup,
            List<String> modulePackages) {
        if (compilerArgs.isEnableV2()) {
            // figure out which mappers exists as they may not exist for v1 libs.
            SortedSet<String> availableDependencyModules = getDirectDependencies(
                    processingEnv, compilerArgs, modulePackages);
            final boolean generateMapper;
            if (compilerArgs.isApp()) {
                // generate mapper for apps only if it is not test or enabled for tests.
                generateMapper = !compilerArgs.isTestVariant() || compilerArgs.isEnabledForTests();
            } else {
                // always generate mapper for libs or features
                generateMapper = true;
            }
            if (generateMapper) {
                writeMapperForModule(compilerArgs, brValueLookup, availableDependencyModules);
            }

            // merged mapper is the one generated for the whole app that includes the mappers
            // generated for individual modules.
            final boolean generateMergedMapper;
            if (compilerArgs.isApp()) {
                generateMergedMapper = !compilerArgs.isTestVariant();
            } else if (!compilerArgs.isFeature()) {
                generateMergedMapper = compilerArgs.isTestVariant();
            } else {
                generateMergedMapper = false;
            }
            if (generateMergedMapper) {
                if (mV1CompatChef != null) {
                    // only generate v1 compat if we are generating the merged
                    writeMapperForV1Compat(compilerArgs, brValueLookup);
                }
                writeMergedMapper(compilerArgs);
            }
        } else {
            final String pkg = ModelAnalyzer.getInstance().libTypes.getBindingPackage();
            final String mapperName = "DataBinderMapperImpl";

            ensureDataBinder();
            LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
            BindingMapperWriter dbr = new BindingMapperWriter(pkg, mapperName,
                    mDataBinder.getLayoutBinders(), compilerArgs, libTypes);
            mFileWriter.writeToFile(
                    pkg + "." + dbr.getClassName(),
                    dbr.write(brValueLookup));
        }
    }

    @NotNull
    private SortedSet<String> getDirectDependencies(
            ProcessingEnvironment processingEnv,
            CompilerArguments compilerArgs,
            List<String> modulePackages) {
        TreeSet<String> explicitPkgs = compilerArgs.parseDirectDependencyPackages();
        if (explicitPkgs != null) {
            // used the value specified by the compiler
            return Sets.newTreeSet(explicitPkgs);
        }
        return modulePackages.stream()
                .filter(modulePackage -> {
                    if (modulePackage.equals(compilerArgs.getModulePackage())) {
                        // I'm not a dependency
                        return false;
                    }
                    String mapper = BindingMapperWriterV2.createMapperQName(modulePackage);
                    TypeElement impl = processingEnv
                            .getElementUtils()
                            .getTypeElement(mapper);
                    return impl != null;
                }).distinct().collect(Collectors.toCollection(TreeSet::new));
    }

    public boolean useAndroidX() {
        return ModelAnalyzer.getInstance().libTypes.getUseAndroidX();
    }

    private void writeMapperForV1Compat(
            CompilerArguments compilerArgs,
            BindableBag.BRMapping brValueLookup) {
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        BindingMapperWriter dbr = new BindingMapperWriter(
                BindingMapperWriter.v1CompatMapperPkg(useAndroidX()),
                BindingMapperWriter.V1_COMPAT_MAPPER_NAME,
                mV1CompatChef.getLayoutBinders(),
                compilerArgs,
                libTypes);
        mFileWriter.writeToFile(
                BindingMapperWriter.v1CompatQName(useAndroidX()),
                dbr.write(brValueLookup));
    }

    public List<LayoutBinder> getLayoutBinders() {
        return mDataBinder.getLayoutBinders();
    }

    /**
     * Writes the mapper android.databinding.DataBinderMapperImpl which is a merged mapper
     * that includes all mappers from dependencies.
     */
    private void writeMergedMapper(CompilerArguments compilerArgs) {

        Set<String> featurePackageIds = loadFeaturePackageIds(compilerArgs);
        StringBuilder sb = new StringBuilder();
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        MergedBindingMapperWriter mergedBindingMapperWriter =
                new MergedBindingMapperWriter(
                        compilerArgs,
                        featurePackageIds,
                        mV1CompatChef != null,
                        libTypes);
        TypeSpec mergedMapperSpec = mergedBindingMapperWriter.write();
        try {
            JavaFile.builder(mergedBindingMapperWriter.getPkg(), mergedMapperSpec)
                    .build().writeTo(sb);
            mFileWriter.writeToFile(mergedBindingMapperWriter.getQualifiedName(),
                    sb.toString());
        } catch (IOException e) {
            Scope.defer(new ScopedException("cannot generate merged mapper class", e));
        }
    }

    @NonNull
    private Set<String> loadFeaturePackageIds(CompilerArguments compilerArgs) {
        if (compilerArgs.getBaseFeatureInfoDir() == null) {
            return Collections.emptySet();
        }
        File input = new File(compilerArgs.getBaseFeatureInfoDir(),
                DataBindingBuilder.FEATURE_PACKAGE_LIST_FILE_NAME);
        if (!input.exists()) {
            return Collections.emptySet();
        }
        return FeatureInfoList.fromFile(input).getPackages();
    }

    /**
     * Generates a mapper that knows only about the bindings in this module (excl dependencies).
     */
    private void writeMapperForModule(
            CompilerArguments compilerArgs,
            BindableBag.BRMapping brValueLookup,
            Set<String> availableDependencyModules) {
        GenClassInfoLog infoLog;
        try {
            infoLog = ResourceBundle.loadClassInfoFromFolder(compilerArgs.getClassLogDir());
        } catch (IOException e) {
            Scope.defer(new ScopedException("Cannot read class info log"));
            infoLog = new GenClassInfoLog();
        }
        GenClassInfoLog infoLogInThisModule = infoLog
                .createPackageInfoLog(compilerArgs.getModulePackage());
        LibTypes libTypes = ModelAnalyzer.getInstance().libTypes;
        BindingMapperWriterV2 v2 = new BindingMapperWriterV2(
                infoLogInThisModule,
                compilerArgs,
                libTypes,
                availableDependencyModules);
        TypeSpec spec = v2.write(brValueLookup);
        StringBuilder sb = new StringBuilder();
        try {
            JavaFile.builder(v2.getPkg(), spec).build().writeTo(sb);
            mFileWriter.writeToFile(v2.getQualifiedName(), sb.toString());
        } catch (IOException e) {
            Scope.defer(new ScopedException("cannot generate mapper class", e));
        }
    }

    /**
     * Adds variables to list of Bindables.
     */
    public void addBRVariables(BindableHolder bindables) {
        ensureDataBinder();
        for (LayoutBinder layoutBinder : mDataBinder.mLayoutBinders) {
            for (String variableName : layoutBinder.getUserDefinedVariables().keySet()) {
                bindables.addVariable(variableName, layoutBinder.getClassName());
            }
        }
    }

    public void sealModels() {
        ensureDataBinder();
        mDataBinder.sealModels();
    }

    public void writeViewBinderInterfaces(boolean isLibrary) {
        ensureDataBinder();
        mDataBinder.writerBaseClasses(isLibrary);
    }

    public void writeViewBinders(int minSdk) {
        ensureDataBinder();
        mDataBinder.writeBinders(minSdk);
    }

    public void writeComponent() {
        ensureDataBinder();
        mDataBinder.writeComponent();
    }

    public Set<String> getClassesToBeStripped() {
        ensureDataBinder();
        return mDataBinder.getClassesToBeStripped();
    }

    public interface BindableHolder {
        void addVariable(String variableName, String containingClassName);
    }
}
