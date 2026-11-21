package eu.kanade.tachiyomi.util

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

/**
 * The WebGPU reader's JNI symbols (`Java_androidx_webgpu_GPU_createInstance` and friends) are
 * bound to class and method names. A minified beta that shrinks those classes reports
 * "No implementation found ... createInstance" and silently opens on the legacy pager, so the
 * keep rules are a correctness invariant, not a build detail worth trusting to a comment.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuProguardRulesTest {
    private val rules: String
        get() {
            val file = File("proguard-rules.pro").takeIf(File::exists)
                ?: File("app/proguard-rules.pro")
            return file.readText()
        }

    @Test
    fun `Dawn wrapper classes survive shrinking`() {
        rules shouldContain "-keep class androidx.webgpu.** { *; }"
    }

    @Test
    fun `WebGPU renderer classes survive shrinking`() {
        rules shouldContain "-keep class ca.mpreg.webgpuviewer.** { *; }"
    }

    @Test
    fun `native method declarations are never renamed`() {
        rules shouldContain "-keepclasseswithmembernames class * {\n    native <methods>;\n}"
    }
}
