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

package android.databinding.tool.reflection.java;

import android.databinding.tool.Context;
import android.databinding.tool.LibTypes;
import android.databinding.tool.reflection.ImportBag;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.SdkUtil;
import android.databinding.tool.reflection.TypeUtil;
import android.databinding.tool.util.L;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaAnalyzer extends ModelAnalyzer {
    public static final Map<String, Class> PRIMITIVE_TYPES;
    static {
        PRIMITIVE_TYPES = new HashMap<String, Class>();
        PRIMITIVE_TYPES.put("boolean", boolean.class);
        PRIMITIVE_TYPES.put("byte", byte.class);
        PRIMITIVE_TYPES.put("short", short.class);
        PRIMITIVE_TYPES.put("char", char.class);
        PRIMITIVE_TYPES.put("int", int.class);
        PRIMITIVE_TYPES.put("long", long.class);
        PRIMITIVE_TYPES.put("float", float.class);
        PRIMITIVE_TYPES.put("double", double.class);
    }

    private HashMap<String, JavaClass> mClassCache = new HashMap<String, JavaClass>();

    private final ClassLoader mClassLoader;

    public JavaAnalyzer(ClassLoader classLoader, LibTypes libTypes) {
        super(libTypes);
        mClassLoader = classLoader;
    }

    @Override
    public JavaClass loadPrimitive(String className) {
        Class clazz = PRIMITIVE_TYPES.get(className);
        if (clazz == null) {
            return null;
        } else {
            return new JavaClass(clazz);
        }
    }

    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    @Override
    protected boolean findGeneratedAnnotation() {
        try {
            return Class.forName(GENERATED_ANNOTATION) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public ModelClass findClassInternal(String className, ImportBag imports) {
        // TODO handle imports
        JavaClass loaded = mClassCache.get(className);
        if (loaded != null) {
            return loaded;
        }
        L.d("trying to load class %s from %s", className, mClassLoader.toString());
        loaded = loadPrimitive(className);
        if (loaded == null) {
            try {
                className = TypeUtil.getInstance().toBinaryName(className);
                if (className.startsWith("[")) {
                    loaded = new JavaClass(Class.forName(className, false, mClassLoader));
                    mClassCache.put(className, loaded);
                } else {
                    loaded = loadRecursively(className);
                    mClassCache.put(className, loaded);
                }

            } catch (Throwable t) {
//                L.e(t, "cannot load class " + className);
            }
        }
        // expr visitor may call this to resolve statics. Sometimes, it is OK not to find a class.
        if (loaded == null) {
            return null;
        }
        L.d("loaded class %s", loaded.mClass.getCanonicalName());
        return loaded;
    }

    @Override
    public ModelClass findClass(Class classType) {
        return new JavaClass(classType);
    }

    @Override
    public TypeUtil createTypeUtil() {
        return new JavaTypeUtil();
    }

    private JavaClass loadRecursively(String className) throws ClassNotFoundException {
        try {
            L.d("recursively checking %s", className);
            return new JavaClass(mClassLoader.loadClass(className));
        } catch (ClassNotFoundException ex) {
            int lastIndexOfDot = className.lastIndexOf(".");
            if (lastIndexOfDot == -1) {
                throw ex;
            }
            return loadRecursively(className.substring(0, lastIndexOfDot) + "$" + className
                    .substring(lastIndexOfDot + 1));
        }
    }

    private static String loadAndroidHome() {
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            L.d("%s %s", entry.getKey(), entry.getValue());
        }

        String pathFromBazel = loadBazelSdk();
        if (pathFromBazel != null) {
            return pathFromBazel;
        }

        if(env.containsKey("ANDROID_HOME")) {
            return env.get("ANDROID_HOME");
        }
        // check for local.properties file
        File folder = new File(".").getAbsoluteFile();
        while (folder != null && folder.exists()) {
            File f = new File(folder, "local.properties");
            if (f.exists() && f.canRead()) {
                try {
                    for (String line : FileUtils.readLines(f)) {
                        List<String> keyValue = Splitter.on('=').splitToList(line);
                        if (keyValue.size() == 2) {
                            String key = keyValue.get(0).trim();
                            if (key.equals("sdk.dir")) {
                                return keyValue.get(1).trim();
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }
            folder = folder.getParentFile();
        }

        return null;
    }

    private static String loadBazelSdk() {
        String workspace = System.getenv("TEST_WORKSPACE");
        String workspaceParent = System.getenv("TEST_SRCDIR");
        if (workspace != null && workspaceParent != null) {
            File workspaceRoot = new File(workspaceParent, workspace);
            String sdkDirectoryName;

            String osName = System.getProperty("os.name");
            if (osName.startsWith("Mac")) {
                sdkDirectoryName = "darwin";
            } else if (osName.startsWith("Linux")) {
                sdkDirectoryName = "linux";
            } else if (osName.startsWith("Windows")) {
                sdkDirectoryName = "windows";
            } else {
                throw new AssertionError("Unknown OS: " + osName);
            }

            return new File(workspaceRoot, "prebuilts/studio/sdk/" + sdkDirectoryName).getAbsolutePath();
        }
        return null;
    }

    public static void initForTests() {
        String androidHome = loadAndroidHome();
        if (Strings.isNullOrEmpty(androidHome) || !new File(androidHome).exists()) {
            throw new IllegalStateException(
                    "you need to have ANDROID_HOME set in your environment"
                            + " to run compiler tests");
        }
        // find latest SDK
        final File platforms = new File(androidHome + "/platforms");
        final String prefix = "android-";
        final Collection<File> sdks = FileUtils
                .listFilesAndDirs(platforms, FileFilterUtils.falseFileFilter(),
                        FileFilterUtils.prefixFileFilter(prefix));
        File androidJar = null;
        int maxVersion = -1;
        for (File sdk : sdks) {
            try {
                int version = Integer.parseInt(sdk.getName().substring(prefix.length()));
                if (version > maxVersion) {
                    androidJar = new File(sdk, "android.jar");
                    maxVersion = version;
                }
            } catch (NumberFormatException ex) {
                L.d("cannot parse number from " +  sdk.getName());
            }
        }
        if (androidJar == null || !androidJar.exists() || !androidJar.canRead()) {
            throw new IllegalStateException("cannot find android jar");
        }
        // now load android data binding library as well

        try {
            ClassLoader classLoader = new URLClassLoader(new URL[]{androidJar.toURI().toURL()},
                    ModelAnalyzer.class.getClassLoader());
            JavaAnalyzer javaAnalyzer = new JavaAnalyzer(classLoader, new LibTypes(true));
            Context.initForTests(javaAnalyzer,
                    SdkUtil.create(new File(androidHome), 8));
        } catch (MalformedURLException e) {
            throw new RuntimeException("cannot create class loader", e);
        }
    }
}
