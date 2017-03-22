package tw.idv.palatis.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import tw.idv.palatis.ble.database.WeakObservable;

import static java.lang.annotation.RetentionPolicy.SOURCE;
import static tw.idv.palatis.ble.BuildConfig.DEBUG;

public class BluetoothDevice {
    private static final String TAG = BluetoothDevice.class.getSimpleName();

    private static final UUID UUID_DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long LONG_TIME_NO_SEE_TIMEOUT = 15000; // ms

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
    @ConnectionState
    private int mConnectionState = BluetoothProfile.STATE_DISCONNECTED;
    private boolean mAutoConnect = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mGattExecutor = Executors.newSingleThreadExecutor();
    private final OnErrorObservable mOnErrorObservable = new OnErrorObservable();
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

    public int getBondState() {
        return mNativeDevice.getBondState();
    }

    public void createBond() {
        mNativeDevice.createBond();
    }

    private final OnLongTimeNoSeeObservable mOnLongTimeNoSeeObservable = new OnLongTimeNoSeeObservable();

    private final Runnable mOnLongTimeNoSeeRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG)
                Log.d(TAG, "onLongTimeNoSee(): goodbye!");
            mOnLongTimeNoSeeObservable.dispatchLongTimeNoSee();
        }
    };

    public void sayHi() {
        if (mGatt != null) {
            if (DEBUG)
                Log.d(TAG, "sayHi(): hello~ somebody there?");

            mHandler.removeCallbacks(mOnLongTimeNoSeeRunnable);
            mHandler.postDelayed(mOnLongTimeNoSeeRunnable, LONG_TIME_NO_SEE_TIMEOUT);
        }
    }

    /**
     * get the connection state, one of {@link BluetoothProfile#STATE_CONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_DISCONNECTING}, or
     * {@link BluetoothProfile#STATE_DISCONNECTED}.
     *
     * @return current connection state
     */
    @SuppressWarnings("WrongConstant")
    @ConnectionState
    public int getConnectionState() {
        return mConnectionState;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, @ConnectionState int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onConnectionStateChange(): Failed! device = " + getAddress() + ", status = " + status + ", newState = " + newState);
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            if (DEBUG)
                Log.d(TAG, "onConnectionStateChanged(): device = " + getAddress() + ", " + status + " => " + newState);

            mConnectionState = newState;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mHandler.removeCallbacks(mOnLongTimeNoSeeRunnable);
                gatt.discoverServices();
            } else if (!mAutoConnect && newState == BluetoothProfile.STATE_DISCONNECTED) {
                mHandler.postDelayed(mOnLongTimeNoSeeRunnable, LONG_TIME_NO_SEE_TIMEOUT);
                mGatt = null;
            }

            mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(newState);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServiceDiscovered(): Failed! device = " + getAddress() + ", status = " + status);
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            final List<BluetoothGattService> services = gatt.getServices();
            for (final BluetoothGattService nativeService : services) {
                tw.idv.palatis.ble.services.BluetoothGattService service = tw.idv.palatis.ble.services.BluetoothGattService.fromNativeService(BluetoothDevice.this, nativeService);
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
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            if (DEBUG) {
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
            }

            synchronized (BluetoothDevice.this) {
                BluetoothDevice.this.notify();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite(): Failed! device = " + getAddress() +
                        ", service = " + characteristic.getService().getUuid() +
                        ", characteristic = " + characteristic.getUuid() +
                        ", status = " + status
                );
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            if (DEBUG) {
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
            }

            synchronized (BluetoothDevice.this) {
                BluetoothDevice.this.notify();
            }
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

            if (DEBUG) {
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
            }

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
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            if (DEBUG) {
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
            }

            synchronized (BluetoothDevice.this) {
                BluetoothDevice.this.notify();
            }
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
                mOnErrorObservable.dispatchGattError(status);
                return;
            }

            if (DEBUG) {
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
            }

            synchronized (BluetoothDevice.this) {
                BluetoothDevice.this.notify();
            }
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
    };

    /**
     * connect to the device
     *
     * @param context     the application's {@link Context}
     * @param autoConnect auto re-connect wheh disconnected
     */
    public void connect(@NonNull Context context, boolean autoConnect) {
        if (mGatt != null)
            throw new IllegalStateException("device " + getName() + " - " + getAddress() + " not in disconnected state.");

        mHandler.removeCallbacks(mOnLongTimeNoSeeRunnable);
        mGatt = mNativeDevice.connectGatt(context, mAutoConnect = autoConnect, mGattCallback);
        mConnectionState = BluetoothProfile.STATE_CONNECTING;
        mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(mConnectionState);
    }

    public void disconnect() {
        if (mGatt == null)
            return;

        mGatt.disconnect();
        mGatt = null;
        mConnectionState = BluetoothProfile.STATE_DISCONNECTING;
        mOnConnectionStateChangedObservable.dispatchConnectionStateChanged(mConnectionState);
        sayHi();
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

    public void readCharacteristic(final tw.idv.palatis.ble.services.BluetoothGattService service, final BluetoothGattCharacteristic characteristic) {
        mGattExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (BluetoothDevice.this) {
                        mGatt.readCharacteristic(characteristic);

                        long start_ms = System.currentTimeMillis();
                        BluetoothDevice.this.wait(3000);
                        if (System.currentTimeMillis() - start_ms >= 3000) {
                            mOnErrorObservable.dispatchTimedOut(service);
                            return;
                        }

                        service.onCharacteristicRead(characteristic);
                    }
                } catch (InterruptedException ignored) {
                    if (DEBUG)
                        Log.d(TAG, "readCharacteristic(): thread interrupted.");
                } catch (Exception ex) {
                    mOnErrorObservable.dispatchFatalError(service, ex);
                }
            }
        });
    }

    public void writeCharacteristic(final tw.idv.palatis.ble.services.BluetoothGattService service, final BluetoothGattCharacteristic characteristic, final byte[] data) {
        mGattExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (BluetoothDevice.this) {
                        characteristic.setValue(data);
                        mGatt.writeCharacteristic(characteristic);
                        long start_ms = System.currentTimeMillis();
                        BluetoothDevice.this.wait(3000);
                        if (System.currentTimeMillis() - start_ms >= 3000) {
                            mOnErrorObservable.dispatchTimedOut(service);
                            return;
                        }
                        service.onCharacteristicWrite(characteristic);
                    }
                } catch (InterruptedException ignored) {
                    if (DEBUG)
                        Log.d(TAG, "writeCharacteristic(): thread interrupted.");
                } catch (Exception ex) {
                    mOnErrorObservable.dispatchFatalError(service, ex);
                }
            }
        });
    }

    public void writeDescriptor(final tw.idv.palatis.ble.services.BluetoothGattService service, final BluetoothGattDescriptor descriptor, final byte[] data) {
        mGattExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (BluetoothDevice.this) {
                        descriptor.setValue(data);
                        mGatt.writeDescriptor(descriptor);

                        long start_ms = System.currentTimeMillis();
                        BluetoothDevice.this.wait(3000);
                        if (System.currentTimeMillis() - start_ms >= 3000) {
                            mOnErrorObservable.dispatchTimedOut(service);
                            return;
                        }

                        service.onDescriptorWrite(descriptor);
                    }
                } catch (InterruptedException ignored) {
                    if (DEBUG)
                        Log.d(TAG, "setCharacteristicNotification(): thread interrupted.");
                } catch (Exception ex) {
                    mOnErrorObservable.dispatchFatalError(service, ex);
                }
            }
        });
    }

    public void setCharacteristicNotification(final tw.idv.palatis.ble.services.BluetoothGattService service, final BluetoothGattCharacteristic characteristic, final boolean enabled) {
        if (DEBUG && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0)
            Log.d(TAG, "setCharacteristicNotification(): characteristic doesn't support NOTIFY.");

        mGatt.setCharacteristicNotification(characteristic, enabled);
        final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID_DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            Log.e(TAG, "setCharacteristicNotification(): characteristic doesn't have config descriptor! notification might not work.");
            return;
        }

        writeDescriptor(service, descriptor, enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
    }

    public boolean addOnErrorListener(@NonNull OnErrorListener listener) {
        return mOnErrorObservable.registerObserver(listener);
    }

    public boolean removeOnErrorListener(@NonNull OnErrorListener listener) {
        return mOnErrorObservable.unregisterObserver(listener);
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

    public boolean addOnLongTimeNoSeeListener(@NonNull OnLongTimeNoSeeListener listener) {
        return mOnLongTimeNoSeeObservable.registerObserver(listener);
    }

    public boolean removeOnLongTimeNoSeeListener(@NonNull OnLongTimeNoSeeListener listener) {
        return mOnLongTimeNoSeeObservable.unregisterObserver(listener);
    }

    private class OnServiceDiscoveredObservable extends WeakObservable<OnServiceDiscoveredListener> {
        void dispatchServiceDiscovered(@NonNull final tw.idv.palatis.ble.services.BluetoothGattService service) {
            dispatch(mHandler, new OnDispatchCallback<OnServiceDiscoveredListener>() {
                @Override
                public void onDispatch(OnServiceDiscoveredListener observer) {
                    observer.onServiceDiscovered(service);
                }
            });
        }
    }

    private class OnConnectionStateChangedObservable extends WeakObservable<OnConnectionStateChangedListener> {
        void dispatchConnectionStateChanged(@ConnectionState final int newState) {
            dispatch(mHandler, new OnDispatchCallback<OnConnectionStateChangedListener>() {
                @Override
                public void onDispatch(OnConnectionStateChangedListener observer) {
                    observer.onConnectionStateChanged(newState);
                }
            });
        }
    }

    private class OnErrorObservable extends WeakObservable<OnErrorListener> {
        void dispatchGattError(final int status) {
            dispatch(mHandler, new OnDispatchCallback<OnErrorListener>() {
                @Override
                public void onDispatch(OnErrorListener observer) {
                    observer.onGattError(status);
                }
            });
        }

        void dispatchTimedOut(@NonNull final tw.idv.palatis.ble.services.BluetoothGattService service) {
            dispatch(mHandler, new OnDispatchCallback<OnErrorListener>() {
                @Override
                public void onDispatch(OnErrorListener observer) {
                    observer.onTimedOut(service);
                }
            });
        }

        void dispatchFatalError(@NonNull final tw.idv.palatis.ble.services.BluetoothGattService service, @NonNull final Throwable ex) {
            dispatch(mHandler, new OnDispatchCallback<OnErrorListener>() {
                @Override
                public void onDispatch(OnErrorListener observer) {
                    observer.onFatalError(service, ex);
                }
            });
        }
    }

    private class OnLongTimeNoSeeObservable extends WeakObservable<OnLongTimeNoSeeListener> {
        void dispatchLongTimeNoSee() {
            dispatch(mHandler, new OnDispatchCallback<OnLongTimeNoSeeListener>() {
                @Override
                public void onDispatch(OnLongTimeNoSeeListener observer) {
                    observer.onLongTimeNoSee(BluetoothDevice.this);
                }
            });
        }
    }

    public interface OnErrorListener {
        @UiThread
        void onGattError(int status);

        @UiThread
        void onTimedOut(@NonNull tw.idv.palatis.ble.services.BluetoothGattService service);

        @UiThread
        void onFatalError(@NonNull tw.idv.palatis.ble.services.BluetoothGattService service, @NonNull Throwable ex);
    }

    public interface OnServiceDiscoveredListener {
        @UiThread
        void onServiceDiscovered(@NonNull tw.idv.palatis.ble.services.BluetoothGattService service);
    }

    public interface OnConnectionStateChangedListener {
        @UiThread
        void onConnectionStateChanged(@ConnectionState int newState);
    }

    public interface OnLongTimeNoSeeListener {
        @UiThread
        void onLongTimeNoSee(@NonNull BluetoothDevice device);
    }
}
