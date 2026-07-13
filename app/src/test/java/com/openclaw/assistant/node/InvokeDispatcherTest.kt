package com.openclaw.assistant.node

import com.openclaw.assistant.protocol.OpenClawBridgeCommand
import com.openclaw.assistant.protocol.OpenClawCalendarCommand
import com.openclaw.assistant.protocol.OpenClawCameraCommand
import com.openclaw.assistant.protocol.OpenClawClipboardCommand
import com.openclaw.assistant.protocol.OpenClawContactsCommand
import com.openclaw.assistant.protocol.OpenClawDeviceCommand
import com.openclaw.assistant.protocol.OpenClawLocationCommand
import com.openclaw.assistant.protocol.OpenClawMotionCommand
import com.openclaw.assistant.protocol.OpenClawNotificationsCommand
import com.openclaw.assistant.protocol.OpenClawPhotosCommand
import com.openclaw.assistant.protocol.OpenClawScreenCommand
import com.openclaw.assistant.protocol.OpenClawSmsCommand
import com.openclaw.assistant.protocol.OpenClawSystemCommand
import com.openclaw.assistant.protocol.OpenClawWifiCommand
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CTB-build contract (Spec 001 §C / CTB_RESTRICTED): every device/tool invoke
 * is refused with the stable CAPABILITY_DISABLED error before any handler is
 * touched. All handler mocks are strict — an unexpected handler call fails
 * the test.
 */
class InvokeDispatcherTest {
  private val canvas = mockk<CanvasController>()
  private val cameraHandler = mockk<CameraHandler>()
  private val locationHandler = mockk<LocationHandler>()
  private val screenHandler = mockk<ScreenHandler>()
  private val smsHandler = mockk<SmsHandler>()
  private val notificationsHandler = mockk<NotificationsHandler>()
  private val systemHandler = mockk<SystemHandler>()
  private val photosHandler = mockk<PhotosHandler>()
  private val contactsHandler = mockk<ContactsHandler>()
  private val calendarHandler = mockk<CalendarHandler>()
  private val motionHandler = mockk<MotionHandler>()
  private val a2uiHandler = mockk<A2UIHandler>()
  private val debugHandler = mockk<DebugHandler>()
  private val appUpdateHandler = mockk<AppUpdateHandler>()
  private val deviceHandler = mockk<DeviceHandler>()
  private val wifiHandler = mockk<WifiHandler>()
  private val clipboardHandler = mockk<ClipboardHandler>()
  private val appHandler = mockk<AppHandler>()
  private val voiceWakeHandler = mockk<VoiceWakeHandler>()
  private val mobileBridgeHandler = mockk<MobileBridgeHandler>()

  private fun createDispatcher(
    isForeground: Boolean = true,
    cameraEnabled: Boolean = true,
    locationEnabled: Boolean = true
  ) = InvokeDispatcher(
    canvas = canvas,
    cameraHandler = cameraHandler,
    locationHandler = locationHandler,
    screenHandler = screenHandler,
    smsHandler = smsHandler,
    notificationsHandler = notificationsHandler,
    systemHandler = systemHandler,
    photosHandler = photosHandler,
    contactsHandler = contactsHandler,
    calendarHandler = calendarHandler,
    motionHandler = motionHandler,
    a2uiHandler = a2uiHandler,
    debugHandler = debugHandler,
    appUpdateHandler = appUpdateHandler,
    deviceHandler = deviceHandler,
    wifiHandler = wifiHandler,
    clipboardHandler = clipboardHandler,
    appHandler = appHandler,
    voiceWakeHandler = voiceWakeHandler,
    mobileBridgeHandler = mobileBridgeHandler,
    isForeground = { isForeground },
    cameraEnabled = { cameraEnabled },
    locationEnabled = { locationEnabled }
  )

  private val privilegedCommands = listOf(
    OpenClawCameraCommand.List.rawValue,
    OpenClawCameraCommand.Snap.rawValue,
    OpenClawScreenCommand.Record.rawValue,
    OpenClawSmsCommand.Send.rawValue,
    OpenClawSmsCommand.ReadLatest.rawValue,
    OpenClawLocationCommand.Get.rawValue,
    OpenClawNotificationsCommand.List.rawValue,
    OpenClawSystemCommand.Notify.rawValue,
    OpenClawSystemCommand.Brightness.rawValue,
    OpenClawPhotosCommand.Latest.rawValue,
    OpenClawContactsCommand.Search.rawValue,
    OpenClawCalendarCommand.Events.rawValue,
    OpenClawMotionCommand.Activity.rawValue,
    OpenClawWifiCommand.List.rawValue,
    OpenClawClipboardCommand.Read.rawValue,
    OpenClawDeviceCommand.Status.rawValue,
    OpenClawBridgeCommand.Execute.rawValue,
    "app.update",
    "debug.logs",
  )

  @Test
  fun `every device and tool invoke is refused with CAPABILITY_DISABLED`() = runTest {
    val dispatcher = createDispatcher()
    privilegedCommands.forEach { command ->
      val result = dispatcher.handleInvoke(command, null)
      assertEquals("command $command must be refused", false, result.ok)
      assertEquals("command $command error code", "CAPABILITY_DISABLED", result.error?.code)
    }
  }

  @Test
  fun `unknown commands are also refused before reaching the parser`() = runTest {
    val dispatcher = createDispatcher()
    val result = dispatcher.handleInvoke("no.such.command", null)
    assertEquals(false, result.ok)
    assertEquals("CAPABILITY_DISABLED", result.error?.code)
  }

  @Test
  fun `gate applies regardless of foreground and capability toggles`() = runTest {
    val dispatcher = createDispatcher(isForeground = false, cameraEnabled = false, locationEnabled = false)
    val result = dispatcher.handleInvoke(OpenClawCameraCommand.List.rawValue, null)
    assertEquals(false, result.ok)
    assertEquals("CAPABILITY_DISABLED", result.error?.code)
  }
}
