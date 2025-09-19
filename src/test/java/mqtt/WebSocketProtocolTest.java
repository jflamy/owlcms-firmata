package mqtt;

import app.owlcms.firmata.utils.WebSocketProtocol;
import org.junit.Assert;
import org.junit.Test;

public class WebSocketProtocolTest {

    @Test
    public void testExact443() {
        Assert.assertEquals("wss", WebSocketProtocol.selectProtocol("443"));
    }

    @Test
    public void testEndsWith443() {
        Assert.assertEquals("wss", WebSocketProtocol.selectProtocol("1443"));
    }

    @Test
    public void testStartsWith8() {
        Assert.assertEquals("ws", WebSocketProtocol.selectProtocol("8080"));
    }

    @Test
    public void testIntOverload() {
        Assert.assertEquals("ws", WebSocketProtocol.selectProtocol(8000));
    }

    @Test
    public void testNullAndOther() {
        Assert.assertEquals("mqtt", WebSocketProtocol.selectProtocol((String) null));
        Assert.assertEquals("mqtt", WebSocketProtocol.selectProtocol("1234"));
    }

    @Test
    public void testBuildUrl() {
        Assert.assertEquals("wss://example.com:443/mqtt", WebSocketProtocol.buildUrl("example.com", "443"));
        Assert.assertEquals("wss://example.com:1443/mqtt", WebSocketProtocol.buildUrl("example.com", "1443"));
        Assert.assertEquals("ws://localhost:8080/mqtt", WebSocketProtocol.buildUrl("localhost", "8080"));
        // For non-ws/wss protocols (mqtt) a URL without trailing /mqtt is returned
        Assert.assertEquals("mqtt://example.com:1234", WebSocketProtocol.buildUrl("example.com", "1234"));
        Assert.assertNull(WebSocketProtocol.buildUrl(null, "8080"));
    }

    @Test
    public void testStandardMqttPorts() {
        Assert.assertEquals("mqtt", WebSocketProtocol.selectProtocol("1883"));
    }
}
