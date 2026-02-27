package skezza.nasbox.domain.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTargetParserTest {

    @Test
    fun parse_supportsProtocolAndShareInHost() {
        val result = SmbTargetParser.parse("smb://example.local/photos", "")
        assertTrue(result is ParsedSmbTargetResult.Success)
        val target = (result as ParsedSmbTargetResult.Success).target
        assertEquals("example.local", target.host)
        assertEquals("photos", target.shareName)
    }

    @Test
    fun parse_allowsHostWithoutShareForRootValidation() {
        val result = SmbTargetParser.parse("example.local", "")
        assertTrue(result is ParsedSmbTargetResult.Success)
        val target = (result as ParsedSmbTargetResult.Success).target
        assertEquals("example.local", target.host)
        assertEquals("", target.shareName)
    }

    @Test
    fun parse_supportsSeparateShareAndTrailingSlash() {
        val result = SmbTargetParser.parse("smb://example.local/", "photos")
        assertTrue(result is ParsedSmbTargetResult.Success)
        val target = (result as ParsedSmbTargetResult.Success).target
        assertEquals("example.local", target.host)
        assertEquals("photos", target.shareName)
    }
}
