package uk.xa0.tulkki.app.extras

import android.app.Application
import android.graphics.Typeface
import android.text.ParcelableSpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuggestionSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.BaseInputConnection
import io.ipfs.cid.Cid
import java.util.ArrayList
import uk.xa0.tulkki.data.text.XhtmlBody
import uk.xa0.tulkki.ui.text.QuoteSpan
import uk.xa0.tulkki.ui.utils.StylingHelper
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.TextNode

/**
 * Writes a styled [Spanned] body out as `http://jabber.org/protocol/xhtml-im` markup.
 *
 * <p>**An `object`, and that is the Java's own shape.** The Java was a class with a private
 * constructor and one `public static final SpannedToXHTML INSTANCE` field; Kotlin's `object` is
 * that pair. `SpannedToXHTML.INSTANCE` - the one reference outside this file, in
 * `TulkkiApplication.onCreate` - is still the same static field, and the class was effectively
 * unsubclassable already because its constructor was private.
 *
 * <p>**Two Java spellings Kotlin does not have.** The two `outer:`-labelled `for` loops are Java's
 * C-style `for (i = start; i < end; i = next)`, whose update step is where a `continue outer`
 * lands; Kotlin has no C-style `for`, so each is a `while (i < end)` with `i = next` written out on
 * **both** exits - the loop's natural end and every `continue@outer` - which is exactly when the
 * Java's update step ran. The `&`/`>>`/`& 0xf` flag arithmetic is `and`/`shr`/`and`, the same
 * operations on the same `Int`s.
 *
 * <p>**Smart casts, not casts.** Java's `instanceof` + cast pairs become `is` tests whose bound
 * value is read directly: the `((StyleSpan) style[j]).getStyle()` read inside both halves of the
 * bold/italic test is one `val` taken from the smart-cast span, and the [ImageSpan]'s `source`
 * local keeps the Java's null check in front of it.
 *
 * <p>Semantics kept: the `SPAN_USER`-shifted flag reads in both passes, the `SuggestionSpan` and
 * `XHTML_IGNORE` removal in [cleanSpans], `BaseInputConnection.removeComposingSpans`, the
 * blockquote recursion with its trailing-`\n` skip, the `XHTML_REMOVE` capture that feeds the
 * `language-` class, every span-to-tag mapping in the Java's own order, the `%.0fpx`/`%.0fem` and
 * `#%06X` formats, `BobTransfer.uri(Cid.decode(...))` for a `z`-prefixed image source, and the
 * newline/double-space body walk with its `\u00A0`. The Java's three unused imports
 * (`AlignmentSpan`, `BulletSpan`, `ParagraphStyle`) are not carried over.
 *
 * <p>Name-string audit for `SpannedToXHTML`: no manifest, `res/xml`, layout, preference or
 * ProGuard reference, and its one caller outside this file is `TulkkiApplication`.
 */
object SpannedToXHTML : XhtmlBody.Writer {

    private fun cleanSpans(text: Spanned): SpannableStringBuilder {
        val newText = SpannableStringBuilder(text)
        val spans = newText.getSpans(0, newText.length, ParcelableSpan::class.java)
        for (span in spans) {
            val userFlags =
                    (text.getSpanFlags(span) and Spanned.SPAN_USER) shr Spanned.SPAN_USER_SHIFT
            if (span is SuggestionSpan || userFlags == StylingHelper.XHTML_IGNORE)
                    newText.removeSpan(span)
        }
        BaseInputConnection.removeComposingSpans(newText)
        return newText
    }

    override fun isPlainText(text: Spanned): Boolean {
        val cleanText = cleanSpans(text)
        val style = cleanText.getSpans(0, cleanText.length, CharacterStyle::class.java)
        return style.size < 1
    }

    override fun append(out: Element, text: Spanned): Element {
        val newText = cleanSpans(text)
        doBlock(out, ArrayList(), newText, 0, newText.length)
        return out
    }

    private fun doBlock(out: Element, parents: ArrayList<Any>, text: Spanned, start: Int, end: Int) {
        var i = start
        outer@ while (i < end) {
            var next = text.nextSpanTransition(i, end, QuoteSpan::class.java)
            val quotes = text.getSpans(i, next, QuoteSpan::class.java)
            for (quote in quotes) {
                if (!parents.contains(quote)) {
                    val us = ArrayList(parents)
                    us.add(quote)
                    next = text.getSpanEnd(quote)
                    doBlock(out.addChild("blockquote"), us, text, i, next)
                    if (next < text.length && text[next] == '\n') next++
                    i = next
                    continue@outer
                }
            }
            withinParagraph(
                    out,
                    text,
                    i,
                    if (next == end && text[end - 1] == '\n') next - 1 else next)
            i = next
        }
    }

    private fun withinParagraph(outer: Element, text: Spanned, start: Int, end: Int) {
        var removed = ""
        var i = start
        outer@ while (i < end) {
            var out = outer
            val next = text.nextSpanTransition(i, end, CharacterStyle::class.java)
            val style = text.getSpans(i, next, CharacterStyle::class.java)
            for (span in style) {
                val userFlags =
                        ((text.getSpanFlags(span) and Spanned.SPAN_USER) shr
                                Spanned.SPAN_USER_SHIFT) and 0xf
                if (userFlags == StylingHelper.XHTML_REMOVE) {
                    removed = text.subSequence(i, next).toString()
                    i = next
                    continue@outer
                }

                if (span is StyleSpan) {
                    val s = span.style
                    if ((s and Typeface.BOLD) != 0) {
                        out =
                                if (userFlags == StylingHelper.XHTML_EMPHASIS) out.addChild("strong")
                                else out.addChild("b")
                    }
                    if ((s and Typeface.ITALIC) != 0) {
                        out =
                                if (userFlags == StylingHelper.XHTML_EMPHASIS) out.addChild("em")
                                else out.addChild("i")
                    }
                }
                if (span is TypefaceSpan) {
                    if (userFlags == StylingHelper.XHTML_CODE) {
                        out = out.addChild("code")
                        if (removed.length > 3)
                                out.setAttribute(
                                        "class", "language-" + removed.substring(3).javaTrim())
                    } else {
                        val s = span.family
                        if ("monospace" == s) {
                            out = out.addChild("tt")
                        }
                    }
                }
                if (span is SuperscriptSpan) {
                    out = out.addChild("sup")
                }
                if (span is SubscriptSpan) {
                    out = out.addChild("sub")
                }
                if (span is UnderlineSpan) {
                    out = out.addChild("u")
                }
                if (span is StrikethroughSpan) {
                    out = out.addChild("span")
                    out.setAttribute("style", "text-decoration:line-through;")
                }
                if (span is URLSpan) {
                    out = out.addChild("a")
                    out.setAttribute("href", span.url)
                }
                if (span is ImageSpan) {
                    var source = span.source
                    if (source != null && source.length > 0 && source[0] == 'z') {
                        try {
                            source = BobTransfer.uri(Cid.decode(source)).toString()
                        } catch (e: Exception) {
                        }
                    }
                    out = out.addChild("img")
                    out.setAttribute("src", source)
                    out.setAttribute("alt", text.subSequence(i, next).toString())
                    i = next
                    continue@outer
                }
                if (span is AbsoluteSizeSpan) {
                    try {
                        var sizeDip = span.size.toFloat()
                        if (!span.dip) {
                            val activityThreadClass = Class.forName("android.app.ActivityThread")
                            val application =
                                    activityThreadClass
                                            .getMethod("currentApplication")
                                            .invoke(null) as Application
                            sizeDip /= application.resources.displayMetrics.density
                        }
                        // px in CSS is the equivalance of dip in Android
                        out = out.addChild("span")
                        out.setAttribute("style", String.format("font-size:%.0fpx;", sizeDip))
                    } catch (e: Exception) {
                    }
                }
                if (span is RelativeSizeSpan) {
                    val sizeEm = span.sizeChange
                    out = out.addChild("span")
                    out.setAttribute("style", String.format("font-size:%.0fem;", sizeEm))
                }
                if (span is ForegroundColorSpan) {
                    val color = span.foregroundColor
                    out = out.addChild("span")
                    out.setAttribute("style", String.format("color:#%06X;", 0xFFFFFF and color))
                }
                if (span is BackgroundColorSpan) {
                    val color = span.backgroundColor
                    out = out.addChild("span")
                    out.setAttribute(
                            "style", String.format("background-color:#%06X;", 0xFFFFFF and color))
                }
            }
            val content = text.subSequence(i, next).toString()
            var prevSpace = false
            for (c in content) {
                if (c == '\n') {
                    prevSpace = false
                    out.addChild("br")
                } else if (prevSpace && c == ' ') {
                    prevSpace = false
                    out.addChild(TextNode("\u00A0"))
                } else {
                    prevSpace = c == ' '
                    out.addChild(TextNode("" + c))
                }
            }
            i = next
        }
    }
}

// Java's `String.trim()`: only the characters up to U+0020. Kotlin's `trim()` is the Unicode set and
// would also strip a non-breaking space (U+00A0) from the language a code span is tagged with, which
// the Java never did.
private fun String.javaTrim(): String = trim { it <= ' ' }
