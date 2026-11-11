package dev.stance.deck.core

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class VectorsTest {
    private val root = Json.parseToJsonElement(File(System.getProperty("stance.vectors")).readText()).jsonObject

    private fun JsonObject.h(k: String) = hex(getValue(k).jsonPrimitive.content)
    private fun JsonObject.o(k: String) = getValue(k).jsonObject

    @Test
    fun ik() {
        val v = root.o("ik")
        val pro = v.h("prologue_hex")
        val phone = KeyPair.fromPrivate(v.h("initiator_static_private_hex"))
        val pc = KeyPair.fromPrivate(v.h("responder_static_private_hex"))
        assertContentEquals(v.h("responder_static_public_hex"), pc.public)
        val i = HandshakeState(Pattern.IK, true, pro, phone, rs = pc.public, fixedEphemeral = v.h("initiator_ephemeral_private_hex"))
        val r = HandshakeState(Pattern.IK, false, pro, pc, fixedEphemeral = v.h("responder_ephemeral_private_hex"))
        val m1 = i.writeMessage()
        assertEquals(v.getValue("message1_hex").jsonPrimitive.content, m1.hex())
        r.readMessage(m1)
        assertContentEquals(phone.public, r.remoteStatic)
        val m2 = r.writeMessage()
        assertEquals(v.getValue("message2_hex").jsonPrimitive.content, m2.hex())
        i.readMessage(m2)
        transport(v, i.split(), r.split())
    }

    @Test
    fun xxpsk3() {
        val v = root.o("xxpsk3")
        val pro = v.h("prologue_hex")
        val phone = KeyPair.fromPrivate(v.h("initiator_static_private_hex"))
        val pc = KeyPair.fromPrivate(v.h("responder_static_private_hex"))
        val psk = v.h("psk_hex")
        val i = HandshakeState(Pattern.XXPSK3, true, pro, phone, psk = psk, fixedEphemeral = v.h("initiator_ephemeral_private_hex"))
        val r = HandshakeState(Pattern.XXPSK3, false, pro, pc, psk = psk, fixedEphemeral = v.h("responder_ephemeral_private_hex"))
        val m1 = i.writeMessage(); assertEquals(v.getValue("message1_hex").jsonPrimitive.content, m1.hex())
        r.readMessage(m1)
        val m2 = r.writeMessage(); assertEquals(v.getValue("message2_hex").jsonPrimitive.content, m2.hex())
        i.readMessage(m2)
        assertContentEquals(pc.public, i.remoteStatic)
        val m3 = i.writeMessage(); assertEquals(v.getValue("message3_hex").jsonPrimitive.content, m3.hex())
        r.readMessage(m3)
        assertContentEquals(phone.public, r.remoteStatic)
        transport(v, i.split(), r.split())
    }

    private fun transport(v: JsonObject, it: Transport, rt: Transport) {
        assertEquals(v.getValue("handshake_hash_hex").jsonPrimitive.content, it.handshakeHash.hex())
        val a = v.o("initiator_to_responder")
        val c1 = it.encrypt(a.getValue("plaintext").jsonPrimitive.content.toByteArray())
        assertEquals(a.getValue("ciphertext_hex").jsonPrimitive.content, c1.hex())
        assertEquals(a.getValue("plaintext").jsonPrimitive.content, String(rt.decrypt(c1)))
        val b = v.o("responder_to_initiator")
        val c2 = rt.encrypt(b.getValue("plaintext").jsonPrimitive.content.toByteArray())
        assertEquals(b.getValue("ciphertext_hex").jsonPrimitive.content, c2.hex())
        assertEquals(b.getValue("plaintext").jsonPrimitive.content, String(it.decrypt(c2)))
    }

    @Test
    fun qrParses() {
        val k = B64Url.encode(ByteArray(32) { 1 })
        val sp = B64Url.encode(ByteArray(32) { 2 })
        val q = parseQr("stance-deck:1?h=192.168.0.2,10.0.0.5&p=47800&k=$k&s=$sp&n=My%20PC%0A")
        assertEquals(listOf("192.168.0.2", "10.0.0.5"), q.hosts)
        assertEquals(47800, q.port)
        assertEquals("My PC", q.name)
        assertEquals(fingerprint(k), q.pcFingerprint)
        kotlin.test.assertFailsWith<DeckException> { parseQr("https://example.org") }
    }
}

class V2Test {
    private val root = Json.parseToJsonElement(File(System.getProperty("stance.vectors")).readText()).jsonObject
    private val v2 = root.getValue("v2").jsonObject
    private val frame = v2.getValue("data_frame").jsonObject
    private fun str(o: JsonObject, k: String) = o.getValue(k).jsonPrimitive.content

    @Test
    fun dataFramePlaintext() {
        val plain = encodeDataFrame(frame.getValue("sid").jsonPrimitive.long, str(frame, "payload").toByteArray())
        assertEquals(str(frame, "plaintext_hex"), plain.hex())
        val d = decodeDataFrame(plain)
        assertEquals(1L, d.sid)
        assertEquals("hello", String(d.payload))
        assertEquals(1L, (parsePlain(plain) as ServerMsg.Data).sid)
    }

    @Test
    fun dataFrameCiphertextIsSecondInitiatorMessage() {
        val v = root.getValue("ik").jsonObject
        val phone = KeyPair.fromPrivate(hex(str(v, "initiator_static_private_hex")))
        val pc = KeyPair.fromPrivate(hex(str(v, "responder_static_private_hex")))
        val pro = hex(str(v, "prologue_hex"))
        val i = HandshakeState(Pattern.IK, true, pro, phone, rs = pc.public, fixedEphemeral = hex(str(v, "initiator_ephemeral_private_hex")))
        val r = HandshakeState(Pattern.IK, false, pro, pc, fixedEphemeral = hex(str(v, "responder_ephemeral_private_hex")))
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        val it = i.split(); val rt = r.split()
        val ping = v.getValue("initiator_to_responder").jsonObject
        assertEquals(str(ping, "ciphertext_hex"), it.encrypt(str(ping, "plaintext").toByteArray()).hex())
        rt.decrypt(hex(str(ping, "ciphertext_hex")))
        val c = it.encrypt(hex(str(frame, "plaintext_hex")))
        assertEquals(str(frame, "ciphertext_hex"), c.hex())
        assertEquals(str(frame, "plaintext_hex"), rt.decrypt(c).hex())
    }

    @Test
    fun capsAndOfferJson() {
        assertEquals(str(v2, "caps_json"), ClientMsg.Caps(2, listOf("clip.text")).toJson())
        val caps = parseServerMsg(str(v2, "caps_json")) as ServerMsg.Caps
        assertEquals(2, caps.proto); assertEquals(listOf("clip.text"), caps.caps)
        val offer = StreamMsg.Offer(1, "clip.text", 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertEquals(str(v2, "offer_json"), offer.toJson())
        assertEquals(offer, (parseServerMsg(str(v2, "offer_json")) as ServerMsg.Stream).msg)
    }

    @Test
    fun streamMessagesRoundTrip() {
        val all = listOf(
            StreamMsg.Offer(2, "file.pull", 10, "ab", "a.txt", "text/plain"), StreamMsg.Accept(2, 100),
            StreamMsg.Decline(3, "unsupported"), StreamMsg.Window(2, 5), StreamMsg.End(2),
            StreamMsg.Done(2, false, "bad hash"), StreamMsg.Done(2, true), StreamMsg.Cancel(2, "x"),
        )
        for (m in all) assertEquals(m, (parseServerMsg(m.toJson()) as ServerMsg.Stream).msg)
        assertEquals(4_000_000_000L, ((parseServerMsg(StreamMsg.End(4_000_000_000L).toJson()) as ServerMsg.Stream).msg).sid)
    }

    @Test
    fun helloProtoDefaultsToOne() {
        val old = """{"t":"hello","schema":1,"name":"x","hosts":[],"port":1,"buttons":[],"state":{"version":1}}"""
        val h = parseServerMsg(old) as ServerMsg.Hello
        assertEquals(1, h.proto); assertEquals(emptyList(), h.caps)
        val new = parseServerMsg(old.replace("\"name\"", "\"proto\":2,\"caps\":[\"clip.text\",5],\"name\"")) as ServerMsg.Hello
        assertEquals(2, new.proto); assertEquals(listOf("clip.text"), new.caps)
    }

    @Test
    fun dataFrameBounds() {
        encodeDataFrame(1, ByteArray(PAYLOAD_MAX))
        kotlin.test.assertFailsWith<DeckException> { encodeDataFrame(1, ByteArray(0)) }
        kotlin.test.assertFailsWith<DeckException> { encodeDataFrame(1, ByteArray(PAYLOAD_MAX + 1)) }
        kotlin.test.assertFailsWith<DeckException> { decodeDataFrame(byteArrayOf(0, 0, 0, 0, 1)) }
        kotlin.test.assertFailsWith<DeckException> { decodeDataFrame(ByteArray(6 + PAYLOAD_MAX)) }
        val max = encodeDataFrame(0xFFFF_FFFFL, byteArrayOf(9))
        assertEquals(0xFFFF_FFFFL, decodeDataFrame(max).sid)
    }
}
