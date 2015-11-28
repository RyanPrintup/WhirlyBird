package com.ryanprintup.android.whirlybird;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

/**
 * I'm not too proud of this code but I was excited to fly the helicopter
 * and the code worked.
 */
public class Controller extends ActionBarActivity implements SensorEventListener, Runnable
{
    private static final String TAG = "Controller";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ConsumerIrManager cir;

    private long lastUpdate = 0;
    private float lastX, lastY;

    private static final int CARRIER_FREQUENCY = 38400;

    private static final int HEADER_DURATION = 2000;
    private static final int LOW_DURATION = 250;
    private static final int ONE_HIGH_DURATION = 750;
    private static final int ZERO_HIGH_DURATION = 350;

    private static final byte STATIONARY_YAW = 63;
    private static final byte STATIONARY_PITCH = 63;
    private static final byte MIDDLE_TRIM = 63;

    private static final byte YAW_INCREMENT = 7;
    private static final byte PITCh_INCREMENT = 7;
    private static final byte THROTTLE_INCREMENT = 7;

    private byte yaw = STATIONARY_YAW;
    private byte pitch = STATIONARY_PITCH;
    private byte throttle = 0;
    private byte trim = MIDDLE_TRIM;

    private boolean controlYaw = true;
    private boolean controlPitch = true;

    private volatile boolean emitting = false;
    private Thread irThread;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        cir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    private int[] createPacket(byte yaw, byte pitch, byte throttle, byte trim)
    {
        // Packet is made up of 3 header pulses and 64 command pulses
        int[] packet = new int[67];
        // Form the command from individual arguments
        int command = yaw << 24 | pitch << 16 | throttle << 8 | trim;

        // Add header
        packet[0] = HEADER_DURATION;
        packet[1] = HEADER_DURATION;
        packet[2] = LOW_DURATION;

        // Reverse bits so they are inserted
        // in the correct order in the loop
        command = Integer.reverse(command);

        // Start at 3 to skip header
        int x = 3;
        for (int i = 0; i < 32; i++) {
            // Grab 1 bit
            int bit = ((command >> i) & 0x01);

            packet[x++] = (bit == 1) ? ONE_HIGH_DURATION : ZERO_HIGH_DURATION;
            packet[x++] = LOW_DURATION;
        }

        return packet;
    }

    /**
     * Use the volume up and down keys
     * to adjust throtle by increments of 10
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (throttle + THROTTLE_INCREMENT > 127) {
                throttle = 127;
            } else {
                throttle += THROTTLE_INCREMENT;
            }

            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (throttle - THROTTLE_INCREMENT < 0) {
                throttle = 0;
            } else {
                throttle -= THROTTLE_INCREMENT;
            }

            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event)
    {
        Sensor sensor = event.sensor;

        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];

            long curTime = System.currentTimeMillis();

            if ((curTime - lastUpdate) > 250) {
                long diffTime = (curTime - lastUpdate);
                lastUpdate = curTime;

                int range = 5;

                if (x > range)  x = range;
                if (x < -range) x = -range;
                if (y > range)  y = range;
                if (y < -range) y = -range;

                x += range;
                y += range;

                if (x >= 4.5 & x <= 5.5) {
                    yaw = STATIONARY_YAW;
                } else {
                    if (controlYaw) yaw = (byte) Math.round((127 / (range * 2)) * x);
                }

                if (y >= 4.5 & y <= 5.5) {
                    pitch = STATIONARY_YAW;
                } else {
                    if(controlPitch) pitch = (byte) Math.round((127 / (range * 2)) * y);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy)
    {
    }

    @Override
    public void run()
    {
        while (emitting) {
            cir.transmit(CARRIER_FREQUENCY, createPacket(yaw, pitch, throttle, trim));
        }
    }

    public void onStartEmittingButton(View view)
    {
        startEmitting();
    }

    public void onStopEmittingButton(View view)
    {
        stopEmitting();
    }

    public void onToggleYaw(View view)
    {
        controlYaw = !controlYaw;
        yaw = STATIONARY_YAW;
    }

    public void onTogglePitch(View view)
    {
        controlPitch = !controlPitch;
        pitch = STATIONARY_PITCH;
    }

    private synchronized void startEmitting()
    {
        if (irThread != null | emitting) {
            return;
        }

        emitting = true;
        irThread = new Thread(this);
        irThread.start();
    }

    private synchronized void stopEmitting()
    {
        if (irThread == null || !emitting) {
            return;
        }

        emitting = false;
        try {
            irThread.join();
        } catch(InterruptedException e) {
        }

        irThread = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.menu_controller, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        finish();

        stopEmitting();
    }
}
