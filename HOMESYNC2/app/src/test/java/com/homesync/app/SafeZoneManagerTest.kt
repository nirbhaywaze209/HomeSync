package com.homesync.app

import com.homesync.app.util.CustomSafeZone
import org.junit.Assert.*
import org.junit.Test

class SafeZoneManagerTest {

    @Test
    fun customSafeZone_holdsCorrectProperties() {
        val zone = CustomSafeZone(
            id = "zone_1",
            name = "Grandma's House",
            icon = "🏡",
            latitude = 28.6129,
            longitude = 77.2095,
            radiusMeters = 450f,
            colorHex = "#0284C7"
        )

        assertEquals("zone_1", zone.id)
        assertEquals("Grandma's House", zone.name)
        assertEquals("🏡", zone.icon)
        assertEquals(28.6129, zone.latitude, 0.0001)
        assertEquals(77.2095, zone.longitude, 0.0001)
        assertEquals(450f, zone.radiusMeters, 0.01f)
        assertEquals("#0284C7", zone.colorHex)
    }
}
