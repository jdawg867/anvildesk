package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessTest {
    @Test
    fun acceptsUidZero() {
        assertTrue(RootAccess.isRootUid("0\n"))
    }

    @Test
    fun rejectsNonRootUid() {
        assertFalse(RootAccess.isRootUid("10234\n"))
    }

    @Test
    fun doesNotAcceptZeroEmbeddedInOtherOutput() {
        assertFalse(RootAccess.isRootUid("uid=0(root) gid=0(root)"))
    }
}
