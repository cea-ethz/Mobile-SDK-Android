package com.dji.sdk.sample.demo.localmission;

import android.app.Service;
import android.content.Context;
import android.media.MediaFormat;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.view.PresentableView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.util.Arrays;

import ch.ethz.cea.dca.CameraCalibrator;
import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaFileInfo;
import dji.thirdparty.afinal.core.AsyncTask;

public class ArucoCalibrationView extends RelativeLayout
        implements
        View.OnClickListener,
        PresentableView,
        CameraCalibrator.OnAddFrameListener,
        DJICodecManager.YuvDataCallback {

    private Mat rgb;
    private CameraCalibrator calibrator;

    private Camera camera;

    // Live in-app video feed

    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private DJICodecManager mCodecManager;

    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;

    private int videoViewWidth;
    private int videoViewHeight;

    private long lastupdate;

    private int count;

    private boolean openCVLoaded = false;


    private BaseLoaderCallback loaderCallback;

    public ArucoCalibrationView(Context context) {
        super(context);
        init(context);

        loaderCallback = new BaseLoaderCallback(context){
            @Override
            public void onManagerConnected(int status){
                if(status == LoaderCallbackInterface.SUCCESS){
                    openCVLoaded = true;
                }
                else {
                    super.onManagerConnected(status);
                }
            }
        };

    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_aruco_calibration, this, true);

        initUI();
        //taking hte place of onResume here?
        initPreviewerSurfaceView(context,this);



    }

    private void initUI() {

        findViewById(R.id.btn_aruco_capture).setOnClickListener(this);
        findViewById(R.id.btn_aruco_calibrate).setOnClickListener(this);

        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();

        if(OpenCVLoader.initDebug()) {
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
        else {
            //Toast.makeText(this, getString(R.string.error_native_lib), Toast.LENGTH_LONG).show();
        }


        calibrator = new CameraCalibrator(360, 180);
        calibrator.setOnAddFrameListener(this);

        rgb = new Mat();

        notifyStatusChange();


    }


    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        //mCodecManager.enabledYuvData(false);
        //mCodecManager.setYuvDataCallback(null);

        rgb.release();
        calibrator.release();

        if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
            VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mReceivedVideoDataListener);
        }
    }

    /*
    @Override
    public void onResume(){
        super.onResume();

        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        if(OpenCVLoader.initDebug())
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        else
            Toast.makeText(this, getString(R.string.error_native_lib), Toast.LENGTH_LONG).show();
    }
    */


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
                }
            });
        }


        //Camera camera = DJISampleApplication.getProductInstance().getCamera();
        camera.setSystemStateCallback(new SystemState.Callback() {
            @Override
            public void onUpdate(@NonNull SystemState systemState) {
                if ((!systemState.isShootingSinglePhoto()) && (!systemState.isStoringPhoto())) {
                }
            }
        });
    }







    @Override
    public int getDescription() {
        return R.string.aruco_calibration_view_name;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    @Override
    public void onClick(View v) {
        switch(v.getId()) {
            case R.id.btn_aruco_capture:
                calibrator.addFrame();
                break;

            case R.id.btn_aruco_calibrate:

                break;
        }
    }

    @Override
    public void onAddFrame(boolean added) {
        // Succes or failure feedback if sufficient tags are in frame.
    }


    @Override
    public void onYuvDataReceived(MediaFormat format, final ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
        //DJILog.d(TAG, "onYuvDataReceived " + dataSize);
        if (count++ % 30 == 0 && yuvFrame != null && openCVLoaded == true) {
            final byte[] bytes = new byte[dataSize];
            yuvFrame.get(bytes);
            //DJILog.d(TAG, "onYuvDataReceived2 " + dataSize);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    System.out.println("YUV Received");
                    System.out.println("Bytes : " + bytes.length);
                    System.out.println("Size : " + width + " , " + height);
                    System.out.println(format);
                    // two samples here, it may has other color format.
                    int colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    //byte[] data = new byte[frame.capacity()];
                    //((ByteBuffer) frame.duplicate().clear()).get(data);

                    final byte[] bytesLuminance = Arrays.copyOfRange(bytes,0,(width * height));

                    Mat matGRAY =  new Mat(width, height, CvType.CV_8UC1);
                    matGRAY.put(0,0,bytesLuminance);
                    System.out.println("before redner");
                    //calibrator.render(rgb,matGRAY);
                    System.out.println("after render");

                }
            });
        }
    }




    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private void initPreviewerSurfaceView(Context context, DJICodecManager.YuvDataCallback yuvCallback) {
        System.out.println("AYE");
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        System.out.println(videostreamPreviewSh);
        System.out.println("BEE");
        //Log.d(TAG, "real onSurfaceTextureAvailable");
        //Log.d(TAG, "real onSurfaceTextureAvailable3: width " + videoViewWidth + " height " + videoViewHeight);
        SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                videoViewWidth = videostreamPreviewSf.getWidth();
                videoViewHeight = videostreamPreviewSf.getHeight();
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(context.getApplicationContext(), holder, videoViewWidth,
                            videoViewHeight);
                    mCodecManager.enabledYuvData(true);
                    mCodecManager.setYuvDataCallback(yuvCallback);
                }

            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mCodecManager != null) {
                    mCodecManager.cleanSurface();
                    mCodecManager.destroyCodec();
                    mCodecManager = null;
                }

            }
        };

        videostreamPreviewSh.addCallback(surfaceCallback);
    }

    private void notifyStatusChange() {
        final BaseProduct product = VideoDecodingApplication.getProductInstance();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    //Log.d(TAG, "camera recv video data size: " + size);
                    lastupdate = System.currentTimeMillis();
                }
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
                else {
                    System.out.println("mCodecManager is null >:O");
                }

            }
        };

        Camera mCamera;
        if (null == product || !product.isConnected()) {
            mCamera = null;
            //showToast("Disconnected");
        } else {
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                mCamera = product.getCamera();
                if (mCamera != null) {
                    if (mCamera.isFlatCameraModeSupported()) {
                        mCamera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError djiError) {
                                if(djiError!=null){
                                    //showToast("can't change flat mode of camera, error:" + djiError.getDescription());
                                }
                            }
                        });
                    } else {
                        mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError djiError) {
                                if (djiError != null) {
                                    //showToast("can't change mode of camera, error:" + djiError.getDescription());
                                }
                            }
                        });
                    }
                }

                if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                    VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
                }

            }
        }
    }

}
