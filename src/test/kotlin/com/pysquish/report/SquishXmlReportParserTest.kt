package com.pysquish.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SquishXmlReportParserTest {

    // Mirrors the real xml3.4 shape: cases and sections are both <test>,
    // sections nest, and message text lives in a <name> child.
    private val sample = """
        <SquishReport version="3.4">
          <test type="testsuite">
            <prolog time="t0"><name>suite_demo</name></prolog>
            <test type="testcase">
              <prolog time="t1"><name>tst_login</name></prolog>
              <message time="t2" type="LOG"><name>Starting</name><location>a.py:1</location></message>
              <test type="section">
                <prolog time="t3"><name>Feature A</name></prolog>
                <message type="PASS"><name>step ok</name></message>
                <test type="section">
                  <prolog time="t4"><name>Object X</name></prolog>
                  <message type="FAIL"><name>boom</name></message>
                  <epilog time="t5"/>
                </test>
                <epilog time="t6"/>
              </test>
              <epilog time="t7"/>
            </test>
          </test>
        </SquishReport>
    """.trimIndent()

    private fun parse(xml: String): SquishRunReport {
        val file = Files.createTempFile("results", ".xml")
        Files.writeString(file, xml)
        return SquishXmlReportParser.parse(file)!!
    }

    @Test
    fun `parses a single test case with its verdict`() {
        val report = parse(sample)
        assertEquals(1, report.tests.size)
        val test = report.tests.first()
        assertEquals("tst_login", test.name)
        assertEquals(SquishVerdict.FAIL, test.verdict)
        assertEquals(SquishVerdict.FAIL, report.verdictOf("tst_login"))
    }

    @Test
    fun `nests sections into multiple foldable layers`() {
        val test = parse(sample).tests.first()

        // Test root: a LOG entry then the "Feature A" section.
        val log = test.root.children[0] as SquishReportNode.Entry
        assertEquals(SquishLogLevel.LOG, log.level)
        assertEquals("Starting", log.message)

        val featureA = test.root.children[1] as SquishReportNode.Section
        assertEquals("Feature A", featureA.title)
        assertTrue(featureA.containsFailure)

        // Feature A: a PASS entry then a nested "Object X" section (2nd layer).
        val pass = featureA.children[0] as SquishReportNode.Entry
        assertEquals(SquishLogLevel.PASS, pass.level)
        assertEquals("step ok", pass.message)

        val objectX = featureA.children[1] as SquishReportNode.Section
        assertEquals("Object X", objectX.title)
        assertTrue(objectX.containsFailure)

        // Object X (3rd layer): the FAIL entry with text from <name>.
        val fail = objectX.children[0] as SquishReportNode.Entry
        assertEquals(SquishLogLevel.FAIL, fail.level)
        assertEquals("boom", fail.message)
    }

    @Test
    fun `message text comes from the name child`() {
        val test = parse(sample).tests.first()
        val log = test.root.children[0] as SquishReportNode.Entry
        assertNotNull(log.message)
        assertEquals("a.py:1", log.detail)
    }
}
