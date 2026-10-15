package com.mrgregles.bsp_teleport;

import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.network.Message;
import com.mrgregles.bsp_teleport.network.RedisLink;
import com.mrgregles.bsp_teleport.teleport.RandomTeleport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the network layer that need no Minecraft server: wire formats. */
class WireFormatTest {

    @Test
    void destinationRoundTrip() {
        Destination d = new Destination("survival-2", "minecraft:the_nether", 1.5, 64, -20.25, 90f, -10f, "Welcome; mind the gap");
        Destination back = Destination.parse(d.serialize());
        assertEquals(d, back);
        assertTrue(back.hasFacing());
        assertTrue(back.crossServer());
        assertEquals("Welcome; mind the gap", back.message());
        assertEquals(-1, back.protect());
        assertEquals(3, Destination.parse(d.withProtect(3).serialize()).protect());
    }

    @Test
    void ringAgainstBorder() {
        double[] box = {-484, -484, 484, 484};               // a 1000 border shrunk by 16, centred on 0,0
        assertTrue(RandomTeleport.ringTouches(box, 0, 0, 500, 5000));     // ring reaches the corners (684 away)
        assertFalse(RandomTeleport.ringTouches(box, 0, 0, 700, 5000));    // ring starts past the corners
        assertTrue(RandomTeleport.ringTouches(box, 0, 0, 0, 100));        // small ring inside
        double[] small = {-134, -134, 134, 134};             // a 300 border
        assertFalse(RandomTeleport.ringTouches(small, 0, 0, 500, 5000));
        assertTrue(RandomTeleport.ringTouches(small, 0, 0, 100, 5000));
        assertFalse(RandomTeleport.ringTouches(box, 5000, 5000, 500, 1000)); // spawn far outside, ring misses the box
    }

    @Test
    void destinationWithoutFacing() {
        Destination d = new Destination("", "minecraft:overworld", 0, 70, 0, "");
        Destination back = Destination.parse(d.serialize());
        assertFalse(back.hasFacing());
        assertFalse(back.crossServer());
        assertEquals("", back.message());
    }

    @Test
    void positionRoundTrip() {
        Position p = new Position("hub", "minecraft:overworld", 100.5, 65, -3.25, 180f, 45f);
        assertEquals(p, Position.parse(p.serialize()));
    }

    @Test
    void messageRoundTrip() {
        Message m = new Message(Message.Type.TP_REQUEST, Message.To.PLAYER, "Steve").put("here", "true").put("fromName", "Alex");
        m.source = "survival-1";
        Message back = Message.fromJson(m.toJson());
        assertEquals(Message.Type.TP_REQUEST, back.type);
        assertEquals(Message.To.PLAYER, back.to);
        assertEquals("Steve", back.target);
        assertEquals("survival-1", back.source);
        assertTrue(back.flag("here"));
        assertEquals("Alex", back.get("fromName"));
        assertEquals("", back.get("missing"));
    }

    @Test
    void respEncode() {
        String encoded = new String(RedisLink.encode("PUBLISH", "chan", "héllo"), StandardCharsets.UTF_8);
        assertEquals("*3\r\n$7\r\nPUBLISH\r\n$4\r\nchan\r\n$6\r\nhéllo\r\n", encoded);
    }

    @Test
    void respDecode() throws IOException {
        Object simple = RedisLink.decode(stream("+OK\r\n"));
        assertEquals("OK", simple);
        Object error = RedisLink.decode(stream("-NOAUTH Authentication required\r\n"));
        assertEquals("-NOAUTH Authentication required", error);
        Object number = RedisLink.decode(stream(":42\r\n"));
        assertEquals(42L, number);
        Object bulk = RedisLink.decode(stream("$5\r\nhello\r\n"));
        assertEquals("hello", bulk);
        assertNull(RedisLink.decode(stream("$-1\r\n")));
        Object array = RedisLink.decode(stream("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n"));
        assertEquals(List.of("message", "chan", "hi"), array);
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
