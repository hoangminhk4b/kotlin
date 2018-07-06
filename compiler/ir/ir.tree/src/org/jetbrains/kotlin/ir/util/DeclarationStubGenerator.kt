/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.lazy.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.impl.IrErrorExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.types.KotlinType

class DeclarationStubGenerator(
    moduleDescriptor: ModuleDescriptor,
    val symbolTable: SymbolTable,
    val origin: IrDeclarationOrigin
) {

    private val lazyTable = IrLazySymbolTable(this, symbolTable)

    internal var unboundSymbolGeneration: Boolean
        get() = lazyTable.unboundSymbolGeneration
        set(value) { lazyTable.unboundSymbolGeneration = value }


    private val typeTranslator = TypeTranslator(lazyTable, LazyScopedTypeParametersResolver())
    private val constantValueGenerator = ConstantValueGenerator(moduleDescriptor, lazyTable)

    init {
        typeTranslator.constantValueGenerator = constantValueGenerator
        constantValueGenerator.typeTranslator = typeTranslator
    }

    fun generateEmptyModuleFragmentStub(descriptor: ModuleDescriptor, irBuiltIns: IrBuiltIns): IrModuleFragment =
        IrModuleFragmentImpl(descriptor, irBuiltIns)

    fun generateOrGetEmptyExternalPackageFragmentStub(descriptor: PackageFragmentDescriptor): IrExternalPackageFragment {
        return symbolTable.declareExternalPackageFragment(descriptor)
    }

    fun generateMemberStub(descriptor: DeclarationDescriptor): IrDeclaration =
        when (descriptor) {
            is ClassDescriptor ->
                if (DescriptorUtils.isEnumEntry(descriptor))
                    generateEnumEntryStub(descriptor)
                else
                    generateClassStub(descriptor)
            is ClassConstructorDescriptor ->
                generateConstructorStub(descriptor)
            is FunctionDescriptor ->
                generateFunctionStub(descriptor)
            is PropertyDescriptor ->
                generatePropertyStub(descriptor)
            else ->
                throw AssertionError("Unexpected member descriptor: $descriptor")
        }

    internal fun generatePropertyStub(descriptor: PropertyDescriptor): IrProperty =
        IrPropertyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor).also { irProperty ->
            val getterDescriptor = descriptor.getter
            if (getterDescriptor == null) {
                irProperty.backingField =
                        symbolTable.declareField(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            origin,
                            descriptor.original,
                            descriptor.type.toIrType()
                        )
            } else {
                irProperty.getter = generateFunctionStub(getterDescriptor)
            }

            irProperty.setter = descriptor.setter?.let { generateFunctionStub(it) }
        }

    fun generateFunctionStub(descriptor: FunctionDescriptor): IrSimpleFunction {
        val referenced = symbolTable.referenceSimpleFunction(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        return symbolTable.declareSimpleFunction(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            if (descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
                IrDeclarationOrigin.FAKE_OVERRIDE
            } else {
                origin
            },
            descriptor.original
        ) { IrLazyFunction(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator) }
    }

    internal fun generateConstructorStub(descriptor: ClassConstructorDescriptor): IrConstructor {
        val referenced = symbolTable.referenceConstructor(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        return symbolTable.declareConstructor(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor.original
        ) { IrLazyConstructor(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator) }
    }

    private fun KotlinType.toIrType() = typeTranslator.translateType(this)

    internal fun generateValueParameterStub(descriptor: ValueParameterDescriptor): IrValueParameter {
        return IrValueParameterImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin,
            descriptor, descriptor.type.toIrType(), descriptor.varargElementType?.toIrType()
        ).also { irValueParameter ->
            if (descriptor.declaresDefaultValue()) {
                irValueParameter.defaultValue =
                        IrExpressionBodyImpl(
                            IrErrorExpressionImpl(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET, descriptor.type.toIrType(),
                                "Stub expression for default value of ${descriptor.name}"
                            )
                        )
            }
        }
    }

    internal fun generateClassStub(descriptor: ClassDescriptor): IrClass {
        val referenceClass = symbolTable.referenceClass(descriptor)
        if (referenceClass.isBound) {
            return referenceClass.owner
        }
        return symbolTable.declareClass(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor
        ) { IrLazyClass(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator) }
    }

    internal fun generateEnumEntryStub(descriptor: ClassDescriptor): IrEnumEntry {
        val referenced = symbolTable.referenceEnumEntry(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }
        return symbolTable.declareEnumEntry(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor)
    }

    internal fun generateOrGetTypeParameterStub(descriptor: TypeParameterDescriptor): IrTypeParameter {
//        val referenced = symbolTable.referenceTypeParameter(descriptor)
//        if (referenced.isBound) {
//            return referenced.owner
//        }
        //TODO: not sure about declareGlobalTypeParameter, maybe in some cases declareScopedTypeParameter is required
        //return symbolTable.declareGlobalTypeParameter(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor) {
            return IrLazyTypeParameter(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin,
                    IrTypeParameterSymbolImpl(descriptor), this, typeTranslator
            )
        //}
    }
}