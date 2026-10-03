package org.readera.openreadera.engine

internal object MobiConverter {
    val isAvailable: Boolean = try {
        System.loadLibrary("openreadera_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic
    external fun nativeConvertMobiToEpub(sourcePath: String, targetPath: String): Boolean
}
