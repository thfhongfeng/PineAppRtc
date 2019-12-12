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
import android.os.HandlerThread;
import android.util.Log;

import com.pine.rtc.org.component.AsyncHttpURLConnection.AsyncHttpEvents;
import com.pine.rtc.org.component.RoomParametersFetcher.RoomParametersFetcherEvents;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 * <p>
 * <p>To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
public class WebSocketRTCClient implements AppRTCClient, WebSocketChannelClient.WebSocketChannelEvents {
    private static final String TAG = "WSRTCClient";

    private static final String ROOM_JOIN = "join";
    private static final String ROOM_MESSAGE = "message";
    private static final String ROOM_LEAVE = "leave";
    private final Handler mHandler;
    private boolean mInitiator;
    private SignalingEvents mEvents;
    private WebSocketChannelClient mWsClient;
    private ConnectionState mRoomState;
    private RoomConnectionParameters mConnectionParameters;
    private String mMessageUrl;
    private String mLeaveUrl;

    public WebSocketRTCClient(SignalingEvents events) {
        this.mEvents = events;
        mRoomState = ConnectionState.NEW;
        final HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

    // Put a |key|->|value| mapping in |json|.
    private static void jsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to an AppRTC room URL using supplied connection
    // parameters, retrieves room parameters and connect to WebSocket server.
    @Override
    public void connectToRoom(RoomConnectionParameters connectionParameters) {
        this.mConnectionParameters = connectionParameters;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                connectToRoomInternal();
            }
        });
    }

    @Override
    public void disconnectFromRoom() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                disconnectFromRoomInternal();
                mHandler.getLooper().quit();
            }
        });
    }

    // Connects to room - function runs on a local looper thread.
    private void connectToRoomInternal() {
        String connectionUrl = getConnectionUrl(mConnectionParameters);
        Log.d(TAG, "Connect to room: " + connectionUrl);
        mRoomState = ConnectionState.NEW;
        mWsClient = new WebSocketChannelClient(mHandler, this);

        RoomParametersFetcherEvents callbacks = new RoomParametersFetcherEvents() {
            @Override
            public void onSignalingParametersReady(final SignalingParameters params) {
                WebSocketRTCClient.this.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        WebSocketRTCClient.this.signalingParametersReady(params);
                    }
                });
            }

            @Override
            public void onSignalingParametersError(String description) {
                WebSocketRTCClient.this.reportError(description);
            }
        };

        new RoomParametersFetcher(mConnectionParameters.originRoomUrl, connectionUrl, null, callbacks).makeRequest();
    }

    // Disconnect from room and send bye messages - runs on a local looper thread.
    private void disconnectFromRoomInternal() {
        Log.d(TAG, "Disconnect. Room state: " + mRoomState);
        if (mRoomState == ConnectionState.CONNECTED) {
            Log.d(TAG, "Closing room.");
            sendPostMessage(MessageType.LEAVE, mLeaveUrl, null);
        }
        mRoomState = ConnectionState.CLOSED;
        if (mWsClient != null) {
            mWsClient.disconnect(true);
        }
    }

    // Helper functions to get connection, post message and leave message URLs
    private String getConnectionUrl(RoomConnectionParameters connectionParameters) {
        return connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
                + getQueryString(connectionParameters);
    }

    private String getMessageUrl(
            RoomConnectionParameters connectionParameters, SignalingParameters signalingParameters) {
        return connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId
                + "/" + signalingParameters.clientId + getQueryString(connectionParameters);
    }

    private String getLeaveUrl(
            RoomConnectionParameters connectionParameters, SignalingParameters signalingParameters) {
        return connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/"
                + signalingParameters.clientId + getQueryString(connectionParameters);
    }

    private String getQueryString(RoomConnectionParameters connectionParameters) {
        if (connectionParameters.urlParameters != null) {
            return "?" + connectionParameters.urlParameters;
        } else {
            return "";
        }
    }

    // Callback issued when room parameters are extracted. Runs on local
    // looper thread.
    private void signalingParametersReady(final SignalingParameters signalingParameters) {
        Log.d(TAG, "Room connection completed.");
        if (mConnectionParameters.loopback
                && (!signalingParameters.initiator || signalingParameters.offerSdp != null)) {
            reportError("Loopback room is busy.");
            return;
        }
        if (!mConnectionParameters.loopback && !signalingParameters.initiator
                && signalingParameters.offerSdp == null) {
            Log.w(TAG, "No offer SDP in room response.");
        }
        mInitiator = signalingParameters.initiator;
        mMessageUrl = getMessageUrl(mConnectionParameters, signalingParameters);
        mLeaveUrl = getLeaveUrl(mConnectionParameters, signalingParameters);
        Log.d(TAG, "Message URL: " + mMessageUrl);
        Log.d(TAG, "Leave URL: " + mLeaveUrl);
        mRoomState = ConnectionState.CONNECTED;

        // Fire connection and signaling parameters events.
        mEvents.onConnectedToRoom(signalingParameters);

        // Connect and register WebSocket client.
        mWsClient.connect(mConnectionParameters.originRoomUrl, signalingParameters.wssUrl,
                signalingParameters.wssPostUrl);
        mWsClient.register(mConnectionParameters.roomId, signalingParameters.clientId);
    }

    // Send local offer SDP to the other participant.
    @Override
    public void sendOfferSdp(final SessionDescription sdp) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRoomState != ConnectionState.CONNECTED) {
                    reportError("Sending offer SDP in non connected state.");
                    return;
                }
                JSONObject json = new JSONObject();
                jsonPut(json, "sdp", sdp.description);
                jsonPut(json, "type", "offer");
                sendPostMessage(MessageType.MESSAGE, mMessageUrl, json.toString());
                if (mConnectionParameters.loopback) {
                    // In loopback mode rename this offer to answer and route it back.
                    SessionDescription sdpAnswer = new SessionDescription(
                            SessionDescription.Type.fromCanonicalForm("answer"), sdp.description);
                    mEvents.onRemoteDescription(sdpAnswer);
                }
            }
        });
    }

    // Send local answer SDP to the other participant.
    @Override
    public void sendAnswerSdp(final SessionDescription sdp) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mConnectionParameters.loopback) {
                    Log.e(TAG, "Sending answer in loopback mode.");
                    return;
                }
                JSONObject json = new JSONObject();
                jsonPut(json, "sdp", sdp.description);
                jsonPut(json, "type", "answer");
                mWsClient.send(json.toString());
            }
        });
    }

    // Send Ice candidate to the other participant.
    @Override
    public void sendLocalIceCandidate(final IceCandidate candidate) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                JSONObject json = new JSONObject();
                jsonPut(json, "type", "candidate");
                jsonPut(json, "label", candidate.sdpMLineIndex);
                jsonPut(json, "id", candidate.sdpMid);
                jsonPut(json, "candidate", candidate.sdp);
                if (mInitiator) {
                    // Call initiator sends ice candidates to GAE server.
                    if (mRoomState != ConnectionState.CONNECTED) {
                        reportError("Sending ICE candidate in non connected state.");
                        return;
                    }
                    sendPostMessage(MessageType.MESSAGE, mMessageUrl, json.toString());
                    if (mConnectionParameters.loopback) {
                        mEvents.onRemoteIceCandidate(candidate);
                    }
                } else {
                    // Call receiver sends ice candidates to websocket server.
                    mWsClient.send(json.toString());
                }
            }
        });
    }

    // Send removed Ice candidates to the other participant.
    @Override
    public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                JSONObject json = new JSONObject();
                jsonPut(json, "type", "remove-candidates");
                JSONArray jsonArray = new JSONArray();
                for (final IceCandidate candidate : candidates) {
                    jsonArray.put(toJsonCandidate(candidate));
                }
                jsonPut(json, "candidates", jsonArray);
                if (mInitiator) {
                    // Call initiator sends ice candidates to GAE server.
                    if (mRoomState != ConnectionState.CONNECTED) {
                        reportError("Sending ICE candidate removals in non connected state.");
                        return;
                    }
                    sendPostMessage(MessageType.MESSAGE, mMessageUrl, json.toString());
                    if (mConnectionParameters.loopback) {
                        mEvents.onRemoteIceCandidatesRemoved(candidates);
                    }
                } else {
                    // Call receiver sends ice candidates to websocket server.
                    mWsClient.send(json.toString());
                }
            }
        });
    }

    // --------------------------------------------------------------------
    // WebSocketChannelEvents interface implementation.
    // All events are called by WebSocketChannelClient on a local looper thread
    // (passed to WebSocket client constructor).
    @Override
    public void onWebSocketMessage(final String msg) {
        if (mWsClient.getState() != WebSocketChannelClient.WebSocketConnectionState.REGISTERED) {
            Log.e(TAG, "Got WebSocket message in non registered state.");
            return;
        }
        try {
            JSONObject json = new JSONObject(msg);
            String msgText = json.getString("msg");
            String errorText = json.optString("error");
            if (msgText.length() > 0) {
                json = new JSONObject(msgText);
                String type = json.optString("type");
                if (type.equals("candidate")) {
                    mEvents.onRemoteIceCandidate(toJavaCandidate(json));
                } else if (type.equals("remove-candidates")) {
                    JSONArray candidateArray = json.getJSONArray("candidates");
                    IceCandidate[] candidates = new IceCandidate[candidateArray.length()];
                    for (int i = 0; i < candidateArray.length(); ++i) {
                        candidates[i] = toJavaCandidate(candidateArray.getJSONObject(i));
                    }
                    mEvents.onRemoteIceCandidatesRemoved(candidates);
                } else if (type.equals("answer")) {
                    if (mInitiator) {
                        SessionDescription sdp = new SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"));
                        mEvents.onRemoteDescription(sdp);
                    } else {
                        reportError("Received answer for call initiator: " + msg);
                    }
                } else if (type.equals("offer")) {
                    if (!mInitiator) {
                        SessionDescription sdp = new SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"));
                        mEvents.onRemoteDescription(sdp);
                    } else {
                        reportError("Received offer for call receiver: " + msg);
                    }
                } else if (type.equals("bye")) {
                    mEvents.onChannelClose();
                } else {
                    reportError("Unexpected WebSocket message: " + msg);
                }
            } else {
                if (errorText != null && errorText.length() > 0) {
                    reportError("WebSocket error message: " + errorText);
                } else {
                    reportError("Unexpected WebSocket message: " + msg);
                }
            }
        } catch (JSONException e) {
            reportError("WebSocket message JSON parsing error: " + e.toString());
        }
    }

    @Override
    public void onWebSocketClose() {
        mEvents.onChannelClose();
    }

    @Override
    public void onWebSocketError(String description) {
        reportError("WebSocket error: " + description);
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private void reportError(final String errorMessage) {
        Log.e(TAG, errorMessage);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRoomState != ConnectionState.ERROR) {
                    mRoomState = ConnectionState.ERROR;
                    mEvents.onChannelError(errorMessage);
                }
            }
        });
    }

    // Send SDP or ICE candidate to a room server.
    private void sendPostMessage(
            final MessageType messageType, final String url, final String message) {
        String logInfo = url;
        if (message != null) {
            logInfo += ". Message: " + message;
        }
        Log.d(TAG, "C->GAE: " + logInfo);
        AsyncHttpURLConnection httpConnection =
                new AsyncHttpURLConnection("POST", mConnectionParameters.originRoomUrl
                        , url, message, new AsyncHttpEvents() {
                    @Override
                    public void onHttpError(String errorMessage) {
                        reportError("GAE POST error: " + errorMessage);
                    }

                    @Override
                    public void onHttpComplete(String response) {
                        if (messageType == MessageType.MESSAGE) {
                            try {
                                JSONObject roomJson = new JSONObject(response);
                                String result = roomJson.getString("result");
                                if (!result.equals("SUCCESS")) {
                                    reportError("GAE POST error: " + result);
                                }
                            } catch (JSONException e) {
                                reportError("GAE POST JSON error: " + e.toString());
                            }
                        }
                    }
                });
        httpConnection.send();
    }

    // Converts a Java candidate to a JSONObject.
    private JSONObject toJsonCandidate(final IceCandidate candidate) {
        JSONObject json = new JSONObject();
        jsonPut(json, "label", candidate.sdpMLineIndex);
        jsonPut(json, "id", candidate.sdpMid);
        jsonPut(json, "candidate", candidate.sdp);
        return json;
    }

    // Converts a JSON candidate to a Java object.
    private IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
        return new IceCandidate(
                json.getString("id"), json.getInt("label"), json.getString("candidate"));
    }

    private enum ConnectionState {NEW, CONNECTED, CLOSED, ERROR}

    private enum MessageType {MESSAGE, LEAVE}
}
