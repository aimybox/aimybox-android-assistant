package com.justai.aimybox.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import com.justai.aimybox.Aimybox
import com.justai.aimybox.components.adapter.AimyboxAssistantAdapter
import com.justai.aimybox.components.extensions.isPermissionGranted
import com.justai.aimybox.components.extensions.startActivityIfExist
import com.justai.aimybox.components.view.AimyboxButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext


class AimyboxAssistantFragment : Fragment(), CoroutineScope {

    companion object {
        private const val REQUEST_PERMISSION_CODE = 100
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Main + Job()

    private lateinit var viewModel: AimyboxAssistantViewModel
    private lateinit var recycler: RecyclerView
    private lateinit var aimyboxButton: AimyboxButton

    private val adapter = AimyboxAssistantAdapter(viewModel::onButtonClick)

    private var revealTimeMs = 0L

    override fun onAttach(context: Context) {
        super.onAttach(context)

        val aimyboxProvider = requireNotNull(findAimyboxProvider()) {
            "Parent Activity or Application must implement AimyboxProvider interface"
        }

        if (!::viewModel.isInitialized) {
            viewModel = ViewModelProviders.of(requireActivity(), aimyboxProvider.getViewModelFactory())
                .get(AimyboxAssistantViewModel::class.java)
        }
        onViewModelInitialized(viewModel)

        revealTimeMs = context.resources.getInteger(R.integer.assistant_reveal_time_ms).toLong()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        inflater.inflate(R.layout.fragment_aimybox_assistant, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.apply {
            recycler = findViewById(R.id.fragment_aimybox_assistant_recycler)

            recycler.adapter = adapter

            aimyboxButton = findViewById(R.id.fragment_aimybox_assistant_button)
            aimyboxButton.setOnClickListener(::onAimyboxButtonClick)
        }
    }

    @CallSuper
    fun onViewModelInitialized(viewModel: AimyboxAssistantViewModel) {
        viewModel.isAssistantVisible.observe(this, Observer { isVisible ->
            coroutineContext.cancelChildren()
            if (isVisible) aimyboxButton.expand() else aimyboxButton.collapse()
        })

        viewModel.aimyboxState.observe(this, Observer { state ->
            if (state == Aimybox.State.LISTENING) {
                aimyboxButton.onRecordingStarted()
            } else {
                aimyboxButton.onRecordingStopped()
            }
        })

        viewModel.widgets.observe(this, Observer(adapter::setData))

        viewModel.soundVolumeRms.observe(this, Observer { volume ->
            if (::aimyboxButton.isInitialized) {
                aimyboxButton.onRecordingVolumeChanged(volume)
            }
        })

        launch {
            viewModel.urlIntents.consumeEach { url ->
                context?.startActivityIfExist(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onAimyboxButtonClick(view: View) {
        if (requireContext().isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            viewModel.onAssistantButtonClick()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION_CODE)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSION_CODE && permissions.firstOrNull() == Manifest.permission.RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                viewModel.onAssistantButtonClick()
            } else {
                requireActivity().supportFragmentManager.beginTransaction().apply {
                    add(R.id.fragment_aimybox_container, MicrophonePermissionFragment())
                    addToBackStack(null)
                    commit()
                }
            }
        }
    }

    /**
     * Back press handler. Add this method invocation in your activity to make back pressed behavior correct.
     *
     * For example:
     * ```
     * override fun onBackPressed() {
     *     if (!assistantFragment.onBackPressed()) super.onBackPressed()
     * }
     * ```
     * */
    fun onBackPressed(): Boolean {
        val isVisible = viewModel.isAssistantVisible.value ?: false
        if (isVisible) viewModel.onBackPressed()
        return isVisible
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coroutineContext.cancel()
    }

    private fun findAimyboxProvider(): AimyboxProvider? {
        val activity = requireActivity()
        val application = activity.application
        return when {
            activity is AimyboxProvider -> activity
            application is AimyboxProvider -> application
            else -> null
        }
    }
}