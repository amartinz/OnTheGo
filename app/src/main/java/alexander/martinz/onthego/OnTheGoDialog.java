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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;

public class OnTheGoDialog extends Activity implements View.OnClickListener {
    private static final int START_DELAY = 1000;

    private AlertDialog mDialog;

    private Button mToggleOnTheGo;
    private SeekBar mTransparency;
    private Switch mToggleFrontCamera;

    private OnTheGoService.OnTheGoBinder mBinder;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final View v = getLayoutInflater().inflate(R.layout.dialog_onthego, null, false);

        mToggleOnTheGo = (Button) v.findViewById(R.id.on_the_go_toggle);
        mToggleOnTheGo.setOnClickListener(this);

        mTransparency = (SeekBar) v.findViewById(R.id.alpha_bar);
        int progress = (int) (Settings.get(this).getFloat(Settings.KEY_ONTHEGO_ALPHA, 0.5f) * 100);
        mTransparency.setProgress(progress);
        mTransparency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final float alpha = ((float) (seekBar.getProgress()) / 100.0f);
                if (mBinder != null) {
                    final OnTheGoService service = mBinder.getService();
                    if (service != null) {
                        service.setAlpha(alpha);
                    }
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                final float alpha = ((float) (seekBar.getProgress()) / 100.0f);
                Settings.get(OnTheGoDialog.this).setFloat(Settings.KEY_ONTHEGO_ALPHA, alpha);
            }
        });

        mToggleFrontCamera = (Switch) v.findViewById(R.id.front_camera_toggle);
        final boolean isFront = (Settings.get(this).getInt(Settings.KEY_ONTHEGO_CAMERA, 0) == 1);
        mToggleFrontCamera.setChecked(isFront);
        mToggleFrontCamera.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                final int value = isChecked
                        ? OnTheGoService.CAMERA_FRONT
                        : OnTheGoService.CAMERA_BACK;
                Settings.get(OnTheGoDialog.this).setInt(Settings.KEY_ONTHEGO_CAMERA, value);

                if (mBinder != null && mBinder.getService() != null) {
                    mBinder.getService().restartOnTheGo();
                }
            }
        });
        if (!Utils.hasFrontCamera(this)) {
            mToggleFrontCamera.setVisibility(View.GONE);
        }

        final Intent intent = new Intent(OnTheGoDialog.this, OnTheGoService.class);
        bindService(intent, mServiceConnection, Context.BIND_ABOVE_CLIENT);

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.mipmap.ic_launcher);
        builder.setTitle(R.string.app_name);
        builder.setCancelable(true);
        builder.setView(v);

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = builder.create();
        mDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialogInterface) {
                finish();
            }
        });
        mDialog.show();
    }

    @Override public void onClick(View v) {
        final int id = v.getId();
        switch (id) {
            case R.id.on_the_go_toggle: {
                mToggleOnTheGo.setEnabled(false);
                mToggleOnTheGo.postDelayed(new Runnable() {
                    @Override public void run() {
                        mToggleOnTheGo.setEnabled(true);
                    }
                }, START_DELAY);
                final Intent intent = new Intent(OnTheGoDialog.this, OnTheGoService.class);
                if (mBinder != null) {
                    intent.setAction(OnTheGoService.ACTION_STOP);
                    startService(intent);
                    return;
                }
                intent.setAction(OnTheGoService.ACTION_START);
                startService(intent);
                bindService(intent, mServiceConnection, Context.BIND_ABOVE_CLIENT);
                break;
            }
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = (OnTheGoService.OnTheGoBinder) service;
            mToggleOnTheGo.setText(R.string.stop);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            mBinder = null;
            mToggleOnTheGo.setText(R.string.start);
        }
    };

    @Override protected void onDestroy() {
        try {
            unbindService(mServiceConnection);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

}
