package com.wimobile.vf550

import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.biominiseries.enums.DeviceDataHandler

class MainActivity : AppCompatActivity() {
    private var m_usb_manager: UsbManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        m_usb_manager = this.getSystemService(USB_SERVICE) as UsbManager
        val ret = m_usb_manager?.setFingerPrinterPower(true)
    }
}