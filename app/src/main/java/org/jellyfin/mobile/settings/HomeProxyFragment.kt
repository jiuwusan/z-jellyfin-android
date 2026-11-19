package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.FragmentHomeProxyBinding
import org.jellyfin.mobile.proxy.HomeProxyForm
import org.jellyfin.mobile.proxy.HomeProxySettings
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.withThemedContext
import org.koin.android.ext.android.inject

class HomeProxyFragment : Fragment() {
    private val settings: HomeProxySettings by inject()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val themedInflater = inflater.withThemedContext(requireContext(), R.style.AppTheme_Settings)
        val binding = FragmentHomeProxyBinding.inflate(themedInflater, container, false)
        binding.root.applyWindowInsetsAsMargins()
        binding.toolbar.setTitle(R.string.home_proxy_title)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationContentDescription(R.string.home_proxy_back)
        binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        val form = HomeProxyForm(binding.form, settings)
        binding.saveProfile.setOnClickListener {
            if (form.save()) {
                Toast.makeText(requireContext(), R.string.home_proxy_saved, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
        return binding.root
    }
}
