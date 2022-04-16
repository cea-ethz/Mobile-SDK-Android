package com.dji.sdk.sample.demo.localmission;

import android.app.Service;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.DialogUtils;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.utils.VideoFeedView;
import com.dji.sdk.sample.internal.view.PresentableView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

import ch.ethz.cea.dca.*;

import dji.common.airlink.PhysicalSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.virtualstick.*;
import dji.common.gimbal.GimbalState;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.util.CommonCallbacks;

import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.Compass;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaFileInfo;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

import static com.google.android.gms.internal.zzahn.runOnUiThread;

import javax.vecmath.Point2f;
import javax.vecmath.Vector2f;

public class LocalMissionView extends RelativeLayout
        implements View.OnClickListener, PresentableView {

    private LocalMission localMission;

    private Gimbal gimbal = null;
    private Compass compass;

    private boolean cameraReady = true;

    private float heading_real;
    private float gimbal_pitch_real = 0;
    private float ultrasonic_height_real = 0;

    private float vstickPitch = 0;
    private float vstickRoll = 0;
    private float vstickYaw = 0;
    private float vstickThrottle = 0f;

    private Point2f positionEstimated;

    private final float controllerUpdateTime = 0.1f;

    private Camera camera;

    private VideoFeedView primaryVideoFeed;
    private VideoFeeder.PhysicalSourceListener sourceListener;
    private View primaryCoverView;

    private Timer sendVirtualStickDataTimer;
    private SendVirtualStickDataTask sendVirtualStickDataTask;

    // UI Objects

    private TextView textViewListenerHeading;
    private TextView textViewListenerVelocity;
    private TextView textViewListenerPositionEstimated;
    private TextView textViewListenerVStick;
    private TextView textViewListenerPositionGPS;
    private TextView textViewListenerMissionState;
    private TextView textViewListenerMissionError;

    private TextView textViewMissionList;

    private int updateCount = 0;

    public LocalMissionView(Context context) {
        super(context);
        init(context);

        positionEstimated = new Point2f();
    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_local_mission, this, true);

        initUI();
    }

    private void initUI() {
        // Camera
        primaryCoverView = findViewById(R.id.primary_cover_view);
        primaryVideoFeed = (VideoFeedView) findViewById(R.id.primary_video_feed);
        primaryVideoFeed.setCoverView(primaryCoverView);

        // Flight info text displays
        textViewListenerHeading = (TextView) findViewById(R.id.text_heading);
        textViewListenerVelocity = (TextView) findViewById(R.id.text_velocity);
        textViewListenerPositionEstimated = (TextView) findViewById(R.id.text_position_estimated);
        textViewListenerVStick = (TextView) findViewById(R.id.text_vstick);
        textViewListenerPositionGPS = (TextView) findViewById(R.id.text_position_gps);
        textViewMissionList = (TextView) findViewById(R.id.lm_list_text);
        textViewListenerMissionState = (TextView) findViewById(R.id.text_mission_state);
        textViewListenerMissionError = (TextView) findViewById(R.id.text_mission_error);

        // Buttons
        findViewById(R.id.btn_mission_load).setOnClickListener(this);
        findViewById(R.id.btn_mission_start).setOnClickListener(this);
        findViewById(R.id.btn_enable_virtual_stick).setOnClickListener(this);
        findViewById(R.id.btn_disable_virtual_stick).setOnClickListener(this);
        findViewById(R.id.btn_take_off).setOnClickListener(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();

        if (ModuleVerificationUtil.isCameraModuleAvailable()) {
            camera = DJISampleApplication.getAircraftInstance().getCamera();
            if (ModuleVerificationUtil.isMatrice300RTK() || ModuleVerificationUtil.isMavicAir2()) {
                camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError -> ToastUtils.setResultToToast("setFlatMode to PHOTO_SINGLE"));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> ToastUtils.setResultToToast("setMode to shoot_PHOTO"));
            }
//            camera.setMediaFileCallback(new MediaFile.Callback() {
//                @Override
//                public void onNewFile(@NonNull MediaFile mediaFile) {
//                    ToastUtils.setResultToToast("New photo generated");
//                }
//            });
            camera.setNewGeneratedMediaFileInfoCallback(new MediaFile.NewFileInfoCallback() {
                @Override
                public void onNewFileInfo(@NonNull MediaFileInfo mediaFileInfo) {
                    ToastUtils.setResultToToast("New photo generated");
                    cameraReady = true;
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (null != sendVirtualStickDataTimer) {
            if (sendVirtualStickDataTask != null) {
                sendVirtualStickDataTask.cancel();

            }
            sendVirtualStickDataTimer.cancel();
            sendVirtualStickDataTimer.purge();
            sendVirtualStickDataTimer = null;
            sendVirtualStickDataTask = null;
        }
        tearDownListeners();
        super.onDetachedFromWindow();
    }


    private void setUpListeners() {
        // Receive : Camera
        sourceListener = new VideoFeeder.PhysicalSourceListener() {
            @Override
            public void onChange(VideoFeeder.VideoFeed videoFeed, PhysicalSource newPhysicalSource) {
            }
        };
        setVideoFeederListeners(true);

        Camera camera = DJISampleApplication.getProductInstance().getCamera();
        camera.setSystemStateCallback(new SystemState.Callback() {
            @Override
            public void onUpdate(@NonNull SystemState systemState) {
                if ((!systemState.isShootingSinglePhoto()) && (!systemState.isStoringPhoto())) {
                    cameraReady = true;
                }
            }
        });

        // Receive : Gimbal
        Gimbal gimbal = getGimbalInstance();
        gimbal.setStateCallback(new GimbalState.Callback() {
            @Override
            public void onUpdate(final GimbalState state) {
                gimbal_pitch_real = state.getAttitudeInDegrees().getPitch();
            }
        });

        // Receive : FlightController - 10hz
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            FlightController flightController =
                    ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController();

            flightController.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                    runMission();
                    updateFlightInfo(djiFlightControllerCurrentState);
                }
            });
            if (ModuleVerificationUtil.isCompassAvailable()) {
                compass = flightController.getCompass();
            }
        }

        // Only create VStick timer after its enabled
        sendVirtualStickDataTask = new LocalMissionView.SendVirtualStickDataTask();
        sendVirtualStickDataTimer = new Timer();
        sendVirtualStickDataTimer.schedule(sendVirtualStickDataTask, 100, 100);
    }

    private void tearDownListeners() {
        setVideoFeederListeners(false);

        if(ModuleVerificationUtil.isFlightControllerAvailable()) {
            ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController().setStateCallback(null);
        }
    }

    private void setVideoFeederListeners(boolean isOpen) {
        if (VideoFeeder.getInstance() == null) return;

        final BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (product != null) {
            VideoFeeder.VideoDataListener primaryVideoDataListener =
                    primaryVideoFeed.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);
            if (isOpen) {
                VideoFeeder.getInstance().addPhysicalSourceListener(sourceListener);
            } else {
                VideoFeeder.getInstance().removePhysicalSourceListener(sourceListener);
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(primaryVideoDataListener);
            }
        }
    }

    private void updateFlightInfo(FlightControllerState djiFlightControllerCurrentState) {
        // Read from Compass
        if (null != compass) {
            heading_real = compass.getHeading();
        }

        // Read from FC State about Velocity, Estimated Position (Basic), Position (GPS)
        float vx = djiFlightControllerCurrentState.getVelocityX();
        float vy = djiFlightControllerCurrentState.getVelocityY();
        float vz = djiFlightControllerCurrentState.getVelocityZ();

        Vector2f velocity = new Vector2f(vx,vy);

        Vector2f scaledVelocity = new Vector2f(velocity);
        scaledVelocity.scale(controllerUpdateTime);

        positionEstimated.add(scaledVelocity);

        LocationCoordinate3D positionGPS = djiFlightControllerCurrentState.getAircraftLocation();
        GPSSignalLevel signalLevel = djiFlightControllerCurrentState.getGPSSignalLevel();
        ultrasonic_height_real = djiFlightControllerCurrentState.getUltrasonicHeightInMeters();

        // Update UI Elements
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                DecimalFormat df = new DecimalFormat("0.000");
                textViewListenerVelocity.setText(getContext().getString(
                        R.string.listener_velocity,df.format(vx),df.format(vy),df.format(vz)));
                textViewListenerPositionEstimated.setText(getContext().getString(
                        R.string.listener_position,df.format(positionEstimated.x),df.format(positionEstimated.y)));
                textViewListenerPositionGPS.setText(getContext().getString(
                        R.string.listener_gps,signalLevel, positionGPS.getLatitude(),positionGPS.getLongitude()));
                textViewListenerHeading.setText(getContext().getString(
                        R.string.listener_pose_real,heading_real,df.format(ultrasonic_height_real)));
                textViewListenerVStick.setText(getContext().getString(
                        R.string.listener_vstick,df.format(vstickPitch),df.format(vstickRoll),df.format(vstickYaw),df.format(vstickThrottle)));
                if (localMission != null) {
                    String stateText = localMission.missionState + "\n" + localMission.getCurrentEvent().eventState;
                    textViewListenerMissionState.setText(getContext().getString(R.string.listener_mission_state,stateText));
                }

            }
        });
    }

    private void runMission() {
        if (localMission == null || localMission.missionState != LocalMissionState.RUNNING) {
            return;
        }

        // Move to next mission event if necessary
        if (localMission.getCurrentEvent().eventState == LocalMissionEventState.FINISHED) {
            localMission.advance();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textViewMissionList.setText(localMission.toString());
                }
            });
        }

        // Execute on current mission event
        LocalMissionEvent lmEvent = localMission.getCurrentEvent();
        System.out.println(lmEvent.eventState);
        switch(lmEvent.eventType) {
            case GO_TO:
                eventGOTO(lmEvent.data0,lmEvent.data1);
                break;
            case AIM_AT:
                break;
            case GIMBAL:
                eventGIMBAL(lmEvent.data0);
                break;
            case PHOTO:
                eventPHOTO();
            case ALTITUDE:
                break;
            case ALIGN:
                break;
            default:
                System.out.println("Unhandled event type : " + lmEvent.eventType);
                break;
        }
    }


    private void eventGOTO(float x, float y) {
        Vector2f vec = new Vector2f(x,y);
        vec.sub(positionEstimated);
        float d = vec.length();
        if (d < 0.5) {
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
            vstickPitch = 0;
            vstickRoll = 0;
        }
        else {
            vec.normalize();
            vec.scale(1f);
            vstickPitch = vec.x;
            vstickRoll = vec.y;
        }
    }


    private void eventGIMBAL(float angle) {
        Gimbal gimbal = getGimbalInstance();
        if (gimbal == null) {
            return;
        }

        // Rotation Finished, continue
        if (Math.abs(gimbal_pitch_real - angle) < 1) {
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
        }
        // Else Begin Rotation
        else if (localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
            System.out.println("Rotate to " + angle);
            if (angle > 30 || angle < -90) {
                System.out.println("Bad angle to gimbal");
                return;
            }
            Rotation.Builder builder = new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).time(0.5);
            builder.pitch(angle);

            gimbal.rotate(builder.build(), new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                }
            });

            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
        }
        // Else wait while rotation occurs


    }


    private void eventPHOTO() {
        if (isCameraAvailable() && cameraReady && localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
            Camera camera = DJISampleApplication.getProductInstance().getCamera();
            camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (null == djiError) {
                        ToastUtils.setResultToToast(getContext().getString(R.string.success));
                    } else {
                        ToastUtils.setResultToToast(djiError.getDescription());
                    }
                    System.out.println("Shoot");

                    final Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
                        }
                    }, 1500);

                }
            });
            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
            cameraReady = false;
        }
    }

    @Override
    public void onClick(View v) {
        // Set Control Modes for Virtual Stick Inputs
        // Settings are for absolute height control, directional horizontal control relative to the
        // world, and absolute angle direction
        FlightController flightController = ModuleVerificationUtil.getFlightController();
        if (flightController == null) {
            return;
        }

        switch(v.getId()) {
            case R.id.btn_enable_virtual_stick:
                flightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        flightController.setVerticalControlMode(VerticalControlMode.POSITION);
                        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                        flightController.setYawControlMode(YawControlMode.ANGLE);
                        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);

                        // Advanced mode allows for GPS-stabilized hovering, and collision avoidance
                        // if desired
                        flightController.setVirtualStickAdvancedModeEnabled(true);
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;

            case R.id.btn_disable_virtual_stick:
                flightController.setVirtualStickModeEnabled(false, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;
            case R.id.btn_take_off:
                flightController.startTakeoff(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        DialogUtils.showDialogBasedOnError(getContext(), djiError);
                    }
                });
                break;
            case R.id.btn_mission_load:
                Thread thread = new Thread(new Runnable() {
                    @Override public void run() {
                        loadMission();
                    }
                });
                thread.start();
                break;
            case R.id.btn_mission_start:
                localMission.missionState = LocalMissionState.RUNNING;
                localMission.getCurrentEvent().eventState = LocalMissionEventState.START;
                break;
        }
    }


    private void loadMission() {
        //TODO: Make this not hardcoded
        String urlString = "http://192.168.0.164:8000/mission.json"; // Wifi
        //String urlString = "http://192.168.80.121:8000/mission.json"; // Hotspot

        try {
            HttpURLConnection urlConnection = null;
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setReadTimeout(10000 /* milliseconds */ );
            urlConnection.setConnectTimeout(15000 /* milliseconds */ );
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
            br.close();

            String jsonString = sb.toString();

            localMission = new LocalMission();
            localMission.loadFromJson(jsonString);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textViewMissionList.setText(localMission.toString());
                }
            });

        }
        catch(Exception e) {
            System.out.println(e);
        }
    }


    @Override
    public int getDescription() {
        return R.string.flight_controller_listview_local_mission;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (ModuleVerificationUtil.isFlightControllerAvailable()) {

                DJISampleApplication.getAircraftInstance()
                        .getFlightController()
                        .sendVirtualStickFlightControlData(new FlightControlData(vstickPitch,
                                        vstickRoll,
                                        vstickYaw,
                                        vstickThrottle),
                                new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        updateCount += 1;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                textViewListenerMissionError.setText(getContext().getString(R.string.listener_mission_error,updateCount + ""));
                                                if (djiError != null) {
                                                    textViewListenerMissionError.setText(getContext().getString(R.string.listener_mission_error,djiError.toString()));
                                                }
                                            }
                                        });
                                    }
                                });
            }
        }
    }

    private Gimbal getGimbalInstance() {
        if (gimbal == null) {
            initGimbal();
        }
        return gimbal;
    }

    private void initGimbal() {
        if (DJISDKManager.getInstance() != null) {
            BaseProduct product = DJISDKManager.getInstance().getProduct();
            if (product != null) {
                if (product instanceof Aircraft) {
                    gimbal = ((Aircraft) product).getGimbals().get(0);
                } else {
                    gimbal = product.getGimbal();
                }
            }
        }
    }

    private boolean isCameraAvailable() {
        return (null != DJISampleApplication.getProductInstance()) && (null != DJISampleApplication.getProductInstance()
                .getCamera());
    }
}
