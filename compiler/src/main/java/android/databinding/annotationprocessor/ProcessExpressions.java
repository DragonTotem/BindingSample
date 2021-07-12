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

import android.databinding.tool.CompilerChef;
import android.databinding.tool.CompilerArguments;
import android.databinding.tool.LayoutXmlProcessor;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.ScopedException;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.store.GenClassInfoLog;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.util.GenerationalClassUtil;
import android.databinding.tool.util.L;
import android.databinding.tool.util.LoggedErrorException;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.util.StringUtils;
import android.databinding.tool.writer.BindingMapperWriter;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.base.Joiner;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.xml.bind.JAXBException;

public class ProcessExpressions extends ProcessDataBinding.ProcessingStep {
    public ProcessExpressions() {
    }

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, CompilerArguments args)
            throws JAXBException {
        try {
            ResourceBundle resourceBundle;
            resourceBundle = new ResourceBundle(
                    args.getModulePackage(),
                    ModelAnalyzer.getInstance().libTypes.getUseAndroidX());
            L.d("creating resource bundle for %s", args.getModulePackage());
            final List<IntermediateV2> intermediateList;
            GenClassInfoLog infoLog = null;
            @Nullable
            CompilerChef v1CompatChef = null;
            if (args.isEnableV2()) {
                try {
                    L.d("trying to read class log from %s", args.getClassLogDir());
                    infoLog = ResourceBundle.loadClassInfoFromFolder(args.getClassLogDir());
                    L.d("done reading class log. cools.");
                } catch (IOException e) {
                    L.d(e,"failed to read class log :/");
                    infoLog = new GenClassInfoLog();
                    Scope.defer(new ScopedException("cannot load the info log from %s",
                            args.getClassLogDir()));
                }
                resourceBundle.addDependencyLayouts(infoLog);
                intermediateList = Collections.emptyList();
                v1CompatChef = new ProcessExpressionsFromV1Compat(
                        processingEnvironment,
                        args,
                        loadDependencyIntermediates(),
                        getWriter()
                ).generate();
            } else {
                intermediateList = loadDependencyIntermediates();
                for (Intermediate intermediate : intermediateList) {
                    try {
                        try {
                            intermediate.appendTo(resourceBundle, false);
                        } catch (Throwable throwable) {
                            L.e(throwable, "unable to prepare resource bundle");
                        }
                    } catch (LoggedErrorException e) {
                        // This will be logged later
                    }
                }
            }
            IntermediateV2 mine = createIntermediateFromLayouts(args.getLayoutInfoDir(),
                    intermediateList);
            if (mine != null) {
                if (!args.isEnableV2()) {
                    mine.updateOverridden(resourceBundle);
                    intermediateList.add(mine);
                    saveIntermediate(args, mine);
                }
                mine.appendTo(resourceBundle, true);
            }
            // generate them here so that bindable parser can read
            try {
                writeResourceBundle(resourceBundle, args, infoLog, v1CompatChef);
            } catch (Throwable t) {
                L.e(t, "cannot generate view binders");
            }
        } catch (LoggedErrorException e) {
            // This will be logged later
        }
        return true;
    }

    private List<IntermediateV2> loadDependencyIntermediates() {
        final List<Intermediate> original = GenerationalClassUtil.get().load(
                GenerationalClassUtil.ExtensionFilter.LAYOUT, Intermediate.class);
        final List<IntermediateV2> upgraded = new ArrayList<>(original.size());
        for (Intermediate intermediate : original) {
            final Intermediate updatedIntermediate = intermediate.upgrade();
            Preconditions.check(updatedIntermediate instanceof IntermediateV2, "Incompatible data"
                    + " binding dependency. Please update your dependencies or recompile them with"
                    + " application module's data binding version.");
            //noinspection ConstantConditions
            upgraded.add((IntermediateV2) updatedIntermediate);
        }
        return upgraded;
    }

    private void saveIntermediate(CompilerArguments args, IntermediateV2 intermediate) {
        GenerationalClassUtil.get().write(args.getModulePackage(),
                        GenerationalClassUtil.ExtensionFilter.LAYOUT, intermediate);
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, CompilerArguments args) {
    }

    private IntermediateV2 createIntermediateFromLayouts(File layoutInfoDir,
            List<IntermediateV2> intermediateList) {
        L.d("creating intermediate list from input layouts of %s", layoutInfoDir);
        final Set<String> excludeList = new HashSet<String>();
        for (IntermediateV2 lib : intermediateList) {
            excludeList.addAll(lib.mLayoutInfoMap.keySet());
        }
        IntermediateV2 result = new IntermediateV2();
        if (!layoutInfoDir.isDirectory()) {
            // it is a zip in blaze / bazel.
            L.d("trying to load layout info from zip file");
            if (layoutInfoDir.exists()) {
                L.d("found zip file %s", layoutInfoDir);
                try {
                    loadLayoutInfoFromZipFile(layoutInfoDir, result, excludeList);
                    L.d("done loading from zip file");
                } catch (IOException e) {
                    L.e(e, "error while trying to load layout info from %s", layoutInfoDir);
                }
            } else {
                L.d("layout info folder does not exist, skipping for %s", layoutInfoDir.getPath());
            };
        } else {
            // it is a directory, search sub folders.
            for (File layoutFile : FileUtils.listFiles(layoutInfoDir, new String[]{"xml"}, true)) {
                if (excludeList.contains(layoutFile.getName())) {
                    continue;
                }
                L.d("found xml file %s", layoutFile.getAbsolutePath());
                try {
                    result.addEntry(layoutFile.getName(), FileUtils.readFileToString(layoutFile));
                } catch (IOException e) {
                    L.e(e, "cannot load layout file information. Try a clean build");
                }
            }
            // also accept zip files
            for (File zipFile : FileUtils.listFiles(layoutInfoDir, new String[]{"zip"}, true)) {
                try {
                    L.d("found zip file %s", zipFile.getAbsolutePath());
                    loadLayoutInfoFromZipFile(zipFile, result, excludeList);
                } catch (IOException e) {
                    L.e(e, "error while reading layout zip file %s", zipFile);
                }
            }
        }

        L.d("done loading info files");
        return result;
    }

    private void loadLayoutInfoFromZipFile(File zipFile, IntermediateV2 result,
            Set<String> excludeList) throws IOException {
        ZipFile zf = new ZipFile(zipFile);
        L.d("checking zip file %s", zipFile);
        final Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            L.d("checking entry %s", entry.getName());
            if (excludeList.contains(entry.getName())) {
                L.d("exclude entry %s", entry.getName());
                continue;
            }
            L.d("use entry %s", entry.getName());
            try {
                result.addEntry(entry.getName(), IOUtils.toString(zf.getInputStream(entry),
                        Charsets.UTF_8));
                L.d("loaded entry %s", entry.getName());
            } catch (IOException e) {
                L.e(e, "cannot load layout file information. Try a clean build");
            }
        }
        L.d("done loading zip file %s", zipFile);

    }

    private void writeResourceBundle(
            ResourceBundle resourceBundle,
            CompilerArguments compilerArgs,
            @Nullable GenClassInfoLog classInfoLog,
            @NonNull CompilerChef v1CompatChef) {
        final CompilerChef compilerChef = CompilerChef.createChef(resourceBundle,
                getWriter(), compilerArgs);
        compilerChef.setV1CompatChef(v1CompatChef);
        compilerChef.sealModels();
        // write this only if we are compiling an app or a library test app.
        // even if data binding is enabled for tests, we should not re-generate this.
        if (compilerArgs.isLibrary()
                || (!compilerArgs.isTestVariant() && !compilerArgs.isFeature())) {
            compilerChef.writeComponent();
        }
        if (compilerChef.hasAnythingToGenerate()) {
            if (!compilerArgs.isEnableV2()) {
                compilerChef.writeViewBinderInterfaces(compilerArgs.isLibrary()
                        && !compilerArgs.isTestVariant());
            }
            if (compilerArgs.isApp() != compilerArgs.isTestVariant()
                    || (compilerArgs.isEnabledForTests() && !compilerArgs.isLibrary())
                    || compilerArgs.isEnableV2()) {
                compilerChef.writeViewBinders(compilerArgs.getMinApi());
            }
        }
        if (compilerArgs.isLibrary() && !compilerArgs.isTestVariant() &&
                compilerArgs.getExportClassListOutFile() == null) {
            L.e("When compiling a library module, build info must include exportClassListTo path");
        }
        if (compilerArgs.isLibrary() && !compilerArgs.isTestVariant()) {
            Set<String> classNames = compilerChef.getClassesToBeStripped();
            if (v1CompatChef != null) {
                classNames.addAll(v1CompatChef.getClassesToBeStripped());
                classNames.add(BindingMapperWriter.v1CompatMapperPkg(compilerChef.useAndroidX()));
            }
            String out = Joiner.on(StringUtils.LINE_SEPARATOR).join(classNames);
            L.d("Writing list of classes to %s . \nList:%s",
                    compilerArgs.getExportClassListOutFile(), out);
            try {
                //noinspection ConstantConditions
                FileUtils.write(compilerArgs.getExportClassListOutFile(), out);
            } catch (IOException e) {
                L.e(e, "Cannot create list of written classes");
            }
        }
        mCallback.onChefReady(compilerChef, classInfoLog);
    }

    public interface Intermediate extends Serializable {

        Intermediate upgrade();

        void appendTo(ResourceBundle resourceBundle, boolean fromSource) throws Throwable;
    }

    public static class IntermediateV1 implements Intermediate {

        // name to xml content map
        Map<String, String> mLayoutInfoMap = new HashMap<String, String>();

        @Override
        public Intermediate upgrade() {
            final IntermediateV2 updated = new IntermediateV2();
            updated.mLayoutInfoMap = mLayoutInfoMap;
            return updated;
        }

        @Override
        public void appendTo(ResourceBundle resourceBundle, boolean fromSource) throws
                JAXBException {
            extractBundles().forEach(layoutFileBundle -> {
                resourceBundle.addLayoutBundle(layoutFileBundle, fromSource);
            });
        }

        public List<ResourceBundle.LayoutFileBundle> extractBundles() throws JAXBException {
            List<ResourceBundle.LayoutFileBundle> bundles = new ArrayList<>();
            for (String content : mLayoutInfoMap.values()) {
                final InputStream is = IOUtils.toInputStream(content);
                try {
                    final ResourceBundle.LayoutFileBundle bundle = ResourceBundle.LayoutFileBundle
                            .fromXML(is);
                    bundles.add(bundle);
                    L.d("loaded layout info file %s", bundle);
                } finally {
                    IOUtils.closeQuietly(is);
                }
            }
            return bundles;
        }

        public void addEntry(String name, String contents) {
            mLayoutInfoMap.put(name, contents);
        }

        // keeping the method to match deserialized structure
        @SuppressWarnings("unused")
        public void removeOverridden(List<Intermediate> existing) {
        }
    }

    public static class IntermediateV2 extends IntermediateV1 {
        // specify so that we can define updates ourselves.
        private static final long serialVersionUID = 2L;
        @Override
        public void appendTo(ResourceBundle resourceBundle, boolean fromSource) throws JAXBException {
            for (Map.Entry<String, String> entry : mLayoutInfoMap.entrySet()) {
                final InputStream is = IOUtils.toInputStream(entry.getValue());
                try {
                    final ResourceBundle.LayoutFileBundle bundle = ResourceBundle.LayoutFileBundle
                            .fromXML(is);
                    resourceBundle.addLayoutBundle(bundle, fromSource);
                    L.d("loaded layout info file %s", bundle);
                } finally {
                    IOUtils.closeQuietly(is);
                }
            }
        }

        /**
         * if a layout is overridden from a module (which happens when layout is auto-generated),
         * we need to update its contents from the class that overrides it.
         * This must be done before this bundle is saved, otherwise, it will not be recognized
         * when it is used in another project.
         */
        public void updateOverridden(ResourceBundle bundle) throws JAXBException {
            // When a layout is copied from inherited module, it is eleminated while reading
            // info files. (createIntermediateFromLayouts).
            // Build process may also duplicate some files at compile time. This is where
            // we detect those copies and force inherit their module and classname information.
            final Map<String, List<ResourceBundle.LayoutFileBundle>> bundles = bundle
                    .getLayoutBundles();
            for (Map.Entry<String, String> info : mLayoutInfoMap.entrySet()) {
                String key = LayoutXmlProcessor.exportLayoutNameFromInfoFileName(info.getKey());
                final List<ResourceBundle.LayoutFileBundle> existingList = bundles.get(key);
                if (existingList != null && !existingList.isEmpty()) {
                    ResourceBundle.LayoutFileBundle myBundle = ResourceBundle.LayoutFileBundle
                            .fromXML(IOUtils.toInputStream(info.getValue()));
                    final ResourceBundle.LayoutFileBundle inheritFrom = existingList.get(0);
                    myBundle.inheritConfigurationFrom(inheritFrom);
                    L.d("inheriting data for %s (%s) from %s", info.getKey(), key, inheritFrom);
                    mLayoutInfoMap.put(info.getKey(), myBundle.toXML());
                }
            }
        }
    }
}
