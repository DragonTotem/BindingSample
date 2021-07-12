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

package android.databinding.tool.reflection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import android.databinding.tool.Context;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;

import com.android.annotations.VisibleForTesting;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Class that is used for SDK related stuff.
 * <p>
 * Must be initialized with the sdk location to work properly
 */
public class SdkUtil {

    private ApiChecker mApiChecker;

    private final int mMinSdk;

    public SdkUtil(ApiChecker mApiChecker, int mMinSdk) {
        this.mApiChecker = mApiChecker;
        this.mMinSdk = mMinSdk;
    }

    public static SdkUtil create(File sdkPath, int minSdk) {
        ApiChecker checker = new ApiChecker(new File(sdkPath.getAbsolutePath()
                                                     + "/platform-tools/api/api-versions.xml"));
        return new SdkUtil(checker, minSdk);
    }

    public static SdkUtil get() {
        return Context.getSdkUtil();
    }

    public int getMinApi(ModelClass modelClass) {
        return mApiChecker.getMinApi(modelClass.getJniDescription(), null);
    }

    public int getMinApi(ModelMethod modelMethod) {
        ModelClass declaringClass = modelMethod.getDeclaringClass();
        Preconditions.checkNotNull(mApiChecker, "should've initialized api checker");
        int minApi = Integer.MAX_VALUE;
        String methodDesc = modelMethod.getJniDescription();
        while (declaringClass != null) {
            String classDesc = declaringClass.getJniDescription();
            int result = mApiChecker.getMinApi(classDesc, methodDesc);
            L.d("checking method api for %s, class:%s method:%s. result: %d", modelMethod.getName(),
                    classDesc, methodDesc, result);
            if (result > 0) {
                minApi = Math.min(minApi, result);
            }
            declaringClass = declaringClass.getSuperclass();
        }
        if (minApi == Integer.MAX_VALUE) {
            return 1;
        }
        return minApi;
    }

    public ApiChecker getApiChecker() {
        return mApiChecker;
    }

    public int getMinSdk() {
        return mMinSdk;
    }

    @VisibleForTesting
    public void swapApiChecker(ApiChecker apiChecker) {
        mApiChecker = apiChecker;
    }

    public static class ApiChecker {

        private Map<String, Integer> mFullLookup;

        private Document mDoc;

        private XPath mXPath;

        public ApiChecker(File apiFile) {
            InputStream inputStream = null;
            try {
                if (apiFile == null || !apiFile.exists()) {
                    // Use getResource().openStream() instead of getResourceAsStream() to avoid
                    // concurrency issue (see http://issuetracker.google.com/137929327 for details)
                    inputStream = getClass().getClassLoader().getResource("api-versions.xml").openStream();
                } else {
                    inputStream = FileUtils.openInputStream(apiFile);
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                mDoc = builder.parse(inputStream);
                XPathFactory xPathFactory = XPathFactory.newInstance();
                mXPath = xPathFactory.newXPath();
                buildFullLookup();
            } catch (Throwable t) {
                L.e(t, "cannot load api descriptions from %s", apiFile);
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }

        private void buildFullLookup() throws XPathExpressionException {
            NodeList allClasses = mDoc.getChildNodes().item(0).getChildNodes();
            mFullLookup = new HashMap<String, Integer>(allClasses.getLength() * 4);
            for (int j = 0; j < allClasses.getLength(); j++) {
                Node node = allClasses.item(j);
                if (node.getNodeType() != Node.ELEMENT_NODE || !"class"
                        .equals(node.getNodeName())) {
                    continue;
                }
                //L.d("checking node %s", node.getAttributes().getNamedItem("name").getNodeValue());
                int classSince = getSince(node);
                String classDesc = node.getAttributes().getNamedItem("name").getNodeValue();

                final NodeList childNodes = node.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node child = childNodes.item(i);
                    if (child.getNodeType() != Node.ELEMENT_NODE || !"method"
                            .equals(child.getNodeName())) {
                        continue;
                    }
                    int methodSince = getSince(child);
                    int since = Math.max(classSince, methodSince);
                    String methodDesc = child.getAttributes().getNamedItem("name")
                            .getNodeValue();
                    String key = cacheKey(classDesc, methodDesc);
                    mFullLookup.put(key, since);
                }
            }
        }

        /**
         * Returns 0 if we cannot find the API level for the method.
         */
        public int getMinApi(String classDesc, String methodOrFieldDesc) {
            if (mDoc == null || mXPath == null) {
                return 1;
            }
            if (classDesc == null || classDesc.isEmpty()) {
                return 1;
            }
            final String key = cacheKey(classDesc, methodOrFieldDesc);
            Integer since = mFullLookup.get(key);
            return since == null ? 0 : since;
        }

        private static String cacheKey(String classDesc, String methodOrFieldDesc) {
            return classDesc + "~" + methodOrFieldDesc;
        }

        private static int getSince(Node node) {
            final Node since = node.getAttributes().getNamedItem("since");
            if (since != null) {
                final String nodeValue = since.getNodeValue();
                if (nodeValue != null && !nodeValue.isEmpty()) {
                    try {
                        return Integer.parseInt(nodeValue);
                    } catch (Throwable t) {
                    }
                }
            }

            return 1;
        }
    }
}
