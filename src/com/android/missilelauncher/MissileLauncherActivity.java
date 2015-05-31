/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.missilelauncher;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MissileLauncherActivity extends Activity
        implements View.OnClickListener, Runnable {

    private static final String TAG = "MissileLauncherActivity";

    private Button mFire;
    private Button mShoot;
    private UsbManager mUsbManager;
    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpointIntr;
    private SensorManager mSensorManager;
    private Sensor mGravitySensor;
    
    UsbDevice deviceFound = null;
    UsbInterface usbInterfaceFound = null;
    UsbEndpoint endpointIn = null;
    UsbEndpoint endpointOut = null;

    // USB control commands
    private static final int COMMAND_UP = 1;
    private static final int COMMAND_DOWN = 2;
    private static final int COMMAND_RIGHT = 4;
    private static final int COMMAND_LEFT = 8;
    private static final int COMMAND_FIRE = 16;
    private static final int COMMAND_STOP = 32;
    private static final int COMMAND_STATUS = 64;

    // constants for accelerometer orientation
    private static final int TILT_LEFT = 1;
    private static final int TILT_RIGHT = 2;
    private static final int TILT_UP = 4;
    private static final int TILT_DOWN = 8;
    private static final double THRESHOLD = 5.0;
    
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    PendingIntent mPermissionIntent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.launcher);
        mFire = (Button)findViewById(R.id.fire);
        mFire.setOnClickListener(this);
        mShoot = (Button)findViewById(R.id.shoot);
        mShoot.setOnClickListener(this);
        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
        
        registerReceiver(mUsbDeviceReceiver, new IntentFilter(
                UsbManager.ACTION_USB_DEVICE_ATTACHED));
        registerReceiver(mUsbDeviceReceiver, new IntentFilter(
                UsbManager.ACTION_USB_DEVICE_DETACHED));
        mTextView_ShowConsole = (TextView) findViewById(R.id.ShowConsole);
    }
    
    private final BroadcastReceiver mUsbDeviceReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {

                deviceFound = (UsbDevice) intent
                        .getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Toast.makeText(
                        MissileLauncherActivity.this,
                        "ACTION_USB_DEVICE_ATTACHED: \n"
                                + deviceFound.toString(), Toast.LENGTH_LONG)
                        .show();

                searchEndPoint();

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {

                UsbDevice device = (UsbDevice) intent
                        .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                Toast.makeText(MissileLauncherActivity.this,
                        "ACTION_USB_DEVICE_DETACHED: \n" + device.toString(),
                        Toast.LENGTH_LONG).show();

                if (device != null) {
                    if (device == deviceFound) {
                        releaseUsb();
                    } else {
                        Toast.makeText(MissileLauncherActivity.this,
                                "device == deviceFound, no call releaseUsb()\n" +
                                        device.toString() + "\n" +
                                        deviceFound.toString(),
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(MissileLauncherActivity.this,
                            "device == null, no call releaseUsb()", Toast.LENGTH_LONG).show();
                }

            }
        }

    };
    
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {

                Toast.makeText(MissileLauncherActivity.this, "ACTION_USB_PERMISSION",
                        Toast.LENGTH_LONG).show();

                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            searchEndPoint();
                        }
                    } else {
                        Toast.makeText(MissileLauncherActivity.this,
                                "permission denied for device " + device,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    };

    @Override
    public void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(mGravityListener);
    }

    protected void releaseUsb() {
        if (mConnection != null) {
            if (usbInterfaceFound != null) {
                mConnection.releaseInterface(usbInterfaceFound);
                usbInterfaceFound = null;
            }
            mConnection.close();
            mConnection = null;
        }

        
    }
    
    

    @Override
    public void onResume() {
        super.onResume();
        mSensorManager.registerListener(mGravityListener, mGravitySensor,
                SensorManager.SENSOR_DELAY_NORMAL);

        Intent intent = getIntent();
        Log.d(TAG, "intent: " + intent);
        String action = intent.getAction();

        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            //setDevice(device);
            searchEndPoint();
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            if (mDevice != null && mDevice.equals(device)) {
                setDevice(null);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseUsb();
    }
    
    private void searchEndPoint() {

        usbInterfaceFound = null;
        endpointOut = null;
        endpointIn = null;

        // Search device for targetVendorID and targetProductID
        if (deviceFound == null) {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();

            while (deviceIterator.hasNext()) {
                UsbDevice device = deviceIterator.next();
                String s = device.toString() + "\n" + "DeviceID: "
                        + device.getDeviceId() + "\n" + "DeviceName: "
                        + device.getDeviceName() + "\n" + "DeviceClass: "
                        + device.getDeviceClass() + "\n" + "DeviceSubClass: "
                        + device.getDeviceSubclass() + "\n" + "VendorID: "
                        + device.getVendorId() + "\n" + "ProductID: "
                        + device.getProductId() + "\n" + "InterfaceCount: "
                        + device.getInterfaceCount();
                Log.e(TAG, s);
                if (device.getVendorId() == 0x0483) {
                    // if (device.getProductId() == targetProductID) {
                    deviceFound = device;
                    break;
                }
            }
        }

        if (deviceFound == null) {
            Toast.makeText(MissileLauncherActivity.this, "device not found",
                    Toast.LENGTH_LONG).show();
        } else {
            // Search for UsbInterface with Endpoint of USB_ENDPOINT_XFER_BULK,
            // and direction USB_DIR_OUT and USB_DIR_IN

            for (int i = 0; i < deviceFound.getInterfaceCount(); i++) {
                Log.e(TAG, " interface ["+i+"]" +" ");
                UsbInterface usbif = deviceFound.getInterface(i);
                UsbEndpoint tOut = null;
                UsbEndpoint tIn = null;
                int tEndpointCnt = usbif.getEndpointCount();
                Log.e(TAG, " interface ["+i+"]" +" EndpointCount = "+tEndpointCnt);
                if (tEndpointCnt > 0) {
                    for (int j = 0; j < tEndpointCnt; j++) {
                        Log.e(TAG, "Endpoint ["+j+"]"+" Type ="+usbif.getEndpoint(j).getType());
                        Log.e(TAG, "Endpoint ["+j+"]"+" Direction = "+usbif.getEndpoint(j).getDirection());
                        Log.e(TAG, "Endpoint ["+j+"]"+" Address = "+usbif.getEndpoint(j).getAddress());
                        if (usbif.getEndpoint(j).getAddress() == 0x81) {
                            endpointOut = usbif.getEndpoint(j);
                            usbInterfaceFound = usbif;
                        }else if (usbif.getEndpoint(j).getAddress() == 0x1){
                            endpointIn = usbif.getEndpoint(j);
                        }
                    }
                }

            }
            if(endpointOut!=null&&usbInterfaceFound!=null) {
                UsbDeviceConnection connection = mUsbManager.openDevice(deviceFound);
                if (connection != null && connection.claimInterface(usbInterfaceFound, true)) {
                    Log.d(TAG, "open SUCCESS");
                    mConnection = connection;
                } else {
                    Log.d(TAG, "open FAIL");
                    mConnection = null;
                }
            }
        }
    }

    private void setDevice(UsbDevice device) {
        Log.d(TAG, "setDevice " + device);
        if (device.getInterfaceCount() != 1) {
            Log.e(TAG, "could not find interface");
            return;
        }
        UsbInterface intf = device.getInterface(0);
        // device should have one endpoint
        if (intf.getEndpointCount() != 1) {
            Log.e(TAG, "could not find endpoint");
            return;
        }
        // endpoint should be of type interrupt
        UsbEndpoint ep = intf.getEndpoint(0);
        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
            Log.e(TAG, "endpoint is not interrupt type");
            return;
        }
        mDevice = device;
        mEndpointIntr = ep;
        if (device != null) {
            UsbDeviceConnection connection = mUsbManager.openDevice(device);
            if (connection != null && connection.claimInterface(intf, true)) {
                Log.d(TAG, "open SUCCESS");
                mConnection = connection;
                Thread thread = new Thread(this);
                thread.start();

            } else {
                Log.d(TAG, "open FAIL");
                mConnection = null;
            }
         }
    }

    private void sendCommand(int control) {
        synchronized (this) {
            if (control != COMMAND_STATUS) {
                Log.d(TAG, "sendMove " + control);
            }
            if (mConnection != null) {
                byte[] message = new byte[1];
                message[0] = (byte)control;
                // Send command via a control request on endpoint zero
                mConnection.controlTransfer(0x21, 0x9, 0x200, 0, message, message.length, 0);
            }
        }
    }
   private HidBridge hidBridge; 
   public StringBuffer mStringBuffer_Console_Text = new StringBuffer("Show Info:\n");
   public TextView mTextView_ShowConsole;
    public void onClick(View v) {
        if (v == mFire) {
            //sendCommand(COMMAND_FIRE);
            if(hidBridge==null){
                hidBridge = new HidBridge(this,22336, 1155);
                hidBridge.OpenDevice();
            }
        }
        byte[] sendOut = "Hello World!!!".getBytes();
        if(v == mShoot) {
            if(hidBridge!=null) {
                
                hidBridge.StartReadingThread();
                hidBridge.WriteData(sendOut);
            }
        }
    }
    Handler handler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
          mStringBuffer_Console_Text.append(msg.obj);
          mTextView_ShowConsole.setText(mStringBuffer_Console_Text);
            super.handleMessage(msg);
        }
    };
    void log(String messageString){
        handler.obtainMessage(0, messageString).sendToTarget();
    }

    private int mLastValue = 0;

    SensorEventListener mGravityListener = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {

            // compute current tilt
            int value = 0;
            if (event.values[0] < -THRESHOLD) {
                value += TILT_LEFT;
            } else if (event.values[0] > THRESHOLD) {
                value += TILT_RIGHT;
            }
            if (event.values[1] < -THRESHOLD) {
                value += TILT_UP;
            } else if (event.values[1] > THRESHOLD) {
                value += TILT_DOWN;
            }

            if (value != mLastValue) {
                mLastValue = value;
                // send motion command if the tilt changed
                switch (value) {
                    case TILT_LEFT:
                        sendCommand(COMMAND_LEFT);
                        break;
                    case TILT_RIGHT:
                       sendCommand(COMMAND_RIGHT);
                        break;
                    case TILT_UP:
                        sendCommand(COMMAND_UP);
                        break;
                    case TILT_DOWN:
                        sendCommand(COMMAND_DOWN);
                        break;
                    default:
                        sendCommand(COMMAND_STOP);
                        break;
                }
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // ignore
        }
    };

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        UsbRequest request = new UsbRequest();
        request.initialize(mConnection, mEndpointIntr);
        byte status = -1;
        while (true) {
            // queue a request on the interrupt endpoint
            request.queue(buffer, 1);
            // send poll status command
            sendCommand(COMMAND_STATUS);
            // wait for status event
            if (mConnection.requestWait() == request) {
                byte newStatus = buffer.get(0);
                if (newStatus != status) {
                    Log.d(TAG, "got status " + newStatus);
                    status = newStatus;
                    if ((status & COMMAND_FIRE) != 0) {
                        // stop firing
                        sendCommand(COMMAND_STOP);
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            } else {
                Log.e(TAG, "requestWait failed, exiting");
                break;
            }
        }
    }
}


