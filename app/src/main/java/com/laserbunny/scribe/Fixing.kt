package com.laserbunny.scribe

/** The most a fix can change at once (the server's limit too). */
const val FIX_MAX = 4000
/** How far back a fix reaches with nothing selected and nothing just typed. */
const val FIX_LINE_MAX = 1500
/** How much of the text around a fix goes along, to understand it by. */
const val FIX_AROUND = 800

/**
 * What a spoken fix is about, given the text box around the cursor: the
 * selected words if there are any, else what Scribe just typed (`last`) if
 * it's still right before the cursor, else the line or paragraph before the
 * cursor (its last [FIX_LINE_MAX] characters, from a word boundary).
 * Null when there's nothing to fix.
 */
fun fixTargetOf(
    before: String, after: String, selected: String?, last: String?,
    mode: String, app: String, field: String,
): Purpose.Fix? {
    val around = after.take(FIX_AROUND)
    if (!selected.isNullOrBlank()) {
        return Purpose.Fix(selected, before.takeLast(FIX_AROUND), around, "selection", mode, app, field)
    }
    if (!last.isNullOrBlank() && last.length <= FIX_MAX && before.endsWith(last)) {
        return Purpose.Fix(last, before.dropLast(last.length).takeLast(FIX_AROUND), around, "last", mode, app, field)
    }
    val content = before.trimEnd()
    if (content.isEmpty()) return null
    var start = content.lastIndexOf('\n') + 1
    if (content.length - start > FIX_LINE_MAX) {
        start = content.length - FIX_LINE_MAX
        while (start < content.length && !content[start - 1].isWhitespace()) start++
    }
    val text = before.substring(start)
    if (text.isBlank()) return null
    return Purpose.Fix(text, before.substring(0, start).takeLast(FIX_AROUND), around, "before", mode, app, field)
}
