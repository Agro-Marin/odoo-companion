package com.odoocompanion.softphone

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SoftphoneTelecomTest {
    private val speaker = endpoint(CallEndpointCompat.TYPE_SPEAKER)
    private val earpiece = endpoint(CallEndpointCompat.TYPE_EARPIECE)
    private val bluetooth = endpoint(CallEndpointCompat.TYPE_BLUETOOTH)
    private val headset = endpoint(CallEndpointCompat.TYPE_WIRED_HEADSET)

    @Test
    fun `a company call stays out of the handset's call log`() {
        val attributes = SoftphoneTelecom.attributes(
            Uri.parse("sip:100@pbx"),
            "Front desk",
            CallAttributesCompat.DIRECTION_INCOMING,
        )
        assertEquals(true, attributes.isLogExcluded)
    }

    @Test
    fun `a company call can be held, so a cellular call does not drop it`() {
        val attributes = SoftphoneTelecom.attributes(
            Uri.parse("sip:100@pbx"),
            null,
            CallAttributesCompat.DIRECTION_INCOMING,
        )
        assertEquals(CallAttributesCompat.SUPPORTS_SET_INACTIVE, attributes.callCapabilities)
        assertEquals(CallAttributesCompat.CALL_TYPE_AUDIO_CALL, attributes.callType)
    }

    @Test
    fun `a call without a name shows its number`() {
        val attributes = SoftphoneTelecom.attributes(
            Uri.fromParts("tel", "5551234", null),
            null,
            CallAttributesCompat.DIRECTION_OUTGOING,
        )
        assertEquals("5551234", attributes.displayName)
    }

    @Test
    fun `off the loudspeaker goes to the most private route there is`() {
        val all = listOf(speaker, earpiece, bluetooth, headset)
        assertSame(headset, SoftphoneConnection.endpointFor(false, all))
        assertSame(bluetooth, SoftphoneConnection.endpointFor(false, all - headset))
        assertSame(earpiece, SoftphoneConnection.endpointFor(false, listOf(speaker, earpiece)))
        assertSame(speaker, SoftphoneConnection.endpointFor(true, all))
        assertNull(SoftphoneConnection.endpointFor(true, listOf(earpiece)))
    }

    @Test
    fun `a call ends in the system once, with the first cause`() {
        val control = FakeControl()
        val connection = connection(control)
        connection.end(DisconnectCause.MISSED)
        connection.end(DisconnectCause.ERROR)
        connection.disconnect()
        connection.onSessionEnded()
        idle()
        assertEquals(listOf(DisconnectCause.MISSED), control.disconnects)
    }

    @Test
    fun `every cause the app ends a call with is one telecom takes`() {
        for (cause in listOf(DisconnectCause.LOCAL, DisconnectCause.MISSED)) {
            val control = FakeControl()
            connection(control).end(cause)
            idle()
            assertEquals(listOf(cause), control.disconnects)
        }
    }

    @Test
    fun `declining a ringing call ends it as rejected`() {
        val control = FakeControl()
        connection(control, hangUpSip = { it.end(DisconnectCause.REMOTE) }).reject()
        idle()
        assertEquals(listOf(DisconnectCause.REJECTED), control.disconnects)
    }

    @Test
    fun `hanging up on the handset ends the call as local`() {
        val control = FakeControl()
        var hungUp = 0
        val connection = connection(control, hangUpSip = {
            hungUp++
            it.end(DisconnectCause.REMOTE)
        })
        connection.disconnect()
        connection.disconnect()
        idle()
        assertEquals(listOf(DisconnectCause.LOCAL), control.disconnects)
        assertEquals(1, hungUp)
    }

    @Test
    fun `a call that already ended is not hung up again`() {
        var hungUp = 0
        val connection = connection(FakeControl(), hangUpSip = { hungUp++ })
        connection.end(DisconnectCause.MISSED)
        connection.reject()
        assertEquals(0, hungUp)
    }

    @Test
    fun `a resume the SIP stack cannot carry out hands the focus back`() {
        val control = FakeControl()
        connection(control).resume()
        idle()
        assertEquals(listOf("active", "inactive"), control.focus)
    }

    @Test
    fun `a resume the system refuses leaves the call held`() {
        val refused = CallControlResult.Error(CallException.ERROR_CANNOT_HOLD_CURRENT_ACTIVE_CALL)
        val control = FakeControl(activeResult = refused)
        connection(control).resume()
        idle()
        assertEquals(listOf("active"), control.focus)
    }

    @Test
    fun `the loudspeaker button asks for the endpoint the system offers`() {
        val control = FakeControl()
        val connection = connection(control)
        connection.follow()
        control.available.value = listOf(earpiece, speaker)
        idle()
        connection.routeToSpeaker(true)
        connection.routeToSpeaker(false)
        idle()
        assertEquals(listOf(speaker, earpiece), control.requested)
    }

    @Test
    fun `no endpoint offered yet asks for none`() {
        val control = FakeControl()
        val connection = connection(control)
        connection.follow()
        connection.routeToSpeaker(true)
        idle()
        assertTrue(control.requested.isEmpty())
    }

    @Test
    fun `a hold the SIP stack cannot carry out is refused to the system`() {
        val connection = connection(FakeControl())
        assertThrows(IllegalStateException::class.java) { connection.onSystemHold() }
        assertThrows(IllegalStateException::class.java) { connection.onSystemResume() }
    }

    private fun connection(control: FakeControl, hangUpSip: (SoftphoneConnection) -> Unit = {},) =
        SoftphoneConnection(RuntimeEnvironment.getApplication(), control, hangUpSip)

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun endpoint(type: Int) =
        CallEndpointCompat("endpoint $type", type, ParcelUuid(UUID.randomUUID()))

    private class FakeControl(
        private val activeResult: CallControlResult = CallControlResult.Success(),
    ) : CallControlScope {
        val disconnects = mutableListOf<Int>()
        val requested = mutableListOf<CallEndpointCompat>()
        val focus = mutableListOf<String>()
        val available = MutableStateFlow<List<CallEndpointCompat>>(emptyList())

        override val coroutineContext = Dispatchers.Main

        override fun getCallId(): ParcelUuid = ParcelUuid(UUID(0, 0))

        override suspend fun setActive(): CallControlResult {
            focus += "active"
            return activeResult
        }

        override suspend fun setInactive(): CallControlResult {
            focus += "inactive"
            return CallControlResult.Success()
        }

        override suspend fun answer(callType: Int) = CallControlResult.Success()

        override suspend fun disconnect(disconnectCause: DisconnectCause): CallControlResult {
            require(disconnectCause.code in SoftphoneCallBook.VOIP_CAUSES) {
                "Telecom refuses cause ${disconnectCause.code}"
            }
            disconnects += disconnectCause.code
            return CallControlResult.Success()
        }

        override suspend fun requestEndpointChange(
            endpoint: CallEndpointCompat
        ): CallControlResult {
            requested += endpoint
            return CallControlResult.Success()
        }

        override val currentCallEndpoint = emptyFlow<CallEndpointCompat>()

        override val availableEndpoints = available

        override val isMuted = MutableStateFlow(false)
    }
}
