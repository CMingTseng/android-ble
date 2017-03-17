package tw.idv.palatis.ble.services;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.util.Log;

import java.util.UUID;

import tw.idv.palatis.ble.database.WeakObservable;

/**
 * Created by Palatis on 2017/3/15.
 */

public class BatteryService extends BluetoothGattService {
    private static final String TAG = BatteryService.class.getSimpleName();

    // service UUID
    @SuppressWarnings("unused")
    public static final UUID UUID_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");

    // characteristic UUID
    public static final UUID UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    public static final int LEVEL_UNKNOWN = -1;
    public static final int LEVEL_UNAVAILABLE = -2;

    private BluetoothGattCharacteristic mBatteryLevelCharacteristic;
    private int mBatteryLevel = LEVEL_UNKNOWN;

    private final OnBatteryLevelChangedObservable mOnBatteryLevelChangedObservable = new OnBatteryLevelChangedObservable();

    protected BatteryService(@NonNull BluetoothGatt gatt, @NonNull android.bluetooth.BluetoothGattService nativeService) {
        super(gatt, nativeService);

        mBatteryLevelCharacteristic = nativeService.getCharacteristic(UUID_BATTERY_LEVEL);
        if (mBatteryLevelCharacteristic == null) {
            mBatteryLevel = LEVEL_UNAVAILABLE;
            Log.e(TAG, "this BATTERY_SERVICE doesn't have BATTERY_LEVEL characteristics... = =");
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicRead(characteristic);
        onCharacteristicChanged(characteristic);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        final int newLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        Log.d(TAG, "onCharacteristicUpdate(): battery level = " + newLevel + ", old = " + mBatteryLevel);
        if (mBatteryLevel != newLevel)
            mOnBatteryLevelChangedObservable.dispatchBatteryLevelChanged(mBatteryLevel = newLevel);
    }

    public int getBatteryLevel() {
        if (mBatteryLevelCharacteristic == null)
            return LEVEL_UNAVAILABLE;

        if (mBatteryLevel == LEVEL_UNKNOWN)
            readCharacteristic(mBatteryLevelCharacteristic);

        return mBatteryLevel;
    }

    public boolean addOnBatteryLevelChangedListener(OnBatteryLevelChangedListener listener) {
        boolean result = mOnBatteryLevelChangedObservable.registerObserver(listener);
        setCharacteristicNotification(mBatteryLevelCharacteristic, mOnBatteryLevelChangedObservable.numObservers() != 0);
        return result;
    }

    public boolean removeOnBatteryLevelChangedListener(OnBatteryLevelChangedListener listener) {
        boolean result = mOnBatteryLevelChangedObservable.unregisterObserver(listener);
        setCharacteristicNotification(mBatteryLevelCharacteristic, mOnBatteryLevelChangedObservable.numObservers() != 0);
        return result;
    }

    private class OnBatteryLevelChangedObservable extends WeakObservable<OnBatteryLevelChangedListener> {
        void dispatchBatteryLevelChanged(final int newLevel) {
            housekeeping();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    // iterate backward, because observer may unregister itself.
                    for (int i = mObservers.size() - 1; i >= 0; --i) {
                        final OnBatteryLevelChangedListener observer = mObservers.get(i).get();
                        if (observer != null)
                            observer.onBatteryLevelChanged(newLevel);
                    }
                }
            });
        }
    }

    public interface OnBatteryLevelChangedListener {
        @UiThread
        void onBatteryLevelChanged(int newLevel);
    }
}