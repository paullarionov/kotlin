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

import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.Name
import java.lang.reflect.TypeVariable
import kotlin.properties.Delegates
import java.lang.reflect.Method
import java.lang.reflect.Constructor

public class ReflectJavaTypeParameter(
        private val typeVariable: TypeVariable<*>
) : ReflectJavaElement(), JavaTypeParameter {
    private val _index: Int by Delegates.lazy { (): Int ->
        val owner = typeVariable.getGenericDeclaration()!!
        var index = 0
        for (typeParameter in owner.getTypeParameters()) {
            if (typeVariable == typeParameter) return@lazy index
            index++
        }
        throw IllegalStateException("Type variable $typeVariable not found in container $owner")
    }

    override fun getIndex() = _index

    override fun getUpperBounds(): List<ReflectJavaClassifierType> {
        val bounds = typeVariable.getBounds().map { bound -> ReflectJavaClassifierType(bound) }
        if (bounds.size == 1 && bounds[0].classifierType == javaClass<Any>()) return listOf()
        return bounds
    }

    override fun getOwner(): JavaTypeParameterListOwner? {
        val owner = typeVariable.getGenericDeclaration()
        return when (owner) {
            is Class<*> -> ReflectJavaClass(owner)
            is Method -> ReflectJavaMethod(owner)
            is Constructor<*> -> ReflectJavaConstructor(owner)
            null -> null
            else -> throw UnsupportedOperationException("Unsupported type parameter list owner (${owner.javaClass}): $owner")
        }
    }

    override fun getType(): JavaType {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun getTypeProvider() = ReflectJavaTypeProvider

    override fun getName() = Name.identifier(typeVariable.getName() ?: "T${getIndex()}")

    override fun equals(other: Any?) = other is ReflectJavaTypeParameter && typeVariable == other.typeVariable
    override fun hashCode() = typeVariable.hashCode()
}
