package org.readera.openreadera.ui.reader.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationLaserPointerTest {
    @Test
    fun strokesAccumulateAndExpireFiveSecondsAfterTheirOwnRelease() {
        val trails = mutableListOf<LaserTrail>()
        val firstPoints = listOf(Offset(10f, 10f), Offset(20f, 20f))
        val secondPoints = listOf(Offset(30f, 30f), Offset(40f, 40f))

        addLaserTrail(trails, firstPoints, releasedAtMillis = 1_000L)
        addLaserTrail(trails, secondPoints, releasedAtMillis = 2_000L)

        assertEquals(listOf(firstPoints, secondPoints), trails.map { it.points })
        assertEquals(listOf(6_000L, 7_000L), trails.map { it.expiresAtMillis })

        expireLaserTrails(trails, nowMillis = 5_999L)
        assertEquals(listOf(firstPoints, secondPoints), trails.map { it.points })

        expireLaserTrails(trails, nowMillis = 6_000L)
        assertEquals(listOf(secondPoints), trails.map { it.points })

        expireLaserTrails(trails, nowMillis = 6_999L)
        assertEquals(listOf(secondPoints), trails.map { it.points })

        expireLaserTrails(trails, nowMillis = 7_000L)
        assertEquals(emptyList<LaserTrail>(), trails)
    }
}
