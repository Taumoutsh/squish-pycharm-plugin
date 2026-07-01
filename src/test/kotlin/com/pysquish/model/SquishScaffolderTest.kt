package com.pysquish.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class SquishScaffolderTest {

    @Test
    fun `suite and test names get their conventional prefixes`() {
        assertEquals("suite_login", SquishScaffolder.suiteDirName("login"))
        assertEquals("suite_login", SquishScaffolder.suiteDirName("suite_login"))
        assertEquals("tst_add", SquishScaffolder.testDirName("add"))
        assertEquals("tst_add", SquishScaffolder.testDirName("tst_add"))
    }

    @Test
    fun `name validation rejects empty and illegal characters`() {
        assertNull(SquishScaffolder.validateName("valid_name-1"))
        assertNotNull(SquishScaffolder.validateName(""))
        assertNotNull(SquishScaffolder.validateName("has space"))
        assertNotNull(SquishScaffolder.validateName("bad/slash"))
    }

    @Test
    fun `suite conf contains the AUT and Python language`() {
        val conf = SquishScaffolder.suiteConf("addressbook")
        assertTrue(conf.contains("AUT=addressbook"))
        assertTrue(conf.contains("LANGUAGE=Python"))
        assertFalse(conf.contains("TEST_CASES"), "TEST_CASES is added only when a test is created")
    }

    @Test
    fun `addTestCase appends to an existing line without duplicating`() {
        val conf = "AUT=x\nTEST_CASES=tst_a tst_b\nVERSION=3\n"
        val once = SquishScaffolder.addTestCase(conf, "tst_c")
        assertTrue(once.contains("TEST_CASES=tst_a tst_b tst_c"))
        val twice = SquishScaffolder.addTestCase(once, "tst_c")
        assertEquals(once, twice, "existing test is not added twice")
    }

    @Test
    fun `addTestCase preserves a colon separator`() {
        val conf = "TEST_CASES: tst_a\n"
        assertTrue(SquishScaffolder.addTestCase(conf, "tst_b").contains("TEST_CASES:tst_a tst_b"))
    }

    @Test
    fun `addTestCase inserts a TEST_CASES line in alphabetical key order`() {
        val conf = "AUT=x\nLANGUAGE=Python\nOBJECTMAPSTYLE=script\nVERSION=3\nWRAPPERS=Qt\n"
        val updated = SquishScaffolder.addTestCase(conf, "tst_first")
        val lines = updated.split("\n").filter { it.isNotBlank() }
        val idx = lines.indexOfFirst { it.startsWith("TEST_CASES") }
        assertTrue(idx > 0)
        assertTrue(lines[idx - 1].startsWith("OBJECTMAPSTYLE"))
        assertTrue(lines[idx + 1].startsWith("VERSION"))
    }

    @Test
    fun `bundled template renders a startApplication when an AUT is given`() {
        val tmpl = SquishScaffolder.bundledTestTemplate()
        val withAut = SquishScaffolder.renderTestScript(
            tmpl,
            mapOf("testName" to "tst_x", "suiteName" to "suite_x", "aut" to "myapp", "hasAut" to true, "date" to "2026-01-01"),
        )
        assertTrue(withAut.contains("startApplication(\"myapp\")"))
        assertTrue(withAut.contains("def main():"))

        val withoutAut = SquishScaffolder.renderTestScript(
            tmpl,
            mapOf("testName" to "tst_x", "suiteName" to "suite_x", "aut" to "", "hasAut" to false, "date" to "2026-01-01"),
        )
        assertFalse(withoutAut.contains("startApplication"))
        assertTrue(withoutAut.contains("pass"))
    }

    @Test
    fun `createSuite writes a suite conf and createTest wires it up`(@TempDir tmp: Path) {
        val suiteDir = SquishScaffolder.createSuite(tmp, "demo", "myapp").getOrThrow()
        assertEquals("suite_demo", suiteDir.fileName.toString())
        assertTrue(Files.isRegularFile(suiteDir.resolve("suite.conf")))

        val script = SquishScaffolder.createTest(
            suiteDir = suiteDir,
            rawName = "smoke",
            templateText = SquishScaffolder.bundledTestTemplate(),
            suiteName = "suite_demo",
            aut = "myapp",
            language = "Python",
        ).getOrThrow()

        assertEquals("test.py", script.fileName.toString())
        assertEquals("tst_smoke", script.parent.fileName.toString())
        assertTrue(script.readText().contains("startApplication(\"myapp\")"))
        assertTrue(suiteDir.resolve("suite.conf").readText().contains("TEST_CASES=tst_smoke"))
    }

    @Test
    fun `createSuite refuses to overwrite an existing folder`(@TempDir tmp: Path) {
        SquishScaffolder.createSuite(tmp, "dup", "").getOrThrow()
        assertTrue(SquishScaffolder.createSuite(tmp, "dup", "").isFailure)
    }
}
