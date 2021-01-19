/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.intrinsics

import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.codegen.*
import org.jetbrains.kotlin.codegen.inline.v
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

object JvmInvokeDynamic : IntrinsicMethod() {
    override fun invoke(expression: IrFunctionAccessExpression, codegen: ExpressionCodegen, data: BlockInfo): PromisedValue {
        fun fail(message: String): Nothing =
            throw AssertionError("$message; expression:\n${expression.dump()}")

        val dynamicCall = expression.getValueArgument(0) as? IrCall
            ?: fail("'dynamicCall' is expected to be a call")
        val dynamicCallee = dynamicCall.symbol.owner
        if (dynamicCallee.parent != codegen.context.ir.symbols.kotlinJvmInternalInvokeDynamicPackage ||
            dynamicCallee.origin != JvmLoweredDeclarationOrigin.INVOVEDYNAMIC_CALL_TARGET
        )
            fail("Unexpected dynamicCallee: '${dynamicCallee.render()}'")

        val bootstrapMethodTag = expression.getValueArgument(1)?.getIntConst()
            ?: fail("'bootstrapMethodTag' is expected to be an int const")
        val bootstrapMethodOwner = expression.getValueArgument(2)?.getStringConst()
            ?: fail("'bootstrapMethodOwner' is expected to be a string const")
        val bootstrapMethodName = expression.getValueArgument(3)?.getStringConst()
            ?: fail("'bootstrapMethodName' is expected to be a string const")
        val bootstrapMethodDesc = expression.getValueArgument(4)?.getStringConst()
            ?: fail("'bootstrapMethodDesc' is expected to be a string const")
        val bootstrapMethodArgs = (expression.getValueArgument(5)?.safeAs<IrVararg>()
            ?: fail("'bootstrapMethodArgs' is expected to be a vararg"))

        val asmBootstrapMethodArgs = bootstrapMethodArgs.elements
            .map { generateBootstrapMethodArg(it, codegen) }
            .toTypedArray()

        val dynamicCalleeMethod = codegen.methodSignatureMapper.mapAsmMethod(dynamicCallee)
        val bootstrapMethodHandle = Handle(bootstrapMethodTag, bootstrapMethodOwner, bootstrapMethodName, bootstrapMethodDesc, false)

        val dynamicCallGenerator = IrCallGenerator.DefaultCallGenerator
        val dynamicCalleeArgumentTypes = dynamicCalleeMethod.argumentTypes
        for (i in dynamicCallee.valueParameters.indices) {
            val dynamicCalleeParameter = dynamicCallee.valueParameters[i]
            val dynamicCalleeArgument = dynamicCall.getValueArgument(i)
                ?: fail("No argument #$i in 'dynamicCall'")
            val dynamicCalleeArgumentType = dynamicCalleeArgumentTypes.getOrElse(i) {
                fail("No argument type #$i in dynamic callee: $dynamicCalleeMethod")
            }
            dynamicCallGenerator.genValueAndPut(dynamicCalleeParameter, dynamicCalleeArgument, dynamicCalleeArgumentType, codegen, data)
        }

        codegen.v.invokedynamic(dynamicCalleeMethod.name, dynamicCalleeMethod.descriptor, bootstrapMethodHandle, asmBootstrapMethodArgs)

        return MaterialValue(codegen, dynamicCalleeMethod.returnType, expression.type)
    }

    private fun generateBootstrapMethodArg(element: IrVarargElement, codegen: ExpressionCodegen): Any =
        when (element) {
            is IrRawFunctionReference ->
                generateMethodHandle(element, codegen)
            is IrCall ->
                when (element.symbol) {
                    codegen.context.ir.symbols.jvmMethodTypeIntrinsic ->
                        generateMethodType(element, codegen)
                    else ->
                        throw AssertionError("Unexpected callee in bootstrap method argument:\n${element.dump()}")
                }
            is IrConst<*> ->
                when (element.kind) {
                    IrConstKind.Byte -> (element.value as Byte).toInt()
                    IrConstKind.Short -> (element.value as Short).toInt()
                    IrConstKind.Int -> element.value as Int
                    IrConstKind.Long -> element.value as Long
                    IrConstKind.Float -> element.value as Float
                    IrConstKind.Double -> element.value as Double
                    IrConstKind.String -> element.value as String
                    else ->
                        throw AssertionError("Unexpected constant expression in bootstrap method argument:\n${element.dump()}")
                }
            else ->
                throw AssertionError("Unexpected bootstrap method argument:\n${element.dump()}")
        }

    private fun generateMethodHandle(irRawFunctionReference: IrRawFunctionReference, codegen: ExpressionCodegen): Any {
        val irFun = irRawFunctionReference.symbol.owner
        val irParentClass = irFun.parentAsClass
        val owner = codegen.typeMapper.mapOwner(irParentClass)
        val asmMethod = codegen.methodSignatureMapper.mapAsmMethod(irFun)
        val handleTag = when {
            irFun.dispatchReceiverParameter == null ->
                Opcodes.H_INVOKESTATIC
            else ->
                Opcodes.H_INVOKEVIRTUAL
        }
        return Handle(handleTag, owner.internalName, asmMethod.name, asmMethod.descriptor, irParentClass.isJvmInterface)
    }

    private fun generateMethodType(jvmMethodTypeCall: IrCall, codegen: ExpressionCodegen): Any {
        val irRawFunctionReference = jvmMethodTypeCall.getValueArgument(0) as? IrRawFunctionReference
            ?: throw AssertionError(
                "Argument in ${jvmMethodTypeCall.symbol.owner.name} call is expected to be a raw function reference:\n" +
                        jvmMethodTypeCall.dump()
            )
        val irFun = irRawFunctionReference.symbol.owner
        // TODO substitute signature
        val asmMethod = codegen.methodSignatureMapper.mapAsmMethod(irFun)
        return Type.getMethodType(asmMethod.descriptor)
    }

    private fun IrExpression.getIntConst() =
        if (this is IrConst<*> && kind == IrConstKind.Int)
            this.value as Int
        else
            null

    private fun IrExpression.getStringConst() =
        if (this is IrConst<*> && kind == IrConstKind.String)
            this.value as String
        else
            null

}