/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.resolve.lazy.descriptors

import org.jetbrains.jet.storage.StorageManager
import org.jetbrains.jet.lang.resolve.BindingTrace
import org.jetbrains.jet.lang.resolve.scopes.JetScope
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor
import org.jetbrains.jet.lang.resolve.DeclarationResolver
import org.jetbrains.jet.lang.resolve.TypeResolver
import org.jetbrains.jet.lang.resolve.lazy.declarations.DeclarationProviderFactory
import org.jetbrains.jet.lang.resolve.AnnotationResolver
import org.jetbrains.jet.lang.resolve.DescriptorResolver
import org.jetbrains.jet.lang.psi.JetScript
import org.jetbrains.jet.lang.descriptors.ScriptDescriptor

public trait LazyClassContext {
    val storageManager: StorageManager
    val trace: BindingTrace
    val outerScope: JetScope
    val moduleDescriptor: ModuleDescriptor
    val descriptorResolver: DescriptorResolver
    val typeResolver: TypeResolver
    val declarationProviderFactory: DeclarationProviderFactory
    val annotationResolver: AnnotationResolver

    fun getScriptDescriptor(script: JetScript): ScriptDescriptor
}