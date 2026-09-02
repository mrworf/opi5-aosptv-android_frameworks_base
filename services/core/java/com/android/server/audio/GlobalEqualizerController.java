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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.util.Slog;

import java.util.Arrays;

/** Owns the optional equalizer attached to the global output mix. */
final class GlobalEqualizerController {
    private static final String TAG = "AS.GlobalEqualizer";

    static final int BAND_COUNT = 5;
    static final short MIN_LEVEL_MB = -1500;
    static final short MAX_LEVEL_MB = 1500;
    private static final short[] FLAT_LEVELS = new short[BAND_COUNT];

    interface SettingsSource {
        boolean isEnabled();
        @Nullable String getBandLevels();
    }

    interface EffectHandle {
        short getNumberOfBands();
        @NonNull short[] getBandLevelRange();
        void setBandLevel(short band, short levelMb);
        int setEnabled(boolean enabled);
        void release();
    }

    interface EffectFactory {
        @NonNull EffectHandle create();
    }

    private static final class FrameworkEffectHandle implements EffectHandle {
        private final Equalizer mEqualizer;

        FrameworkEffectHandle() {
            mEqualizer = new Equalizer(/* priority= */ 0, /* audioSession= */ 0);
        }

        @Override
        public short getNumberOfBands() {
            return mEqualizer.getNumberOfBands();
        }

        @Override
        public short[] getBandLevelRange() {
            return mEqualizer.getBandLevelRange();
        }

        @Override
        public void setBandLevel(short band, short levelMb) {
            mEqualizer.setBandLevel(band, levelMb);
        }

        @Override
        public int setEnabled(boolean enabled) {
            return mEqualizer.setEnabled(enabled);
        }

        @Override
        public void release() {
            mEqualizer.release();
        }
    }

    private final SettingsSource mSettings;
    private final EffectFactory mFactory;
    @Nullable private EffectHandle mEffect;

    GlobalEqualizerController(@NonNull SettingsSource settings) {
        this(settings, FrameworkEffectHandle::new);
    }

    GlobalEqualizerController(@NonNull SettingsSource settings, @NonNull EffectFactory factory) {
        mSettings = settings;
        mFactory = factory;
    }

    /** Applies current persistent state. Disabled state releases the effect and consumes no DSP. */
    synchronized void apply() {
        if (!mSettings.isEnabled()) {
            releaseEffect();
            return;
        }

        final short[] levels = parseBandLevels(mSettings.getBandLevels());
        try {
            if (mEffect == null) {
                mEffect = mFactory.create();
            }
            if (mEffect.getNumberOfBands() != BAND_COUNT) {
                throw new IllegalStateException("expected " + BAND_COUNT + " bands");
            }
            final short[] range = mEffect.getBandLevelRange();
            if (range.length != 2 || range[0] > MIN_LEVEL_MB || range[1] < MAX_LEVEL_MB) {
                throw new IllegalStateException("unsupported level range "
                        + Arrays.toString(range));
            }
            for (short band = 0; band < BAND_COUNT; band++) {
                mEffect.setBandLevel(band, levels[band]);
            }
            if (mEffect.setEnabled(true) != AudioEffect.SUCCESS) {
                throw new IllegalStateException("could not enable effect");
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Global equalizer unavailable; leaving audio unmodified", e);
            releaseEffect();
        }
    }

    /** Discards the stale native handle and restores enabled state after audioserver restart. */
    synchronized void onAudioServerRestarted() {
        releaseEffect();
        apply();
    }

    private void releaseEffect() {
        if (mEffect == null) {
            return;
        }
        final EffectHandle effect = mEffect;
        mEffect = null;
        try {
            effect.setEnabled(false);
        } catch (RuntimeException ignored) {
            // A dead audioserver commonly invalidates the old handle; release it regardless.
        }
        try {
            effect.release();
        } catch (RuntimeException ignored) {
            // The native handle may already have died with audioserver.
        }
    }

    @NonNull
    static short[] parseBandLevels(@Nullable String value) {
        if (value == null) {
            return FLAT_LEVELS.clone();
        }
        final String[] fields = value.split(",", -1);
        if (fields.length != BAND_COUNT) {
            return FLAT_LEVELS.clone();
        }
        final short[] levels = new short[BAND_COUNT];
        try {
            for (int i = 0; i < BAND_COUNT; i++) {
                final int level = Integer.parseInt(fields[i]);
                levels[i] = (short) Math.max(MIN_LEVEL_MB, Math.min(MAX_LEVEL_MB, level));
            }
            return levels;
        } catch (NumberFormatException e) {
            return FLAT_LEVELS.clone();
        }
    }
}
