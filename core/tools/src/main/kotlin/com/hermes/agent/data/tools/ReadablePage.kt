package com.hermes.agent.data.tools

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Turns an HTML page into the text a reader would see, for the model.
 *
 * Menus, cookie banners and footers used to fill most of the character budget
 * before the article began, and numeric entities such as `&#8217;` became
 * spaces. This keeps the main content (a page's `<main>`, else its largest
 * `<article>`, else the body without navigation), keeps headings and list
 * structure, decodes every entity, and lists the page's links so the model can
 * follow one.
 */
internal object ReadablePage {

    data class Page(val title: String, val text: String, val links: List<Link>)
    data class Link(val text: String, val url: String)

    private val NEVER_CONTENT = listOf(
        "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed",
        "form", "button", "select", "input", "textarea", "dialog",
        "[hidden]", "[aria-hidden=true]", "[style*=display:none]", "[style*=display: none]",
    ).joinToString(",")

    private val CHROME = listOf(
        "nav", "aside", "footer",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
        "[role=search]", "[role=dialog]",
    ).joinToString(",")

    private val BLOCKS = setOf(
        "p", "div", "section", "article", "main", "header", "ul", "ol", "dl", "dt", "dd",
        "table", "tr", "blockquote", "pre", "figure", "figcaption", "hr", "address", "details", "summary",
    )

    fun extract(html: String, baseUrl: String, maxLinks: Int = MAX_LINKS): Page {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim()
        doc.select(NEVER_CONTENT).remove()

        val root = doc.selectFirst("main, [role=main]")
            ?: doc.select("article").maxByOrNull { it.text().length }?.takeIf { it.text().length >= MIN_ARTICLE_CHARS }
            ?: doc.body()
        // Page chrome goes; a <header> is chrome only outside the content root, where it
        // is the site banner rather than the article's own title block.
        root.select(CHROME).remove()
        if (root === doc.body()) root.select("header").remove()

        val links = root.select("a[href]")
            .mapNotNull { a ->
                val url = a.absUrl("href")
                val text = a.text().trim()
                if ((url.startsWith("https://") || url.startsWith("http://")) && text.isNotEmpty()) Link(text.take(80), url) else null
            }
            .distinctBy { it.url.substringBefore('#') }
            .take(maxLinks)

        return Page(title, render(root), links)
    }

    private fun render(root: Element): String {
        val out = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        // Preformatted lines are marked so the whitespace clean-up below skips them.
                        is TextNode -> out.append(
                            if (insidePre(node)) PRE + node.wholeText.replace("\n", "\n$PRE") else node.text(),
                        )
                        is Element -> when (val tag = node.normalName()) {
                            "br" -> out.append('\n')
                            "li" -> out.append("\n- ")
                            "td", "th" -> out.append(" | ")
                            "h1", "h2", "h3", "h4", "h5", "h6" -> out.append("\n\n").append("#".repeat(tag[1].digitToInt())).append(' ')
                            in BLOCKS -> out.append("\n\n")
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element) {
                        val tag = node.normalName()
                        if (tag in BLOCKS || (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit())) out.append("\n\n")
                    }
                }
            },
            root,
        )
        return out.toString()
            .lines()
            .map { line ->
                val pre = line.indexOf(PRE)
                if (pre >= 0) line.substring(pre).replace(PRE.toString(), "").trimEnd()
                else line.replace(HORIZONTAL_SPACE, " ").trim()
            }
            .joinToString("\n")
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .replace(EMPTY_BULLET, "")
            .trim()
    }

    private fun insidePre(node: Node): Boolean {
        var p = node.parent()
        while (p != null) {
            if (p is Element && p.normalName() == "pre") return true
            p = p.parent()
        }
        return false
    }

    private val HORIZONTAL_SPACE = Regex("[ \\t\\u00A0\\u2007\\u202F]+")
    private val EXTRA_BLANK_LINES = Regex("\\n{3,}")
    private val EMPTY_BULLET = Regex("(?m)^- *$\\n?")
    private const val PRE = '\u0001'
    private const val MIN_ARTICLE_CHARS = 200
    const val MAX_LINKS = 25
}
