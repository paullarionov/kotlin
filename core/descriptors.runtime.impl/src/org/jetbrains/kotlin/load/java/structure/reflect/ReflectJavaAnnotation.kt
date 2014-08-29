/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.load.java.structure.reflect

import org.jetbrains.kotlin.load.java.structure.JavaAnnotation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.load.java.structure.JavaAnnotationArgument
import java.util.ArrayList

public class ReflectJavaAnnotation(private val annotation: Annotation) : ReflectJavaElement(), JavaAnnotation {
    override fun findArgument(name: Name): JavaAnnotationArgument? {
        val value = annotation.annotationType().getDeclaredMethod(name.asString()).invoke(annotation)
        return value?.let { ReflectJavaAnnotationArgument.create(it, name) }
    }

    override fun getArguments(): Collection<JavaAnnotationArgument> {
        val declaredMethods = annotation.annotationType().getDeclaredMethods()
        val result = ArrayList<ReflectJavaAnnotationArgument>(declaredMethods.size)
        for (method in declaredMethods) {
            val value = method.invoke(annotation) ?: continue
            val name = method.getName() ?: continue
            result.add(ReflectJavaAnnotationArgument.create(value, Name.identifier(name)))
        }
        return result
    }

    override fun resolve() = ReflectJavaClass(annotation.annotationType())

    override fun getClassId() = annotation.annotationType().classId
}
