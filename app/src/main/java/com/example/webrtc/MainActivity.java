package com.example.webrtc;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;

import java.util.ArrayList;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
    //flag to check if sdp is offer or answer
    boolean createOffer;
    //renderers
    private SurfaceViewRenderer pipRenderer;
    private SurfaceViewRenderer fullscreenRenderer;
    private EglBase rootEglBase;
    //video and audio source
    private VideoSource videoSource;
    private AudioSource audioSource;
    //video and audio tracks
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    //peerconnection and factory objects
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    //media constraints
    private MediaConstraints pcConstraints;
    private MediaConstraints audioConstraints;
    private MediaConstraints sdpMediaConstraints;
    //Audio Settings
    private boolean DTLS = true;
    private boolean disableAudioProcessing = true;
    private boolean enableLevelControl = false;

    private Socket socket;

    // peer connection observer
    PeerConnection.Observer observer = new PeerConnection.Observer() {

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            //send ice candidates
            try {
                JSONObject obj = new JSONObject();
                obj.put("sdpMid", iceCandidate.sdpMid);
                obj.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                obj.put("sdp", iceCandidate.sdp);
                socket.emit("candidate", obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            //If video track is available then show only first
            if (mediaStream.videoTracks.size() > 0) {
                ProxyRenderer proxyRenderer = new ProxyRenderer();
                ProxyVideoSink sink = new ProxyVideoSink();
                sink.setTarget(fullscreenRenderer);
                proxyRenderer.setTarget(fullscreenRenderer);
                VideoTrack track = mediaStream.videoTracks.getFirst();
                track.setEnabled(true);
                track.addRenderer(new VideoRenderer(proxyRenderer));
                track.addSink(sink);
            }


        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d("rfk", "Datachannel created");
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }

    };

    //session description observer
    SdpObserver sdpObserver = new SdpObserver() {


        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            peerConnection.setLocalDescription(sdpObserver, sessionDescription);
            try {
                JSONObject obj = new JSONObject();
                obj.put("sdp", sessionDescription.description);
                obj.put("type", sessionDescription.type);
                if (createOffer) {
                    socket.emit("offer", obj);
                } else {
                    socket.emit("answer", obj);
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //make it go fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // Intialize UI .
        pipRenderer = (SurfaceViewRenderer) findViewById(R.id.pip_video_view);
        fullscreenRenderer = (SurfaceViewRenderer) findViewById(R.id.fullscreen_video_view);

        rootEglBase = EglBase.create();

        //initialize video renderers
        fullscreenRenderer.init(rootEglBase.getEglBaseContext(), null);
        fullscreenRenderer.setMirror(true);
        fullscreenRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        fullscreenRenderer.setEnableHardwareScaler(true);

        //initialize video renderers
        pipRenderer.init(rootEglBase.getEglBaseContext(), null);
        pipRenderer.setMirror(true);
        pipRenderer.setZOrderMediaOverlay(true);
        pipRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        pipRenderer.setEnableHardwareScaler(false);

        //initialize and connect webrtc
        init();

    }

    private void init() {

        //initialize peer connection factory with minimal initialization options
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .setEnableVideoHwAcceleration(true)
                        .createInitializationOptions());

        //if loopback is true ignore network mask
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.networkIgnoreMask = 0;

        //initialize audio source and audio error callbacks to log errors
        initAudioErrorCallbacks();

        //create peer connection factory object
        factory = new PeerConnectionFactory(options);

        factory.setVideoHwAccelerationOptions(rootEglBase.getEglBaseContext(), rootEglBase.getEglBaseContext());


        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca").setUsername("webrtc@live.co").setPassword("muazkh").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:192.158.29.39:3478?transport=udp").setUsername("28224511:1379330808").setPassword("JZEOEt2V3Qb0y27GRntt2u2PAYA=").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:turn.bistri.com:80").setUsername("homeo").setPassword("homeo").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:turn.anyfirewall.com:443?transport=tcp").setUsername("webrtc").setPassword("webrtc").createIceServer());


        final PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        createMediaConstraintsInternal();

        peerConnection = factory.createPeerConnection(
                rtcConfig,
                pcConstraints,
                observer);

        //create Video capturer
        VideoCapturer videoCapturer = createVideoCapturer();

        //create Audio Track
        createAudioTrack();

        //create Video Track
        createVideoTrack(videoCapturer);

        //create media stream and add Tracks created earlier
        final MediaStream stream = factory.createLocalMediaStream("ARDAMS");
        stream.addTrack(localAudioTrack);
        stream.addTrack(localVideoTrack);

        //add Stream to peer connection
        peerConnection.addStream(stream);

        try {
            // connect to signaling server and exchange offer, answer and ice candidates
            socket = IO.socket("http://192.168.1.106:3000");
            MessageHandler handler = new MessageHandler();
            socket.on("offer", handler.onOffer);
            socket.on("answer", handler.onAnswer);
            socket.on("candidate", handler.onCandidates);
            socket.on("createoffer", handler.onCreateOffer);
            socket.connect();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void createMediaConstraintsInternal() {

        // Create peer connection constraints.
        pcConstraints = new MediaConstraints();

        // Enable DTLS for normal calls and disable for loopback calls.
        if (DTLS) {
            pcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
        } else {
            pcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        }

        pcConstraints.optional.add(
                new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));

        // Create audio constraints.
        audioConstraints = new MediaConstraints();

        // added for audio performance measurements
        if (disableAudioProcessing) {
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
        }
        if (enableLevelControl) {
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
        }

        // Create SDP constraints.
        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

    }

    //create audio track
    private AudioTrack createAudioTrack() {
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("LocalAudio", audioSource);
        localAudioTrack.setEnabled(true);
        return localAudioTrack;
    }

    //create video track
    private VideoTrack createVideoTrack(VideoCapturer capturer) {
        videoSource = factory.createVideoSource(capturer);
        capturer.startCapture(240, 320, 30);

        ProxyRenderer proxyRenderer = new ProxyRenderer();
        proxyRenderer.setTarget(pipRenderer);

        ProxyVideoSink sink = new ProxyVideoSink();
        sink.setTarget(pipRenderer);

        VideoRenderer renderer = new VideoRenderer(proxyRenderer);

        localVideoTrack = factory.createVideoTrack("VIDEO_TRACK_ID", videoSource);
        localVideoTrack.setEnabled(true);
        localVideoTrack.addRenderer(renderer);
        localVideoTrack.addSink(sink);
        return localVideoTrack;
    }

    //create camera capturer
    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    //create video capturer from camera capturer
    private VideoCapturer createVideoCapturer() {
        return createCameraCapturer(new Camera2Enumerator(this));
    }

    public void initAudioErrorCallbacks() {
        // Set audio record error callbacks.
        WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecord.WebRtcAudioRecordErrorCallback() {
            @Override
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
//                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordStartError(
                    WebRtcAudioRecord.AudioRecordStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
//                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
//                reportError(errorMessage);
            }
        });

        WebRtcAudioTrack.setErrorCallback(new WebRtcAudioTrack.WebRtcAudioTrackErrorCallback() {
            @Override
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                Log.e(TAG, errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackStartError(String errorMessage) {
                Log.e(TAG, errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackError(String errorMessage) {
                Log.e(TAG, errorMessage);
            }
        });
    }

    class ProxyRenderer implements VideoRenderer.Callbacks {
        private VideoRenderer.Callbacks target;

        @Override
        synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            target.renderFrame(frame);
        }

        synchronized public void setTarget(VideoRenderer.Callbacks target) {
            this.target = target;
        }
    }

    class ProxyVideoSink implements VideoSink {
        private VideoSink target;

        @Override
        synchronized public void onFrame(VideoFrame frame) {
            if (target == null) {
//                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                return;
            }

            target.onFrame(frame);
        }

        synchronized public void setTarget(VideoSink target) {
            this.target = target;
        }
    }

    private class MessageHandler {

        private Emitter.Listener onOffer = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONObject obj = (JSONObject) args[0];
                    SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER,
                            obj.getString("sdp"));

                    peerConnection.setRemoteDescription(sdpObserver, sdp);
                    peerConnection.createAnswer(sdpObserver, new MediaConstraints());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        private Emitter.Listener onAnswer = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONObject obj = (JSONObject) args[0];
                    SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER,
                            obj.getString("sdp"));
                    peerConnection.setRemoteDescription(sdpObserver, sdp);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        private Emitter.Listener onCandidates = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONObject obj = (JSONObject) args[0];
                    peerConnection.addIceCandidate(new IceCandidate(obj.getString("sdpMid"),
                            obj.getInt("sdpMLineIndex"),
                            obj.getString("sdp")));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        private Emitter.Listener onCreateOffer = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    createOffer = true;
                    peerConnection.createOffer(sdpObserver, new MediaConstraints());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };


    }

}
