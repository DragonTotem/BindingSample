/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.databinding.tool.reflection.annotation;

import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * This logger stores messages that are to be put in the ProcessingEnvironment's
 * Messager and then writes them all out when flushMessages() is called. Elements
 * are kept in a round-independent format so that the messages can be logged
 * independently from the annotation processing round in which they were created.
 * {@link #flushMessages(ProcessingEnvironment)} should be called when all rounds are over so that
 * superfluous errors aren't generated.
 */
public class AnnotationLogger implements L.Client {
    private final ArrayList<Message> mMessages = new ArrayList<>();

    @Override
    public void printMessage(Diagnostic.Kind kind, String message, Element element) {
        ElementPath elementPath = null;
        if (element != null) {
            elementPath = ElementPath.toElementPath(element);
        }
        Message msg = new Message(kind, message, elementPath);

        synchronized (mMessages) {
            mMessages.add(msg);
        }
    }

    public void flushMessages(ProcessingEnvironment processingEnvironment) {
        Messager messager = processingEnvironment.getMessager();
        synchronized (mMessages) {
            for (Message message : mMessages) {
                if (message.element != null) {
                    Element element = message.element.toElement(processingEnvironment);
                    messager.printMessage(message.kind, message.message, element);
                } else {
                    messager.printMessage(message.kind, message.message);
                }
            }
            mMessages.clear();
        }
    }

    private static class Message {
        public final ElementPath element;
        public final String message;
        public final Diagnostic.Kind kind;

        private Message(Diagnostic.Kind kind, String message, ElementPath element) {
            this.element = element;
            this.message = message;
            this.kind = kind;
        }
    }

    private static abstract class ElementPath {
        abstract Element toElement(ProcessingEnvironment processingEnvironment);

        public static ElementPath toElementPath(Element element) {
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.ENUM) {
                return new TypeElementRoot((TypeElement) element);
            } else if (element.getKind() == ElementKind.PACKAGE) {
                return new PackageElementRoot((PackageElement) element);
            } else {
                Element enclosing = element.getEnclosingElement();
                ElementPath parent = toElementPath(enclosing);

                List<? extends Element> enclosed = enclosing.getEnclosedElements();
                int index = enclosed.indexOf(element);
                if (index < 0) {
                    throw new IllegalStateException("Can't find element in enclosing element");
                }
                return new ElementChild(parent, index);
            }
        }
    }

    private static class ElementChild extends ElementPath {
        private final ElementPath mEnclosing;
        private final int mEnclosingIndex;

        private ElementChild(ElementPath enclosing, int enclosingIndex) {
            mEnclosing = enclosing;
            mEnclosingIndex = enclosingIndex;
        }

        @Override
        Element toElement(ProcessingEnvironment processingEnvironment) {
            Element enclosing = mEnclosing.toElement(processingEnvironment);
            List<? extends Element> enclosed = enclosing.getEnclosedElements();
            return enclosed.get(mEnclosingIndex);
        }
    }

    private static class TypeElementRoot extends ElementPath {
        private final String mElementType;

        private TypeElementRoot(TypeElement element) {
            mElementType = element.getQualifiedName().toString();
        }

        @Override
        Element toElement(ProcessingEnvironment processingEnvironment) {
            Elements elements = processingEnvironment.getElementUtils();
            return elements.getTypeElement(mElementType);
        }
    }

    private static class PackageElementRoot extends ElementPath {
        private final String mPackage;

        private PackageElementRoot(PackageElement element) {
            mPackage = element.getQualifiedName().toString();
        }

        @Override
        Element toElement(ProcessingEnvironment processingEnvironment) {
            Elements elements = processingEnvironment.getElementUtils();
            return elements.getPackageElement(mPackage);
        }
    }}
