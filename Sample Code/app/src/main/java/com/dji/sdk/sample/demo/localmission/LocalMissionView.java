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

import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

public class LocalMissionView extends RelativeLayout
        implements View.OnClickListener, PresentableView {

    // Current mission being executed
    private LocalMission localMission;

    // Initial pose data set during Calibrate button press
    private float calibratedYaw = 0;
    private float calibratedBarometerAltitude = 0;


    private Matrix3f transformLocalToGlobal;
    private Matrix3f transformGlobalToLocal;

    // Hardware objects
    private Camera camera;
    private Gimbal gimbal = null;
    private Compass compass;

    // Should be true when camera is not actively capturing or storing data
    private boolean cameraReady = true;

    // Pose values from direct internal measurement - global updates local in compass callback
    private float yawGlobal = 0;
    private float yawLocal = 0;

    private float gimbalPitchReal = 0;
    private float ultrasonicHeightReal = 0;
    private float barometerAltitudeReal = 0;
    private float barometerAltitudeRaw = 0;

    // Control data sent via the Virtual Stick interface - Local updates global at send time
    private Vector3f vstickPitchRollLocal;
    private Vector3f vstickPitchRollGlobal;
    private float vstickYawLocal = 0;
    private float vstickYawGlobal = 0;
    private float vstickThrottle = 1f;

    // Estimated local position as integrated from the measured velocity - Global updates local in flight info callback
    private Vector3f positionEstimatedLocal;
    private Vector3f positionEstimatedGlobal;

    private Vector3f currentVelocityLocal;
    private Vector3f currentVelocityGlobal;

    // Frequency of the FlightControllerState callback
    private final float controllerUpdateTime = 0.1f;

    // Live in-app video feed
    private VideoFeedView primaryVideoFeed;
    private VideoFeeder.PhysicalSourceListener sourceListener;
    private View primaryCoverView;

    // Timing for sending commands via the Virtual Stick interface
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

    // Helper to track that the Vstick sending function is being called correctly, remove eventually
    private int updateCount = 0;


    public LocalMissionView(Context context) {
        super(context);
        init(context);

        positionEstimatedLocal = new Vector3f();
        positionEstimatedGlobal = new Vector3f();
        vstickPitchRollLocal = new Vector3f();
        vstickPitchRollGlobal = new Vector3f();

        currentVelocityLocal = new Vector3f();
        currentVelocityGlobal = new Vector3f();
        calibrate(0);
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
        textViewListenerHeading = (TextView) findViewById(R.id.text_pose_real);
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
        findViewById(R.id.btn_calibrate).setOnClickListener(this);
    }


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();


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

        if (ModuleVerificationUtil.isCameraModuleAvailable()) {
            camera = DJISampleApplication.getAircraftInstance().getCamera();
            if (ModuleVerificationUtil.isMatrice300RTK() || ModuleVerificationUtil.isMavicAir2()) {
                camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError -> ToastUtils.setResultToToast("setFlatMode to PHOTO_SINGLE"));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> ToastUtils.setResultToToast("setMode to shoot_PHOTO"));
            }

            camera.setNewGeneratedMediaFileInfoCallback(new MediaFile.NewFileInfoCallback() {
                @Override
                public void onNewFileInfo(@NonNull MediaFileInfo mediaFileInfo) {
                    ToastUtils.setResultToToast("New photo generated");
                    cameraReady = true;
                }
            });
        }


        sourceListener = new VideoFeeder.PhysicalSourceListener() {
            @Override
            public void onChange(VideoFeeder.VideoFeed videoFeed, PhysicalSource newPhysicalSource) {
            }
        };
        setVideoFeederListeners(true);

        //Camera camera = DJISampleApplication.getProductInstance().getCamera();
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
                gimbalPitchReal = state.getAttitudeInDegrees().getPitch();
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
            //TODO: This probably should be taken out of the callback
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
            yawGlobal = compass.getHeading();
            yawLocal = yawGlobal - calibratedYaw;
            yawLocal = ensureYawRange(yawLocal);
        }

        // Read from FC State about Velocity, Estimated Position (Basic), Position (GPS)
        float vx = djiFlightControllerCurrentState.getVelocityX();
        float vy = djiFlightControllerCurrentState.getVelocityY();
        float vz = djiFlightControllerCurrentState.getVelocityZ();

        currentVelocityGlobal.set(vx,vy,vz);
        transformGlobalToLocal.transform(currentVelocityGlobal,currentVelocityLocal);

        Vector3f scaledVelocity = new Vector3f(currentVelocityGlobal);
        scaledVelocity.scale(controllerUpdateTime);

        positionEstimatedGlobal.add(scaledVelocity);
        transformGlobalToLocal.transform(positionEstimatedGlobal,positionEstimatedLocal);


        LocationCoordinate3D positionGPS = djiFlightControllerCurrentState.getAircraftLocation();
        GPSSignalLevel signalLevel = djiFlightControllerCurrentState.getGPSSignalLevel();
        ultrasonicHeightReal = djiFlightControllerCurrentState.getUltrasonicHeightInMeters();

        barometerAltitudeRaw = positionGPS.getAltitude();
        barometerAltitudeReal = barometerAltitudeRaw - calibratedBarometerAltitude;

        // Update UI Elements
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                DecimalFormat df = new DecimalFormat("0.000");
                textViewListenerVelocity.setText(getContext().getString(
                        R.string.listener_velocity,
                        df.format(currentVelocityLocal.x),
                        df.format(currentVelocityLocal.y),
                        df.format(currentVelocityLocal.z)));
                textViewListenerPositionEstimated.setText(getContext().getString(
                        R.string.listener_position,df.format(positionEstimatedLocal.x),df.format(positionEstimatedLocal.y)));
                textViewListenerPositionGPS.setText(getContext().getString(
                        R.string.listener_gps,
                        signalLevel,
                        positionGPS.getLatitude(),
                        positionGPS.getLongitude(),
                        positionGPS.getAltitude()));
                textViewListenerHeading.setText(getContext().getString(
                        R.string.listener_pose_real, yawLocal,df.format(barometerAltitudeReal)));
                textViewListenerVStick.setText(getContext().getString(
                        R.string.listener_vstick,
                        df.format(vstickPitchRollLocal.x),
                        df.format(vstickPitchRollLocal.y),
                        df.format(vstickYawLocal),
                        df.format(vstickThrottle)));
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
                eventAIM_AT(lmEvent.data0);
                break;
            case GIMBAL:
                eventGIMBAL(lmEvent.data0);
                break;
            case PHOTO:
                eventPHOTO();
            case ALTITUDE:
                eventALTITUDE(lmEvent.data0);
                break;
            case ALIGN:
                break;
            default:
                System.out.println("Unhandled event type : " + lmEvent.eventType);
                break;
        }
    }


    private void eventGOTO(float x, float y) {
        Vector3f vec = new Vector3f(x,y,0);
        vec.sub(positionEstimatedLocal);
        vec.z = 0;
        float d = vec.length();
        if (d < 0.5) {
            vstickPitchRollLocal.x = 0;
            vstickPitchRollLocal.y = 0;
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
        }
        else {
            vec.normalize();
            vec.scale(1f);
            vstickPitchRollLocal.y = vec.x;
            vstickPitchRollLocal.x = vec.y;
            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
        }
    }


    private void eventAIM_AT(float angle) {
        // First tick
        if (localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
            vstickYawLocal = angle;
            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
        }

        // Exit tick
        if (Math.abs(yawLocal - vstickYawLocal) < 1) {
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
        }
        // Otherwise wait while aircraft rotates
    }


    private void eventGIMBAL(float angle) {
        Gimbal gimbal = getGimbalInstance();
        if (gimbal == null) {
            return;
        }

        // First Tick
        if (localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
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
        // Exit Tick
        else if (Math.abs(gimbalPitchReal - angle) < 1) {
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
        }
        // Else wait while gimbal rotates
    }


    private void eventPHOTO() {
        if (isCameraAvailable() && cameraReady && localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
            //Camera camera = DJISampleApplication.getProductInstance().getCamera();
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
            cameraReady = false;
            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
        }
    }


    private void eventALTITUDE(float altitude) {
        // First tick
        if (localMission.getCurrentEvent().eventState == LocalMissionEventState.START) {
            vstickThrottle = altitude;
            localMission.getCurrentEvent().eventState = LocalMissionEventState.RUNNING;
        }

        // Exit tick
        if (Math.abs(barometerAltitudeReal - vstickThrottle) < 0.2) {
            localMission.getCurrentEvent().eventState = LocalMissionEventState.FINISHED;
        }
        // Otherwise wait while aircraft changes altitude
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
            case R.id.btn_mission_stop:
                localMission.missionState = LocalMissionState.FINISHED;
                break;
            case R.id.btn_calibrate:
                calibrate(yawGlobal);
                break;

        }
    }


    private void loadMission() {
        //TODO: Make this not hardcoded
        //String urlString = "http://192.168.0.164:8000/mission.json"; // Wifi
        String urlString = "http://192.168.80.121:8000/mission.json"; // Hotspot

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

    public void calibrate(float calibrationAngle) {
        calibratedYaw = calibrationAngle;
        float aR = (float)Math.toRadians(calibratedYaw);
        transformLocalToGlobal = new Matrix3f(
                (float)Math.cos(aR), (float)-Math.sin(aR),0,
                (float)Math.sin(aR), (float)Math.cos(aR),0,
                0,0,1);

        transformGlobalToLocal = new Matrix3f(
                (float)Math.cos(-aR), (float)-Math.sin(-aR),0,
                (float)Math.sin(-aR), (float)Math.cos(-aR),0,
                0,0,1);

        calibratedBarometerAltitude = barometerAltitudeRaw;
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
                transformGlobalToLocal.transform(vstickPitchRollLocal,vstickPitchRollGlobal);
                vstickYawGlobal = vstickYawLocal + calibratedYaw;
                vstickYawGlobal = ensureYawRange(vstickYawGlobal);
                DJISampleApplication.getAircraftInstance()
                        .getFlightController()
                        .sendVirtualStickFlightControlData(new FlightControlData(
                                        vstickPitchRollGlobal.x,
                                        vstickPitchRollGlobal.y,
                                        vstickYawGlobal,
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
        return (null != DJISampleApplication.getProductInstance()) &&
                (null != DJISampleApplication.getProductInstance().getCamera());
    }


    /**
     * Normalizes a yaw heading as necessary to fall within the -180:180 DJI accepted range
     * @param input
     * @return
     */
    private float ensureYawRange(float input) {
        if (input < -180) {
            input += 360;
        }
        if (input > 180) {
            input -= 360;
        }
        return(input);
    }
}
