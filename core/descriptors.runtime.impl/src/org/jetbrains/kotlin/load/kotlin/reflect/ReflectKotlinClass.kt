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

package org.jetbrains.kotlin.load.kotlin.reflect

import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.load.java.JvmAnnotationNames.KotlinSyntheticClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.load.java.structure.reflect.classId
import org.jetbrains.kotlin.name.Name
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Constructor

suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private val TYPES_ELIGIBLE_FOR_SIMPLE_VISIT = setOf(
        // Primitives
        javaClass<java.lang.Integer>(), javaClass<java.lang.Character>(), javaClass<java.lang.Byte>(), javaClass<java.lang.Long>(),
        javaClass<java.lang.Short>(), javaClass<java.lang.Boolean>(), javaClass<java.lang.Double>(), javaClass<java.lang.Float>(),
        // Arrays of primitives
        javaClass<IntArray>(), javaClass<CharArray>(), javaClass<ByteArray>(), javaClass<LongArray>(),
        javaClass<ShortArray>(), javaClass<BooleanArray>(), javaClass<DoubleArray>(), javaClass<FloatArray>(),
        // Others
        javaClass<Class<*>>(), javaClass<String>()
)

public class ReflectKotlinClass(private val klass: Class<*>) : KotlinJvmBinaryClass {
    private val classHeader: KotlinClassHeader

    {
        var header: KotlinClassHeader? = null

        for (annotation in klass.getDeclaredAnnotations()) {
            fun loadAbiVersion(): Int {
                val method = annotation.annotationType().getDeclaredMethod("abiVersion")
                return method(annotation) as Int
            }
            fun loadAnnotationData(): Array<String> {
                val method = annotation.annotationType().getDeclaredMethod("data")
                return method(annotation) as Array<String>
            }
            fun loadSyntheticClassKind(): KotlinSyntheticClass.Kind {
                val method = annotation.annotationType().getDeclaredMethod("kind")
                return java.lang.Enum.valueOf(javaClass<KotlinSyntheticClass.Kind>(), method(annotation).toString())
            }

            val name = annotation.annotationType().getName()
            header = when (name) {
                "kotlin.jvm.internal.KotlinClass" -> {
                    KotlinClassHeader(KotlinClassHeader.Kind.CLASS, loadAbiVersion(), loadAnnotationData(), null)
                }
                "kotlin.jvm.internal.KotlinPackage" -> {
                    KotlinClassHeader(KotlinClassHeader.Kind.PACKAGE_FACADE, loadAbiVersion(), loadAnnotationData(), null)
                }
                "kotlin.jvm.internal.KotlinSyntheticClass" -> {
                    KotlinClassHeader(KotlinClassHeader.Kind.SYNTHETIC_CLASS, loadAbiVersion(), null, loadSyntheticClassKind())
                }
                else -> {
                    continue
                }
            }
            break
        }

        if (header == null) {
            // This exception must be caught in the caller
            throw NotAKotlinClass()
        }

        classHeader = header!!
    }

    public class NotAKotlinClass : RuntimeException()

    override fun getClassId(): ClassId = klass.classId

    override fun getClassHeader() = classHeader

    override fun loadClassAnnotations(visitor: KotlinJvmBinaryClass.AnnotationVisitor) {
        for (annotation in klass.getDeclaredAnnotations()) {
            processAnnotation(visitor, annotation)
        }
        visitor.visitEnd()
    }

    override fun visitMembers(memberVisitor: KotlinJvmBinaryClass.MemberVisitor) {
        for (method in klass.getDeclaredMethods()) {
            val visitor = memberVisitor.visitMethod(Name.identifier(method.getName()!!), SignatureSerializer.methodDesc(method))
            if (visitor == null) continue

            for (annotation in method.getDeclaredAnnotations()) {
                processAnnotation(visitor, annotation)
            }

            for ((index, annotations) in method.getParameterAnnotations().withIndices()) {
                for (annotation in annotations) {
                    val argumentVisitor = visitor.visitParameterAnnotation(index, annotation.annotationType().classId)
                    if (argumentVisitor != null) {
                        processAnnotationArguments(argumentVisitor, annotation)
                    }
                }
            }

            visitor.visitEnd()
        }

        for (constructor in klass.getDeclaredConstructors()) {
            // TODO: load annotations on constructors
            val visitor = memberVisitor.visitMethod(Name.special("<init>"), SignatureSerializer.constructorDesc(constructor))
            if (visitor == null) continue

            // Constructors of enums have 2 additional synthetic parameters
            // TODO: the similar logic should probably be present for annotations on parameters of inner class constructors
            var index = if (klass.isEnum()) 2 else 0
            for (annotations in constructor.getParameterAnnotations()) {
                for (annotation in annotations!!) {
                    val argumentVisitor = visitor.visitParameterAnnotation(index, annotation.annotationType().classId)
                    if (argumentVisitor != null) {
                        processAnnotationArguments(argumentVisitor, annotation)
                    }
                }
                index++
            }
        }

        for (field in klass.getDeclaredFields()) {
            val visitor = memberVisitor.visitField(Name.identifier(field.getName()!!), SignatureSerializer.fieldDesc(field), null)
            if (visitor == null) continue

            for (annotation in field.getDeclaredAnnotations()) {
                processAnnotation(visitor, annotation)
            }

            visitor.visitEnd()
        }
    }

    private fun processAnnotation(visitor: KotlinJvmBinaryClass.AnnotationVisitor, annotation: Annotation) {
        val argumentVisitor = visitor.visitAnnotation(annotation.annotationType().classId)
        if (argumentVisitor != null) {
            processAnnotationArguments(argumentVisitor, annotation)
        }
    }

    private fun processAnnotationArguments(visitor: KotlinJvmBinaryClass.AnnotationArgumentVisitor, annotation: Annotation) {
        for (method in annotation.annotationType().getDeclaredMethods()) {
            processAnnotationArgumentValue(visitor, Name.identifier(method.getName()!!), method(annotation)!!)
        }
        visitor.visitEnd()
    }

    private fun processAnnotationArgumentValue(visitor: KotlinJvmBinaryClass.AnnotationArgumentVisitor, name: Name?, value: Any) {
        val clazz = value.javaClass
        when {
            clazz in TYPES_ELIGIBLE_FOR_SIMPLE_VISIT -> {
                visitor.visit(name, value)
            }
            javaClass<Enum<*>>().isAssignableFrom(clazz) -> {
                visitor.visitEnum(name!!, clazz.classId, Name.identifier(value.toString()))
            }
            javaClass<Annotation>().isAssignableFrom(clazz) -> {
                // TODO: support values of annotation types
                throw UnsupportedOperationException("Values of annotation types are not yet supported in Kotlin reflection: $value")
            }
            clazz.isArray() -> {
                val elementVisitor = visitor.visitArray(name!!) ?: return
                val componentType = clazz.getComponentType()!!
                if (javaClass<Enum<*>>().isAssignableFrom(componentType)) {
                    val componentClassName = componentType.classId
                    for (element in value as Array<*>) {
                        elementVisitor.visitEnum(componentClassName, Name.identifier(element.toString()))
                    }
                }
                else {
                    for (element in value as Array<*>) {
                        elementVisitor.visit(element)
                    }
                }
                elementVisitor.visitEnd()
            }
            else -> {
                throw UnsupportedOperationException("Unsupported annotation argument value ($clazz): $value")
            }
        }
    }
}

private object SignatureSerializer {
    fun methodDesc(method: Method): String {
        val sb = StringBuilder()
        sb.append("(")
        for (parameterType in method.getParameterTypes()!!) {
            sb.append(typeDesc(parameterType))
        }
        sb.append(")")
        sb.append(typeDesc(method.getReturnType()!!))
        return sb.toString()
    }

    fun constructorDesc(constructor: Constructor<*>): String {
        val sb = StringBuilder()
        sb.append("(")
        for (parameterType in constructor.getParameterTypes()!!) {
            sb.append(typeDesc(parameterType))
        }
        sb.append(")V")
        return sb.toString()
    }

    fun fieldDesc(field: Field): String {
        return typeDesc(field.getType()!!)
    }

    suppress("UNCHECKED_CAST")
    fun typeDesc(clazz: Class<*>): String {
        if (clazz == Void.TYPE) return "V";
        // This is a clever exploitation of a format returned by Class.getName(): for arrays, it's almost an internal name,
        // but with '.' instead of '/'
        // TODO: ensure there are tests on arrays of nested classes, multi-dimensional arrays, etc.
        val arrayClass = java.lang.reflect.Array.newInstance(clazz as Class<Any>, 0).javaClass
        return arrayClass.getName().substring(1).replace('.', '/')
    }
}
