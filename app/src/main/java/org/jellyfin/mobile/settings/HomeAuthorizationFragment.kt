package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.HOME_SETTINGS_CONFIRMATION_PASSWORD
import org.jellyfin.mobile.authorization.HomeAuthorizationProfile
import org.jellyfin.mobile.authorization.HomeAuthorizationSettings
import org.jellyfin.mobile.databinding.FragmentHomeAuthorizationBinding
import org.jellyfin.mobile.proxy.HomeProxySettings
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.extensions.requireMainActivity
import org.jellyfin.mobile.utils.withThemedContext
import org.koin.android.ext.android.inject
import java.io.IOException
import java.security.GeneralSecurityException

class HomeAuthorizationFragment : Fragment() {
    private val authorization: HomeAuthorizationSettings by inject()
    private val proxy: HomeProxySettings by inject()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentHomeAuthorizationBinding.inflate(
            inflater.withThemedContext(requireContext(), R.style.AppTheme_Settings),
            container,
            false,
        ).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHomeAuthorizationBinding.bind(view)
        val profile = authorization.profile
        binding.root.applyWindowInsetsAsMargins()
        binding.toolbar.setTitle(R.string.home_authorization_title)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationContentDescription(R.string.home_proxy_back)
        binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        binding.authorizationServer.setText(profile?.server ?: proxy.server)
        binding.authorizationUsername.setText(profile?.username.orEmpty())
        if (profile != null) {
            binding.authorizationPassword.setHint(R.string.home_proxy_password_mask)
            binding.authorizationPassword.imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        binding.authorizationConfirmationGroup.isVisible = profile != null
        binding.clearAuthorization.isVisible = profile != null
        binding.saveProfile.setOnClickListener { save(binding) }
        binding.clearAuthorization.setOnClickListener {
            val confirmation = requireConfirmation(binding) ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.home_authorization_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.home_authorization_clear) { _, _ ->
                    if (authorization.clear(confirmation)) {
                        binding.authorizationConfirmation.text.clear()
                        closeAfterChange()
                    } else {
                        showInvalidConfirmation(binding, confirmation)
                    }
                }
                .show()
        }
    }

    private fun save(binding: FragmentHomeAuthorizationBinding) {
        val server = HomeAuthorizationProfile.normalizeServer(binding.authorizationServer.text.toString())
        if (server == null) {
            binding.authorizationServer.error = getString(R.string.home_authorization_server_invalid)
            binding.authorizationServer.requestFocus()
            return
        }
        val username = binding.authorizationUsername.text.toString().trim()
        if (username.isEmpty()) {
            binding.authorizationUsername.error = getString(R.string.home_authorization_username_required)
            binding.authorizationUsername.requestFocus()
            return
        }
        val existing = authorization.profile
        val confirmation = if (existing == null) "" else requireConfirmation(binding) ?: return
        val password = binding.authorizationPassword.text.toString().ifEmpty {
            existing?.takeIf { it.server == server && it.username == username }?.password.orEmpty()
        }
        if (password.isEmpty()) {
            binding.authorizationPassword.error = getString(R.string.home_authorization_password_required)
            binding.authorizationPassword.requestFocus()
            return
        }

        binding.saveProfile.isEnabled = false
        binding.clearAuthorization.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    authorization.save(HomeAuthorizationProfile(server, username, password), confirmation)
                }
                if (!saved) {
                    binding.saveProfile.isEnabled = true
                    binding.clearAuthorization.isEnabled = true
                    showInvalidConfirmation(binding, confirmation)
                    return@launch
                }
                binding.authorizationPassword.text.clear()
                binding.authorizationConfirmation.text.clear()
                Toast.makeText(requireContext(), R.string.home_authorization_saved, Toast.LENGTH_SHORT).show()
                closeAfterChange()
            } catch (_: IOException) {
                showSaveError(binding)
            } catch (_: GeneralSecurityException) {
                showSaveError(binding)
            }
        }
    }

    private fun requireConfirmation(binding: FragmentHomeAuthorizationBinding): String? {
        val confirmation = binding.authorizationConfirmation.text.toString()
        if (confirmation == HOME_SETTINGS_CONFIRMATION_PASSWORD) return confirmation
        showInvalidConfirmation(binding, confirmation)
        return null
    }

    private fun showInvalidConfirmation(binding: FragmentHomeAuthorizationBinding, confirmation: String) {
        val field = binding.authorizationConfirmation
        binding.authorizationConfirmationGroup.isVisible = true
        field.error = getString(
            if (confirmation.isEmpty()) R.string.home_proxy_change_password_required else R.string.home_proxy_change_password_incorrect,
        )
        field.requestFocus()
    }

    private fun showSaveError(binding: FragmentHomeAuthorizationBinding) {
        Toast.makeText(requireContext(), R.string.home_authorization_save_failed, Toast.LENGTH_LONG).show()
        binding.saveProfile.isEnabled = true
        binding.clearAuthorization.isEnabled = true
    }

    private fun closeAfterChange() {
        parentFragmentManager.popBackStack()
        requireMainActivity().refreshHomeServer()
    }
}
