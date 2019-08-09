/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.org.component;

import android.os.Handler;
import android.util.Log;

import com.pine.rtc.org.component.AsyncHttpURLConnection.AsyncHttpEvents;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;

import de.tavendo.autobahn.WebSocket.WebSocketConnectionObserver;
import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketException;

/**
 * WebSocket client implementation.
 * <p>
 * <p>All public methods should be called from a looper executor thread
 * passed in a constructor, otherwise exception will be thrown.
 * All events are dispatched on the same thread.
 */

public class WebSocketChannelClient {
    private static final String TAG = "WSChannelRTCClient";

    private static final int CLOSE_TIMEOUT = 1000;
    private final WebSocketChannelEvents mEvents;
    private final Handler mHandler;
    private final Object mCloseEventLock = new Object();
    // WebSocket send queue. Messages are added to the queue when WebSocket
    // client is not registered and are consumed in register() call.
    private final LinkedList<String> mWsSendQueue;
    private WebSocketConnection mWs;
    private WebSocketObserver mWsObserver;
    private String mOriginUrl;
    private String mWsServerUrl;
    private String mPostServerUrl;
    private String mRoomID;
    private String mClientID;
    private WebSocketConnectionState mState;
    private boolean mCloseEvent;

    public WebSocketChannelClient(Handler handler, WebSocketChannelEvents events) {
        this.mHandler = handler;
        this.mEvents = events;
        mRoomID = null;
        mClientID = null;
        mWsSendQueue = new LinkedList<String>();
        mState = WebSocketConnectionState.NEW;
    }

    public WebSocketConnectionState getState() {
        return mState;
    }

    public void connect(final String originUrl, final String wsUrl, final String postUrl) {
        checkIfCalledOnValidThread();
        if (mState != WebSocketConnectionState.NEW) {
            Log.e(TAG, "WebSocket is already connected.");
            return;
        }
        mOriginUrl = originUrl;
        mWsServerUrl = wsUrl;
        mPostServerUrl = postUrl;
        mCloseEvent = false;

        Log.d(TAG, "Connecting WebSocket to: " + wsUrl + ". Post URL: " + postUrl);
        mWs = new WebSocketConnection();
        mWsObserver = new WebSocketObserver();
        try {
            mWs.connect(new URI(mWsServerUrl), mWsObserver);
        } catch (URISyntaxException e) {
            reportError("URI error: " + e.getMessage());
        } catch (WebSocketException e) {
            reportError("WebSocket connection error: " + e.getMessage());
        }
    }

    public void register(final String roomID, final String clientID) {
        checkIfCalledOnValidThread();
        this.mRoomID = roomID;
        this.mClientID = clientID;
        if (mState != WebSocketConnectionState.CONNECTED) {
            Log.w(TAG, "WebSocket register() in state " + mState);
            return;
        }
        Log.d(TAG, "Registering WebSocket for room " + roomID + ". ClientID: " + clientID);
        JSONObject json = new JSONObject();
        try {
            json.put("cmd", "register");
            json.put("roomid", roomID);
            json.put("clientid", clientID);
            Log.d(TAG, "C->WSS: " + json.toString());
            mWs.sendTextMessage(json.toString());
            mState = WebSocketConnectionState.REGISTERED;
            // Send any previously accumulated messages.
            for (String sendMessage : mWsSendQueue) {
                send(sendMessage);
            }
            mWsSendQueue.clear();
        } catch (JSONException e) {
            reportError("WebSocket register JSON error: " + e.getMessage());
        }
    }

    public void send(String message) {
        checkIfCalledOnValidThread();
        switch (mState) {
            case NEW:
            case CONNECTED:
                // Store outgoing messages and send them after websocket client
                // is registered.
                Log.d(TAG, "WS ACC: " + message);
                mWsSendQueue.add(message);
                return;
            case ERROR:
            case CLOSED:
                Log.e(TAG, "WebSocket send() in error or closed state : " + message);
                return;
            case REGISTERED:
                JSONObject json = new JSONObject();
                try {
                    json.put("cmd", "send");
                    json.put("msg", message);
                    message = json.toString();
                    Log.d(TAG, "C->WSS: " + message);
                    mWs.sendTextMessage(message);
                } catch (JSONException e) {
                    reportError("WebSocket send JSON error: " + e.getMessage());
                }
                break;
        }
    }

    // This call can be used to send WebSocket messages before WebSocket
    // connection is opened.
    public void post(String message) {
        checkIfCalledOnValidThread();
        sendWSSMessage("POST", message);
    }

    public void disconnect(boolean waitForComplete) {
        checkIfCalledOnValidThread();
        Log.d(TAG, "Disconnect WebSocket. State: " + mState);
        if (mState == WebSocketConnectionState.REGISTERED) {
            // Send "bye" to WebSocket server.
            send("{\"type\": \"bye\"}");
            mState = WebSocketConnectionState.CONNECTED;
            // Send http DELETE to http WebSocket server.
            sendWSSMessage("DELETE", "");
        }
        // Close WebSocket in CONNECTED or ERROR states only.
        if (mState == WebSocketConnectionState.CONNECTED || mState == WebSocketConnectionState.ERROR) {
            mWs.disconnect();
            mState = WebSocketConnectionState.CLOSED;

            // Wait for websocket close event to prevent websocket library from
            // sending any pending messages to deleted looper thread.
            if (waitForComplete) {
                synchronized (mCloseEventLock) {
                    while (!mCloseEvent) {
                        try {
                            mCloseEventLock.wait(CLOSE_TIMEOUT);
                            break;
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Wait error: " + e.toString());
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Disconnecting WebSocket done.");
    }

    private void reportError(final String errorMessage) {
        Log.e(TAG, errorMessage);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState != WebSocketConnectionState.ERROR) {
                    mState = WebSocketConnectionState.ERROR;
                    mEvents.onWebSocketError(errorMessage);
                }
            }
        });
    }

    // Asynchronously send POST/DELETE to WebSocket server.
    private void sendWSSMessage(final String method, final String message) {
        String postUrl = mPostServerUrl + "/" + mRoomID + "/" + mClientID;
        Log.d(TAG, "WS " + method + " : " + postUrl + " : " + message);
        AsyncHttpURLConnection httpConnection =
                new AsyncHttpURLConnection(method, mOriginUrl, postUrl, message, new AsyncHttpEvents() {
                    @Override
                    public void onHttpError(String errorMessage) {
                        reportError("WS " + method + " error: " + errorMessage);
                    }

                    @Override
                    public void onHttpComplete(String response) {
                    }
                });
        httpConnection.send();
    }

    // Helper method for debugging purposes. Ensures that WebSocket method is
    // called on a looper thread.
    private void checkIfCalledOnValidThread() {
        if (Thread.currentThread() != mHandler.getLooper().getThread()) {
            throw new IllegalStateException("WebSocket method is not called on valid thread");
        }
    }

    /**
     * Possible WebSocket connection states.
     */
    public enum WebSocketConnectionState {
        NEW, CONNECTED, REGISTERED, CLOSED, ERROR
    }

    /**
     * Callback interface for messages delivered on WebSocket.
     * All events are dispatched from a looper executor thread.
     */
    public interface WebSocketChannelEvents {
        void onWebSocketMessage(final String message);

        void onWebSocketClose();

        void onWebSocketError(final String description);
    }

    private class WebSocketObserver implements WebSocketConnectionObserver {
        @Override
        public void onOpen() {
            Log.d(TAG, "WebSocket connection opened to: " + mWsServerUrl);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mState = WebSocketConnectionState.CONNECTED;
                    // Check if we have pending register request.
                    if (mRoomID != null && mClientID != null) {
                        register(mRoomID, mClientID);
                    }
                }
            });
        }

        @Override
        public void onClose(WebSocketCloseNotification code, String reason) {
            Log.d(TAG, "WebSocket connection closed. Code: " + code + ". Reason: " + reason + ". State: "
                    + mState);
            synchronized (mCloseEventLock) {
                mCloseEvent = true;
                mCloseEventLock.notify();
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mState != WebSocketConnectionState.CLOSED) {
                        mState = WebSocketConnectionState.CLOSED;
                        mEvents.onWebSocketClose();
                    }
                }
            });
        }

        @Override
        public void onTextMessage(String payload) {
            Log.d(TAG, "WSS->C: " + payload);
            final String message = payload;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mState == WebSocketConnectionState.CONNECTED
                            || mState == WebSocketConnectionState.REGISTERED) {
                        mEvents.onWebSocketMessage(message);
                    }
                }
            });
        }

        @Override
        public void onRawTextMessage(byte[] payload) {
        }

        @Override
        public void onBinaryMessage(byte[] payload) {
        }
    }
}
