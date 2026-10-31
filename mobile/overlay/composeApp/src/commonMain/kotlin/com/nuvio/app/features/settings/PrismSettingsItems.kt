package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.prism.prismSettingsItem

// Prism: the Customise entry, named here so the Appearance page can call it (see prism/mobile/apply.sh).
internal fun LazyListScope.prismAppearanceItem(isTablet: Boolean) = prismSettingsItem(isTablet)
