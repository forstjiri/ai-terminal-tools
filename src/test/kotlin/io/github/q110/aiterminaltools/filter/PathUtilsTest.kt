package io.github.q110.aiterminaltools.filter

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.nio.file.Files
import java.util.concurrent.Callable

class PathUtilsTest : BasePlatformTestCase() {

    fun testNormalizesALeadingHomeShorthand() {
        val home = System.getProperty("user.home").replace('\\', '/')

        assertEquals(
            "$home/.opencode/plan/FOR-2528.md",
            normalizePath("~/.opencode/plan/FOR-2528.md")
        )
    }

    fun testResolvesAnExistingAbsoluteFileOutsideTheProject() {
        val path = Files.createTempFile("aitt-external-", ".md")
        try {
            val resolved = ReadAction.nonBlocking(Callable {
                findProjectPath(project, path.toString())
            }).executeSynchronously()
            assertNotNull(resolved)
            assertEquals(path.toAbsolutePath().toString(), resolved!!.path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
