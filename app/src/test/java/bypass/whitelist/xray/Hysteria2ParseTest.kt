package bypass.whitelist.xray

import org.junit.Assert.assertEquals
import org.junit.Test

class Hysteria2ParseTest {

    private val link = "hysteria2://0177392a-cc2d-4862-b8ae-9dddc5832d42@hy-fi.cors-fox.cc:30443/" +
        "?sni=hy.cors-fox.cc&pinSHA256=e716a3fe15c4f1513767e2c5d0672d66a09edbee69129c8fc469fba06edf0280" +
        "#%F0%9F%87%AB%F0%9F%87%AE%20HY"

    @Test
    fun parsesSubscriptionLink() {
        val s = XrayServer.parseShareLink(link, "sub")!!
        assertEquals(XrayProtocol.HYSTERIA2, s.protocol)
        assertEquals("hy-fi.cors-fox.cc", s.address)
        assertEquals(30443, s.port)
        assertEquals("0177392a-cc2d-4862-b8ae-9dddc5832d42", s.uuid)
        assertEquals("hy.cors-fox.cc", s.sni)
        assertEquals("e716a3fe15c4f1513767e2c5d0672d66a09edbee69129c8fc469fba06edf0280", s.pinSha256)
        assertEquals("🇫🇮 HY", s.remark)

        val out = s.toOutboundJson("proxy")
        assertEquals("hysteria", out.getString("protocol"))
        val stream = out.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals(s.uuid, stream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals(s.pinSha256, stream.getJSONObject("tlsSettings").getString("pinnedPeerCertSha256"))

        // round-trips through storage
        assertEquals(s, XrayServer.fromJson(s.toJson()))
    }

    @Test
    fun parsesPortHopAndObfs() {
        val s = XrayServer.parseShareLink("hy2://pass@1.2.3.4:20000-30000?obfs=salamander&obfs-password=x#r")!!
        assertEquals(20000, s.port)
        assertEquals("20000-30000", s.hopPorts)
        assertEquals("x", s.obfsPassword)
    }
}
