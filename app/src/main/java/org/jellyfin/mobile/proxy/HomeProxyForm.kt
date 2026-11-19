package org.jellyfin.mobile.proxy

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.HomeProxyFormBinding
import java.io.IOException
import java.security.GeneralSecurityException

internal class HomeProxyForm(
    private val binding: HomeProxyFormBinding,
    private val settings: HomeProxySettings,
) {
    init {
        val profile = settings.profile
        with(binding) {
            proxyHost.setText(profile?.host ?: HomeProxyProfile.DEFAULT_HOST)
            proxyPort.setText((profile?.port ?: HomeProxyProfile.DEFAULT_PORT).toString())
            proxyUsername.setText(profile?.username ?: HomeProxyProfile.DEFAULT_USERNAME)
            if (!profile?.password.isNullOrEmpty()) {
                proxyPassword.setHint(R.string.home_proxy_password_mask)
                proxyKeepPasswordHelp.isVisible = true
            }
            if (profile != null) proxyPassword.imeOptions = EditorInfo.IME_ACTION_NEXT
            proxyChangePasswordGroup.isVisible = profile != null
        }
    }

    fun save(): Boolean = with(binding) {
        val host = HomeProxyProfile.normalizeHost(proxyHost.text.toString().trim())
            ?: return proxyHost.invalid(R.string.home_proxy_invalid_host)
        val port = proxyPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return proxyPort.invalid(R.string.home_proxy_invalid_port)
        val username = proxyUsername.text.toString().trim()
        if (!HomeProxyProfile.isValidUsername(username)) return proxyUsername.invalid(R.string.home_proxy_invalid_username)
        val password = proxyPassword.text.toString().ifEmpty { settings.profile?.password.orEmpty() }
        if (password.isBlank()) return proxyPassword.invalid(R.string.home_proxy_password_required)
        val changePassword = proxyChangePassword.text.toString()
        if (settings.profile != null && changePassword.isEmpty()) {
            return proxyChangePassword.invalid(R.string.home_proxy_change_password_required)
        }
        try {
            if (!settings.saveProfile(HomeProxyProfile(host, port, username, password), changePassword)) {
                return proxyChangePassword.invalid(R.string.home_proxy_change_password_incorrect)
            }
            proxyPassword.text.clear()
            proxyChangePassword.text.clear()
            true
        } catch (_: GeneralSecurityException) {
            showSaveError()
            false
        } catch (_: IOException) {
            showSaveError()
            false
        }
    }

    private fun EditText.invalid(@StringRes message: Int): Boolean {
        error = context.getString(message)
        requestFocus()
        return false
    }

    private fun showSaveError() {
        Toast.makeText(binding.root.context, R.string.home_proxy_save_failed, Toast.LENGTH_LONG).show()
    }
}

internal fun HomeProxySettings.prompt(context: Context, onSaved: () -> Unit, onCancel: () -> Unit = {}) {
    val binding = HomeProxyFormBinding.inflate(LayoutInflater.from(context))
    val form = HomeProxyForm(binding, this)
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.home_proxy_setup)
        .setView(binding.root)
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setPositiveButton(R.string.home_proxy_save, null)
        .setOnCancelListener { onCancel() }
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (form.save()) {
            dialog.dismiss()
            onSaved()
        }
    }
}
