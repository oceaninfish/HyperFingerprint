package io.fingerprintfiveseconds.hyperos;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.function.LongConsumer;

final class PickupMonitor implements SensorEventListener {
    private static final String XIAOMI_PICKUP = "xiaomi.sensor.pickup";
    private static final String ANDROID_PICKUP = "android.sensor.pick_up_gesture";
    private static final long DEBOUNCE_NS = 600_000_000L;

    private final SensorManager manager;
    private final Handler main;
    private final LongConsumer onPickup;
    private final Sensor sensor;
    private final boolean oneShot;
    private boolean active;
    private boolean registered;
    private long activeSince;
    private long lastPickup;

    private final TriggerEventListener trigger = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (active && registered && event.sensor == sensor && event.timestamp >= activeSince) {
                registered = false;
                accept(event.values, event.timestamp);
                register();
            }
        }
    };

    PickupMonitor(SensorManager manager, Handler main, LongConsumer onPickup) {
        this.manager = manager;
        this.main = main;
        this.onPickup = onPickup;
        sensor = selectSensor(manager.getSensorList(Sensor.TYPE_ALL));
        oneShot = sensor != null && sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT;
    }

    void update(boolean enabled, boolean screenOn) {
        boolean shouldTrack = enabled && sensor != null && (screenOn || sensor.isWakeUpSensor());
        if (active == shouldTrack) {
            return;
        }
        active = shouldTrack;
        activeSince = SystemClock.elapsedRealtimeNanos();
        lastPickup = 0;
        if (active) {
            register();
        } else if (registered) {
            registered = false;
            if (oneShot) {
                manager.cancelTriggerSensor(trigger, sensor);
            } else {
                manager.unregisterListener(this, sensor);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (active && registered && event.sensor == sensor) {
            accept(event.values, event.timestamp);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void register() {
        try {
            registered = oneShot ? manager.requestTriggerSensor(trigger, sensor)
                    : manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
            if (!registered) {
                Log.w(FingerprintModule.TAG, "Pickup sensor registration failed: " + sensor.getStringType());
            }
        } catch (RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not register the pickup sensor", error);
        }
    }

    private void accept(float[] values, long timestamp) {
        // Xiaomi emits 0 or 2 on put-down. Both pickup sensor types report 1 on lift.
        if (values != null && values.length > 0 && values[0] == 1f
                && timestamp >= activeSince && (lastPickup == 0 || timestamp - lastPickup >= DEBOUNCE_NS)) {
            lastPickup = timestamp;
            onPickup.accept(timestamp / 1_000_000L);
        }
    }

    private static Sensor selectSensor(List<Sensor> sensors) {
        Sensor best = null;
        int bestPriority = 0;
        for (Sensor candidate : sensors) {
            String type = candidate.getStringType();
            if (XIAOMI_PICKUP.equals(type) || ANDROID_PICKUP.equals(type)) {
                int priority = (candidate.isWakeUpSensor() ? 2 : 0) + (XIAOMI_PICKUP.equals(type) ? 2 : 1);
                if (priority > bestPriority) {
                    best = candidate;
                    bestPriority = priority;
                }
            }
        }
        return best;
    }
}
