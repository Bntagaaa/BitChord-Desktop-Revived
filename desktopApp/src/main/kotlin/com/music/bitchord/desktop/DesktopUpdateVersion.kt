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
            val match = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(raw) ?: return null
            val nums = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val pre = match.groupValues[2].takeIf(String::isNotEmpty)?.replace(Regex("([A-Za-z]+)([0-9]+)"), "$1.$2")?.split('.').orEmpty()
            if (pre.any(String::isEmpty)) return null
            return DesktopUpdateVersion(nums, pre)
        }
    }
}
