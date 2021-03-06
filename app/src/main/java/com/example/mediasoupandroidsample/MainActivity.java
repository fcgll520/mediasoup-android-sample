package com.example.mediasoupandroidsample;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.Toast;

import com.example.mediasoupandroidsample.permission.PermissionFragment;
import com.example.mediasoupandroidsample.request.Request;
import com.example.mediasoupandroidsample.room.RoomClient;
import com.example.mediasoupandroidsample.room.RoomListener;
import com.example.mediasoupandroidsample.socket.ActionEvent;
import com.example.mediasoupandroidsample.socket.EchoSocket;
import com.example.mediasoupandroidsample.socket.MessageObserver;

import org.mediasoup.droid.Consumer;
import org.mediasoup.droid.Device;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements MessageObserver.Observer, RoomListener {
    private static final String TAG = "MainActivity";

	private SurfaceViewRenderer mVideoView;
    private SurfaceViewRenderer mRemoteVideoView;
    private PermissionFragment mPermissionFragment;
    private RoomClient mClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Mediasoup
        mVideoView = findViewById(R.id.local_video_view);
        mRemoteVideoView = findViewById(R.id.remote_video_view);
	    ImageButton localPauseButton = findViewById(R.id.local_pause_button);
	    ImageButton localPlayButton = findViewById(R.id.local_play_button);
	    ImageButton remotePauseButton = findViewById(R.id.remote_pause_button);
	    ImageButton remotePlayButton = findViewById(R.id.remote_play_button);

	    // local play button
        localPlayButton.setOnClickListener(view -> {
			if (mClient != null) {
				try {
					mClient.resumeLocalAudio();
					mClient.resumeLocalVideo();
					runOnUiThread(() -> Toast.makeText(getBaseContext(), "Local Stream Resumed", Toast.LENGTH_LONG).show());
				} catch (Exception e) {
					Log.e(TAG, "Failed to pause local stream", e);
				}
			}
        });

        // local pause button
        localPauseButton.setOnClickListener(view -> {
	        if (mClient != null) {
				try {
					mClient.pauseLocalAudio();
					mClient.pauseLocalVideo();
					runOnUiThread(() -> Toast.makeText(getBaseContext(), "Local Stream Paused", Toast.LENGTH_LONG).show());
				} catch (Exception e) {
					Log.e(TAG, "Failed to resume local stream", e);
				}
	        }
        });

        // remote play button
	    remotePlayButton.setOnClickListener(view -> {
	    	if (mClient != null) {
	    		try {
	    			mClient.resumeRemoteAudio();
	    			mClient.resumeRemoteVideo();
	    			runOnUiThread(() -> Toast.makeText(getBaseContext(), "Remote Stream Resumed", Toast.LENGTH_LONG).show());
			    } catch (Exception e) {
	    			Log.e(TAG, "Failed to resume remote stream", e);
			    }
		    }
	    });

	    // remote pause button
	    remotePauseButton.setOnClickListener(view -> {
	    	if (mClient != null) {
	    		try {
	    			mClient.pauseRemoteAudio();
	    			mClient.pauseRemoteVideo();
	    			runOnUiThread(() -> Toast.makeText(getBaseContext(), "Remote Stream Paused", Toast.LENGTH_LONG).show());
			    } catch (Exception e) {
	    			Log.e(TAG, "Failed to pause remote stream", e);
			    }
		    }
	    });

	    EglBase.Context eglBaseContext = EglBase.create().getEglBaseContext();
	    runOnUiThread(() -> mRemoteVideoView.init(eglBaseContext, null));

        addPermissionFragment();
        // FIX: race problem, asking for permissions before fragment is full attached..
        getSupportFragmentManager().executePendingTransactions();

        // Connect to the websocket server
        this.connectWebSocket();
    }

    private void connectWebSocket() {
        EchoSocket socket = new EchoSocket();
        socket.register(this);

        try {
            // Connect to server
            socket.connect(getString(R.string.server_socket_url)).get(3000, TimeUnit.SECONDS);

            // Initialize mediasoup client
            initializeMediasoupClient();

            // Get router rtp capabilities
            JSONObject getRoomRtpCapabilitiesResponse = Request.sendGetRoomRtpCapabilitiesRequest(socket, "android");
            JSONObject roomRtpCapabilities = getRoomRtpCapabilitiesResponse.getJSONObject("roomRtpCapabilities");

            // Initialize mediasoup device
            Device device = new Device();
            device.load(roomRtpCapabilities.toString());

            // Create a new room client
            mClient = new RoomClient(socket, device, "android", this);

            // Join the room
	        mClient.join();

	        // Create recv WebRtcTransport
	        mClient.createRecvTransport();

	        // Create send WebRtcTransport
	        mClient.createSendTransport();

	        // Produce local media
	        displayLocalVideo();
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to socket server error=", e);
        }
    }

	/**
	 * Initialize Mediasoup Client
	 */
	private void initializeMediasoupClient() {
        MediasoupClient.initialize(getApplicationContext());
        Log.d(TAG, "Mediasoup client initialized");

        // Set mediasoup log
        Logger.setLogLevel(Logger.LogLevel.LOG_TRACE);
        Logger.setDefaultHandler();
    }

	/**
	 * Capture and start producing local video/audio
	 */
	private void displayLocalVideo () {
        mPermissionFragment.setPermissionCallback(new PermissionFragment.PermissionCallback() {
            @Override
            public void onPermissionGranted() {
                try {
                    EglBase.Context context = EglBase.create().getEglBaseContext();
                    runOnUiThread(() -> mVideoView.init(context, null));

                    mClient.produceAudio();
                    mClient.produceVideo(getBaseContext(), mVideoView, context);
                    mVideoView.bringToFront();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize local stream e=" + e.getLocalizedMessage());
                }
            }

            @Override
            public void onPermissionDenied() {
                Log.w(TAG, "User denied camera/mic permission");
            }
        });

        mPermissionFragment.checkCameraMicPermission();
    }

	/**
	 * Add Permission Headless Fragment
	 */
	private void addPermissionFragment() {
        mPermissionFragment = (PermissionFragment) getSupportFragmentManager().findFragmentByTag(PermissionFragment.TAG);

        if(mPermissionFragment == null) {
            mPermissionFragment = PermissionFragment.newInstance();
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(mPermissionFragment, PermissionFragment.TAG)
                    .commit();
        }
    }

	@Override
	public void on(@ActionEvent.Event String event, JSONObject data) {
		Log.d(TAG, "Received event " + event);

		try {
			switch(event) {
				case ActionEvent.NEW_USER:
					// data.userId
					Log.d(TAG, "NEW_USER id=" + data.getString("userId"));
					break;
				case ActionEvent.NEW_CONSUMER:
					// data.consumerData.consumerUserId - consumer user id
					// data.consumerData.producerUserId - producer user id
					// data.consumerData.producerId - producer id
					// data.consumerData.id // consumer id
					// data.consumerData.kind // consumer kind
					// data.consumerData.rtpParameters // consumer rtpParameters
					// data.consumerData.type // consumer type
					// data.consumerData.producerPaused // producer paused status
					Log.d(TAG, "NEW_CONSUMER data=" + data);
					handleNewConsumerEvent(data.getJSONObject("consumerData"));
					break;
			}
		} catch (JSONException je) {
			Log.e(TAG, "Failed to handle event", je);
		}
	}

	/**
	 * Handle remote newconsumer event
	 * @param consumerInfo ConsumerInfo
	 */
	private void handleNewConsumerEvent(JSONObject consumerInfo) {
    	try {
    		Log.d(TAG, "handleNewConsumerEvent info =" + consumerInfo);
		    mClient.consumeTrack(consumerInfo);
	    } catch (Exception e) {
    		Log.e(TAG, "Failed to consume remote track", e);
	    }
    }

	@Override
	public void onNewConsumer(Consumer consumer) {
		// If the remote consumer is video attach to the remote video renderer
		if (consumer != null && consumer.getKind().equals("video")) {
			VideoTrack videoTrack = (VideoTrack) consumer.getTrack();
			videoTrack.setEnabled(true);
			videoTrack.addSink(mRemoteVideoView);
		}

		try {
			// Resume the remote consumer
			if (consumer.getKind().equals("video"))
				mClient.resumeRemoteVideo();
			else if (consumer.getKind().equals("audio"))
				mClient.resumeRemoteAudio();
		} catch (JSONException je) {
			Log.e(TAG, "Failed to send resume consumer request", je);
		}
	}
}
