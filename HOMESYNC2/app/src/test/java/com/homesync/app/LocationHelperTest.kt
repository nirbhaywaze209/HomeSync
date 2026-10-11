package com.homesync.app

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.*
import org.junit.Test

class LocationHelperTest {

    @Test
    fun latLngCalculations_areAccurate() {
        val baseLoc = LatLng(12.9716, 77.5946) // Bengaluru center
        val homeOffset = LatLng(baseLoc.latitude + 0.0018, baseLoc.longitude - 0.0022)

        assertTrue(homeOffset.latitude > baseLoc.latitude)
        assertTrue(homeOffset.longitude < baseLoc.longitude)
    }
}
