/*
 * Copyright 2015 Alexander Martinz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package alexander.martinz.onthego;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class Settings {
    public static final String KEY_ONTHEGO_ALPHA = "onthego_alpha";
    public static final String KEY_ONTHEGO_CAMERA = "onthego_camera";
    public static final String KEY_ONTHEGO_SERVICE_RESTART = "onthego_service_restart";

    private static Settings sInstance;

    private Context mContext;
    private SharedPreferences mSharedPreferences;

    private Settings(Context context) {
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public static Settings get(Context context) {
        if (sInstance == null) {
            sInstance = new Settings(context);
        }
        return sInstance;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public String getString(String key, String defaultValue) {
        return mSharedPreferences.getString(key, defaultValue);
    }

    public Settings setString(String key, String value) {
        mSharedPreferences.edit().putString(key, value).apply();
        return this;
    }

    public int getInt(String key) {
        return getInt(key, -1);
    }

    public int getInt(String key, int defaultValue) {
        String value = mSharedPreferences.getString(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    public Settings setInt(String key, int value) {
        return setString(key, String.valueOf(value));
    }

    public int getFloat(String key) {
        return getInt(key, -1);
    }

    public float getFloat(String key, float defaultValue) {
        String value = mSharedPreferences.getString(key, String.valueOf(defaultValue));
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    public Settings setFloat(String key, float value) {
        return setString(key, String.valueOf(value));
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return mSharedPreferences.getBoolean(key, defaultValue);
    }

    public Settings setBoolean(String key, boolean value) {
        mSharedPreferences.edit().putBoolean(key, value).apply();
        return this;
    }

}
