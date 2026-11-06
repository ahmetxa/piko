package app.crimera.patches.newx.misc.mount

import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.newx.misc.extension.newXExtensionPatch
import app.crimera.patches.newx.utils.Constants.EXTENSION_PACKAGE
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import java.util.logging.Logger

/*
 * Root mount support for extension-owned components.
 *
 * A mount keeps the stock manifest registered with the package manager, so the activities and
 * receivers the patches add to the manifest do not exist for the system. The extension class
 * `MountComponentProxy` routes launches of those components through a registered stock component
 * and this patch makes the app's AppComponentFactory instantiate the real extension class in its
 * place. Standard installs are unaffected: their components are registered and never rerouted.
 */

private const val MOUNT_PROXY_DESCRIPTOR = "$EXTENSION_PACKAGE/mount/MountComponentProxy;"
private const val EXTENSION_ROOT = "Lapp/morphe/extension/"

private const val INTENT_DESCRIPTOR = "Landroid/content/Intent;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val CLASS_LOADER_DESCRIPTOR = "Ljava/lang/ClassLoader;"

private val routeMethod =
    methodReference("$MOUNT_PROXY_DESCRIPTOR->route(${INTENT_DESCRIPTOR}Landroid/content/Context;)V")
private val resolveClassNameMethod =
    methodReference(
        "$MOUNT_PROXY_DESCRIPTOR->resolveClassName($STRING_DESCRIPTOR${INTENT_DESCRIPTOR}I)$STRING_DESCRIPTOR",
    )

/** The framework base class every `android:appComponentFactory` must extend. */
internal const val FRAMEWORK_FACTORY_DESCRIPTOR = "Landroid/app/AppComponentFactory;"

/** An `AppComponentFactory` callback the hook rewrites, with the matching `MountComponentProxy.KIND_*`. */
internal data class FactoryCallback(
    val name: String,
    val returnType: String,
    val kind: Int,
) {
    /** `(ClassLoader, String className, Intent)`: p1, p2, p3 of the instance method. */
    val parameters = listOf(CLASS_LOADER_DESCRIPTOR, STRING_DESCRIPTOR, INTENT_DESCRIPTOR)
}

/** Must stay equal to `MountComponentProxy.KIND_ACTIVITY` / `KIND_RECEIVER`. */
internal val factoryCallbacks =
    listOf(
        FactoryCallback("instantiateActivity", "Landroid/app/Activity;", kind = 1),
        FactoryCallback("instantiateReceiver", "Landroid/content/BroadcastReceiver;", kind = 2),
    )

/**
 * Contract (Phase 2): inserts at index 0 of an existing callback implementation
 * `className = MountComponentProxy.resolveClassName(className, intent, kind)`, where className is
 * p2 and intent is p3 of the instance method, and kind is a scratch `const`.
 */
internal fun MutableMethod.insertResolveClassNameHook(callback: FactoryCallback) {
    throw PatchException("Phase 3 (T1): insertResolveClassNameHook is not implemented for ${callback.name}")
}

/**
 * Contract (Phase 2): adds `public <callback>(ClassLoader, String, Intent)<Ret>` to [this] factory
 * class, which declares no such method. Body: the resolve hook, then
 * `invoke-super {p0, p1, p2, p3}, <superclass>-><callback>(...)`, `move-result-object`, `return-object`.
 * [superclass] is this class's direct superclass. Returns the added method.
 */
internal fun MutableClass.addFactoryCallbackOverride(
    callback: FactoryCallback,
    superclass: String,
): MutableMethod {
    throw PatchException("Phase 3 (T1): addFactoryCallbackOverride is not implemented for ${callback.name} on $type/$superclass")
}

private val logger = Logger.getLogger("NewXMountComponentProxy")

/** The manifest's `android:appComponentFactory`, as a type descriptor, or null when it has none. */
private var appComponentFactoryDescriptor: String? = null

private val mountComponentProxyResourcePatch =
    resourcePatch {
        execute {
            document("AndroidManifest.xml").use { document ->
                val manifest = document.documentElement
                val application =
                    manifest.getElementsByTagName("application").item(0) as? Element
                        ?: throw PatchException("Mount proxy could not find the manifest application element")
                val factory = application.getAttribute("android:appComponentFactory").trim()
                appComponentFactoryDescriptor =
                    if (factory.isEmpty()) {
                        null
                    } else {
                        val className =
                            if (factory.startsWith(".")) manifest.getAttribute("package") + factory else factory
                        "L${className.replace('.', '/')};"
                    }
            }
        }
    }

internal val newXMountComponentProxyPatch =
    bytecodePatch(
        description = "Lets extension activities and receivers run when the patched app is root mounted.",
    ) {
        dependsOn(newXExtensionPatch, mountComponentProxyResourcePatch)

        execute {
            hookAppComponentFactory()
            routeExtensionIntents()
        }
    }

/**
 * Rewrites the class name at the start of the factory callbacks. The factory is the only process
 * side entry point that sees both the component class name and the launching intent.
 */
context(context: BytecodePatchContext)
private fun hookAppComponentFactory() {
    val factory = appComponentFactoryDescriptor
    if (factory == null) {
        // Standard installs register the extension components and never need the proxy.
        logger.warning("The app declares no AppComponentFactory; extension screens will not open on a root mount")
        return
    }

    factoryCallbacks.forEach { callback ->
        val name = callback.name
        val parameters = callback.parameters
        val returnType = callback.returnType
        val method = context.findFactoryCallback(factory, name, parameters, returnType)
        if (method == null) {
            logger.warning(
                "$factory does not implement $name in the app; extension components of that kind " +
                    "will not run on a root mount",
            )
            return@forEach
        }

        val classNameRegister = method.p0Register + 2
        val intentRegister = method.p0Register + 3
        method.insertHook(
            index = 0,
            // A branch back to the entry would only repeat the idempotent rewrite.
            relocateBranchTargets = false,
        ) {
            invokeStatic(resolveClassNameMethod, classNameRegister, intentRegister)
            moveResult(classNameRegister, STRING_DESCRIPTOR)
        }
    }
}

/** The most derived implementation of a factory callback that lives in the app's dex. */
private fun BytecodePatchContext.findFactoryCallback(
    factory: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): MutableMethod? {
    var type: String? = factory
    val visited = mutableSetOf<String>()
    while (type != null && visited.add(type)) {
        val classDef = classDefByOrNull(type) ?: return null
        val declares =
            classDef.methods.any { method ->
                method.matches(name, parameters, returnType) && method.implementation != null
            }
        if (declares) {
            return mutableClassDefBy(type).methods.single { method ->
                method.matches(name, parameters, returnType)
            }
        }
        type = classDef.superclass
    }
    return null
}

private fun com.android.tools.smali.dexlib2.iface.Method.matches(
    name: String,
    parameters: List<String>,
    returnType: String,
): Boolean =
    this.name == name &&
        this.returnType == returnType &&
        parameterTypes.map(CharSequence::toString) == parameters

private data class IntentConstruction(
    val index: Int,
    val intentRegister: Int,
    val contextRegister: Int,
)

/**
 * Calls the router after every `new Intent(Context, Class)` in extension code, the only shape the
 * extensions use to address their own components. The router leaves registered components alone.
 */
context(context: BytecodePatchContext)
private fun routeExtensionIntents() {
    val owners = mutableListOf<String>()
    context.classDefForEach { classDef ->
        if (!classDef.type.startsWith(EXTENSION_ROOT) || classDef.type == MOUNT_PROXY_DESCRIPTOR) {
            return@classDefForEach
        }
        val constructsIntent =
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.isIntentClassConstructor()
                } == true
            }
        if (constructsIntent) owners += classDef.type
    }

    var routed = 0
    owners.forEach { owner ->
        context.mutableClassDefBy(owner).methods.forEach { method ->
            val constructions = method.intentConstructions()
            // Insert from the end so earlier indices stay valid.
            constructions.sortedByDescending(IntentConstruction::index).forEach { construction ->
                method.insertHook(
                    index = construction.index + 1,
                    // A jump to the next instruction skips the reroute; the intent then keeps its
                    // original component, which is the standard install behaviour.
                    relocateBranchTargets = false,
                ) {
                    invokeStatic(routeMethod, construction.intentRegister, construction.contextRegister)
                }
                routed++
            }
        }
    }

    if (routed == 0) {
        throw PatchException("Mount proxy found no extension intent addressed to an extension component")
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isIntentClassConstructor(): Boolean {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val reference = getReference<MethodReference>() ?: return false
    return reference.definingClass == INTENT_DESCRIPTOR &&
        reference.name == "<init>" &&
        reference.returnType == "V" &&
        reference.parameterTypes.map(CharSequence::toString) ==
        listOf("Landroid/content/Context;", "Ljava/lang/Class;")
}

private fun MutableMethod.intentConstructions(): List<IntentConstruction> {
    if (implementation == null) return emptyList()
    return instructions.mapIndexedNotNull { index, instruction ->
        if (!instruction.isIntentClassConstructor()) return@mapIndexedNotNull null
        val registers =
            when (instruction) {
                is FiveRegisterInstruction ->
                    listOf(instruction.registerC, instruction.registerD, instruction.registerE)
                is RegisterRangeInstruction ->
                    List(3) { offset -> instruction.startRegister + offset }
                else -> throw PatchException("Unexpected Intent constructor encoding in $this at $index")
            }
        IntentConstruction(index = index, intentRegister = registers[0], contextRegister = registers[1])
    }
}
