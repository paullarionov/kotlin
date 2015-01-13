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

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.engine.FrameExtraVariablesProvider
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.TextWithImports
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl
import com.intellij.debugger.engine.evaluation.CodeFragmentKind
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import com.intellij.psi.util.PsiTreeUtil
import java.util.HashSet
import org.jetbrains.kotlin.psi.JetDeclaration
import org.jetbrains.kotlin.psi.JetElement
import com.intellij.xdebugger.settings.XDebuggerSettingsManager
import org.jetbrains.kotlin.psi.JetTreeVisitorVoid
import org.jetbrains.kotlin.psi.JetReferenceExpression
import org.jetbrains.kotlin.psi.JetCallExpression
import org.jetbrains.kotlin.psi.JetClass
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.JetQualifiedExpression
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.JetFileType
import org.jetbrains.kotlin.idea.caches.resolve.analyze

public class KotlinFrameExtraVariablesProvider : FrameExtraVariablesProvider {
    override fun isAvailable(sourcePosition: SourcePosition?, evalContext: EvaluationContext?): Boolean {
        if (sourcePosition == null) return false
        if (sourcePosition.getLine() < 0) return false
        return sourcePosition.getFile().getFileType() == JetFileType.INSTANCE && DebuggerSettings.getInstance().AUTO_VARIABLES_MODE
    }

    override fun collectVariables(
            sourcePosition: SourcePosition?,
            evalContext: EvaluationContext?,
            alreadyCollected: Set<String>?
    ): Set<TextWithImports>? {
        val pair = runReadAction { findReferencedVars(alreadyCollected!!, sourcePosition!!, evalContext!!) }
        return pair.second
//        return hashSetOf(TextWithImportsImpl(CodeFragmentKind.EXPRESSION, "a.hashCode()"))
    }
}

public fun findReferencedVars(
        visibleVars: Set<String>,
        position: SourcePosition,
        evalContext: EvaluationContext
): Pair<Set<String>, Set<TextWithImports>> {
    val line = position.getLine()
    val positionFile = position.getFile()

    val vFile = positionFile.getVirtualFile()
    val doc = if (vFile != null) FileDocumentManager.getInstance().getDocument(vFile) else null
    if (doc == null || doc.getLineCount() == 0 || line > (doc.getLineCount() - 1)) {
        return setOf<String>() to setOf<TextWithImports>()
    }

    val limit = calculateLimitRange(positionFile, doc, line)

    var startLine = Math.max(limit.getStartOffset(), line - 1)
    startLine = Math.min(startLine, limit.getEndOffset())
    while (startLine > limit.getStartOffset() && shouldSkipLine(positionFile, doc, startLine)) {
        startLine--
    }
    val startOffset = doc.getLineStartOffset(startLine)

    var endLine = Math.min(line + 2, limit.getEndOffset())
    while (endLine < limit.getEndOffset() && shouldSkipLine(positionFile, doc, endLine)) {
        endLine++
    }
    val endOffset = doc.getLineEndOffset(endLine)

    val vars = HashSet<String>()
    val expressions = HashSet<TextWithImports>()

    val lineRange = TextRange(startOffset, endOffset)
    if (!lineRange.isEmpty()) {
        val offset = CharArrayUtil.shiftForward(doc.getCharsSequence(), doc.getLineStartOffset(line), " \t")
        val element = positionFile.findElementAt(offset)
        if (element != null) {
            val contElement = PsiTreeUtil.getNonStrictParentOfType<JetDeclaration>(element, javaClass())
                                    ?: PsiTreeUtil.getNonStrictParentOfType<JetElement>(element, javaClass())
            if (contElement != null) {
                val variablesCollector = VariablesCollector(visibleVars, adjustRange(contElement, lineRange), expressions, vars, position, evalContext as EvaluationContextImpl)
                element.accept(variablesCollector)
            }
        }
    }

    return vars to expressions
}

private fun calculateLimitRange(file: PsiFile, doc: Document, line: Int): TextRange {
    val offset = doc.getLineStartOffset(line)
    if (offset > 0) {
        var elem = file.findElementAt(offset)
        while (elem != null) {
            if (elem is JetDeclaration) {
                val elemRange = elem!!.getTextRange()
                return TextRange(doc.getLineNumber(elemRange.getStartOffset()), doc.getLineNumber(elemRange.getEndOffset()))
            }
            elem = elem!!.getParent()
        }
    }
    return TextRange(0, doc.getLineCount() - 1)
}

private fun adjustRange(element: JetElement, originalRange: TextRange): TextRange {
    var resultRange = originalRange
    element.accept(object : JetTreeVisitorVoid() {
        override fun visitDeclaration(dcl: JetDeclaration) {
            val declRange = dcl.getTextRange()
            if (originalRange.intersects(declRange)) {
                val currentRange = resultRange
                val start = Math.min(currentRange.getStartOffset(), declRange.getStartOffset())
                val end = Math.max(currentRange.getEndOffset(), declRange.getEndOffset())
                resultRange = TextRange(start, end)
            }
            super.visitDeclaration(dcl)
        }
    })
    return resultRange
}

private fun shouldSkipLine(file: PsiFile, doc: Document, line: Int): Boolean {
    val start = doc.getLineStartOffset(line)
    val end = doc.getLineEndOffset(line)
    val _start = CharArrayUtil.shiftForward(doc.getCharsSequence(), start, " \n\t")
    if (_start >= end) {
        return true
    }

    var alreadyChecked: TextRange? = null
    var elem: PsiElement? = file.findElementAt(_start)
    while (elem != null && elem!!.getTextOffset() <= end && (alreadyChecked == null || !alreadyChecked!!.contains(elem!!.getTextRange()))) {
        var _elem: PsiElement? = elem
        while (_elem != null && _elem!!.getTextOffset() >= _start) {
            alreadyChecked = _elem!!.getTextRange()

            if (_elem is JetDeclaration) {
                return false
            }
            _elem = _elem!!.getParent()
        }
        elem = elem!!.getNextSibling()
    }
    return true
}

private class VariablesCollector(
        private val myVisibleLocals: Set<String>,
        private val myLineRange: TextRange,
        private val myExpressions: MutableSet<TextWithImports>,
        private val myVars: MutableSet<String>,
        private val myPosition: SourcePosition,
        private val myEvalContext: EvaluationContextImpl
) : JetTreeVisitorVoid() {
    private val myCollectExpressions = XDebuggerSettingsManager.getInstance().getDataViewSettings().isAutoExpressions()

    override fun visitJetElement(element: JetElement) {
        if (element.isInRange()) {
            super.visitJetElement(element)
        }
    }

    override fun visitQualifiedExpression(expression: JetQualifiedExpression) {
        if (expression.isInRange()) {
            val selector = expression.getSelectorExpression()
            if (selector is JetReferenceExpression) {
                val descriptor = expression.analyze()[BindingContext.REFERENCE_TARGET, selector]
                if (descriptor is PropertyDescriptor) {
                    if (descriptor.getCompileTimeInitializer() == null) {
                        myExpressions.add(TextWithImportsImpl(CodeFragmentKind.EXPRESSION, expression.getText()))
                        return
                    }
                }
            }
        }
        super.visitQualifiedExpression(expression)
    }

    override fun visitReferenceExpression(expression: JetReferenceExpression) {
        if (expression.isInRange()) {
            val descriptor = expression.analyze()[BindingContext.REFERENCE_TARGET, expression]
            if (descriptor is PropertyDescriptor) {
                if (descriptor.getCompileTimeInitializer() == null) {
                    myExpressions.add(TextWithImportsImpl(CodeFragmentKind.EXPRESSION, expression.getText()))
                    return
                }
            }
        }
        super.visitReferenceExpression(expression)
    }

    private fun JetElement.isInRange(): Boolean = myLineRange.intersects(this.getTextRange())

    override fun visitCallExpression(expression: JetCallExpression) {
        if (myCollectExpressions) {
//            val psiMethod = expression.resolveMethod()
//            if (psiMethod != null && !DebuggerUtils.hasSideEffectsOrReferencesMissingVars(expression, myVisibleLocals)) {
//                myExpressions.add(TextWithImportsImpl(CodeFragmentKind.EXPRESSION, expression.getText()))
//            }
        }
        super.visitCallExpression(expression)
    }

    /*override fun visitElement(element: PsiElement) {
        if (myLineRange.intersects(element.getTextRange())) {
            super.visitElement(element)
        }
    }*/

    /*override fun visitReferenceExpression(reference: PsiReferenceExpression) {
        if (myLineRange.intersects(reference.getTextRange())) {
            val psiElement = reference.resolve()
            if (psiElement is PsiVariable) {
                val `var` = psiElement as PsiVariable
                if (`var` is PsiField) {
                    if (myCollectExpressions && !DebuggerUtils.hasSideEffectsOrReferencesMissingVars(reference, myVisibleLocals)) {
                        *//*
                                      if (var instanceof PsiEnumConstant && reference.getQualifier() == null) {
                                        final PsiClass enumClass = ((PsiEnumConstant)var).getContainingClass();
                                        if (enumClass != null) {
                                          final PsiExpression expression = JavaPsiFacade.getInstance(var.getProject()).getParserFacade().createExpressionFromText(enumClass.getName() + "." + var.getName(), var);
                                          final PsiReference ref = expression.getReference();
                                          if (ref != null) {
                                            ref.bindToElement(var);
                                            myExpressions.add(new TextWithImportsImpl(expression));
                                          }
                                        }
                                      }
                                      else {
                                        myExpressions.add(new TextWithImportsImpl(reference));
                                      }
                                      *//*
                        val modifierList = `var`.getModifierList()
                        val isConstant = (`var` is PsiEnumConstant) || (modifierList != null && modifierList!!.hasModifierProperty(PsiModifier.STATIC) && modifierList!!.hasModifierProperty(PsiModifier.FINAL))
                        if (!isConstant) {
                            myExpressions.add(TextWithImportsImpl(reference))
                        }
                    }
                }
                else {
                    if (myVisibleLocals.contains(`var`.getName())) {
                        myVars.add(`var`.getName())
                    }
                }
            }
        }
        super.visitReferenceExpression(reference)
    }*/

    /*override fun visitArrayAccessExpression(expression: PsiArrayAccessExpression) {
        if (myCollectExpressions && !DebuggerUtils.hasSideEffectsOrReferencesMissingVars(expression, myVisibleLocals)) {
            myExpressions.add(TextWithImportsImpl(expression))
        }
        super.visitArrayAccessExpression(expression)
    }

    override fun visitParameter(parameter: PsiParameter) {
        processVariable(parameter)
        super.visitParameter(parameter)
    }

    override fun visitLocalVariable(variable: PsiLocalVariable) {
        processVariable(variable)
        super.visitLocalVariable(variable)
    }

    private fun processVariable(variable: PsiVariable) {
        if (myLineRange.intersects(variable.getTextRange()) && myVisibleLocals.contains(variable.getName())) {
            myVars.add(variable.getName())
        }
    }*/
    override fun visitClass(klass: JetClass) {
        // Do not step in to local and anonymous classes...
    }

}