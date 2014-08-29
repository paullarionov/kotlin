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

package org.jetbrains.kotlin.jvm.compiler

import org.jetbrains.kotlin.codegen.GenerationUtils
import org.jetbrains.kotlin.test.JetTestUtils
import java.io.File
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.util.RecursiveDescriptorComparator
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.jetbrains.kotlin.cli.common.output.outputUtils.writeAllTo
import org.jetbrains.kotlin.utils.sure
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.scopes.JetScope
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.kotlin.reflect.ReflectKotlinClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.resolve.scopes.WritableScopeImpl
import org.jetbrains.kotlin.resolve.scopes.RedeclarationHandler
import org.jetbrains.kotlin.resolve.scopes.WritableScope.LockLevel
import org.jetbrains.kotlin.renderer.DescriptorRendererBuilder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.resolveTopLevelClass
import org.jetbrains.kotlin.load.kotlin.reflect.createModule
import java.net.URLClassLoader
import com.intellij.openapi.util.io.FileUtil
import java.util.regex.Pattern
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.test.JetTestUtils.TestFileFactoryNoModules
import org.jetbrains.kotlin.test.util.RecursiveDescriptorComparator.Configuration
import org.jetbrains.kotlin.test.util.DescriptorValidator.ValidationVisitor.errorTypesForbidden
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.test.TestJdkKind

public abstract class AbstractJvmRuntimeDescriptorLoaderTest : TestCaseWithTmpdir() {
    class object {
        private val renderer = DescriptorRendererBuilder()
                .setWithDefinedIn(false)
                .setExcludedAnnotationClasses(listOf(
                        ExpectedLoadErrorsUtil.ANNOTATION_CLASS_NAME,
                        "kotlin.deprecated",
                        "kotlin.data",
                        "org.jetbrains.annotations.NotNull",
                        "org.jetbrains.annotations.Nullable",
                        "org.jetbrains.annotations.Mutable",
                        "org.jetbrains.annotations.ReadOnly"
                ).map { FqName(it) })
                .setOverrideRenderingPolicy(DescriptorRenderer.OverrideRenderingPolicy.RENDER_OPEN_OVERRIDE)
                .setIncludeSynthesizedParameterNames(false)
                .setIncludePropertyConstant(false)
                .setVerbose(true)
                .build()
    }

    // NOTE: this test does a dirty hack of text substitution to make all annotations defined in source code retain at runtime.
    // Specifically each "annotation class" in Kotlin sources is replaced by "Retention(RUNTIME) annotation class", and the same in Java
    protected fun doTest(fileName: String) {
        if (fileName == "compiler/testData/loadJava/compiledJava/annotations/NestedEnumArgument.java") {
            // TODO: this test should be fixed by making JVM back-end generate correct InnerClasses attribute as per JVM specification
            return
        }

        val file = File(fileName)
        val text = FileUtil.loadFile(file, true)
        if (fileName.endsWith(".java")) {
            val sources = JetTestUtils.createTestFiles(file.getName(), text, object : TestFileFactoryNoModules<File>() {
                override fun create(fileName: String, text: String, directives: Map<String, String>): File {
                    val targetFile = File(tmpdir, fileName)
                    targetFile.writeText(addRuntimeRetentionToJavaSource(text))
                    return targetFile
                }
            })
            LoadDescriptorUtil.compileJavaWithAnnotationsJar(sources, tmpdir)
        }
        else if (fileName.endsWith(".kt")) {
            val environment = JetTestUtils.createEnvironmentWithFullJdk(myTestRootDisposable)
            val jetFile = JetTestUtils.createFile(fileName, addRuntimeRetentionToKotlinSource(text), environment.getProject())
            GenerationUtils.compileFileGetClassFileFactoryForTest(jetFile).writeAllTo(tmpdir)
        }

        val classLoader = URLClassLoader(array(tmpdir.toURI().toURL(), ForTestCompileRuntime.runtimeJarForTests().toURI().toURL()))
        val module = createModule(classLoader)

        // Since runtime package view descriptor doesn't support getAllDescriptors(), we construct a synthetic package view here.
        // It has in its scope descriptors for all the classes and top level members generated by the compiler
        val actual = object : PackageViewDescriptor {
            val scope = WritableScopeImpl(JetScope.Empty, this, RedeclarationHandler.THROW_EXCEPTION, "runtime descriptor loader test")

            override fun getFqName() = LoadDescriptorUtil.TEST_PACKAGE_FQNAME
            override fun getMemberScope() = scope
            override fun getModule() = module
            override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>, data: D): R =
                    visitor.visitPackageViewDescriptor(this, data)

            override fun getContainingDeclaration() = throw UnsupportedOperationException()
            override fun getOriginal() = throw UnsupportedOperationException()
            override fun substitute(substitutor: TypeSubstitutor) = throw UnsupportedOperationException()
            override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) = throw UnsupportedOperationException()
            override fun getAnnotations() = throw UnsupportedOperationException()
            override fun getName() = throw UnsupportedOperationException()
        }

        val scope = actual.getMemberScope()
        scope.changeLockLevel(LockLevel.BOTH)

        val generatedPackageDir = File(tmpdir, LoadDescriptorUtil.TEST_PACKAGE_FQNAME.pathSegments().single().asString())
        val allClassFiles = FileUtil.findFilesByMask(Pattern.compile(".*\\.class"), generatedPackageDir)

        for (classFile in allClassFiles) {
            val className = tmpdir.relativePath(classFile).substringBeforeLast(".class").replace('/', '.').replace('\\', '.')
            if (className.endsWith(JvmAbi.CLASS_OBJECT_SUFFIX)) continue

            val klass = classLoader.loadClass(className).sure("Couldn't load class $className")
            val kind = try { ReflectKotlinClass(klass).getClassHeader().kind } catch (e: ReflectKotlinClass.NotAKotlinClass) { null }

            if (kind == KotlinClassHeader.Kind.PACKAGE_FACADE) {
                val packageView = module.getPackage(actual.getFqName()) ?: error("Couldn't resolve package ${actual.getFqName()}")
                scope.importScope(packageView.getMemberScope())
            }
            else if (kind != KotlinClassHeader.Kind.SYNTHETIC_CLASS) {
                // Either a normal Kotlin class or a Java class
                val classDescriptor = resolveClassInModule(module, klass).sure("Couldn't resolve class $className")
                if (classDescriptor.getContainingDeclaration() is PackageFragmentDescriptor) {
                    scope.addClassifierDescriptor(classDescriptor)
                }
            }
        }

        val expected = LoadDescriptorUtil.loadTestPackageAndBindingContextFromJavaRoot(
                tmpdir, getTestRootDisposable(), TestJdkKind.FULL_JDK, ConfigurationKind.ALL
        )

        val comparatorConfiguration = Configuration(
                /* checkPrimaryConstructors = */ fileName.endsWith(".kt"),
                /* checkPropertyAccessors = */ true,
                /* includeMethodsOfKotlinAny = */ false,
                { descriptor ->
                    // Parameter order of annotation constructors is not retained at runtime
                    !(descriptor is ConstructorDescriptor && DescriptorUtils.isAnnotationClass(descriptor.getContainingDeclaration()))
                },
                errorTypesForbidden(), renderer
        )
        RecursiveDescriptorComparator.validateAndCompareDescriptors(expected.first, actual, comparatorConfiguration, null)
    }

    private fun addRuntimeRetentionToKotlinSource(text: String): String {
        return text.replace(
                "annotation class",
                "[java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)] annotation class"
        )
    }

    private fun addRuntimeRetentionToJavaSource(text: String): String {
        return text.replace(
                "@interface",
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface"
        )
    }

    private fun resolveClassInModule(module: ModuleDescriptor, klass: Class<*>): ClassDescriptor? {
        val outerClass = klass.getDeclaringClass()
        if (outerClass == null) return module.resolveTopLevelClass(FqName(klass.getName()))

        val outerDescriptor = resolveClassInModule(module, outerClass) ?: return null

        val simpleName = klass.getSimpleName()
        if (simpleName == JvmAbi.CLASS_OBJECT_CLASS_NAME) return outerDescriptor.getClassObjectDescriptor()

        return outerDescriptor.getUnsubstitutedInnerClassesScope().getClassifier(Name.identifier(simpleName)) as? ClassDescriptor
    }
}
