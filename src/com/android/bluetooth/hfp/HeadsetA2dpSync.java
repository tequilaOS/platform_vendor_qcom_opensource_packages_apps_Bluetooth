/*
 *  Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer in the documentation and/or other materials provided
 *        with the distribution.
 *      * Neither the name of The Linux Foundation nor the names of its
 *        contributors may be used to endorse or promote products derived
 *        from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 *  ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 *  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 *  BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *  WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 *  OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.hfp;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.HashMap;

/**
 * Defines methods used for synchronization between HFP and A2DP
 */
public class HeadsetA2dpSync {
    private static final String TAG = HeadsetA2dpSync.class.getSimpleName();

    // system inteface
    private HeadsetSystemInterface mSystemInterface;
    private HeadsetService mHeadsetService;
    // Hash for storing the A2DP states
    private HashMap<BluetoothDevice, Integer> mA2dpConnState =
                            new HashMap<BluetoothDevice, Integer>();

    // internal variables.
    private int mA2dpSuspendTriggered;// to keep track if A2dp was supended by HFP.
    private BluetoothDevice mDummyDevice = null;

    //reason for a2dp suspended.
    public static final int A2DP_SUSPENDED_NOT_TRIGGERED = 0;
    public static final int A2DP_SUSPENDED_BY_CS_CALL = 1;
    public static final int A2DP_SUSPENDED_BY_VOIP_CALL = 2;
    public static final int A2DP_SUSPENDED_BY_VR = 3;
    // State for a2dp Device
    // current implementation is only concerned about DISCONNECTED, CONNECTED and PLAYING.
    public static final int A2DP_DISCONNECTED = 0;// this implies no connection
    public static final int A2DP_CONNECTING = 1;
    public static final int A2DP_CONNECTED = 2;// this implies connected but not playing
    public static final int A2DP_DISCONNECTING = 3;
    public static final int A2DP_PLAYING = 4;// this implies connected and PLaying
    public static final int A2DP_SUSPENDED = 5;

    HeadsetA2dpSync(HeadsetSystemInterface systemInterface,HeadsetService service) {
        mSystemInterface = systemInterface;
        mHeadsetService = service;
        mA2dpSuspendTriggered = A2DP_SUSPENDED_NOT_TRIGGERED;// initialize with not suspended.
        String dummyAddress = "AA:BB:CC:DD:EE:00";
        mDummyDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(dummyAddress);
    }

    /* check if any of the a2dp device is playing
     * TODO: Remove if not required.
     */
    /* check if any device is in PLAYING/CONNECTED state
     * return types:
     * A2DP_DISCONNECTED: NO Device COnnected
     * A2DP_CONNECTED: All Devices are in Connected state, none in playing state
     * A2DP_PLAYING: Atlease one device in playing state
     */
    public int isA2dpPlaying() {
        int a2dpState = A2DP_DISCONNECTED;
        for(Integer value: mA2dpConnState.values()) {
            if(value == A2DP_CONNECTED) {
                a2dpState = value;
            }
            if(value == A2DP_PLAYING) {
                a2dpState = value;
                Log.d(TAG," isA2dpPlaying returns = " + a2dpState);
                return a2dpState;
            }
        }
        Log.d(TAG," isA2dpPlaying returns = " + a2dpState);
        return a2dpState;
    }
    /* check and suspend A2DP
     * return: true in case we have to wait for suepnd confirmation
     *         false in case we don't have to wait for suepnd confirmation
     * If A2DP is connected, call A2dpSuspended=true ( so that A2DP can't start while call
     * is acvtive), but we don't have to wait for suspend confirmation in this case
     * If A2DP is playing, call A2dpSuspended=true, and wait for suspend confirm
     * if A2DP is not connected, don't do anything.
     * caller need to send reason for suspend request( VR/CS-CALL/VOIP-CALL)
     */
    public boolean suspendA2DP(int reason, BluetoothDevice device){
        int a2dpState = isA2dpPlaying();
        Log.d(TAG," suspendA2DP currPlayingState = "+ a2dpState + " for reason " + reason
              + "mA2dpSuspendTriggered = " + mA2dpSuspendTriggered + " for device " + device);
        if (mA2dpSuspendTriggered != A2DP_SUSPENDED_NOT_TRIGGERED) {
            // A2DPSuspend was triggered already, don't need to do anything.
            if(a2dpState == A2DP_PLAYING) {
                // we are still waiting for suspend from a2dp.Caller shld wait
                return true;
            } else {
                // not playing, caller need not wait.
                return false;
            }
        }
        // not device in connected state, nothing to do. Caller shld not wait
        if(a2dpState == A2DP_DISCONNECTED) {
            Log.d(TAG," A2DP not Connected, nothing to do ");
            return false;
        }
        if(a2dpState == A2DP_CONNECTED) {
            mA2dpSuspendTriggered = reason;
            mSystemInterface.getAudioManager().setParameters("A2dpSuspended=true");
            Log.d(TAG," A2DP Connected,don't wait for suspend ");
            return false;
        }
        if(a2dpState == A2DP_PLAYING) {
            mA2dpSuspendTriggered = reason;
            mSystemInterface.getAudioManager().setParameters("A2dpSuspended=true");
            Log.d(TAG," A2DP Playing ,wait for suspend ");
            return true;
        }
        return false;
    }

    /*
     *This api will be called by SMs, to make A2dpSuspended=false
     */
    public boolean releaseA2DP(BluetoothDevice device) {
        Log.d(TAG," releaseA2DP mA2dpSuspendTriggered " + mA2dpSuspendTriggered +
                            " by device " + device);
        if(mA2dpSuspendTriggered == A2DP_SUSPENDED_NOT_TRIGGERED) {
            return true;
        }
        if (mHeadsetService.isInCall() || mHeadsetService.isRinging() ||
                                                 mHeadsetService.isAudioOn()) {
            Log.d(TAG," Call/Ring/SCO on for some other stateMachine, bail out ");
            return true;
        }
        // A2DP Suspend was triggered by HFP.
        mA2dpSuspendTriggered = A2DP_SUSPENDED_NOT_TRIGGERED;
        mSystemInterface.getAudioManager().setParameters("A2dpSuspended=false");
        return true;
    }

    public void updateA2DPPlayingState(Intent intent) {
        int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                       BluetoothA2dp.STATE_NOT_PLAYING);
        int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                       BluetoothA2dp.STATE_NOT_PLAYING);
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

        Log.d(TAG," updateA2DPPlayingState device: " + device + " transition " +
                                             prevState + "->" + currState);

        // if device is not there and  state=DISCONNECTED, bail out
        if(!mA2dpConnState.containsKey(device)) {
            Log.e(TAG," Got PLay_UPdate without Connectoin Update, this shld not happen "+ device);
        }
        switch(currState) {
        case BluetoothA2dp.STATE_NOT_PLAYING:
            mA2dpConnState.put(device, A2DP_CONNECTED);
            /*
             * send message to statemachine. We send message to SMs
             * only when all devices moved to SUSPENDED.
             */
            int a2dpState = isA2dpPlaying();
            if ((a2dpState == A2DP_DISCONNECTED) ||
                       (a2dpState == A2DP_CONNECTED)) {
                mHeadsetService.sendA2dpStateChangeUpdate(a2dpState);
            }
            break;
        case BluetoothA2dp.STATE_PLAYING:
            mA2dpConnState.put(device, A2DP_PLAYING);
            // if call/ ring is ongoing and we received playing, we need to suspend
            if (mHeadsetService.isInCall() || mHeadsetService.isRinging()) {
                Log.d(TAG," CALL/Ring is active ");
                suspendA2DP(A2DP_SUSPENDED_BY_CS_CALL, mDummyDevice);
            }
            break;
        }
        Log.d(TAG," device: " + device + " state = " + mA2dpConnState.get(device));
    }
    /*
     * BookKeeping of A2dp Device State, based on intents from A2DPStateMachine.
     */
    public void updateA2DPConnectionState(Intent intent) {
        int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                       BluetoothProfile.STATE_DISCONNECTED);
        int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                       BluetoothProfile.STATE_DISCONNECTED);
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

        Log.d(TAG," updateA2DPConnectionState device: " + device + " transition " +
                                             prevState + "->" + currState);

        // if device is not there and  state=DISCONNECTED, bail out
        if(!mA2dpConnState.containsKey(device) &&
           (currState == BluetoothProfile.STATE_DISCONNECTED)) {
            Log.e(TAG," Got Disc for device not in entry "+ device);
                return;
        }
        switch(currState) {
        case BluetoothProfile.STATE_DISCONNECTED:
            mA2dpConnState.remove(device);
            break;
        // treating everything else as Connected
        case BluetoothProfile.STATE_DISCONNECTING:
        case BluetoothProfile.STATE_CONNECTING:
            mA2dpConnState.put(device, A2DP_CONNECTED);
            break;
        case BluetoothProfile.STATE_CONNECTED:
            mA2dpConnState.put(device, A2DP_CONNECTED);
            if (mHeadsetService.isInCall() || mHeadsetService.isRinging()) {
                Log.d(TAG," CALL is active/ringing, A2DP got connected, suspending");
                suspendA2DP(A2DP_SUSPENDED_BY_CS_CALL, mDummyDevice);
            }
            break;
        }
        Log.d(TAG," device: " + device + " state = " + mA2dpConnState.get(device));
    }
}
