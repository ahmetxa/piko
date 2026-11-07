package app.crimera.patches.newx.misc.mount

// Guards the 12.40.1-prod.01 root-mount regression: the manifest's CoreComponentFactory keeps only
// <init> in dex (R8 removed its overrides), so the old hook found no callback to patch and silently
// skipped, and Piko settings opened the stock proxy activity (PerfDiagnosticsActivity) instead.
// These tests prove a missing callback is added with the resolve hook ahead of the super call, and
// that an existing callback receives the same hook in front of its original body.

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.toInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.HiddenApiRestriction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MountComponentProxyPatchTest {
    private val factoryType = "Landroidx/core/app/CoreComponentFactory;"
    private val resolveDescriptor =
        "Lapp/morphe/extension/newx/mount/MountComponentProxy;->resolveClassName" +
            "(Ljava/lang/String;Landroid/content/Intent;I)Ljava/lang/String;"
    private val constOpcodes = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

    @Test
    fun `a factory without the callback gains an override that resolves before calling super`() {
        assertEquals(
            listOf("instantiateActivity" to 1, "instantiateReceiver" to 2),
            factoryCallbacks.map { it.name to it.kind },
        )

        factoryCallbacks.forEach { callback ->
            val factory = factoryWithOnlyInit()
            val added = factory.addFactoryCallbackOverride(callback, FRAMEWORK_FACTORY_DESCRIPTOR)

            assertEquals(2, factory.methods.size, "${callback.name}: <init> plus exactly one override")
            assertTrue(factory.methods.contains(added), "${callback.name}: returned method must be the added one")
            assertEquals(callback.name, added.name)
            assertEquals(callback.returnType, added.returnType)
            assertEquals(
                listOf("Ljava/lang/ClassLoader;", "Ljava/lang/String;", "Landroid/content/Intent;"),
                added.parameterTypes.map(CharSequence::toString),
            )
            assertTrue(added.accessFlags and AccessFlags.PUBLIC.value != 0, "${callback.name} must be public")
            assertEquals(0, added.accessFlags and AccessFlags.FINAL.value, "${callback.name} must not be final")
            assertEquals(0, added.accessFlags and AccessFlags.SYNTHETIC.value, "${callback.name} must not be synthetic")

            val registerCount = added.implementation!!.registerCount
            val p0 = registerCount - 4
            val (p1, p2, p3) = Triple(p0 + 1, p0 + 2, p0 + 3)
            val instructions = added.instructions.toList()
            assertEquals(
                listOf(
                    instructions[0].opcode,
                    Opcode.INVOKE_STATIC,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.INVOKE_SUPER,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.RETURN_OBJECT,
                ),
                instructions.map { it.opcode },
                "${callback.name}: unexpected override body",
            )

            val constant = instructions[0]
            assertTrue(constant.opcode in constOpcodes, "${callback.name}: first instruction must load the kind")
            val kindRegister = (constant as OneRegisterInstruction).registerA
            assertEquals(callback.kind, (constant as NarrowLiteralInstruction).narrowLiteral)
            assertTrue(kindRegister !in p0..p3, "${callback.name}: kind scratch v$kindRegister clobbers this or a parameter")

            val resolve = instructions[1]
            assertEquals(Opcode.INVOKE_STATIC, resolve.opcode)
            assertEquals(resolveDescriptor, resolve.getReference<MethodReference>().toString())
            assertEquals(listOf(p2, p3, kindRegister), resolve.registers())

            val moveResult = instructions[2]
            assertEquals(Opcode.MOVE_RESULT_OBJECT, moveResult.opcode)
            assertEquals(p2, (moveResult as OneRegisterInstruction).registerA, "the resolved name must replace p2")

            val superCall = instructions[3]
            assertEquals(Opcode.INVOKE_SUPER, superCall.opcode)
            assertEquals(
                "Landroid/app/AppComponentFactory;->${callback.name}" +
                    "(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Intent;)${callback.returnType}",
                superCall.getReference<MethodReference>().toString(),
            )
            assertEquals(listOf(p0, p1, p2, p3), superCall.registers())

            val superResult = instructions[4]
            assertEquals(Opcode.MOVE_RESULT_OBJECT, superResult.opcode)
            val resultRegister = (superResult as OneRegisterInstruction).registerA
            val returned = instructions[5]
            assertEquals(Opcode.RETURN_OBJECT, returned.opcode)
            assertEquals(resultRegister, (returned as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `an existing callback keeps its body behind the resolve hook`() {
        factoryCallbacks.forEach { callback ->
            val method = existingOverride(callback)
            method.insertResolveClassNameHook(callback)

            val instructions = method.instructions.toList()
            assertEquals(
                listOf(
                    instructions[0].opcode,
                    Opcode.INVOKE_STATIC,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.INVOKE_SUPER,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.RETURN_OBJECT,
                ),
                instructions.map { it.opcode },
                "${callback.name}: hook must be a 3-instruction prefix before the original body",
            )

            val registerCount = method.implementation!!.registerCount
            val p0 = registerCount - 4
            val kindRegister = (instructions[0] as OneRegisterInstruction).registerA
            assertEquals(callback.kind, (instructions[0] as NarrowLiteralInstruction).narrowLiteral)
            assertTrue(kindRegister !in p0..(p0 + 3), "${callback.name}: scratch clobbers this or a parameter")
            assertEquals(resolveDescriptor, instructions[1].getReference<MethodReference>().toString())
            assertEquals(listOf(p0 + 2, p0 + 3, kindRegister), instructions[1].registers())
            assertEquals(p0 + 2, (instructions[2] as OneRegisterInstruction).registerA)
            assertEquals(
                listOf(p0, p0 + 1, p0 + 2, p0 + 3),
                instructions[3].registers(),
                "the original super call must be preserved",
            )
        }
    }

    private fun Instruction.registers(): List<Int> {
        val five = this as FiveRegisterInstruction
        return listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG)
            .take(five.registerCount)
    }

    private fun factoryWithOnlyInit(): MutableClass {
        val implementation = MethodImplementationBuilder(1)
        implementation.addInstruction("return-void".toInstruction())
        val init =
            ImmutableMethod(
                factoryType,
                "<init>",
                emptyList<ImmutableMethodParameter>(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                emptySet<ImmutableAnnotation>(),
                emptySet<HiddenApiRestriction>(),
                implementation.methodImplementation,
            )
        return MutableClass(
            ImmutableClassDef(
                factoryType,
                AccessFlags.PUBLIC.value,
                FRAMEWORK_FACTORY_DESCRIPTOR,
                emptyList<String>(),
                null,
                emptySet<ImmutableAnnotation>(),
                emptyList<ImmutableField>(),
                listOf(init),
            ),
        )
    }

    /** `<callback>(ClassLoader, String, Intent)` with 2 locals, body: `super.<callback>(...)` then return it. */
    private fun existingOverride(callback: FactoryCallback): MutableMethod {
        val implementation = MethodImplementationBuilder(6)
        implementation.addInstruction("return-void".toInstruction())
        val method =
            MutableMethod(
                ImmutableMethod(
                    factoryType,
                    callback.name,
                    callback.parameters.map { ImmutableMethodParameter(it, emptySet<ImmutableAnnotation>(), null) },
                    callback.returnType,
                    AccessFlags.PUBLIC.value,
                    emptySet<ImmutableAnnotation>(),
                    emptySet<HiddenApiRestriction>(),
                    implementation.methodImplementation,
                ),
            )
        method.addInstructionsWithLabels(
            0,
            """
                invoke-super {v2, v3, v4, v5}, Landroid/app/AppComponentFactory;->${callback.name}(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Intent;)${callback.returnType}
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )
        // Drop the placeholder return-void left behind the inserted body.
        method.removeInstruction(3)
        return method
    }
}
