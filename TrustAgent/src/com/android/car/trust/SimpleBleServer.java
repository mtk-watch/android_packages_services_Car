/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.car.trust;

import static android.bluetooth.BluetoothProfile.GATT_SERVER;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * A generic service to start a BLE
 */
public abstract class SimpleBleServer extends Service {

    private final AdvertiseCallback mAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.d(Utils.LOG_TAG, "Successfully started advertising service");
            for (ConnectionCallback callback : mConnectionCallbacks) {
                callback.onServerStarted();
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e(Utils.LOG_TAG, "Failed to advertise, errorCode: " + errorCode);
            for (ConnectionCallback callback : mConnectionCallbacks) {
                callback.onServerStartFailed(errorCode);
            }
        }
    };

    private final BluetoothGattServerCallback mGattServerCallback =
            new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device,
                final int status, final int newState) {
            Log.d(Utils.LOG_TAG, "GattServer connection change status: " + status
                    + " newState: " + newState
                    + " device name: " + device.getName());
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                for (ConnectionCallback callback : mConnectionCallbacks) {
                    callback.onDeviceConnected(device);
                }
            }
        }

        @Override
        public void onServiceAdded(final int status, BluetoothGattService service) {
            Log.d(Utils.LOG_TAG, "Service added status: " + status + " uuid: " + service.getUuid());
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                int requestId, int offset, final BluetoothGattCharacteristic characteristic) {
            Log.d(Utils.LOG_TAG, "Read request for characteristic: " + characteristic.getUuid());
            mGattServer.sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
            SimpleBleServer.
                    this.onCharacteristicRead(device, requestId, offset, characteristic);
        }

        @Override
        public void onCharacteristicWriteRequest(final BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean
                responseNeeded, int offset, byte[] value) {
            Log.d(Utils.LOG_TAG, "Write request for characteristic: " + characteristic.getUuid());
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, value);

            SimpleBleServer.
                    this.onCharacteristicWrite(device, requestId, characteristic,
                    preparedWrite, responseNeeded, offset, value);
        }
    };

    private final Set<ConnectionCallback> mConnectionCallbacks = new HashSet<>();

    private BluetoothManager mBluetoothManager;
    private BluetoothLeAdvertiser mAdvertiser;

    protected BluetoothGattServer mGattServer;

    /**
     * Starts the GATT server with the given {@link BluetoothGattService} and begins
     * advertising with the {@link ParcelUuid}.
     * @param advertiseUuid Service Uuid used in the {@link AdvertiseData}
     * @param service {@link BluetoothGattService} that will be discovered by clients
     */
    protected void start(ParcelUuid advertiseUuid, BluetoothGattService service) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(Utils.LOG_TAG, "System does not support BLE");
            return;
        }

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mGattServer == null) {
            Log.e(Utils.LOG_TAG, "Gatt Server not created");
            return;
        }

        // We only allow adding one service in this implementation. If multiple services need
        // to be added, then they need to be queued up and added only after
        // BluetoothGattServerCallback.onServiceAdded is called.
        mGattServer.addService(service);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(advertiseUuid)
                .build();

        mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        if (mAdvertiser == null) {
            Log.e(Utils.LOG_TAG, "Failed to get BLE advertiser");
            return;
        }
        mAdvertiser.startAdvertising(settings, data, mAdvertisingCallback);
    }

    /**
     * Stops the advertiser and GATT server. This needs to be done to avoid leaks
     */
    protected void stop() {
        if (mAdvertiser != null) {
            mAdvertiser.stopAdvertising(mAdvertisingCallback);
            mAdvertiser.cleanup();
        }

        if (mGattServer != null) {
            mGattServer.clearServices();
            try {
                for (BluetoothDevice d : mBluetoothManager.getConnectedDevices(GATT_SERVER)) {
                    mGattServer.cancelConnection(d);
                }
            } catch (UnsupportedOperationException e) {
                Log.e(Utils.LOG_TAG, "Error getting connected devices", e);
            } finally {
                mGattServer.close();
            }
        }

        mConnectionCallbacks.clear();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    public void registerConnectionCallback(ConnectionCallback callback) {
        Log.d(Utils.LOG_TAG, "Adding connection listener");
        mConnectionCallbacks.add(callback);
    }

    public void unregisterConnectionCallback(ConnectionCallback callback) {
        mConnectionCallbacks.remove(callback);
    }

    /**
     * Triggered when this BleService receives a write request from a remote
     * device. Sub-classes should implement how to handle requests.
     */
    public abstract void onCharacteristicWrite(final BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean
            responseNeeded, int offset, byte[] value);

    /**
     * Triggered when this BleService receives a read request from a remote device.
     */
    public abstract void onCharacteristicRead(BluetoothDevice device,
            int requestId, int offset, final BluetoothGattCharacteristic characteristic);

    /**
     * Callback that is notified when the status of the BLE server changes.
     */
    public interface ConnectionCallback {
        /**
         * Called when the GATT server is started and BLE is successfully advertising.
         */
        void onServerStarted();

        /**
         * Called when the BLE advertisement fails to start.
         *
         * @param errorCode Error code (see {@link AdvertiseCallback}#ADVERTISE_FAILED_* constants)
         */
        void onServerStartFailed(int errorCode);

        /**
         * Called when a device is connected.
         * @param device {@link BluetoothDevice} that is connected
         */
        void onDeviceConnected(BluetoothDevice device);
    }
}
