/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.audiofx.AudioEffect;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class GlobalEqualizerControllerTest {
    private static final class FakeSettings
            implements GlobalEqualizerController.SettingsSource {
        boolean enabled;
        String levels;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String getBandLevels() {
            return levels;
        }
    }

    private static final class FakeEffect
            implements GlobalEqualizerController.EffectHandle {
        final List<Short> appliedLevels = new ArrayList<>();
        boolean enabled;
        boolean released;
        short bandCount = GlobalEqualizerController.BAND_COUNT;
        short[] range = {GlobalEqualizerController.MIN_LEVEL_MB,
                GlobalEqualizerController.MAX_LEVEL_MB};

        @Override
        public short getNumberOfBands() {
            return bandCount;
        }

        @Override
        public short[] getBandLevelRange() {
            return range;
        }

        @Override
        public void setBandLevel(short band, short levelMb) {
            appliedLevels.add(levelMb);
        }

        @Override
        public int setEnabled(boolean value) {
            enabled = value;
            return AudioEffect.SUCCESS;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    @Test
    public void parseBandLevels_validAndClamped() {
        assertArrayEquals(new short[] {-1500, -200, 0, 400, 1500},
                GlobalEqualizerController.parseBandLevels("-2000,-200,0,400,2000"));
    }

    @Test
    public void parseBandLevels_malformedFallsBackToFlat() {
        assertArrayEquals(new short[5],
                GlobalEqualizerController.parseBandLevels("100,broken,300,400,500"));
        assertArrayEquals(new short[5],
                GlobalEqualizerController.parseBandLevels("100,200"));
    }

    @Test
    public void apply_enabledProgramsAllBands() {
        FakeSettings settings = new FakeSettings();
        settings.enabled = true;
        settings.levels = "-400,-200,200,400,100";
        FakeEffect effect = new FakeEffect();
        GlobalEqualizerController controller =
                new GlobalEqualizerController(settings, () -> effect);

        controller.apply();

        assertEquals(List.of((short) -400, (short) -200, (short) 200, (short) 400,
                (short) 100), effect.appliedLevels);
        assertTrue(effect.enabled);
        assertFalse(effect.released);
    }

    @Test
    public void apply_disabledReleasesExistingEffect() {
        FakeSettings settings = new FakeSettings();
        settings.enabled = true;
        FakeEffect effect = new FakeEffect();
        GlobalEqualizerController controller =
                new GlobalEqualizerController(settings, () -> effect);
        controller.apply();

        settings.enabled = false;
        controller.apply();

        assertFalse(effect.enabled);
        assertTrue(effect.released);
    }

    @Test
    public void apply_wrongBandCountFailsOpen() {
        FakeSettings settings = new FakeSettings();
        settings.enabled = true;
        FakeEffect effect = new FakeEffect();
        effect.bandCount = 10;
        GlobalEqualizerController controller =
                new GlobalEqualizerController(settings, () -> effect);

        controller.apply();

        assertTrue(effect.released);
        assertFalse(effect.enabled);
        assertTrue(effect.appliedLevels.isEmpty());
    }

    @Test
    public void audioServerRestartRecreatesAndRestoresEffect() {
        FakeSettings settings = new FakeSettings();
        settings.enabled = true;
        FakeEffect first = new FakeEffect();
        FakeEffect second = new FakeEffect();
        int[] creation = {0};
        GlobalEqualizerController controller = new GlobalEqualizerController(settings,
                () -> creation[0]++ == 0 ? first : second);
        controller.apply();

        controller.onAudioServerRestarted();

        assertTrue(first.released);
        assertTrue(second.enabled);
        assertEquals(2, creation[0]);
    }
}
