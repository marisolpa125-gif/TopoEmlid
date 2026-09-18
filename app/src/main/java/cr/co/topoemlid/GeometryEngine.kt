package cr.co.topoemlid

import kotlin.math.*

data class XY(val x: Double, val y: Double)

object GeometryEngine {
    fun rectangle(origin: XY, widthM: Double, lengthM: Double, azimuthDeg: Double): List<XY> {
        require(widthM > 0 && lengthM > 0)
        val a = Math.toRadians(azimuthDeg)
        val along = XY(sin(a), cos(a))
        val right = XY(cos(a), -sin(a))
        val p1 = origin
        val p2 = XY(origin.x + right.x * widthM, origin.y + right.y * widthM)
        val p3 = XY(p2.x + along.x * lengthM, p2.y + along.y * lengthM)
        val p4 = XY(origin.x + along.x * lengthM, origin.y + along.y * lengthM)
        return listOf(p1, p2, p3, p4, p1)
    }

    fun equilateralTriangle(origin: XY, sideM: Double, azimuthDeg: Double): List<XY> {
        require(sideM > 0)
        val a = Math.toRadians(azimuthDeg)
        val p2 = XY(origin.x + sin(a) * sideM, origin.y + cos(a) * sideM)
        val h = sideM * sqrt(3.0) / 2.0
        val mid = XY((origin.x + p2.x) / 2.0, (origin.y + p2.y) / 2.0)
        val rightA = a + Math.PI / 2.0
        val p3 = XY(mid.x + sin(rightA) * h, mid.y + cos(rightA) * h)
        return listOf(origin, p2, p3, origin)
    }

    fun circle(center: XY, radiusM: Double, vertices: Int = 72): List<XY> {
        require(radiusM > 0 && vertices >= 12)
        return (0..vertices).map { i ->
            val a = 2.0 * Math.PI * i / vertices
            XY(center.x + cos(a) * radiusM, center.y + sin(a) * radiusM)
        }
    }

    fun polygonArea(points: List<XY>): Double {
        if (points.size < 3) return 0.0
        return abs(points.zipWithNext().sumOf { (a, b) -> a.x * b.y - b.x * a.y } / 2.0)
    }
}
