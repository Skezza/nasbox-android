package skezza.smbsync.domain.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTargetParserTest {

    @Test
    fun parse_supportsProtocolAndShareInHost() {
        val result = SmbTargetParser.parse("smb://quanta.local/photos", "")
        assertTrue(result is ParsedSmbTargetResult.Success)
        val target = (result as ParsedSmbTargetResult.Success).target
        assertEquals("quanta.local", target.host)
        assertEquals("photos", target.shareName)
    }

    @Test
    fun parse_supportsSeparateShareAndTrailingSlash() {
        val result = SmbTargetParser.parse("smb://quanta.local/", "photos")
        assertTrue(result is ParsedSmbTargetResult.Success)
        val target = (result as ParsedSmbTargetResult.Success).target
        assertEquals("quanta.local", target.host)
        assertEquals("photos", target.shareName)
    }
}
