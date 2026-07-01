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
 * Parses a Squish `xml3.5` report into the [SquishRunReport] model.
 *
 * The parser is intentionally tolerant: it walks the document, treating leaf
 * `<test>` elements as test cases, `<section>` elements as foldable layers, and
 * `<message>`/`<verification>`/`<result>` elements as entries. Levels come from
 * a `type` attribute (or the element name as a fallback). This keeps it robust
 * across minor schema differences between Squish builds.
 */
object SquishXmlReportParser {

    private val LOG = logger<SquishXmlReportParser>()

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
            // Harden against external entities / DTD fetches.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isNamespaceAware = false
        }
        val doc = Files.newInputStream(xml).use { factory.newDocumentBuilder().parse(it) }
        doc.documentElement.normalize()

        val caseElements = collectCaseTests(doc.documentElement)
        val tests = caseElements.map { parseCase(it) }
        return SquishRunReport(tests)
    }

    /** Leaf `<test>` elements (those containing no nested `<test>`) are cases. */
    private fun collectCaseTests(root: Element): List<Element> {
        val allTests = mutableListOf<Element>()
        collectByTag(root, "test", allTests)
        val leaves = allTests.filter { test -> childElements(test).none { descendantHasTag(it, "test") } }
        // If the document has no <test> at all, treat the root as a single case.
        return leaves.ifEmpty { if (allTests.isEmpty()) listOf(root) else allTests }
    }

    private fun parseCase(caseEl: Element): SquishTestReport {
        val name = nameOf(caseEl) ?: caseEl.getAttribute("name").ifBlank { "test" }
        val root = SquishReportNode.Section(title = name, timestamp = timeOf(caseEl))
        for (child in childElements(caseEl)) {
            appendNode(child, root.children)
        }
        return SquishTestReport(name = name, verdict = verdictOf(root), root = root)
    }

    /** Appends the report node(s) produced by [el] into [out]. */
    private fun appendNode(el: Element, out: MutableList<SquishReportNode>) {
        when (el.tagName.lowercase()) {
            "prolog", "epilog", "name" -> return // structural, not content
            "section" -> {
                val section = SquishReportNode.Section(
                    title = nameOf(el) ?: el.getAttribute("title").ifBlank { "Section" },
                    timestamp = timeOf(el),
                )
                for (child in childElements(el)) appendNode(child, section.children)
                out.add(section)
            }
            "message" -> out.add(entryOf(el, el.getAttribute("type")))
            "verification" -> {
                // A verification wraps one or more <result> elements.
                val results = childElements(el).filter { it.tagName.equals("result", true) }
                if (results.isEmpty()) {
                    out.add(entryOf(el, el.getAttribute("type").ifBlank { "PASS" }))
                } else {
                    results.forEach { out.add(entryOf(it, it.getAttribute("type"))) }
                }
            }
            "result", "scriptedverificationresult" ->
                out.add(entryOf(el, el.getAttribute("type")))
            "log", "pass", "fail", "error", "warning", "fatal", "info" ->
                out.add(entryOf(el, el.tagName))
            else -> {
                // Unknown wrapper: recurse so we don't lose nested content.
                for (child in childElements(el)) appendNode(child, out)
            }
        }
    }

    private fun entryOf(el: Element, typeToken: String?): SquishReportNode.Entry {
        val detailEl = childElements(el).firstOrNull {
            it.tagName.equals("description", true) ||
                it.tagName.equals("text", true) ||
                it.tagName.equals("detail", true)
        }
        val message = (detailEl?.let { directText(it) } ?: directText(el))
            .ifBlank { el.getAttribute("text") }
            .ifBlank { el.getAttribute("message") }
        val detailValues = childElements(el)
            .filter { it.tagName.equals("detail", true) && it !== detailEl }
            .joinToString("\n") { directText(it) }
            .ifBlank { null }
        return SquishReportNode.Entry(
            level = SquishLogLevel.from(typeToken),
            message = message.trim().ifEmpty { "(no message)" },
            detail = detailValues,
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
