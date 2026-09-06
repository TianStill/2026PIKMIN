package com.pikmin.fakegps.update

/** SemVer precedence, with optional v prefix and abbreviated numeric core. Invalid input fails closed. */
object VersionOrder {
    private val pattern = Regex("^[vV]?(\\d+(?:\\.\\d+){0,2})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    fun compare(left: String, right: String): Int? {
        val a = pattern.matchEntire(left.trim()) ?: return null
        val b = pattern.matchEntire(right.trim()) ?: return null
        val ac = a.groupValues[1].split('.').map { it.toBigInteger() }
        val bc = b.groupValues[1].split('.').map { it.toBigInteger() }
        for (i in 0..2) {
            val c = (ac.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(bc.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c
        }
        val ap = a.groupValues[2]; val bp = b.groupValues[2]
        if (ap == bp) return 0
        if (ap.isEmpty()) return 1
        if (bp.isEmpty()) return -1
        val av = ap.split('.'); val bv = bp.split('.')
        for (i in 0 until minOf(av.size, bv.size)) {
            val an = av[i].toBigIntegerOrNull(); val bn = bv[i].toBigIntegerOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> av[i].compareTo(bv[i])
            }
            if (c != 0) return c
        }
        return av.size.compareTo(bv.size)
    }
}
