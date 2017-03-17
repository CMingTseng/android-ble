package tw.idv.palatis.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.AnyThread;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v4.util.ArrayMap;
import android.util.Log;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tw.idv.palatis.ble.database.WeakObservable;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public abstract class BluetoothDevice {
    private static final String TAG = BluetoothDevice.class.getSimpleName();

    @Retention(SOURCE)
    @IntDef({
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
            BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED
    })
    public @interface ConnectionState {
    }

    private android.bluetooth.BluetoothDevice mNativeDevice;
    private BluetoothGatt mGatt = null;
    private int mRssi = -127;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final OnConnectionStateChangedObservable mOnConnectionStateChangedObservable = new OnConnectionStateChangedObservable();
    private final OnServiceDiscoveredObservable mOnServiceDiscoveredObservable = new OnServiceDiscoveredObservable();

    private final ArrayMap<UUID, tw.idv.palatis.ble.services.BluetoothGattService> mGattServices = new ArrayMap<>();

    public BluetoothDevice(@NonNull android.bluetooth.BluetoothDevice device) {
        mNativeDevice = device;
    }

    /**
     * @return the name of the device
     */
    public String getName() {
        return mNativeDevice.getName();
    }

    /**
     * @return the bluetooth MAC address of the device
     */
    public String getAddress() {
        return mNativeDevice.getAddress();
    }

    /**
     * @return the received signal strength in dBm. The valid range is [-127, 127].
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * @param rssi the new RSSI value
     */
    public void updateRssi(int rssi) {
        mRssi = rssi;
    }

    /**
     * get the connection state, one of {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_DISCONNECTING}, or
     * {@link BluetoothProfile#STATE_DISCONNECTED}.
     *
     * @param context application context
     * @return current connection state
     */
    @SuppressWarnings("WrongConstant")
    @ConnectionState
    public int getConnectionState(@NonNull final Context context) {
        if (mGatt == null)
            return BluetoothProfile.STATE_DISCONNECTED;

        // FIXME: can't use return {@link BluetoothGattService#getConnectionState} here... throws {@link UnsupportedOperationException}...
        final BluetoothManager btmgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return btmgr.getConnectionState(mNativeDevice, BluetoothProfile.GATT);
    }

    /**
     * connect to the device
     *
     * @param context the Application {@link Context}
     * @return true if connection started
     */
    public boolean connect(@NonNull final Context context, boolean autoConnect, @Nullable final OnErrorCallback callback) {
        if (mGatt != null && getConnectionState(context) != BluetoothProfile.STATE_DISCONNECTED)
            return false;

        Log.d(TAG, "connect(): device = " + getAddress());
        mGatt = mNativeDevice.connectGatt(context, autoConnect, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, @ConnectionState int newState) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onConnectionStateChange(): Failed! device = " + getAddress() + ", status = " + status);
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                Log.d(TAG, "onConnectionStateChanged(): device = " + getAddress() + ", " + status + " => " + newState);
                mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(newState);
                if (newState == BluetoothProfile.STATE_CONNECTED)
                    gatt.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onServiceDiscovered(): Failed! device = " + getAddress() + ", status = " + status);
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                final List<BluetoothGattService> services = gatt.getServices();
                for (final BluetoothGattService nativeService : services) {
                    tw.idv.palatis.ble.services.BluetoothGattService service = tw.idv.palatis.ble.services.BluetoothGattService.fromNativeService(gatt, nativeService);
                    mGattServices.put(nativeService.getUuid(), service);
                    mOnServiceDiscoveredObservable.dispatchServiceDiscovered(service);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onCharacteristicRead(): Failed! device = " + getAddress() +
                            ", service = " + characteristic.getService().getUuid() +
                            ", characteristic = " + characteristic.getUuid() +
                            ", status = " + status
                    );
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                tw.idv.palatis.ble.services.BluetoothGattService service = getService(characteristic.getService().getUuid());
                if (service == null) {
                    Log.e(TAG, "onCharacteristicRead(): unregistered service! device = " + getAddress() +
                            ", service = " + characteristic.getService().getUuid() +
                            ", characteristic = " + characteristic.getUuid()
                    );
                    return;
                }

                final StringBuilder sb = new StringBuilder(characteristic.getValue().length * 3);
                for (final byte b : characteristic.getValue())
                    sb.append(String.format("%02x-", b));
                if (sb.length() > 1)
                    sb.delete(sb.length() - 1, sb.length());
                Log.d(TAG, "onCharacteristicRead(): device = " + getAddress() +
                        ", service = " + characteristic.getService().getUuid() +
                        ", characteristic = " + characteristic.getUuid() +
                        ", data = (0x) " + sb.toString()
                );

                service.onCharacteristicRead(characteristic);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onCharacteristicWrite(): Failed! device = " + getAddress() +
                            ", service = " + characteristic.getService().getUuid() +
                            ", characteristic = " + characteristic.getUuid() +
                            ", status = " + status
                    );
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                tw.idv.palatis.ble.services.BluetoothGattService service = getService(characteristic.getService().getUuid());
                if (service == null) {
                    Log.e(TAG, "onCharacteristicWrite(): unregistered service! device = " + getAddress() +
                            ", service = " + characteristic.getService().getUuid() +
                            ", characteristic = " + characteristic.getUuid()
                    );
                    return;
                }

                StringBuilder sb = new StringBuilder(characteristic.getValue().length * 3);
                for (final byte b : characteristic.getValue())
                    sb.append(String.format("%02x-", b));
                if (sb.length() > 1)
                    sb.delete(sb.length() - 1, sb.length());
                Log.d(TAG, "onCharacteristicWrite(): device = " + getAddress() +
                        ", service = " + characteristic.getService().getUuid() +
                        ", characteristic = " + characteristic.getUuid() +
                        ", data = (0x) " + sb.toString()
                );

                service.onCharacteristicWrite(characteristic);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                final tw.idv.palatis.ble.services.BluetoothGattService service = getService(characteristic.getService().getUuid());
                if (service == null) {
                    Log.e(TAG, "onCharacteristicChanged(): unregistered service! device = " + getAddress() +
                            ", service = " + characteristic.getService().getUuid() +
                            ", characteristic = " + characteristic.getUuid()
                    );
                    return;
                }

                final StringBuilder sb = new StringBuilder(characteristic.getValue().length * 3);
                for (final byte b : characteristic.getValue())
                    sb.append(String.format("%02x-", b));
                if (sb.length() > 1)
                    sb.delete(sb.length() - 1, sb.length());
                Log.d(TAG, "onCharacteristicChanged(): device = " + getAddress() +
                        ", service = " + characteristic.getService().getUuid() +
                        ", characteristic = " + characteristic.getUuid() +
                        ", data = (0x) " + sb.toString()
                );

                service.onCharacteristicChanged(characteristic);
            }

            @Override
            public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onDescriptorRead(): Failed! device = " + getAddress() +
                            ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                            ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                            ", descriptor = " + descriptor.getUuid() +
                            ", status = " + status
                    );
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                final tw.idv.palatis.ble.services.BluetoothGattService service = getService(descriptor.getCharacteristic().getService().getUuid());
                if (service == null) {
                    Log.e(TAG, "onDescriptorRead(): unregistered service! device = " + getAddress() +
                            ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                            ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                            ", descriptor = " + descriptor.getUuid()
                    );
                    return;
                }

                final StringBuilder sb = new StringBuilder(descriptor.getValue().length * 3);
                for (final byte b : descriptor.getValue())
                    sb.append(String.format("%02x-", b));
                if (sb.length() > 1)
                    sb.delete(sb.length() - 1, sb.length());
                Log.d(TAG, "onDescriptorRead(): device = " + getAddress() +
                        ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                        ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                        ", descriptor = " + descriptor.getUuid() +
                        ", data = (0x) " + sb.toString()
                );

                service.onDescriptorRead(descriptor);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onDescriptorWrite(): Failed! device = " + getAddress() +
                            ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                            ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                            ", descriptor = " + descriptor.getUuid() +
                            ", status = " + status
                    );
                    if (callback != null)
                        callback.onGattError(status);
                    return;
                }

                final tw.idv.palatis.ble.services.BluetoothGattService service = getService(descriptor.getCharacteristic().getService().getUuid());
                if (service == null) {
                    Log.e(TAG, "onDescriptorWrite(): unregistered service! device = " + getAddress() +
                            ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                            ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                            ", descriptor = " + descriptor.getUuid()
                    );
                    return;
                }

                final StringBuilder sb = new StringBuilder(descriptor.getValue().length * 3);
                for (final byte b : descriptor.getValue())
                    sb.append(String.format("%02x-", b));
                if (sb.length() > 1)
                    sb.delete(sb.length() - 1, sb.length());
                Log.d(TAG, "onDescriptorWrite(): device = " + getAddress() +
                        ", service = " + descriptor.getCharacteristic().getService().getUuid() +
                        ", characteristic = " + descriptor.getCharacteristic().getUuid() +
                        ", descriptor = " + descriptor.getUuid() +
                        ", data = (0x) " + sb.toString()
                );

                service.onDescriptorWrite(descriptor);
            }

            @Override
            public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
                super.onReliableWriteCompleted(gatt, status);
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                super.onReadRemoteRssi(gatt, rssi, status);
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                super.onMtuChanged(gatt, mtu, status);
            }
        });
        mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(getConnectionState(context));
        return true;
    }

    public void disconnect(@NonNull Context context) {
        mGatt.disconnect();
        mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(getConnectionState(context));
    }

    /**
     * get a service with specific service UUID
     *
     * @param uuid the UUID of the service
     * @return the {@link tw.idv.palatis.ble.services.BluetoothGattService}, {@code null} if not found.
     */
    @Nullable
    public tw.idv.palatis.ble.services.BluetoothGattService getService(@NonNull UUID uuid) {
        return mGattServices.get(uuid);
    }

    @NonNull
    public List<tw.idv.palatis.ble.services.BluetoothGattService> getServices(@NonNull UUID uuid) {
        final ArrayList<tw.idv.palatis.ble.services.BluetoothGattService> services = new ArrayList<>(mGattServices.size());
        final int count = mGattServices.size();
        for (int i = 0; i < count; ++i) {
            final tw.idv.palatis.ble.services.BluetoothGattService service = mGattServices.valueAt(i);
            if (service.getUuid().equals(uuid))
                services.add(service);
        }
        return services;
    }

    /**
     * return all services discovered for this device
     *
     * @return all services discovered for this device
     */
    @NonNull
    public List<tw.idv.palatis.ble.services.BluetoothGattService> getServices() {
        final ArrayList<tw.idv.palatis.ble.services.BluetoothGattService> services = new ArrayList<>(mGattServices.size());
        final int count = mGattServices.size();
        for (int i = 0; i < count; ++i)
            services.add(mGattServices.valueAt(i));
        return services;
    }

    public boolean addOnServiceDiscoveredListener(@NonNull OnServiceDiscoveredListener listener) {
        return mOnServiceDiscoveredObservable.registerObserver(listener);
    }

    public boolean removeOnServiceDiscoveredListener(@NonNull OnServiceDiscoveredListener listener) {
        return mOnServiceDiscoveredObservable.unregisterObserver(listener);
    }

    public boolean addOnConnectionStateChangedListener(@NonNull OnConnectionStateChangedListener listener) {
        return mOnConnectionStateChangedObservable.registerObserver(listener);
    }

    public boolean removeOnConnectionStateChangedListener(@NonNull OnConnectionStateChangedListener listener) {
        return mOnConnectionStateChangedObservable.unregisterObserver(listener);
    }

    private class OnServiceDiscoveredObservable extends WeakObservable<OnServiceDiscoveredListener> {
        void dispatchServiceDiscovered(@NonNull final tw.idv.palatis.ble.services.BluetoothGattService service) {
            housekeeping();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    // iterate backward, because observer may unregister itself.
                    for (int i = mObservers.size() - 1; i >= 0; --i) {
                        final OnServiceDiscoveredListener listener = mObservers.get(i).get();
                        if (listener != null)
                            listener.onServiceDiscovered(service);
                    }
                }
            });
        }
    }

    private class OnConnectionStateChangedObservable extends WeakObservable<OnConnectionStateChangedListener> {
        void dispatchConnectionStateChanged(@ConnectionState final int newState) {
            housekeeping();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    // iterate backward, because observer may unregister itself.
                    for (int i = mObservers.size() - 1; i >= 0; --i) {
                        final OnConnectionStateChangedListener listener = mObservers.get(i).get();
                        if (listener != null)
                            listener.onConnectionStateChanged(newState);
                    }
                }
            });
        }
    }

    public interface OnErrorCallback {
        @AnyThread
        void onGattError(int status);
    }

    public interface OnServiceDiscoveredListener {
        @UiThread
        void onServiceDiscovered(@NonNull tw.idv.palatis.ble.services.BluetoothGattService service);
    }

    public interface OnConnectionStateChangedListener {
        @UiThread
        void onConnectionStateChanged(@ConnectionState int newState);
    }
}