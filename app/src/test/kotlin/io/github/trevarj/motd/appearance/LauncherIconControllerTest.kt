package io.github.trevarj.motd.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.LauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class LauncherIconControllerTest {
    @Test fun aliasFqcn_covers_all_seven_variants_under_the_stable_namespace() {
        assertEquals("io.github.trevarj.motd.LauncherDefault", launcherAliasFqcn(LauncherIcon.DEFAULT))
        assertEquals("io.github.trevarj.motd.LauncherMono", launcherAliasFqcn(LauncherIcon.MONO))
        assertEquals("io.github.trevarj.motd.LauncherTerminal", launcherAliasFqcn(LauncherIcon.TERMINAL))
        assertEquals("io.github.trevarj.motd.LauncherGruvbox", launcherAliasFqcn(LauncherIcon.GRUVBOX))
        assertEquals("io.github.trevarj.motd.LauncherCatppuccin", launcherAliasFqcn(LauncherIcon.CATPPUCCIN))
        assertEquals("io.github.trevarj.motd.LauncherNord", launcherAliasFqcn(LauncherIcon.NORD))
        assertEquals("io.github.trevarj.motd.LauncherLight", launcherAliasFqcn(LauncherIcon.LIGHT))
    }

    @Test fun aliasFqcn_is_distinct_per_variant() {
        val fqcns = LauncherIcon.entries.map(::launcherAliasFqcn)
        assertEquals(fqcns.size, fqcns.toSet().size)
    }

    @Test fun componentPlan_enables_the_target_and_disables_every_other_alias_exactly_once() {
        val (enable, disable) = launcherComponentPlan(LauncherIcon.NORD, LauncherIcon.entries)
        assertEquals(launcherAliasFqcn(LauncherIcon.NORD), enable)
        assertFalse(disable.contains(enable))
        assertEquals(LauncherIcon.entries.size - 1, disable.size)
        assertEquals(disable.size, disable.toSet().size) // no duplicates
        val expectedDisabled =
            LauncherIcon.entries
                .filter { it != LauncherIcon.NORD }
                .map(::launcherAliasFqcn)
                .toSet()
        assertEquals(expectedDisabled, disable.toSet())
    }

    @Test fun componentPlan_for_default_disables_all_six_variant_aliases() {
        val (enable, disable) = launcherComponentPlan(LauncherIcon.DEFAULT, LauncherIcon.entries)
        assertEquals(launcherAliasFqcn(LauncherIcon.DEFAULT), enable)
        assertEquals(6, disable.size)
        assertFalse(disable.contains(launcherAliasFqcn(LauncherIcon.DEFAULT)))
    }
}

@RunWith(RobolectricTestRunner::class)
class LauncherIconControllerApplyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager get() = context.packageManager
    private val controller by lazy { LauncherIconController(context) }

    private fun componentFor(icon: LauncherIcon) = ComponentName(context.packageName, launcherAliasFqcn(icon))

    private fun isEnabled(
        icon: LauncherIcon,
        manifestDefaultEnabled: Boolean,
    ): Boolean =
        when (packageManager.getComponentEnabledSetting(componentFor(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> manifestDefaultEnabled
        }

    @Test fun apply_enables_only_the_selected_alias() {
        controller.apply(LauncherIcon.GRUVBOX)

        assertTrue(isEnabled(LauncherIcon.GRUVBOX, manifestDefaultEnabled = false))
        LauncherIcon.entries.filter { it != LauncherIcon.GRUVBOX }.forEach { other ->
            assertFalse(
                "expected $other disabled",
                isEnabled(other, manifestDefaultEnabled = other == LauncherIcon.DEFAULT),
            )
        }
    }

    @Test fun apply_switching_variants_disables_the_previous_alias() {
        controller.apply(LauncherIcon.NORD)
        assertTrue(isEnabled(LauncherIcon.NORD, manifestDefaultEnabled = false))

        controller.apply(LauncherIcon.LIGHT)
        assertTrue(isEnabled(LauncherIcon.LIGHT, manifestDefaultEnabled = false))
        assertFalse(isEnabled(LauncherIcon.NORD, manifestDefaultEnabled = false))
    }

    @Test fun apply_back_to_default_re_enables_the_default_alias_and_disables_the_rest() {
        controller.apply(LauncherIcon.MONO)
        controller.apply(LauncherIcon.DEFAULT)

        assertTrue(isEnabled(LauncherIcon.DEFAULT, manifestDefaultEnabled = true))
        assertFalse(isEnabled(LauncherIcon.MONO, manifestDefaultEnabled = false))
    }

    @Test fun apply_is_a_no_op_when_the_target_is_already_current() {
        controller.apply(LauncherIcon.CATPPUCCIN)
        val settingBefore = packageManager.getComponentEnabledSetting(componentFor(LauncherIcon.CATPPUCCIN))

        controller.apply(LauncherIcon.CATPPUCCIN)

        assertEquals(settingBefore, packageManager.getComponentEnabledSetting(componentFor(LauncherIcon.CATPPUCCIN)))
        assertTrue(isEnabled(LauncherIcon.CATPPUCCIN, manifestDefaultEnabled = false))
    }
}
