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
