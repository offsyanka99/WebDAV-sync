package org.vovchenko.webdavsync.build

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites the dav4jvm jar so CalDAV and CardDAV factories are no longer reachable.
 * R8 can then drop those types. Other classes and methods are copied unchanged.
 *
 * This is a task input, not a registered artifact transform. Requesting a custom
 * artifact type from the Android configurations made AGP read the rewritten jar as
 * AAR metadata.
 */
object StripDav4jvmFactories {
    fun rewriteJar(input: java.io.File, output: java.io.File) {
            output.parentFile?.mkdirs()
            ZipFile(input).use { zip ->
                ZipOutputStream(output.outputStream()).use { zipOut ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        val bytes = zip.getInputStream(entry).readBytes()
                        zipOut.write(if (entry.isDirectory) bytes else rewriteClass(entry.name, bytes))
                        zipOut.closeEntry()
                    }
                }
            }
    }
}

private fun rewriteClass(entryName: String, bytes: ByteArray): ByteArray {
    if (entryName != "at/bitfire/dav4jvm/PropertyRegistry.class" &&
        entryName != "at/bitfire/dav4jvm/property/webdav/ResourceType\$Factory.class"
    ) {
        return bytes
    }
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(Dav4jvmClassVisitor(writer), 0)
    return writer.toByteArray()
}

private class Dav4jvmClassVisitor(
    next: ClassVisitor,
) : ClassVisitor(Opcodes.ASM9, next) {
    private var className: String = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        className = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return when {
            className == "at/bitfire/dav4jvm/PropertyRegistry" && name == "registerDefaultFactories" ->
                RegistryMethodVisitor(api, delegate)
            className == "at/bitfire/dav4jvm/property/webdav/ResourceType\$Factory" &&
                name == "create" &&
                descriptor == RESOURCE_TYPE_CREATE ->
                ResourceTypeMethodVisitor(api, delegate)
            else -> delegate
        }
    }
}

    /**
     * [at.bitfire.dav4jvm.PropertyRegistry.registerDefaultFactories] fills one array.
     * Entries whose factory owner is CalDAV or CardDAV are removed. The array length and
     * the indexes are rewritten from what the class file contains.
     */
    private class RegistryMethodVisitor(
        api: Int,
        private val delegate: MethodVisitor,
    ) : MethodVisitor(api, null) {
        private val insns = mutableListOf<Insn>()
        private var maxStack = 4
        private var maxLocals = 2

        override fun visitCode() = Unit

        override fun visitInsn(opcode: Int) {
            insns += Insn.Simple(opcode)
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            insns += Insn.IntOp(opcode, operand)
        }

        override fun visitVarInsn(opcode: Int, variable: Int) {
            insns += Insn.Var(opcode, variable)
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            insns += Insn.TypeOp(opcode, type)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            insns += Insn.Field(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            insns += Insn.Method(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
            error("registerDefaultFactories gained a branch; the factory list rewrite does not apply")
        }

        override fun visitLdcInsn(value: Any) {
            error("registerDefaultFactories gained an ldc; the factory list rewrite does not apply")
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
            this.maxStack = maxStack
            this.maxLocals = maxLocals
        }

        override fun visitEnd() {
            val parsed = parseRegistry(insns)
            val kept = parsed.entries.filterNot { isCalDavOrCardDav(it.owner) }
            if (kept.size == parsed.entries.size) {
                error("registerDefaultFactories did not name a CalDAV or CardDAV factory")
            }
            REQUIRED_WEBDAV_FACTORIES.forEach { required ->
                if (kept.none { it.owner == required }) {
                    error("registerDefaultFactories no longer registers $required")
                }
            }
            delegate.visitCode()
            delegate.visitVarInsn(Opcodes.ALOAD, 0)
            pushInt(delegate, kept.size)
            delegate.visitTypeInsn(Opcodes.ANEWARRAY, PROPERTY_FACTORY)
            delegate.visitVarInsn(Opcodes.ASTORE, 1)
            kept.forEachIndexed { index, field ->
                delegate.visitVarInsn(Opcodes.ALOAD, 1)
                pushInt(delegate, index)
                delegate.visitFieldInsn(Opcodes.GETSTATIC, field.owner, field.name, field.descriptor)
                delegate.visitInsn(Opcodes.AASTORE)
            }
            parsed.tail.forEach { it.emit(delegate) }
            delegate.visitMaxs(maxStack, maxLocals)
            delegate.visitEnd()
        }
    }

    /**
     * ResourceType.create compares a few collection names against CalDAV and CardDAV singletons.
     * Those comparisons are what keep the two classes live. Each one is replaced with the same
     * comparison against [WebDAV.Collection], which this method already uses, so instruction
     * sizes and stack frames stay as they were. A non-collection name still falls through and
     * is stored as the name from the XML.
     */
    private class ResourceTypeMethodVisitor(
        api: Int,
        delegate: MethodVisitor,
    ) : MethodVisitor(api, delegate) {
        private var replaced = 0

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            if (isCalDavOrCardDav(owner)) {
                replaced++
                super.visitFieldInsn(Opcodes.GETSTATIC, WEBDAV, "INSTANCE", WEBDAV_TYPE)
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor)
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (isCalDavOrCardDav(owner)) {
                replaced++
                super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WEBDAV,
                    "getCollection",
                    PROPERTY_NAME_GETTER,
                    false,
                )
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }

        override fun visitEnd() {
            if (replaced == 0) {
                error("ResourceType.create no longer references CalDAV or CardDAV")
            }
            super.visitEnd()
        }
    }

private const val RESOURCE_TYPE_CREATE =
    "(Lorg/xmlpull/v1/XmlPullParser;)Lat/bitfire/dav4jvm/property/webdav/ResourceType;"
private const val PROPERTY_FACTORY = "at/bitfire/dav4jvm/PropertyFactory"
private const val WEBDAV = "at/bitfire/dav4jvm/property/webdav/WebDAV"
private const val WEBDAV_TYPE = "Lat/bitfire/dav4jvm/property/webdav/WebDAV;"
private const val PROPERTY_NAME_GETTER = "()Lat/bitfire/dav4jvm/Property\$Name;"

private val REQUIRED_WEBDAV_FACTORIES = listOf(
    "at/bitfire/dav4jvm/property/webdav/GetETag\$Factory",
    "at/bitfire/dav4jvm/property/webdav/GetContentLength\$Factory",
    "at/bitfire/dav4jvm/property/webdav/GetLastModified\$Factory",
    "at/bitfire/dav4jvm/property/webdav/ResourceType\$Factory",
    "at/bitfire/dav4jvm/property/webdav/QuotaAvailableBytes\$Factory",
    "at/bitfire/dav4jvm/property/webdav/QuotaUsedBytes\$Factory",
)

private fun isCalDavOrCardDav(owner: String): Boolean =
    owner.startsWith("at/bitfire/dav4jvm/property/caldav/") ||
        owner.startsWith("at/bitfire/dav4jvm/property/carddav/")

private sealed interface Insn {
    fun emit(visitor: MethodVisitor)

    data class Simple(val opcode: Int) : Insn {
        override fun emit(visitor: MethodVisitor) = visitor.visitInsn(opcode)
    }

    data class IntOp(val opcode: Int, val operand: Int) : Insn {
        override fun emit(visitor: MethodVisitor) = visitor.visitIntInsn(opcode, operand)
    }

    data class Var(val opcode: Int, val variable: Int) : Insn {
        override fun emit(visitor: MethodVisitor) = visitor.visitVarInsn(opcode, variable)
    }

    data class TypeOp(val opcode: Int, val type: String) : Insn {
        override fun emit(visitor: MethodVisitor) = visitor.visitTypeInsn(opcode, type)
    }

    data class Field(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
    ) : Insn {
        override fun emit(visitor: MethodVisitor) = visitor.visitFieldInsn(opcode, owner, name, descriptor)
    }

    data class Method(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
        val isInterface: Boolean,
    ) : Insn {
        override fun emit(visitor: MethodVisitor) =
            visitor.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

private data class FactoryField(val owner: String, val name: String, val descriptor: String)

private data class ParsedRegistry(val entries: List<FactoryField>, val tail: List<Insn>)

private fun parseRegistry(insns: List<Insn>): ParsedRegistry {
    var index = 0
    fun next(): Insn = insns.getOrNull(index++)
        ?: error("registerDefaultFactories ended before the factory array was closed")

    val loadThis = next()
    val length = next()
    val newArray = next()
    val storeArray = next()
    if (loadThis != Insn.Var(Opcodes.ALOAD, 0) ||
        length.intConstant() == null ||
        newArray != Insn.TypeOp(Opcodes.ANEWARRAY, PROPERTY_FACTORY) ||
        storeArray != Insn.Var(Opcodes.ASTORE, 1)
    ) {
        error("registerDefaultFactories does not start by allocating a PropertyFactory array")
    }
    val declared = length.intConstant()!!
    val entries = mutableListOf<FactoryField>()
    while (index < insns.size) {
        val slotLoad = insns[index]
        if (slotLoad != Insn.Var(Opcodes.ALOAD, 1)) break
        if (index + 3 >= insns.size) break
        val slot = insns[index + 1].intConstant()
        val field = insns[index + 2] as? Insn.Field
        val store = insns[index + 3]
        if (slot == null || field == null || field.opcode != Opcodes.GETSTATIC || store != Insn.Simple(Opcodes.AASTORE)) {
            break
        }
        if (slot != entries.size) {
            error("registerDefaultFactories index $slot is not the next slot ${entries.size}")
        }
        entries += FactoryField(field.owner, field.name, field.descriptor)
        index += 4
    }
    if (entries.size != declared) {
        error("registerDefaultFactories declared $declared factories and stored ${entries.size}")
    }
    if (entries.isEmpty()) error("registerDefaultFactories stored no factories")
    return ParsedRegistry(entries, insns.drop(index))
}

private fun Insn.intConstant(): Int? = when (this) {
    is Insn.Simple -> when (opcode) {
        Opcodes.ICONST_M1 -> -1
        Opcodes.ICONST_0 -> 0
        Opcodes.ICONST_1 -> 1
        Opcodes.ICONST_2 -> 2
        Opcodes.ICONST_3 -> 3
        Opcodes.ICONST_4 -> 4
        Opcodes.ICONST_5 -> 5
        else -> null
    }
    is Insn.IntOp -> if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) operand else null
    else -> null
}

private fun pushInt(visitor: MethodVisitor, value: Int) {
    when (value) {
        in -1..5 -> visitor.visitInsn(Opcodes.ICONST_0 + value)
        in Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt() -> visitor.visitIntInsn(Opcodes.BIPUSH, value)
        else -> visitor.visitIntInsn(Opcodes.SIPUSH, value)
    }
}
