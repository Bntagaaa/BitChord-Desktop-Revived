package com.music.bitchord.desktop

/** SemVer ordering, also accepting historic beta1 tags and omitted patch numbers. */
internal data class DesktopUpdateVersion(val numbers: List<Int>, val pre: List<String>) : Comparable<DesktopUpdateVersion> {
    override fun compareTo(other: DesktopUpdateVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val cmp = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        if (pre.isEmpty() || other.pre.isEmpty()) return when { pre == other.pre -> 0; pre.isEmpty() -> 1; else -> -1 }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val cmp = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> a.compareTo(b) }
            if (cmp != 0) return cmp
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(raw: String): DesktopUpdateVersion? {
            val match = Regex("^v?((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))?)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$").matchEntire(raw) ?: return null
            val nums = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val label = match.groupValues[2]
            // Only the project's historic alpha1/beta1/rc1 convention is normalized.
            // General SemVer alphanumeric identifiers (e.g. x10) retain lexical ordering.
            val historic = Regex("^(alpha|beta|rc)(0|[1-9][0-9]*)$").matchEntire(label)
            val pre = (historic?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: label)
                .takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return DesktopUpdateVersion(nums, pre)
        }
    }
}
