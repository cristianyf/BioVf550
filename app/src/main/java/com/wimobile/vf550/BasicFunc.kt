package com.wimobile.vf550

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.biominiseries.BioMiniFactory
import com.biominiseries.CaptureResponder
import com.biominiseries.IBioMiniDevice
import com.biominiseries.IBioMiniDevice.CaptureOption
import com.biominiseries.IBioMiniDevice.FingerState
import com.biominiseries.IBioMiniDevice.SecureDataMode
import com.biominiseries.IBioMiniDevice.TemplateData
import com.biominiseries.IUsbEventHandler.DeviceChangeEvent
import com.biominiseries.enums.DeviceDataHandler
import com.biominiseries.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException

class BasicFunc : AppCompatActivity(), View.OnClickListener {
    //Object
    var mWakeLock: WakeLock? = null
    private var mContext: Context? = null
    private var mUsbManager: UsbManager? = null
    var mUsbDevice: UsbDevice? = null
    private var mPermissionIntent: PendingIntent? = null
    private var mBioMiniFactory: BioMiniFactory? = null
    var mCurrentDevice: IBioMiniDevice? = null
    private val mCaptureOption = CaptureOption()
    private var isAbortCapturing = false

    //UI Component
    private var mImageView: ImageView? = null
    private var mCaptureSingleButton: Button? = null

    //+FP POWER ON/OFF
    var m_usb_manager: UsbManager? = null
    var ret = -1

    //FP POWER ON/OFF+
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basic_func)
        loadResource()

        //+FP POWER ON/OFF
        try {
            if (m_usb_manager == null) m_usb_manager =
                this.getSystemService(USB_SERVICE) as UsbManager
            ret = m_usb_manager?.setFingerPrinterPower(true) ?: -1
            Log.d(TAG, "onCreate: power on : $ret")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        //FP POWER ON/OFF+
        if (mContext == null) mContext = this
        requestWakeLock()
        if (mUsbManager == null) mUsbManager = getSystemService(USB_SERVICE) as UsbManager
        initUsbListener()
        addDeviceToUsbDeviceList()
    }

    fun loadResource() {
        Logger.d("START!")
        mImageView = findViewById(R.id._ivCapture)
        mCaptureSingleButton = findViewById(R.id.bt_capturesingle)
        mCaptureSingleButton?.setOnClickListener(this)
    }

    private fun requestWakeLock() {
        Logger.d("START!")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ":BioMini WakeLock")
        mWakeLock?.acquire()
    }

    private fun initUsbListener() {
        Logger.d("start!")
        mContext!!.registerReceiver(mUsbReceiver, IntentFilter(ACTION_USB_PERMISSION))
        val attachfilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        mContext!!.registerReceiver(mUsbReceiver, attachfilter)
        val detachfilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        mContext!!.registerReceiver(mUsbReceiver, detachfilter)
    }

    fun addDeviceToUsbDeviceList() {
        Logger.d("start!")
        if (mUsbManager == null) {
            Logger.d("mUsbManager is null")
            return
        }
        if (mUsbDevice != null) {
            Logger.d("usbdevice is not null!")
            return
        }
        val deviceList = mUsbManager!!.deviceList
        val deviceIter: Iterator<UsbDevice> = deviceList.values.iterator()
        while (deviceIter.hasNext()) {
            val _device = deviceIter.next()
            if (_device.vendorId == 0x16d1) {
                Logger.d("found Xperix usb device")
                mUsbDevice = _device
                if (mUsbManager?.hasPermission(mUsbDevice) == false) {
                    Logger.d("This device need to Usb Permission!")
                    sendEmptyMsgToHandler(REQUEST_USB_PERMISSION)
                } else {
                    Logger.d("This device alread have USB permission! please activate this device.")
                    sendEmptyMsgToHandler(ACTIVATE_USB_DEVICE)
                }
            } else {
                Logger.d("This device is not biominiseries device!  : " + _device.vendorId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 30) {
            if (!isExternalStorageManager) {
                showSettingAccessFolder()
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        Logger.d("START!")
        if (!mWakeLock!!.isHeld) mWakeLock!!.acquire()
    }

    override fun onPause() {
        super.onPause()
        Logger.d("START!")
        if (mWakeLock!!.isHeld) mWakeLock!!.release()
    }

    override fun onStop() {
        super.onStop()
        Logger.d("START!")
        if (mWakeLock!!.isHeld) mWakeLock!!.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d("START!")
        var result = 0
        if (mCurrentDevice != null) {
            if (mCurrentDevice!!.isCapturing) {
                doAbortCapture()
                while (mCurrentDevice!!.isCapturing) {
                    SystemClock.sleep(10)
                }
            }
        }
        if (mBioMiniFactory != null) {
            if (mUsbDevice != null) result = mBioMiniFactory!!.removeDevice(mUsbDevice)
            if (result == IBioMiniDevice.ErrorCode.OK.value() || result == IBioMiniDevice.ErrorCode.ERR_NO_DEVICE.value()) {
                mBioMiniFactory!!.close()
                mContext!!.unregisterReceiver(mUsbReceiver)
                mUsbDevice = null
                mCurrentDevice = null
            }
        }
        //+FP POWER ON/OFF
        if (m_usb_manager == null) m_usb_manager = this.getSystemService(USB_SERVICE) as UsbManager
        ret = m_usb_manager?.setFingerPrinterPower(false) ?: -1
        Log.d(TAG, "onDestroy: power off : $ret")
        //FP POWER ON/OFF+
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setLogInTextView("write permission granted")
            requestBatteryOptimization()
        }
    }

    override fun onClick(v: View) {
        if (mCurrentDevice == null) {
            setLogInTextView(mContext!!.resources.getString(R.string.error_device_not_conneted))
            return
        }
        val err_other_capture_running =
            mContext!!.resources.getString(R.string.error_other_capture_runnging)
        when (v.id) {
            R.id.bt_capturesingle -> {
                if (mCurrentDevice!!.isCapturing) {
                    setLogInTextView(err_other_capture_running)
                    sendMsgToHandler(SET_UI_CLICKED_ENABLED, false)
                    return
                }
                Logger.d("bt_capturesingle clicked!")
                FingerCapture.doSinlgeCapture(mCurrentDevice!!)
            }
        }
    }

    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                ACTION_USB_PERMISSION -> {
                    Logger.d("ACTION_USB_PERMISSION")
                    val hasUsbPermission =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Logger.d("haspermission = $hasUsbPermission")
                    if (hasUsbPermission && mUsbDevice != null) {
                        Logger.d(mUsbDevice!!.deviceName + " is acquire the usb permission. activate this device.")
                        sendEmptyMsgToHandler(ACTIVATE_USB_DEVICE)
                    } else {
                        Logger.d("USB permission is not granted!")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Logger.d("ACTION_USB_DEVICE_ATTACHED")
                    addDeviceToUsbDeviceList()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Logger.d("ACTION_USB_DEVICE_DETACHED")
                    setLogInTextView(resources.getString(R.string.usb_detached))
                    if (mCurrentDevice != null) {
                        if (mCurrentDevice!!.isCapturing) {
                            doAbortCapture()
                        }
                    }
                    removeDevice()
                }

                else -> {}
            }
        }
    }
    var mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        @SuppressLint("WrongConstant")
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                ACTIVATE_USB_DEVICE -> {
                    if (mUsbDevice != null) Logger.d("ACTIVATE_USB_DEVICE : " + mUsbDevice!!.deviceName)
                    createBioMiniDevice()
                }

                REQUEST_USB_PERMISSION -> {
                    var FLAG_MUTABLE = 0 //PendingIntent.FLAG_MUTABLE
                    if (Build.VERSION.SDK_INT >= 31 /*Build.VERSION_CODES.S*/) {
                        FLAG_MUTABLE = 1 shl 25
                    }
                    mPermissionIntent = PendingIntent.getBroadcast(
                        mContext, 0, Intent(
                            ACTION_USB_PERMISSION
                        ), FLAG_MUTABLE
                    )
                    mUsbManager!!.requestPermission(mUsbDevice, mPermissionIntent)
                }

                DO_FIRMWARE_UPDATE -> {
                    val firmware_file_path = msg.obj as String
                    try {
                        doFwUpdate(firmware_file_path)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                SET_TEXT_LOGVIEW -> {
                    val _log = msg.obj as String
                    Log.d(TAG, "SET_TEXT_LOGVIEW: $_log\n")
                }

                SET_UI_CLICKED_ENABLED -> {
                    Logger.d("SET_UI_CLICKED_ENABLED")
                    val ui_click_enabled = msg.obj as Boolean
                    setUiClickable(ui_click_enabled)
                }
            }
        }
    }

    private fun removeDevice() {
        Logger.d("ACTION_USB_DEVICE_DETACHED")
        if (mBioMiniFactory != null) {
            mBioMiniFactory!!.removeDevice(mUsbDevice)
            mBioMiniFactory!!.close()
        }
        mUsbDevice = null
        mCurrentDevice = null
        cleareViewForCapture()
        resetSettingMenu()
    }

    @Synchronized
    fun setLogInTextView(msg: String) {
        sendMsgToHandler(SET_TEXT_LOGVIEW, msg)
    }

    private fun createBioMiniDevice() {
        Logger.d("START!")
        if (mUsbDevice == null) {
            setLogInTextView(resources.getString(R.string.error_device_not_conneted))
            return
        }
        if (mBioMiniFactory != null) {
            mBioMiniFactory!!.close()
        }
        Logger.d("new BioMiniFactory( )")
        mBioMiniFactory = object : BioMiniFactory(mContext, mUsbManager) {
            //for android sample
            override fun onDeviceChange(event: DeviceChangeEvent, dev: Any) {
                Logger.d("onDeviceChange : $event")
            }
        }
        Logger.d("new BioMiniFactory( ) : $mBioMiniFactory")
        val _result = mBioMiniFactory?.addDevice(mUsbDevice)
        if (_result == true) {
            mCurrentDevice = mBioMiniFactory?.getDevice(0)
            if (mCurrentDevice != null) {
                setLogInTextView(resources.getString(R.string.device_attached))
                Logger.d("mCurrentDevice attached : $mCurrentDevice")
                runOnUiThread {
                    if (mCurrentDevice != null /*&& mCurrentDevice.getDeviceInfo() != null*/) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestWritePermission()
                        }
                        setEnableMenuForDevice()
                        cleareImageView()
                    }
                }
            } else {
                Logger.d("mCurrentDevice is null")
            }
        } else {
            Logger.d("addDevice is fail!")
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun requestWritePermission() {
        Logger.d("start!")
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_PERMISSION
            )
        } else {
            Logger.d("WRITE_EXTERNAL_STORAGE permission already granted!")
            requestBatteryOptimization()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val getpermission = Intent()
                getpermission.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                val uri = Uri.fromParts("package", this.packageName, null)
                getpermission.data = uri
                startActivity(getpermission)
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    fun resetSettingMenu() {
        if (mCurrentDevice == null) {
            mCaptureSingleButton!!.isEnabled = false
        }
    }

    private fun cleareImageView() {
        if (mImageView != null) {
            mImageView!!.setImageBitmap(null)
        }
    }

    private fun cleareViewForCapture() {
        cleareImageView()
    }

    private fun doAbortCapture() {
        Thread(Runnable {
            if (mCurrentDevice != null) {
                if (mCurrentDevice!!.isCapturing == false) {
                    setLogInTextView("Capture Function is already aborted.")
                    mCaptureOption.captureFuntion = IBioMiniDevice.CaptureFuntion.NONE
                    sendMsgToHandler(SET_USER_INPUT_ENABLED, true)
                    sendMsgToHandler(SET_UI_CLICKED_ENABLED, true)
                    isAbortCapturing = false
                    return@Runnable
                }
                val result = mCurrentDevice!!.abortCapturing()
                Logger.d("run: abortCapturing : $result")
                if (result == 0) {
                    if (mCaptureOption.captureFuntion != IBioMiniDevice.CaptureFuntion.NONE) setLogInTextView(
                        mCaptureOption.captureFuntion.name + " is aborted."
                    )
                    mCaptureOption.captureFuntion = IBioMiniDevice.CaptureFuntion.NONE
                    sendMsgToHandler(SET_USER_INPUT_ENABLED, true)
                    sendMsgToHandler(SET_UI_CLICKED_ENABLED, true)
                    isAbortCapturing = false
                } else {
                    if (result == IBioMiniDevice.ErrorCode.ERR_CAPTURE_ABORTING.value()) {
                        setLogInTextView("abortCapture is still running.")
                    } else {
                        setLogInTextView("abort capture fail!")
                    }
                }
            }
        }).start()
    }

    private fun setUiClickable(isClickable: Boolean) {
        Logger.d("isClickable = $isClickable")
        mCaptureSingleButton!!.isClickable = isClickable
    }

    @Throws(IOException::class)
    private fun doFwUpdate(path: String) {
        Logger.d("doFwUpdate: start!")
        var result = 0
        val target_file = File(path)
        Logger.d("target_file : $target_file")
        val _filename = target_file.name
        val _devicename = mCurrentDevice!!.deviceInfo.deviceName
        if (_devicename.contains("Slim 2S")) {
            if (!_filename.contains("BMS2S")) {
                setLogInTextView("This is not BMS2S Firmware File!")
                return
            }
        }
        if (_devicename.contains("Slim 3")) {
            if (!_filename.contains("BMS3")) {
                setLogInTextView("This is not BMS3 Firmware File!")
                return
            }
        }
        result = mCurrentDevice!!.doFwUpdate(target_file)
        setLogInTextView("$_filename firmware Update process is started!")
        if (result == IBioMiniDevice.ErrorCode.OK.value()) {
            setLogInTextView("Fw Update is successfull!")
            setLogInTextView("Device will reboot automatically.")
        } else {
            setLogInTextView("Fw Update is fail!!")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FIRMWARE_UPDATE_EVENT -> if (resultCode == RESULT_OK) {
                // Get the Uri of the selected file
                val _fileuri = data!!.data

                // Get the path
                val fwfile = uriToFile(mContext, _fileuri)
                val path = fwfile!!.absolutePath
                setLogInTextView("FW : $path")
                if (mUsbDevice!!.productId == BioMiniSlim2) {
                    if (!path.contains("BMS2")) {
                        setLogInTextView("This is not BMS2 Firmware File!")
                        return
                    }
                }
                val msg = Message()
                msg.what = DO_FIRMWARE_UPDATE
                msg.obj = fwfile
                mHandler.sendMessage(msg)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun clearCache(context: Context?) {
        try {
            val cacheDir = context!!.cacheDir
            val files = cacheDir.listFiles()
            if (files != null) {
                for (file in files) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // handle the exception
        }
    }

    private fun uriToFile(context: Context?, uri: Uri?): File? {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var outputFile: File? = null
        try {
            clearCache(context)
            inputStream = context!!.contentResolver.openInputStream(uri!!)
            outputFile = File(context.cacheDir, getFileName(context, uri))
            outputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream!!.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return outputFile
    }

    @SuppressLint("Range")
    fun getFileName(context: Context?, uri: Uri?): String? {
        var result: String? = null
        if (uri!!.scheme == "content") {
            val cursor = context!!.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor!!.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result!!.lastIndexOf('/')
            if (cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }

    private fun setEnableMenuForDevice() {
        Logger.d("START!")
        //capture single
        mCaptureSingleButton!!.isEnabled = true
    }

    private fun sendMsgToHandler(what: Int, msgToSend: String) {
        val msg = Message()
        msg.what = what
        msg.obj = msgToSend
        mHandler.sendMessage(msg)
    }

    private fun sendMsgToHandler(what: Int, objToSend: Any) {
        val msg = Message()
        msg.what = what
        msg.obj = objToSend
        mHandler.sendMessage(msg)
    }

    private fun sendEmptyMsgToHandler(what: Int) {
        mHandler.sendEmptyMessage(what)
    }

    // if Android Target SDK >=30
    private fun showSettingAccessFolder() {
        if (!isExternalStorageManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                accessManageFolder()
            }
        }
    }

    private val isExternalStorageManager: Boolean
        private get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

    @RequiresApi(Build.VERSION_CODES.R)
    fun accessManageFolder() {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent()
            intent.action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            val uri = Uri.fromParts("package", this.packageName, null)
            intent.data = uri
            startActivity(intent)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
        private const val TAG = "BioMini"
        private const val REQUEST_WRITE_PERMISSION = 786

        //basic event
        private const val BASE_EVENT = 3000
        private const val ACTIVATE_USB_DEVICE = BASE_EVENT + 1
        private const val REQUEST_USB_PERMISSION = BASE_EVENT + 4
        private const val DO_FIRMWARE_UPDATE = BASE_EVENT + 7
        private const val SET_TEXT_LOGVIEW = BASE_EVENT + 10
        private const val SET_USER_INPUT_ENABLED = BASE_EVENT + 13
        private const val SET_UI_CLICKED_ENABLED = BASE_EVENT + 14

        //File Browser Event
        private const val BASE_FILE_BROWSER_EVENT = 5000
        private const val FIRMWARE_UPDATE_EVENT = BASE_FILE_BROWSER_EVENT + 1

        //Device ID
        private const val BioMiniSlim2 = 0x0408
        fun getPath(context: Context?, uri: Uri?): String? {
            val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
            // DocumentProvider
            Logger.d("uri.getScheme: " + uri!!.scheme)
            if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
                // ExternalStorageProvider
                if (isExternalStorageDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split =
                        docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val type = split[0]
                    if ("primary".equals(type, ignoreCase = true)) {
                        return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    }
                    // TODO handle non-primary volumes
                } else if (isDownloadsDocument(uri)) {
                    val id = DocumentsContract.getDocumentId(uri)
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"),
                        java.lang.Long.valueOf(id)
                    )
                    return getDataColumn(context, contentUri, null, null)
                } else if (isMediaDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split =
                        docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val type = split[0]
                    var contentUri: Uri? = null
                    if ("image" == type) {
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else if ("video" == type) {
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else if ("audio" == type) {
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(split[1])
                    return getDataColumn(context, contentUri, selection, selectionArgs)
                }
            } else if ("content".equals(uri.scheme, ignoreCase = true)) {
                return getDataColumn(context, uri, null, null)
            } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                return uri.path
            }
            return null
        }

        /**
         * Get the value of the data column for this Uri. This is useful for
         * MediaStore Uris, and other file-based ContentProviders.
         *
         * @param context       The context.
         * @param uri           The Uri to query.
         * @param selection     (Optional) Filter used in the query.
         * @param selectionArgs (Optional) Selection arguments used in the query.
         * @return The value of the _data column, which is typically a file path.
         */
        fun getDataColumn(
            context: Context?, uri: Uri?, selection: String?,
            selectionArgs: Array<String>?
        ): String? {
            var cursor: Cursor? = null
            val column = "_data"
            val projection = arrayOf(
                column
            )
            try {
                cursor = context!!.contentResolver.query(
                    uri!!, projection, selection, selectionArgs,
                    null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val column_index = cursor.getColumnIndexOrThrow(column)
                    return cursor.getString(column_index)
                }
            } finally {
                cursor?.close()
            }
            return null
        }

        /**
         * @param uri The Uri to check.
         * @return Whether the Uri authority is ExternalStorageProvider.
         */
        fun isExternalStorageDocument(uri: Uri?): Boolean {
            return "com.android.externalstorage.documents" == uri!!.authority
        }

        /**
         * @param uri The Uri to check.
         * @return Whether the Uri authority is DownloadsProvider.
         */
        fun isDownloadsDocument(uri: Uri?): Boolean {
            return "com.android.providers.downloads.documents" == uri!!.authority
        }

        /**
         * @param uri The Uri to check.
         * @return Whether the Uri authority is MediaProvider.
         */
        fun isMediaDocument(uri: Uri?): Boolean {
            return "com.android.providers.media.documents" == uri!!.authority
        }
    }
}