package com.wimobile.vf550

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.USB_SERVICE
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import com.biominiseries.BioMiniFactory
import com.biominiseries.CaptureResponder
import com.biominiseries.IBioMiniDevice
import com.biominiseries.IBioMiniDevice.CaptureOption
import com.biominiseries.IUsbEventHandler
import com.biominiseries.enums.DeviceDataHandler
import com.biominiseries.util.Logger
import java.io.UnsupportedEncodingException

object FingerCapture {

    var mCurrentDevice: IBioMiniDevice? = null
    private val mCaptureOption = CaptureOption()
    var mDeviceDataHandler: DeviceDataHandler? = null

    private var mUsbManager: UsbManager? = null
    var mUsbDevice: UsbDevice? = null

    @SuppressLint("StaticFieldLeak")
    private var mBioMiniFactory: BioMiniFactory? = null

    fun getUsbManager(context: Context): UsbManager? {
        if (mUsbManager == null) {
            mUsbManager = context.getSystemService(USB_SERVICE) as UsbManager
        }
        return mUsbManager
    }

    fun getUsbDevice(): UsbDevice? {
        return if (mUsbDevice != null) {
            mUsbDevice
        } else {
            val deviceList = mUsbManager!!.deviceList
            val deviceIter: Iterator<UsbDevice> = deviceList.values.iterator()
            while (deviceIter.hasNext()) {
                val _device = deviceIter.next()
                if (_device.vendorId == 0x16d1) {
                    Logger.d("found Xperix usb device")
                    mUsbDevice = _device
                } else {
                    Logger.d("This device is not biominiseries device!  : " + _device.vendorId)
                }
            }
            mUsbDevice
        }
    }

    private fun createBioMiniDevice(activity: AppCompatActivity): IBioMiniDevice? {
        if (mCurrentDevice != null) {
            return mCurrentDevice
        }
        val usbManager = getUsbManager(activity)
        val usbDevice = getUsbDevice()
        Logger.d("START!")
        if (usbDevice == null) {
            return null
        }
        if (mBioMiniFactory != null) {
            mBioMiniFactory!!.close()
        }
        Logger.d("new BioMiniFactory( )")
        mBioMiniFactory = object : BioMiniFactory(activity, usbManager) {
            override fun onDeviceChange(event: IUsbEventHandler.DeviceChangeEvent, dev: Any) {
                Logger.d("onDeviceChange : $event")
            }
        }
        Logger.d("new BioMiniFactory( ) : $mBioMiniFactory")
        val result = mBioMiniFactory?.addDevice(mUsbDevice)
        if (result == true) {
            mCurrentDevice = mBioMiniFactory?.getDevice(0)
            if (mCurrentDevice != null) {
                Logger.d("mCurrentDevice attached : $mCurrentDevice")
            } else {
                Logger.d("mCurrentDevice is null")
            }
        } else {
            Logger.d("addDevice is fail!")
        }

        return mCurrentDevice
    }

    fun doSingleCapture(activity: AppCompatActivity): Int {
        val mCurrentDevice = createBioMiniDevice(activity)
        if (mCurrentDevice?.isCapturing == true) {
            return 1
        }

        Logger.d("START!")
        if (mDeviceDataHandler == null) {
            mDeviceDataHandler = DeviceDataHdlr.getInstance()
            mDeviceDataHandler!!.dataCallback = mCaptureCallBack
        }

        mCaptureOption.captureFuntion = IBioMiniDevice.CaptureFuntion.CAPTURE_SINGLE
        mCaptureOption.extractParam.captureTemplate = true
        mCaptureOption.extractParam.maxTemplateSize =
            IBioMiniDevice.MaxTemplateSize.MAX_TEMPLATE_1024
        if (mDeviceDataHandler!!.secureMode && mDeviceDataHandler!!.secureModeKey == "" == false) {
            try {
                val _key = mDeviceDataHandler!!.secureModeKey
                mCurrentDevice!!.setEncryptionKey(_key.toByteArray(charset("UTF-8")))
                Logger.d("getSecureDataMode: " + mDeviceDataHandler!!.secureDataMode)
                val secureDataMode = IBioMiniDevice.SecureDataMode.fromInt(
                    mDeviceDataHandler!!.secureDataMode
                )
                this.mCurrentDevice!!.setEncryptDataMode(secureDataMode, _key)
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
            }
        } else {
            mCurrentDevice!!.setEncryptDataMode(IBioMiniDevice.SecureDataMode.NONE, "")
        }
        return if (this.mCurrentDevice != null) {
            val captured = mCurrentDevice!!.captureSingle(
                mCaptureOption,
                mCaptureCallBack,
                true
            )
            return if (captured) {
                0
            } else 1
        } else {
            1
        }
    }

    var mCaptureCallBack: CaptureResponder = object : CaptureResponder() {
        override fun onCapture(context: Any, fingerState: IBioMiniDevice.FingerState) {
            super.onCapture(context, fingerState)
        }

        override fun onCaptureEx(
            context: Any,
            option: IBioMiniDevice.CaptureOption,
            capturedImage: Bitmap?,
            capturedTemplate: IBioMiniDevice.TemplateData?,
            fingerState: IBioMiniDevice.FingerState,
        ): Boolean {

            //fpquality example
            if (mCurrentDevice != null) {
                val imageData = mCurrentDevice!!.captureImageAsRAW_8
                if (imageData != null) {
                    val mode = IBioMiniDevice.FpQualityMode.NQS_MODE_DEFAULT
                    val _fpquality = mCurrentDevice!!.getFPQuality(
                        imageData,
                        mCurrentDevice!!.imageWidth,
                        mCurrentDevice!!.imageHeight,
                        mode.value()
                    )
                    Logger.d("_fpquality : $_fpquality")
                }
            }
            return true
        }

        override fun onCaptureError(context: Any, errorCode: Int, error: String) {
            when (errorCode) {
                IBioMiniDevice.ErrorCode.CTRL_ERR_IS_CAPTURING.value() -> {
                    Logger.d("CTRL_ERR_CAPTURE_ABORTED occured. CTRL_ERR_IS_CAPTURING")
                }

                IBioMiniDevice.ErrorCode.CTRL_ERR_CAPTURE_ABORTED.value() -> {
                    Logger.d("CTRL_ERR_CAPTURE_ABORTED occured. CTRL_ERR_CAPTURE_ABORTED")
                }

                IBioMiniDevice.ErrorCode.CTRL_ERR_FAKE_FINGER.value() -> {
                    Logger.d("CTRL_ERR_CAPTURE_ABORTED occured. CTRL_ERR_FAKE_FINGER")
                }

                else -> {
                    Logger.d("CTRL_ERR_CAPTURE_ABORTED occured.UNKNOWN ERROR")
                }
            }
        }
    }
}