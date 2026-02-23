package pde.format

import java.net.URLClassLoader

class EclipseFormatter(
    private val classLoader: URLClassLoader,
    private val options: Map<String, String>,
    private val lineSeparator: String
) {
    private val formatter: Any
    private val formatMethod: java.lang.reflect.Method
    private val documentClass: Class<*>
    private val documentSetMethod: java.lang.reflect.Method
    private val documentGetMethod: java.lang.reflect.Method
    private val textEditApplyMethod: java.lang.reflect.Method
    private val kind: Int

    init {
        val formatterClass = classLoader.loadClass("org.eclipse.jdt.internal.formatter.DefaultCodeFormatter")
        formatter = formatterClass.getConstructor(Map::class.java).newInstance(options)
        formatMethod = formatterClass.getMethod(
            "format",
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )

        documentClass = classLoader.loadClass("org.eclipse.jface.text.Document")
        documentSetMethod = documentClass.getMethod("set", String::class.java)
        documentGetMethod = documentClass.getMethod("get")

        val textEditClass = classLoader.loadClass("org.eclipse.text.edits.TextEdit")
        val idocumentClass = classLoader.loadClass("org.eclipse.jface.text.IDocument")
        textEditApplyMethod = textEditClass.getMethod("apply", idocumentClass)

        val codeFormatterClass = classLoader.loadClass("org.eclipse.jdt.core.formatter.CodeFormatter")
        val kCompilationUnit = codeFormatterClass.getField("K_COMPILATION_UNIT").getInt(null)
        val fIncludeComments = codeFormatterClass.getField("F_INCLUDE_COMMENTS").getInt(null)
        kind = kCompilationUnit or fIncludeComments
    }

    fun format(text: String, range: Range?): String {
        val normalized = normalizeRange(text, range)
        val edit = formatMethod.invoke(
            formatter,
            kind,
            text,
            normalized.start,
            normalized.length,
            0,
            lineSeparator
        ) ?: error("Formatter returned no edits")

        val document = documentClass.getConstructor().newInstance()
        documentSetMethod.invoke(document, text)
        textEditApplyMethod.invoke(edit, document)
        return documentGetMethod.invoke(document) as String
    }

    private fun normalizeRange(text: String, range: Range?): NormalizedRange {
        val textLength = text.length
        val safeEnd = (range?.end ?: textLength).coerceAtMost(textLength)
        val safeStart = (range?.start ?: 0).coerceAtLeast(0).coerceAtMost(safeEnd)
        val lineStart = lineStartOffset(text, safeStart)
        val length = (safeEnd - lineStart).coerceAtLeast(0)
        return NormalizedRange(lineStart, length)
    }

    private fun lineStartOffset(text: String, offset: Int): Int {
        if (offset <= 0) {
            return 0
        }
        val lfIndex = text.lastIndexOf('\n', offset - 1)
        val crIndex = text.lastIndexOf('\r', offset - 1)
        val idx = maxOf(lfIndex, crIndex)
        return if (idx < 0) 0 else idx + 1
    }

    private data class NormalizedRange(val start: Int, val length: Int)
}
