package com.dpdpxray.core.rules.science

import kotlin.math.sqrt

/**
 * Kendall's rank correlation tau-b (Kendall 1938; ties: Kendall 1945), written here so the analysis needs no library.
 *
 * tau_b = (nc - nd) / sqrt((n0 - n1) * (n0 - n2)), with n0 = n(n-1)/2 pairs, nc concordant, nd discordant, n1 pairs tied
 * in x and n2 pairs tied in y. [of] uses Knight's O(n log n) method (sort by x then y, count the inversions left in y by
 * merge sort); [naive] counts every pair and is used to test it.
 */
object KendallTau {
    data class Result(val tauB: Double, val concordant: Long, val discordant: Long, val tiedX: Long, val tiedY: Long, val tiedBoth: Long, val pairs: Long)

    fun of(x: LongArray, y: LongArray): Result {
        require(x.size == y.size)
        val n = x.size
        val order = (0 until n).sortedWith(compareBy<Int>({ x[it] }, { y[it] }))
        val n0 = n.toLong() * (n - 1) / 2
        var n1 = 0L
        var n3 = 0L
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && x[order[j + 1]] == x[order[i]]) j++
            val t = (j - i + 1).toLong()
            n1 += t * (t - 1) / 2
            var k = i
            while (k <= j) {
                var m = k
                while (m + 1 <= j && y[order[m + 1]] == y[order[k]]) m++
                val v = (m - k + 1).toLong()
                n3 += v * (v - 1) / 2
                k = m + 1
            }
            i = j + 1
        }
        val ys = LongArray(n) { y[order[it]] }
        val nd = inversions(ys, LongArray(n), 0, n)
        var n2 = 0L
        i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && ys[j + 1] == ys[i]) j++
            val u = (j - i + 1).toLong()
            n2 += u * (u - 1) / 2
            i = j + 1
        }
        val nc = n0 - n1 - n2 + n3 - nd
        return Result(tau(nc, nd, n0, n1, n2), nc, nd, n1, n2, n3, n0)
    }

    fun naive(x: LongArray, y: LongArray): Result {
        var nc = 0L; var nd = 0L; var n1 = 0L; var n2 = 0L; var n3 = 0L
        for (a in x.indices) for (b in a + 1 until x.size) {
            val dx = x[a].compareTo(x[b])
            val dy = y[a].compareTo(y[b])
            when {
                dx == 0 && dy == 0 -> { n1++; n2++; n3++ }
                dx == 0 -> n1++
                dy == 0 -> n2++
                (dx > 0) == (dy > 0) -> nc++
                else -> nd++
            }
        }
        val n0 = x.size.toLong() * (x.size - 1) / 2
        return Result(tau(nc, nd, n0, n1, n2), nc, nd, n1, n2, n3, n0)
    }

    private fun tau(nc: Long, nd: Long, n0: Long, n1: Long, n2: Long): Double {
        val denom = sqrt((n0 - n1).toDouble() * (n0 - n2).toDouble())
        return if (denom == 0.0) Double.NaN else (nc - nd) / denom
    }

    /** Sorts a[from, to) ascending and returns the number of pairs i < j with a[i] > a[j]. */
    private fun inversions(a: LongArray, tmp: LongArray, from: Int, to: Int): Long {
        if (to - from < 2) return 0
        val mid = (from + to) ushr 1
        var count = inversions(a, tmp, from, mid) + inversions(a, tmp, mid, to)
        var i = from; var j = mid; var k = from
        while (i < mid && j < to) {
            if (a[j] < a[i]) { count += mid - i; tmp[k++] = a[j++] } else tmp[k++] = a[i++]
        }
        while (i < mid) tmp[k++] = a[i++]
        while (j < to) tmp[k++] = a[j++]
        System.arraycopy(tmp, from, a, from, to - from)
        return count
    }

    /** Dense ranks of [values] (equal values share a rank), so doubles can be compared exactly as longs. */
    fun ranks(raw: DoubleArray): LongArray {
        val values = DoubleArray(raw.size) { raw[it] + 0.0 }  // -0.0 + 0.0 == 0.0, so both zeros share a rank
        val distinct = values.distinct().sorted()
        val index = HashMap<Double, Long>(distinct.size * 2).apply { distinct.forEachIndexed { i, v -> put(v, i.toLong()) } }
        return LongArray(values.size) { index.getValue(values[it]) }
    }
}
