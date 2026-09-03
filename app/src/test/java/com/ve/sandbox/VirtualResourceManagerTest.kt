package com.ve.sandbox

import android.content.res.AssetManager
import org.junit.Test

class VirtualResourceManagerTest {

    @Test
    fun listDeclaredMethods() {
        println("Declared methods on AssetManager in stub jar:")
        AssetManager::class.java.declaredMethods.forEach {
            println("  ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}): ${it.returnType.simpleName}")
        }
    }
}
