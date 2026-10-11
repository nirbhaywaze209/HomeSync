package com.homesync.app

import com.homesync.app.util.UserAccount
import org.junit.Assert.*
import org.junit.Test

class AuthManagerTest {

    @Test
    fun userAccount_holdsCorrectFields() {
        val account = UserAccount(
            email = "parent@homesync.app",
            password = "securePassword123",
            name = "John Doe",
            role = "GUARDIAN",
            childCode = "HS-849201"
        )

        assertEquals("parent@homesync.app", account.email)
        assertEquals("securePassword123", account.password)
        assertEquals("John Doe", account.name)
        assertEquals("GUARDIAN", account.role)
        assertEquals("HS-849201", account.childCode)
    }
}
