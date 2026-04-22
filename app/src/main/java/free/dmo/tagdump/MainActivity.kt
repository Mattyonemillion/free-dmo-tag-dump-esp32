package free.dmo.tagdump

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var editUrl: EditText
    private lateinit var textStatus: TextView
    private lateinit var textView: TextView
    private lateinit var listProfiles: ListView
    private lateinit var btnRefresh: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnRename: Button
    private lateinit var btnDelete: Button
    private lateinit var btnUploadLast: Button
    private lateinit var btnWipe: Button
    private lateinit var btnReboot: Button
    private lateinit var btnWifiReset: Button

    private lateinit var client: FreeDmoClient
    private lateinit var prefs: android.content.SharedPreferences
    private val adapterItems = ArrayList<String>()
    private var profiles: List<Profile> = emptyList()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Last scan (kept for "Upload last scan" + details dialog)
    private var lastScanBlocksHex: String? = null
    private var lastScanUidHex: String? = null
    private var lastScanDump: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editUrl = findViewById(R.id.edit_url)
        textStatus = findViewById(R.id.text_status)
        textView = findViewById(R.id.text_view)
        listProfiles = findViewById(R.id.list_profiles)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnSwitch = findViewById(R.id.btn_switch)
        btnRename = findViewById(R.id.btn_rename)
        btnDelete = findViewById(R.id.btn_delete)
        btnUploadLast = findViewById(R.id.btn_upload_last)
        btnWipe = findViewById(R.id.btn_wipe)
        btnReboot = findViewById(R.id.btn_reboot)
        btnWifiReset = findViewById(R.id.btn_wifi_reset)

        prefs = getSharedPreferences("freedmo", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("base_url", "http://freedmo.local") ?: "http://freedmo.local"
        editUrl.setText(savedUrl)
        client = FreeDmoClient(savedUrl)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, adapterItems)
        listProfiles.adapter = listAdapter

        btnRefresh.setOnClickListener { saveUrl(); refreshProfiles() }
        btnSwitch.setOnClickListener { onSwitchClicked() }
        btnRename.setOnClickListener { onRenameClicked() }
        btnDelete.setOnClickListener { onDeleteClicked() }
        btnUploadLast.setOnClickListener { onUploadLastClicked() }
        btnWipe.setOnClickListener { onWipeClicked() }
        btnReboot.setOnClickListener { onRebootClicked() }
        btnWifiReset.setOnClickListener { onWifiResetClicked() }

        textView.setOnClickListener { showScanDetails() }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (!nfcAdapter.isEnabled) {
            textView.text = "NFC is not available / disabled."
        }

        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        checkAndAskForPermission()
        setupForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        stopForegroundDispatch()
    }

    private fun saveUrl() {
        val url = editUrl.text.toString().trim().ifEmpty { "http://freedmo.local" }
        prefs.edit().putString("base_url", url).apply()
        client.baseUrl = url
    }

    private fun selectedProfile(): Profile? {
        val pos = listProfiles.checkedItemPosition
        if (pos < 0 || pos >= profiles.size) return null
        return profiles[pos]
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        runOnUiThread {
            val enabled = !busy
            btnRefresh.isEnabled = enabled
            btnSwitch.isEnabled = enabled
            btnRename.isEnabled = enabled
            btnDelete.isEnabled = enabled
            btnWipe.isEnabled = enabled
            btnReboot.isEnabled = enabled
            btnWifiReset.isEnabled = enabled
            btnUploadLast.isEnabled = enabled && lastScanBlocksHex != null
            if (message != null) textStatus.text = message
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun refreshProfiles() {
        saveUrl()
        setBusy(true, "Loading profiles...")
        Thread {
            try {
                val list = client.getProfiles()
                runOnUiThread {
                    profiles = list
                    adapterItems.clear()
                    val active = list.firstOrNull { it.active }
                    for (p in list) {
                        val activeMark = if (p.active) "*" else " "
                        val uploadedMark = if (p.uploaded) "[U]" else "   "
                        adapterItems.add("$activeMark $uploadedMark ${p.idx}: ${p.name}")
                    }
                    listAdapter.notifyDataSetChanged()
                    if (active != null) {
                        listProfiles.setItemChecked(list.indexOf(active), true)
                        textStatus.text = "Active: ${active.idx} — ${active.name}"
                    } else {
                        textStatus.text = "Connected (${list.size} profiles)"
                    }
                    setBusy(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed", e)
                runOnUiThread {
                    textStatus.text = "Error: ${e.message}"
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun onSwitchClicked() {
        val p = selectedProfile() ?: return toast("Select a profile first")
        if (p.active) return toast("Profile already active")
        AlertDialog.Builder(this)
            .setTitle("Switch to ${p.name}?")
            .setMessage("The printer will be offline for ~10 seconds while the ESP resets.")
            .setPositiveButton("Switch") { _, _ -> performSwitch(p.idx) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSwitch(idx: Int) {
        setBusy(true, "Switching to $idx, waiting ~12 s...")
        Thread {
            try {
                client.switchProfile(idx)
                for (remaining in 12 downTo 1) {
                    val r = remaining
                    runOnUiThread { textStatus.text = "Switching to $idx... ${r}s" }
                    Thread.sleep(1000)
                }
                runOnUiThread { setBusy(false); refreshProfiles() }
            } catch (e: Exception) {
                Log.e(TAG, "switch failed", e)
                toast("Switch failed: ${e.message}")
                runOnUiThread { setBusy(false) }
            }
        }.start()
    }

    private fun onRenameClicked() {
        val p = selectedProfile() ?: return toast("Select a profile first")
        if (!p.uploaded) return toast("Static profiles cannot be renamed")
        promptNameDesc("Rename profile ${p.idx}", p.name, p.description) { name, desc ->
            setBusy(true, "Renaming...")
            Thread {
                try {
                    client.renameProfile(p.idx, name, desc)
                    runOnUiThread { setBusy(false); refreshProfiles() }
                } catch (e: Exception) {
                    toast("Rename failed: ${e.message}")
                    runOnUiThread { setBusy(false) }
                }
            }.start()
        }
    }

    private fun onDeleteClicked() {
        val p = selectedProfile() ?: return toast("Select a profile first")
        if (!p.uploaded) return toast("Static profiles cannot be deleted")
        if (p.active) return toast("Switch to another profile first")
        AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setPositiveButton("Delete") { _, _ ->
                setBusy(true, "Deleting...")
                Thread {
                    try {
                        client.deleteProfile(p.idx)
                        runOnUiThread { setBusy(false); refreshProfiles() }
                    } catch (e: Exception) {
                        toast("Delete failed: ${e.message}")
                        runOnUiThread { setBusy(false) }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onWipeClicked() {
        AlertDialog.Builder(this)
            .setTitle("Wipe all uploaded profiles?")
            .setMessage("This deletes every uploaded profile. Static ones stay. Refused if an upload is active.")
            .setPositiveButton("Wipe") { _, _ ->
                setBusy(true, "Wiping...")
                Thread {
                    try {
                        client.wipeProfiles()
                        runOnUiThread { setBusy(false); refreshProfiles() }
                    } catch (e: Exception) {
                        toast("Wipe failed: ${e.message}")
                        runOnUiThread { setBusy(false) }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onRebootClicked() {
        AlertDialog.Builder(this)
            .setTitle("Reboot ESP32?")
            .setMessage("Keeps saved Wi-Fi credentials and active profile.")
            .setPositiveButton("Reboot") { _, _ ->
                setBusy(true, "Rebooting, waiting 15 s...")
                Thread {
                    try {
                        client.reset()
                    } catch (_: Exception) { /* expected — device drops connection */ }
                    for (r in 15 downTo 1) {
                        runOnUiThread { textStatus.text = "Rebooting... ${r}s" }
                        Thread.sleep(1000)
                    }
                    runOnUiThread { setBusy(false); refreshProfiles() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onWifiResetClicked() {
        AlertDialog.Builder(this)
            .setTitle("Wipe Wi-Fi credentials?")
            .setMessage("ESP32 reboots into setup-portal mode (SSID 'FreeDMO-XXXX'). You will lose connection.")
            .setPositiveButton("Wipe Wi-Fi") { _, _ ->
                setBusy(true, "Resetting Wi-Fi...")
                Thread {
                    try {
                        client.wifiReset()
                    } catch (_: Exception) { /* expected */ }
                    runOnUiThread {
                        textStatus.text = "Wi-Fi wiped. Connect phone to FreeDMO-XXXX AP to reconfigure."
                        setBusy(false)
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onUploadLastClicked() {
        val hex = lastScanBlocksHex ?: return toast("Scan a tag first")
        val uid = lastScanUidHex ?: "unknown"
        val defaultName = "scan_$uid".take(32)
        val defaultDesc = "scanned on phone ${timestamp()}".take(80)
        promptNameDesc("Upload scan ($uid)", defaultName, defaultDesc) { name, desc ->
            setBusy(true, "Uploading...")
            Thread {
                try {
                    val idx = client.uploadProfile(name, desc, hex)
                    toast("Stored as profile $idx")
                    runOnUiThread { setBusy(false); refreshProfiles() }
                } catch (e: Exception) {
                    toast("Upload failed: ${e.message}")
                    runOnUiThread { setBusy(false) }
                }
            }.start()
        }
    }

    private fun promptNameDesc(
        title: String,
        defaultName: String,
        defaultDesc: String,
        onOk: (String, String) -> Unit
    ) {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val nameEdit = EditText(this).apply {
            hint = "Name (≤32 chars)"
            setText(defaultName)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val descEdit = EditText(this).apply {
            hint = "Description (≤80 chars)"
            setText(defaultDesc)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val n = nameEdit.text.toString().trim()
                val d = descEdit.text.toString().trim()
                if (n.isEmpty() || d.isEmpty()) toast("Name and description required")
                else onOk(n.take(32), d.take(80))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    private fun showScanDetails() {
        val dump = lastScanDump ?: return toast("No scan yet")
        val scroll = android.widget.ScrollView(this)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val tv = TextView(this).apply {
            text = dump
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Last scan details")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    // -------- NFC scan path (largely unchanged) --------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            val nfcVTag: NfcV? = NfcV.get(tag)
            if (tag == null || nfcVTag == null) {
                textView.text = "Could not read tag."
                return
            }

            Thread {
                lateinit var inventoryResponse: ByteArray
                lateinit var uid: ByteArray
                lateinit var sysInfoResponse: ByteArray
                lateinit var signatureResponse: ByteArray
                lateinit var blk00to0F: ByteArray
                lateinit var blk10to1F: ByteArray
                lateinit var blk20to2F: ByteArray
                lateinit var blk30to3F: ByteArray
                lateinit var blk40to4F: ByteArray

                try {
                    nfcVTag.connect()
                    if (nfcVTag.isConnected) {
                        val inventory = byteArrayOfInts(0x36, 0x01, 0x00, 0x00)
                        inventoryResponse = transceive(nfcVTag, inventory)
                        uid = inventoryResponse.copyOfRange(1, 9)
                        sysInfoResponse = transceive(nfcVTag, byteArrayOfInts(0x22, 0x2B).plus(uid))
                        signatureResponse = transceive(nfcVTag, byteArrayOfInts(0x22, 0xBD, 0x04).plus(uid))
                        blk00to0F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x00, 0x0F))
                        blk10to1F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x10, 0x0F))
                        blk20to2F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x20, 0x0F))
                        blk30to3F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x30, 0x0F))
                        blk40to4F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x40, 0x0F))
                    }
                    nfcVTag.close()

                    val display = StringBuilder()
                        .append("inventory: ").append(bytesToHex(inventoryResponse)).append("\n")
                        .append("sysInfo: ").append(bytesToHex(sysInfoResponse)).append("\n")
                        .append("signature: ").append(bytesToHex(signatureResponse)).append("\n")
                        .append("blocks:\n")
                        .append(bytesToHex(blk00to0F)).append(", ")
                        .append(bytesToHex(blk10to1F)).append(", ")
                        .append(bytesToHex(blk20to2F)).append(", ")
                        .append(bytesToHex(blk30to3F)).append(", ")
                        .append(bytesToHex(blk40to4F)).append("\n")

                    // For ESP32 upload: concat raw blocks → 640-char hex (no separators, no 0x)
                    val allBlocks = blk00to0F + blk10to1F + blk20to2F + blk30to3F + blk40to4F
                    val blocksHex = bytesToHex(allBlocks, cstyle = false)
                    val uidHex = bytesToHex(uid.reversedArray(), cstyle = false)

                    runOnUiThread {
                        if (blocksHex.length == 640) {
                            lastScanBlocksHex = blocksHex
                            lastScanUidHex = uidHex
                            lastScanDump = display.toString()
                            btnUploadLast.isEnabled = true
                            textView.text = "Last scan: UID $uidHex (tap for details)"
                            textStatus.text = "Scan captured (UID $uidHex). Upload or save ready."
                        } else {
                            lastScanBlocksHex = null
                            lastScanUidHex = null
                            lastScanDump = display.toString()
                            btnUploadLast.isEnabled = false
                            textView.text = "Incomplete scan (tap for details)"
                            textStatus.text = "Scan incomplete: got ${blocksHex.length / 2} bytes, need 320"
                        }
                    }

                    val fileName = uidHex + "_" + SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date()) + ".txt"
                    appendToFile(fileName, display.toString())
                } catch (e: Exception) {
                    Log.e(TAG, e.toString())
                    runOnUiThread { textStatus.text = "Scan error: ${e.message}" }
                }
            }.start()
        }
    }

    private fun transceive(nfcTag: NfcV, data: ByteArray): ByteArray {
        val response = nfcTag.transceive(data)
        return response.copyOfRange(1, response.size)
    }

    private fun appendToFile(fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveContentInFileToExternalStorageAfterQ(content, fileName)
        } else {
            saveContentInFileToExternalStorage(content, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveContentInFileToExternalStorageAfterQ(content: String, fileName: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            contentResolver.openOutputStream(uri, "wa").use { output ->
                output?.write(content.toByteArray())
                output?.close()
            }
        }
    }

    private fun saveContentInFileToExternalStorage(content: String, fileName: String) {
        @Suppress("DEPRECATION") var root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        root = File(root, fileName)
        try {
            if (!root.exists()) {
                root.createNewFile()
            }
            val fileOutputStream = FileOutputStream(root, true)
            fileOutputStream.write(content.toByteArray())
            fileOutputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
        }
    }

    private fun createMultipleBlockReadRequest(uid: ByteArray, vararg integers: Int): ByteArray {
        return byteArrayOfInts(0x22, 0x23).plus(uid).plus(byteArrayOfInts(integers[0], integers[1]))
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                textView.text = "NFC permission not granted."
            }
        }

    private fun checkAndAskForPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun setupForegroundDispatch() {
        val activity: Activity = this
        val intent = Intent(activity.applicationContext, activity.javaClass)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            activity.applicationContext,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, null)
    }

    private fun stopForegroundDispatch() {
        nfcAdapter.disableForegroundDispatch(this)
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()
    private fun bytesToHex(bytes: ByteArray?, cstyle: Boolean = true): String {
        if (null == bytes) return ""
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v: Int = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        if (!cstyle) return String(hexChars)

        val prettyHexChars = ArrayList<Char>()
        for (index in hexChars.indices) {
            if (index % 2 == 0) {
                prettyHexChars.add('0')
                prettyHexChars.add('x')
            }
            prettyHexChars.add(hexChars[index])
            if (index % 2 == 1 && index != hexChars.size - 1)
                prettyHexChars.add(',')
        }
        return String(prettyHexChars.toCharArray())
    }

    private fun byteArrayOfInts(vararg integers: Int) =
        ByteArray(integers.size) { position -> integers[position].toByte() }

    companion object {
        private const val TAG = "MainActivity"
    }
}
