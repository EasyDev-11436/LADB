package com.draco.ladb.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethod
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

const val ALL_FILES_ACCESS_PERMISSION = 4

class MainActivity : AppCompatActivity() {
    /* View Model */
    private val viewModel: MainActivityViewModel by viewModels()

    /* UI components */
    private lateinit var command: TextInputEditText
    private lateinit var output: MaterialTextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var progress: ProgressBar

    /* Alert dialogs */
    private lateinit var pairDialog: MaterialAlertDialogBuilder

    /* Held when pairing */
    private var pairingLatch = CountDownLatch(0)

    private var lastCommand = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        command = findViewById(R.id.command)
        output = findViewById(R.id.output)
        outputScrollView = findViewById(R.id.output_scrollview)
        progress = findViewById(R.id.progress)

        val permissionsToRequire = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequire.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequire.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissionsToRequire.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequire.toTypedArray(), 0)
        }

        requestAllFilesAccessPermission()

        pairDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_title)
            .setCancelable(false)
            .setView(R.layout.dialog_pair)

        /* Send commands to the ADB instance */
        command.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val text = command.text.toString()
                    lastCommand = text
                    command.text = null
                    lifecycleScope.launch(Dispatchers.IO) {
                        viewModel.adb.sendToShellProcess(text)
                    }
                }
                return@setOnKeyListener true
            }
            return@setOnKeyListener false
        }

        /* Update the output text */
        viewModel.outputText.observe(this, Observer {
            output.text = it
            outputScrollView.post {
                outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                command.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(command, InputMethod.SHOW_EXPLICIT)
            }
        })

        /* Restart the activity on reset */
        viewModel.adb.closed.observe(this, Observer {
            if (it == true) {
                val intent = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                finishAffinity()
                startActivity(intent)
                exitProcess(0)
            }
        })

        /* Prepare progress bar, pairing latch, and script executing */
        viewModel.adb.ready.observe(this, Observer {
            if (it != true) {
                runOnUiThread {
                    command.isEnabled = false
                    progress.visibility = View.VISIBLE
                }
                return@Observer
            }

            lifecycleScope.launch(Dispatchers.IO) {
                pairingLatch.await()

                runOnUiThread {
                    command.isEnabled = true
                    progress.visibility = View.INVISIBLE
                }

                if (viewModel.getScriptFromIntent(intent) != null)
                    executeFromScript()
            }
        })

        /* Check if we need to pair with the device on Android 11 */
        with(PreferenceManager.getDefaultSharedPreferences(this)) {
            if (viewModel.shouldWePair(this)) {
                pairingLatch = CountDownLatch(1)
                viewModel.adb.debug("Requesting pairing information")
                askToPair {
                    with(edit()) {
                        putBoolean(getString(R.string.paired_key), true)
                        apply()
                    }
                    pairingLatch.countDown()
                }
            }
        }

        viewModel.piracyCheck(this)
    }

    /**
     * Execute a script from the main intent
     */
    private fun executeFromScript() {
        val code = viewModel.getScriptFromIntent(intent) ?: return

        /* Invalidate intent */
        intent.type = ""

        Snackbar.make(output, getString(R.string.snackbar_file_opened), Snackbar.LENGTH_SHORT)
            .setAction(getString(R.string.dismiss)) {}
            .show()

        viewModel.adb.sendScript(code)
    }

    /**
     * Ask the user to pair
     */
    private fun askToPair(callback: Runnable? = null) {
        pairDialog
            .create()
            .apply {
                setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay)) { _, _ ->
                    val port = findViewById<TextInputEditText>(R.id.port)!!.text.toString()
                    val code = findViewById<TextInputEditText>(R.id.code)!!.text.toString()

                    lifecycleScope.launch(Dispatchers.IO) {
                        viewModel.adb.debug("Requesting additional pairing information")
                        viewModel.adb.pair(port, code)

                        callback?.run()
                    }
                }
            }
            .show()
    }

    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Access all files on external storage has been granted", Toast.LENGTH_SHORT).show()
        } else {
            val builder = AlertDialog.Builder(this)
                .setTitle("Tip")
                .setMessage("Need permission to access all files on external storage")
                .setPositiveButton("OK") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, ALL_FILES_ACCESS_PERMISSION)
                }
                .setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "You must allow all the permissions.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.last_command -> {
                command.setText(lastCommand)
                command.setSelection(lastCommand.length)
                true
            }
            R.id.help -> {
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.share -> {
                try {
                    val uri = FileProvider.getUriForFile(
                            this,
                            BuildConfig.APPLICATION_ID + ".provider",
                            viewModel.adb.outputBufferFile
                    )
                    val intent = Intent(Intent.ACTION_SEND)
                    with(intent) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "file/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Snackbar.make(output, getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT)
                            .setAction(getString(R.string.dismiss)) {}
                            .show()
                }
                true
            }
            R.id.clear -> {
                viewModel.clearOutputText()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }
}
