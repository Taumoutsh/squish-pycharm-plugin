package com.pysquish.report

import com.intellij.openapi.diagnostic.logger
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Parses a Squish `xml3.4` report into the [SquishRunReport] model.
 *
 * Schema shape (the important bits):
 * ```
 * <test type="testsuite">
 *   <prolog><name>suite</name></prolog>
 *   <test type="testcase">
 *     <prolog><name>tst_case</name></prolog>
 *     <message type="LOG"><name>text</name><location/></message>
 *     <test type="section">              <!-- sections are <test type="section"> -->
 *       <prolog><name>Section</name></prolog>
 *       <message type="PASS"><name>..</name></message>
 *       <test type="section"> ... </test>  <!-- nested arbitrarily deep -->
 *       <epilog/>
 *     </test>
 *     <epilog/>
 *   </test>
 * </test>
 * ```
 * Both test cases and sections are `<test>` elements distinguished by `type`;
 * message text is in a `<name>` child. The parser tolerates other Squish XML
 * shapes (`<section>`, `<verification>`, `<result>`) as a fallback.
 */
object SquishXmlReportParser {

    private val LOG = logger<SquishXmlReportParser>()

    private val TEXT_CHILDREN = setOf("name", "description", "text", "detail")

    /** Locates and parses `results.xml` under [reportDir]; null on any failure. */
    fun parse(reportDir: Path): SquishRunReport? {
        val xml = locateResultsXml(reportDir) ?: run {
            LOG.info("No results.xml found under $reportDir")
            return null
        }
        return runCatching { parseFile(xml) }
            .onFailure { LOG.warn("Failed to parse Squish report $xml", it) }
            .getOrNull()
    }

    private fun locateResultsXml(dir: Path): Path? {
        if (dir.isRegularFile()) return dir
        if (!dir.isDirectory()) return null
        val direct = dir.resolve("results.xml")
        if (direct.isRegularFile()) return direct
        return runCatching {
            Files.walk(dir).use { stream ->
                stream.filter { it.isRegularFile() && it.name.endsWith(".xml") }
                    .sorted(compareByDescending { it.name == "results.xml" })
                    .findFirst().orElse(null)
            }
        }.getOrNull()
    }

    private fun parseFile(xml: Path): SquishRunReport {
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isNamespaceAware = false
        }
        val doc = Files.newInputStream(xml).use { factory.newDocumentBuilder().parse(it) }
        doc.documentElement.normalize()

        return SquishRunReport(collectCases(doc.documentElement).map { parseCase(it) })
    }

    /** Test cases are `<test type="testcase">`; falls back for other schemas. */
    private fun collectCases(root: Element): List<Element> {
        val allTests = mutableListOf<Element>()
        collectByTag(root, "test", allTests)

        val cases = allTests.filter { typeOf(it) == "testcase" }
        if (cases.isNotEmpty()) return cases

        // Fallback: <test> that are neither sections nor the suite container.
        val nonStructural = allTests.filter { typeOf(it) !in setOf("section", "testsuite") }
        if (nonStructural.isNotEmpty()) return nonStructural

        // Last resort: leaf <test> elements, or the root itself.
        val leaves = allTests.filter { t -> childElements(t).none { descendantHasTag(it, "test") } }
        return leaves.ifEmpty { if (allTests.isEmpty()) listOf(root) else allTests }
    }

    private fun parseCase(caseEl: Element): SquishTestReport {
        val name = nameOf(caseEl) ?: caseEl.getAttribute("name").ifBlank { "test" }
        val root = SquishReportNode.Section(title = name, timestamp = timeOf(caseEl))
        for (child in childElements(caseEl)) appendNode(child, root.children)
        return SquishTestReport(name = name, verdict = verdictOf(root), root = root)
    }

    /** Appends the report node(s) produced by [el] into [out]. */
    private fun appendNode(el: Element, out: MutableList<SquishReportNode>) {
        val tag = el.tagName.lowercase()
        when {
            tag == "prolog" || tag == "epilog" || tag == "name" || tag == "location" -> return

            // Sections (and any nested <test>) become foldable layers.
            tag == "test" || tag == "section" -> {
                val section = SquishReportNode.Section(
                    title = nameOf(el) ?: el.getAttribute("name").ifBlank { "Section" },
                    timestamp = timeOf(el),
                )
                for (child in childElements(el)) appendNode(child, section.children)
                out.add(section)
            }

            tag == "message" -> out.add(messageNode(el))

            tag == "verification" -> {
                val results = childElements(el).filter { it.tagName.equals("result", true) }
                if (results.isEmpty()) out.add(entryOf(el, el.getAttribute("type").ifBlank { "PASS" }))
                else results.forEach { out.add(entryOf(it, it.getAttribute("type"))) }
            }

            tag == "result" || tag == "scriptedverificationresult" ->
                out.add(entryOf(el, el.getAttribute("type")))

            else -> for (child in childElements(el)) appendNode(child, out)
        }
    }

    /** Text marking the start of a Python traceback in a Squish message. */
    const val TRACEBACK_MARKER = "Traceback (most recent call last)"

    /**
     * Builds the node for a `<message>`. A message carrying a Python traceback
     * becomes a foldable [SquishReportNode.Section] with one TRACEBACK line per
     * frame, so it renders readably; everything else is a plain entry.
     */
    private fun messageNode(el: Element): SquishReportNode {
        val entry = entryOf(el, el.getAttribute("type"))
        if (!entry.message.contains(TRACEBACK_MARKER, ignoreCase = true)) return entry

        val section = SquishReportNode.Section(title = TRACEBACK_MARKER, timestamp = entry.timestamp)
        val lines = entry.message.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        lines.forEachIndexed { index, line ->
            // Keep the final line (the exception) at the original failure level so
            // the verdict, red coloring and auto-expand still work; frames are
            // TRACEBACK (monospace).
            val level = if (index == lines.lastIndex && entry.level.isFailure) {
                entry.level
            } else {
                SquishLogLevel.TRACEBACK
            }
            section.children.add(SquishReportNode.Entry(level = level, message = line))
        }
        return section
    }

    private fun entryOf(el: Element, typeToken: String?): SquishReportNode.Entry {
        val textEl = childElements(el).firstOrNull { it.tagName.lowercase() in TEXT_CHILDREN }
        val message = (textEl?.let { directText(it) } ?: directText(el))
            .ifBlank { el.getAttribute("text") }
            .ifBlank { el.getAttribute("message") }
            .trim()
            .ifEmpty { "(no message)" }
        val location = childElements(el)
            .firstOrNull { it.tagName.equals("location", true) }
            ?.let { directText(it) }
            ?.trim()?.ifBlank { null }
        return SquishReportNode.Entry(
            level = SquishLogLevel.from(typeToken),
            message = message,
            detail = location,
            timestamp = timeOf(el),
        )
    }

    private fun verdictOf(section: SquishReportNode.Section): SquishVerdict {
        var sawFailure = false
        var sawAny = false
        fun walk(node: SquishReportNode) {
            when (node) {
                is SquishReportNode.Entry -> {
                    sawAny = true
                    if (node.level.isFailure) sawFailure = true
                }
                is SquishReportNode.Section -> node.children.forEach { walk(it) }
                is SquishReportNode.Image -> {}
            }
        }
        section.children.forEach { walk(it) }
        return when {
            sawFailure -> SquishVerdict.FAIL
            sawAny -> SquishVerdict.PASS
            else -> SquishVerdict.UNKNOWN
        }
    }

    // --- DOM helpers -------------------------------------------------------

    private fun typeOf(el: Element): String = el.getAttribute("type").trim().lowercase()

    private fun childElements(el: Element): List<Element> {
        val result = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) result.add(n as Element)
        }
        return result
    }

    private fun collectByTag(el: Element, tag: String, out: MutableList<Element>) {
        for (child in childElements(el)) {
            if (child.tagName.equals(tag, true)) out.add(child)
            collectByTag(child, tag, out)
        }
    }

    private fun descendantHasTag(el: Element, tag: String): Boolean {
        if (el.tagName.equals(tag, true)) return true
        return childElements(el).any { descendantHasTag(it, tag) }
    }

    private fun nameOf(el: Element): String? {
        // <prolog><name>..</name></prolog>, or a direct <name>, or name="".
        val prolog = childElements(el).firstOrNull { it.tagName.equals("prolog", true) }
        val nameEl = (prolog?.let { childElements(it) } ?: emptyList())
            .firstOrNull { it.tagName.equals("name", true) }
            ?: childElements(el).firstOrNull { it.tagName.equals("name", true) }
        val text = nameEl?.let { directText(it) }?.trim()
        if (!text.isNullOrEmpty()) return text
        return el.getAttribute("name").trim().ifEmpty { null }
    }

    private fun timeOf(el: Element): String? {
        el.getAttribute("time").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val prolog = childElements(el).firstOrNull { it.tagName.equals("prolog", true) }
        return prolog?.getAttribute("time")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Concatenates the direct text (and CDATA) children of [el], trimmed. */
    private fun directText(el: Element): String {
        val sb = StringBuilder()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(n.nodeValue)
            }
        }
        return sb.toString().trim()
    }
}
