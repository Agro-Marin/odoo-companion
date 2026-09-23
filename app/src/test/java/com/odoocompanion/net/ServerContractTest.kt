package com.odoocompanion.net

import com.odoocompanion.config.Settings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Every body below was captured from a live remote_mobile (device_mobile at
// agromarin 931899623, a mobile-phone-category device) on 2026-09-23 --
// regenerate them from a server with tools/smoke-test.sh rather than editing
// them by hand. Each is classified twice: by a client that has not yet seen the
// server name itself, and by one that has, because a handset spends its life in
// the second state and the answer must not change between them.
class ServerContractTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun settings(namesItself: Boolean) = Settings(
        baseUrl = server.url("/").toString().trimEnd('/'),
        identifier = "SMOKE-PHONE",
        token = "t",
        serverNamesItself = namesItself,
    )

    private suspend fun classify(code: Int, body: String): UploadOutcome {
        val outcomes = listOf(false, true).map { namesItself ->
            server.enqueue(MockResponse(code = code, body = body))
            OdooClient().post(settings(namesItself), "location", "{}")
        }
        assertEquals(
            "a learned server must read the same reply the same way",
            1,
            outcomes.distinct().size
        )
        return outcomes.first()
    }

    @Test
    fun `the location endpoint's success`() = runTest {
        val outcome = classify(
            200,
            """{"service":"remote_mobile","status":"success","accepted":2,"duplicates":0,""" +
                """"skipped":0,"skipped_indexes":[],"device_name":"Smoke Phone",""" +
                """"max_payload_bytes":52428800}""",
        )
        assertEquals(UploadOutcome.Success(2, 0, 0, limitBytes = 52_428_800), outcome)
    }

    @Test
    fun `the call log endpoint's success carries a matched count the client ignores`() = runTest {
        val outcome = classify(
            200,
            """{"service":"remote_mobile","status":"success","accepted":1,"duplicates":0,""" +
                """"skipped":0,"skipped_indexes":[],"matched":0,"device_name":"Smoke Phone",""" +
                """"max_payload_bytes":52428800}""",
        )
        assertEquals(UploadOutcome.Success(1, 0, 0, limitBytes = 52_428_800), outcome)
    }

    @Test
    fun `the recording endpoint's success counts nothing at all`() = runTest {
        val outcome = classify(
            200,
            """{"service":"remote_mobile","status":"success","recording_id":1,"matched":true,""" +
                """"device_name":"Smoke Phone","max_payload_bytes":52428800}""",
        )
        assertEquals(
            "no accepted key on this route, so a count can never be the discriminator " +
                "for a delivery -- only \"status\" can",
            UploadOutcome.Success(0, 0, 0, limitBytes = 52_428_800),
            outcome,
        )
    }

    // The integration layer answers refusals as RFC 9457 problem documents, so
    // "status" here is the integer 409, not the word "success".
    @Test
    fun `the transport layer's duplicate, inside the window`() = runTest {
        val outcome = classify(
            409,
            """{"service":"remote_mobile","type":"urn:odoo:inbound:duplicate_event",""" +
                """"title":"duplicate event","status":409,"detail":"Duplicate event detected",""" +
                """"error":"duplicate_event","message":"Duplicate event detected"}""",
        )
        assertEquals(UploadOutcome.Duplicate, outcome)
    }

    @Test
    fun `the model layer's duplicate, once the window has passed`() = runTest {
        val outcome = classify(
            200,
            """{"service":"remote_mobile","status":"success","accepted":0,"duplicates":1,""" +
                """"skipped":0,"skipped_indexes":[],"device_name":"Smoke Phone",""" +
                """"max_payload_bytes":2048}""",
        )
        assertEquals(UploadOutcome.Success(0, 1, 0, limitBytes = 2048), outcome)
    }

    @Test
    fun `a batch the server read and could not store`() = runTest {
        val outcome = classify(
            422,
            """{"service":"remote_mobile","status":"success","accepted":0,"duplicates":0,""" +
                """"skipped":1,"skipped_indexes":[0],"device_name":"Smoke Phone",""" +
                """"max_payload_bytes":52428800}""",
        )
        assertEquals(UploadOutcome.Rejected(422), outcome)
    }

    @Test
    fun `a batch with nothing in it`() = runTest {
        val outcome = classify(
            400,
            """{"service":"remote_mobile","error":"no_points","message":"No GPS fixes in payload"}""",
        )
        assertEquals(UploadOutcome.Rejected(400), outcome)
    }

    // This client only ever sends a body it serialised, so a server that could
    // not parse it received something else: a body damaged on the way. That is
    // the link's fault; refusing it dead-lettered a call as `refused`, the one
    // state no operator action reverses.
    @Test
    fun `a body the server could not parse is the link's fault, not a refusal`() = runTest {
        val outcome = classify(
            400,
            """{"service":"remote_mobile","type":"urn:odoo:inbound:invalid_json",""" +
                """"title":"invalid json","status":400,"detail":"the request body is not JSON",""" +
                """"error":"invalid_json","message":"the request body is not JSON"}""",
        )
        assertTrue(outcome.toString(), outcome is UploadOutcome.Retry)
        assertTrue((outcome as UploadOutcome.Retry).serverFault.not())
    }

    @Test
    fun `a payload over the device's cap, with the cap in the body`() = runTest {
        val outcome = classify(
            413,
            """{"service":"remote_mobile","type":"urn:odoo:inbound:payload_too_large",""" +
                """"title":"payload too large","status":413,""" +
                """"detail":"Request exceeds maximum size of 2KB","error":"payload_too_large",""" +
                """"message":"Request exceeds maximum size of 2KB","limit_bytes":2048}""",
        )
        assertEquals(UploadOutcome.TooLarge(2048), outcome)
    }

    @Test
    fun `a rejected token`() = runTest {
        val outcome = classify(
            401,
            """{"service":"remote_mobile","type":"urn:odoo:inbound:authentication_failed",""" +
                """"title":"authentication failed","status":401,""" +
                """"detail":"unauthenticated request for Smoke Phone from 127.0.0.1",""" +
                """"error":"authentication_failed",""" +
                """"message":"unauthenticated request for Smoke Phone from 127.0.0.1"}""",
        )
        assertTrue(outcome is UploadOutcome.Retry)
    }

    @Test
    fun `an identifier the server does not know`() = runTest {
        val outcome = classify(
            404,
            """{"service":"remote_mobile","type":"urn:odoo:inbound:endpoint_not_found",""" +
                """"title":"endpoint not found","status":404,""" +
                """"detail":"Endpoint not found or inactive","error":"endpoint_not_found",""" +
                """"message":"Endpoint not found or inactive"}""",
        )
        assertTrue(outcome.toString(), outcome is UploadOutcome.Retry)
        assertTrue((outcome as UploadOutcome.Retry).reason.contains("archived"))
    }
}
