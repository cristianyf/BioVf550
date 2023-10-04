package com.wimobile.vf550

import android.graphics.Bitmap
import com.biominiseries.CaptureResponder
import com.biominiseries.IBioMiniDevice
import com.biominiseries.IBioMiniDevice.CaptureOption
import com.biominiseries.enums.DeviceDataHandler
import com.biominiseries.util.Logger
import java.io.UnsupportedEncodingException

object FingerCapture {

    var mCurrentDevice: IBioMiniDevice? = null
    private val mCaptureOption = CaptureOption()
    var mDeviceDataHandler: DeviceDataHandler? = null

    fun doSinlgeCapture(mCurrentDevice: IBioMiniDevice) {
        this.mCurrentDevice = mCurrentDevice
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
                this.mCurrentDevice!!.setEncryptionKey(_key.toByteArray(charset("UTF-8")))
                Logger.d("getSecureDataMode: " + mDeviceDataHandler!!.secureDataMode)
                val secureDataMode = IBioMiniDevice.SecureDataMode.fromInt(
                    mDeviceDataHandler!!.secureDataMode
                )
                this.mCurrentDevice!!.setEncryptDataMode(secureDataMode, _key)
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
            }
        } else {
            this.mCurrentDevice!!.setEncryptDataMode(IBioMiniDevice.SecureDataMode.NONE, "")
        }
        if (this.mCurrentDevice != null) {
            val result = this.mCurrentDevice!!.captureSingle(
                mCaptureOption,
                mCaptureCallBack,
                true
            )
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
            fingerState: IBioMiniDevice.FingerState
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