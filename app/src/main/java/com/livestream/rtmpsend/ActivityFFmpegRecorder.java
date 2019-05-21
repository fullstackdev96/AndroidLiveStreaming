package com.livestream.rtmpsend;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.RelativeLayout;

import org.bytedeco.javacv.FrameRecorder;

import java.io.File;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ActivityFFmpegRecorder extends Activity implements
	OnClickListener, OnTouchListener {

	private final static String CLASS_LABEL = "LiveStream";
	private RelativeLayout topLayout = null;
	private Button btnStop;

	private PowerManager.WakeLock mWakeLock;
	private String strVideoPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "liverecord.mp4";
	private File fileVideoPath = null;
	private Uri uriVideoPath = null;
	private AudioRecordRunnable audioRecordRunnable;
	private Thread audioThread;
	private Camera cameraDevice;
	private CameraView cameraView;
	private Parameters cameraParameters = null;
	private volatile FFmpegFrameRecorder videoRecorder;
	private RecorderThread recorderThread;

	private SavedFrames lastSavedframe = new SavedFrames(null, 0L, false, false);

	private boolean recording = false;
	private boolean isRecordingStarted = false;
	private boolean isRotateVideo = false;
	private boolean isFrontCam = false;
	private boolean isPreviewOn = false;
	private boolean nextEnabled = false;
	volatile boolean runAudioThread = true;
	private boolean isRecordingSaved = false;
	private boolean isFinalizing = false;

	private int currentResolution = Constants.RESOLUTION_MEDIUM_VALUE;
	private int previewWidth = 640;
	private int screenWidth = 480;
	private int previewHeight = 480;
	private int sampleRate = 44100;
	private int defaultCameraId = -1;
	private int defaultScreenResolution = -1;
	private int cameraSelection = 0;
	private int frameRate = 30;
	private int totalRecordingTime = 60000;
	private int minRecordingTime = 3000;

	private long firstTime = 0;
	private long startPauseTime = 0;
	private long totalPauseTime = 0;
	private long pausedTime = 0;
	private long stopPauseTime = 0;
	private long totalTime = 0;

	private volatile long mAudioTimestamp = 0L;
	private long mLastAudioTimestamp = 0L;
	private long frameTime = 0L;
	private long mVideoTimestamp = 0L;
	private volatile long mAudioTimeRecorded;

	private RecorderState currentRecorderState = RecorderState.PRESS;

	private byte[] firstData = null;
	private byte[] bufferByte;
	private byte[] baBuffer;
	private Thread mThread;

	private DeviceOrientationEventListener orientationListener;
	// The degrees of the device rotated clockwise from its natural orientation.
	private int deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
	private Handler mHandler;

	private boolean initSuccess = false;

	private boolean isFirstFrame = true;

	private final ReentrantLock lock = new ReentrantLock();

	public enum RecorderState {
		PRESS(1), RECORDING(2), MINIMUM_RECORDING_REACHED(3), MINIMUM_RECORDED(4), SUCCESS(5);

		private int mIntValue;

		RecorderState(int intValue) {
			mIntValue = intValue;
		}

		int getIntValue() {
			return mIntValue;
		}
	}


	public class AudioRecordRunnable implements Runnable {

		int bufferSize;
		short[] audioData;
		int bufferReadResult;
		private final AudioRecord audioRecord;
		public volatile boolean isInitialized;
		private int mCount = 0;

		private AudioRecordRunnable() {
			bufferSize = AudioRecord.getMinBufferSize(sampleRate,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
			audioData = new short[bufferSize];
		}

		private void record(ShortBuffer shortBuffer) {
			try {
				if (videoRecorder != null) {
					this.mCount += shortBuffer.limit();
					videoRecorder.recordSamples(new Buffer[]{shortBuffer});
				}
			}
			catch (FrameRecorder.Exception localException) {

			}
			return;
		}

		private void updateTimestamp() {
			if (videoRecorder != null) {
				int i = Util.getTimeStampInNsFromSampleCounted(this.mCount);
				if (mAudioTimestamp != i) {
					mAudioTimestamp = i;
					mAudioTimeRecorded = System.nanoTime();
				}
			}
		}

		public void run() {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
			this.isInitialized = false;
			if (audioRecord != null) {
				while (this.audioRecord.getState() == 0) {
					try {
						Thread.sleep(100L);
					}
					catch (InterruptedException localInterruptedException) {
					}
				}
				this.isInitialized = true;
				this.audioRecord.startRecording();
				while (((runAudioThread) || (mVideoTimestamp > mAudioTimestamp))) {
					updateTimestamp();
					bufferReadResult = this.audioRecord.read(audioData, 0, audioData.length);
					if ((bufferReadResult > 0) && ((isRecordingStarted && recording) || (mVideoTimestamp > mAudioTimestamp)))
						record(ShortBuffer.wrap(audioData, 0, bufferReadResult));
				}
				this.audioRecord.stop();
				this.audioRecord.release();
			}
		}
	}


	public class CameraView extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback {

		private SurfaceHolder mHolder;


		public CameraView(Context context, Camera camera) {
			super(context);
			cameraDevice = camera;
			cameraParameters = cameraDevice.getParameters();
			mHolder = getHolder();
			mHolder.addCallback(CameraView.this);
			mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
			cameraDevice.setPreviewCallbackWithBuffer(CameraView.this);
		}


		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			try {
				stopPreview();
				cameraDevice.setPreviewDisplay(holder);
			}
			catch (IOException exception) {
				cameraDevice.release();
				cameraDevice = null;
			}
		}

		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			if (isPreviewOn)
				cameraDevice.stopPreview();
			handleSurfaceChanged();
			startPreview();
			cameraDevice.autoFocus(null);
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			try {
				mHolder.addCallback(null);
				cameraDevice.setPreviewCallback(null);

			}
			catch (RuntimeException e) {
			}
		}

		public void timerRun() {
			Log.d("Timer : ", "Preview Timer");

			lock.lock();

			long frameTimeStamp = 0L;

			if (mAudioTimestamp == 0L && firstTime > 0L) {
				frameTimeStamp = 1000L * (System.currentTimeMillis() - firstTime);
			}
			else if (mLastAudioTimestamp == mAudioTimestamp) {
				frameTimeStamp = mAudioTimestamp + frameTime;
			}
			else {
				long l2 = (System.nanoTime() - mAudioTimeRecorded) / 1000L;
				frameTimeStamp = l2 + mAudioTimestamp;
				mLastAudioTimestamp = mAudioTimestamp;
			}

			if (isRecordingStarted && recording) {

				if (lastSavedframe != null
					&& lastSavedframe.getFrameBytesData() != null) {

					if (isFirstFrame) {
						isFirstFrame = false;
						//firstData = baBuffer.toByteArray();
					}
					totalTime = System.currentTimeMillis() - firstTime - pausedTime - ((long)(1.0 / (double)frameRate) * 1000);
					if (!nextEnabled && totalTime >= minRecordingTime) {
						nextEnabled = true;
					}
					if (nextEnabled && totalTime >= totalRecordingTime) {
						mHandler.sendEmptyMessage(5);
					}
					if (currentRecorderState == RecorderState.RECORDING && totalTime >= minRecordingTime) {
						currentRecorderState = RecorderState.MINIMUM_RECORDING_REACHED;
						mHandler.sendEmptyMessage(2);
					}

					mVideoTimestamp += frameTime;
					if (lastSavedframe.getTimeStamp() > mVideoTimestamp) {
						mVideoTimestamp = lastSavedframe.getTimeStamp();
					}

					recorderThread.putByteData(lastSavedframe);
				}
			}

			lastSavedframe = new SavedFrames(baBuffer, frameTimeStamp, isRotateVideo, isFrontCam);
			cameraDevice.addCallbackBuffer(bufferByte);

			lock.lock();
		}

		public void startPreview() {
			if (!isPreviewOn && cameraDevice != null) {
				isPreviewOn = true;

				cameraDevice.startPreview();

				mThread = new Thread() {
					@Override
					public void run() {
						long timeStart;
						long timeEst;
						long timeFloat = 0;

						for (;;) {
							if (!isPreviewOn)
								break;
							timeStart = Calendar.getInstance().getTime().getTime();
							timerRun();
							timeEst = Calendar.getInstance().getTime().getTime();
							timeEst -= timeStart;

							timeFloat++;

							if (timeFloat > 2) {
								try { sleep(34 - timeEst); } catch (Exception e) {}
								timeFloat = 0;
							}
							else {
								try { sleep(33 - timeEst); } catch (Exception e) {}
							}
						}
					}
				};
				mThread.start();
			}
		}

		public void stopPreview() {
			if (isPreviewOn && cameraDevice != null) {
				isPreviewOn = false;
				cameraDevice.stopPreview();
			}
		}

		@Override
		public void onPreviewFrame(byte[] data, Camera camera) {
			baBuffer = Arrays.copyOf(data, data.length);

		}
	}


	public class DeviceOrientationEventListener
		extends OrientationEventListener {
		public DeviceOrientationEventListener(Context context) {
			super(context);
		}

		@Override
		public void onOrientationChanged(int orientation) {
			// We keep the last known orientation. So if the user first orient
			// the camera then point the camera to floor or sky, we still have
			// the correct orientation.
			if (orientation == ORIENTATION_UNKNOWN) return;
			deviceOrientation = Util.roundOrientation(orientation, deviceOrientation);
			if (deviceOrientation == 0) {
				isRotateVideo = true;
			}
			else {
				isRotateVideo = false;
			}
		}
	}


	@SuppressLint("HandlerLeak")
	public void initHandler() {
		mHandler = new Handler() {
			@Override
			public void dispatchMessage(Message msg) {
				switch (msg.what) {
					case 2:
						if (currentRecorderState == RecorderState.RECORDING) {
						}
						else if (currentRecorderState == RecorderState.MINIMUM_RECORDING_REACHED) {
						}
						else if (currentRecorderState == RecorderState.MINIMUM_RECORDED) {
						}
						else if (currentRecorderState == RecorderState.PRESS) {
						}
						else if (currentRecorderState == RecorderState.SUCCESS) {
						}
						break;
					case 3:
						if (!isRecordingStarted)
							initiateRecording();
						else {
							stopPauseTime = System.currentTimeMillis();
							totalPauseTime = stopPauseTime - startPauseTime - ((long)(1.0 / (double)frameRate) * 1000);
//							pausedTime += totalPauseTime;
						}
						recording = true;
						currentRecorderState = RecorderState.RECORDING;
						mHandler.sendEmptyMessage(2);
						break;
					case 4:
						recording = false;
						startPauseTime = System.currentTimeMillis();
						if (totalTime >= totalRecordingTime) {
							currentRecorderState = RecorderState.SUCCESS;
							mHandler.sendEmptyMessage(2);
						}
						else if (totalTime >= minRecordingTime) {
							currentRecorderState = RecorderState.MINIMUM_RECORDED;
							mHandler.sendEmptyMessage(2);
						}
						else {
							currentRecorderState = RecorderState.PRESS;
							mHandler.sendEmptyMessage(2);
						}
						break;
					case 5:
						currentRecorderState = RecorderState.SUCCESS;
						mHandler.sendEmptyMessage(2);
						break;
					default:
						break;
				}
			}
		};
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_recorder);

		PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
		mWakeLock.acquire();

		DisplayMetrics displaymetrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
		//Find screen dimensions
		screenWidth = displaymetrics.widthPixels;

		orientationListener = new DeviceOrientationEventListener(ActivityFFmpegRecorder.this);

		initHandler();

		initLayout();
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		if (!initSuccess)
			return false;
		return super.dispatchTouchEvent(ev);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mHandler.sendEmptyMessage(2);

		if (orientationListener != null)
			orientationListener.enable();

		if (mWakeLock == null) {
			PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
			mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
			mWakeLock.acquire();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (!isFinalizing)
			finish();
		if (orientationListener != null)
			orientationListener.disable();
		if (mWakeLock != null) {
			mWakeLock.release();
			mWakeLock = null;
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		isRecordingStarted = false;
		runAudioThread = false;

		releaseResources();

		if (cameraView != null) {
			cameraView.stopPreview();
			if (cameraDevice != null) {
				cameraDevice.setPreviewCallback(null);
				cameraDevice.release();
			}
			cameraDevice = null;
		}
		firstData = null;
		cameraDevice = null;
		cameraView = null;
		if (mWakeLock != null) {
			mWakeLock.release();
			mWakeLock = null;
		}
	}

	@Override
	public void onBackPressed() {
		mHandler.removeMessages(3);
		if (recording) {
			recording = false;
			mHandler.removeMessages(4);
			mHandler.sendEmptyMessage(4);
		}

//		if (isRecordingStarted)
//			showCancellDialog();
//		else
		videoTheEnd(false);

		super.onBackPressed();
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {

//		if (!recordFinish) {
//			if (totalTime < totalRecordingTime) {
//				switch (event.getAction()) {
//					case MotionEvent.ACTION_DOWN:
//						if (deviceOrientation == 0) {
//							isRotateVideo = true;
//						}
//						else {
//							isRotateVideo = false;
//						}
//						mHandler.removeMessages(3);
//						mHandler.removeMessages(4);
//						mHandler.sendEmptyMessageDelayed(3, 300);
//						break;
//					case MotionEvent.ACTION_UP:
//						mHandler.removeMessages(3);
//						if (recording) {
//							recording = false;
//							mHandler.removeMessages(4);
//							mHandler.sendEmptyMessage(4);
//						}
//
//						break;
//				}
//			}
//			else {
//
//				recording = false;
//				saveRecording();
//			}
//		}
		return true;
	}

	@Override
	public void onClick(View v) {
		if (v.getId() == R.id.ID_BTN_STOP) {
			if (recording) {
				onBackPressed();
				return;
			}

			if (deviceOrientation == 0) {
				isRotateVideo = true;
			}
			else {
				isRotateVideo = false;
			}
			mHandler.removeMessages(3);
			mHandler.removeMessages(4);
			mHandler.sendEmptyMessageDelayed(3, 300);
			btnStop.setText("STOP");

			return;
		}
	}

	public void initLayout() {
		btnStop = (Button)findViewById(R.id.ID_BTN_STOP);
		btnStop.setOnClickListener(this);

		initCameraLayout();
	}

	@SuppressLint("StaticFieldLeak")
	public void initCameraLayout() {
		new AsyncTask<String, Integer, Boolean>() {

			@Override
			protected Boolean doInBackground(String... params) {
				boolean result = setCamera();
				if (!initSuccess) {
					initVideoRecorder();
					startRecording();
					initSuccess = true;
				}
				return result;
			}

			@Override
			protected void onPostExecute(Boolean result) {
				if (!result || cameraDevice == null) {
					finish();
					return;
				}

				topLayout = (RelativeLayout)findViewById(R.id.ID_VIEW_VIDEO);
				if (topLayout != null && topLayout.getChildCount() > 0)
					topLayout.removeAllViews();

				cameraView = new CameraView(ActivityFFmpegRecorder.this, cameraDevice);

				handleSurfaceChanged();
				if (recorderThread == null) {
					recorderThread = new RecorderThread(videoRecorder, previewWidth, previewHeight);
					recorderThread.start();
				}
				RelativeLayout.LayoutParams layoutParam1 = new RelativeLayout.LayoutParams(screenWidth, (int)(screenWidth * (previewWidth / (previewHeight * 1f))));
				layoutParam1.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
				RelativeLayout.LayoutParams layoutParam2 = new RelativeLayout.LayoutParams(screenWidth, screenWidth);
				layoutParam2.topMargin = screenWidth;

				View view = new View(ActivityFFmpegRecorder.this);
				view.setFocusable(false);
				view.setBackgroundColor(Color.BLACK);
				view.setFocusableInTouchMode(false);

				topLayout.addView(cameraView, layoutParam1);
				//topLayout.addView(view, layoutParam2);

				topLayout.setOnTouchListener(ActivityFFmpegRecorder.this);

				if (cameraSelection == CameraInfo.CAMERA_FACING_FRONT) {
					isFrontCam = true;
				}
				else {
					isFrontCam = false;
				}
			}

		}.execute("start");
	}

	public boolean setCamera() {
		try {
			if (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO) {
				int numberOfCameras = Camera.getNumberOfCameras();
				CameraInfo cameraInfo = new CameraInfo();
				for (int i = 0; i < numberOfCameras; i++) {
					Camera.getCameraInfo(i, cameraInfo);
					if (cameraInfo.facing == cameraSelection) {
						defaultCameraId = i;
					}
				}
			}
			stopPreview();
			if (cameraDevice != null)
				cameraDevice.release();

			if (defaultCameraId >= 0)
				cameraDevice = Camera.open(defaultCameraId);
			else
				cameraDevice = Camera.open();

		}
		catch (Exception e) {
			return false;
		}
		return true;
	}

	public void initVideoRecorder() {
		strVideoPath = Util.createFinalPath(this);

		RecorderParameters recorderParameters = Util.getRecorderParameter(currentResolution);
		sampleRate = recorderParameters.getAudioSamplingRate();
		frameRate = recorderParameters.getVideoFrameRate();
		frameTime = (1000000L / frameRate);

		fileVideoPath = new File(strVideoPath);
		videoRecorder = new FFmpegFrameRecorder(strVideoPath, Constants.OUTPUT_WIDTH, Constants.OUTPUT_HEIGHT, recorderParameters.getAudioChannel());
		videoRecorder.setFormat(recorderParameters.getVideoOutputFormat());
		videoRecorder.setSampleRate(recorderParameters.getAudioSamplingRate());
		videoRecorder.setFrameRate(recorderParameters.getVideoFrameRate());
		videoRecorder.setVideoCodec(recorderParameters.getVideoCodec());
		videoRecorder.setVideoQuality(recorderParameters.getVideoQuality());
		videoRecorder.setAudioQuality(recorderParameters.getVideoQuality());
		videoRecorder.setAudioCodec(recorderParameters.getAudioCodec());
		videoRecorder.setVideoBitrate(recorderParameters.getVideoBitrate());
		videoRecorder.setAudioBitrate(recorderParameters.getAudioBitrate());
		//videoRecorder.setVideoOption(recorderParameters.getAudioBitrate());
		audioRecordRunnable = new AudioRecordRunnable();
		audioThread = new Thread(audioRecordRunnable);
	}

	public void startRecording() {
		try {
			if (videoRecorder != null)
				videoRecorder.start();
			else finish();
			if (audioThread != null)
				audioThread.start();
			else finish();
		}
		catch (FFmpegFrameRecorder.Exception e) {
			e.printStackTrace();
		}
	}

	public void stopPreview() {
		if (isPreviewOn && cameraDevice != null) {
			isPreviewOn = false;
			cameraDevice.stopPreview();
		}
	}

	public void handleSurfaceChanged() {
		if (cameraDevice == null) {
			finish();
			return;
		}
		List<Camera.Size> resolutionList = Util.getResolutionList(cameraDevice);
		if (resolutionList != null && resolutionList.size() > 0) {
			Collections.sort(resolutionList, new Util.ResolutionComparator());
			Camera.Size previewSize = null;
			if (defaultScreenResolution == -1) {
				boolean hasSize = false;
				for (int i = 0; i < resolutionList.size(); i++) {
					Size size = resolutionList.get(i);
					if (size != null && size.width == 640 && size.height == 480) {
						previewSize = size;
						hasSize = true;
						break;
					}
				}
				if (!hasSize) {
					int mediumResolution = resolutionList.size() / 2;
					if (mediumResolution >= resolutionList.size())
						mediumResolution = resolutionList.size() - 1;
					previewSize = resolutionList.get(mediumResolution);
				}
			}
			else {
				if (defaultScreenResolution >= resolutionList.size())
					defaultScreenResolution = resolutionList.size() - 1;
				previewSize = resolutionList.get(defaultScreenResolution);
			}
			if (previewSize != null) {
				previewWidth = previewSize.width;
				previewHeight = previewSize.height;
				cameraParameters.setPreviewSize(previewWidth, previewHeight);
				if (videoRecorder != null) {
					videoRecorder.setImageWidth(previewWidth);
					videoRecorder.setImageHeight(previewHeight);
				}
			}
		}

		bufferByte = new byte[previewWidth * previewHeight * 3 / 2];

		cameraDevice.addCallbackBuffer(bufferByte);

		cameraParameters.setPreviewFrameRate(frameRate);


		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO) {
			cameraDevice.setDisplayOrientation(Util.determineDisplayOrientation(ActivityFFmpegRecorder.this, defaultCameraId));
			List<String> focusModes = cameraParameters.getSupportedFocusModes();
			if (focusModes != null) {

				if (((Build.MODEL.startsWith("GT-I950"))
					|| (Build.MODEL.endsWith("SCH-I959"))
					|| (Build.MODEL.endsWith("MEIZU MX3"))) && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {

					cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
				}
				else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
					cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
				}
				else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
					cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
				}
			}
		}
		else
			cameraDevice.setDisplayOrientation(90);

		// {{ Set Camera FPS
		Point fps = new Point(15000, 15000);
		List<int[]> fpsRanges = cameraParameters.getSupportedPreviewFpsRange();
		fps.x = fpsRanges.get(fpsRanges.size() - 1)[0];
		fps.y = fpsRanges.get(fpsRanges.size() - 1)[1];
		cameraParameters.setPreviewFpsRange(fps.x, fps.y);
//		cameraParameters.setRotation(90);
		// }} Set Camera FPS

		cameraDevice.setParameters(cameraParameters);
	}

	public void videoTheEnd(boolean isSuccess) {
		releaseResources();
		if (fileVideoPath != null && fileVideoPath.exists() && !isSuccess)
			fileVideoPath.delete();

		returnToCaller(isSuccess);
	}

	public void returnToCaller(boolean valid) {
		try {
			setActivityResult(valid);
		}
		catch (Throwable e) {
			e.printStackTrace();
		}
		finally {
			finish();
		}
	}

	public void setActivityResult(boolean valid) {
		Intent resultIntent = new Intent();
		int resultCode;
		if (valid) {
			resultCode = RESULT_OK;
			resultIntent.setData(uriVideoPath);
		}
		else
			resultCode = RESULT_CANCELED;

		setResult(resultCode, resultIntent);
	}

	public void releaseResources() {
		if (recorderThread != null) {
			recorderThread.finish();
		}
		isRecordingSaved = true;
		try {
			if (videoRecorder != null) {
				videoRecorder.stop();
				videoRecorder.release();
			}
		}
		catch (FrameRecorder.Exception e) {
			e.printStackTrace();
		}
		videoRecorder = null;
		lastSavedframe = null;
	}

	public void initiateRecording() {
		firstTime = System.currentTimeMillis();
		isRecordingStarted = true;
		totalPauseTime = 0;
		pausedTime = 0;
	}

}