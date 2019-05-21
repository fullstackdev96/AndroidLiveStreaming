package com.livestream.rtmpsend;


import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore.Video;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.io.File;
import java.util.Comparator;
import java.util.List;

public class Util {

	public static ContentValues videoContentValues = null;
	// Orientation hysteresis amount used in rounding, in degrees
	public static final int ORIENTATION_HYSTERESIS = 5;


	public static class ResolutionComparator implements Comparator<Camera.Size> {
		@Override
		public int compare(Camera.Size size1, Camera.Size size2) {
			if (size1.height != size2.height)
				return size1.height - size2.height;
			else
				return size1.width - size2.width;
		}
	}


	public static int determineDisplayOrientation(Activity activity, int defaultCameraId) {
		int displayOrientation = 0;
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO) {
			CameraInfo cameraInfo = new CameraInfo();
			Camera.getCameraInfo(defaultCameraId, cameraInfo);

			int degrees = getRotationAngle(activity);

			if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
				displayOrientation = (cameraInfo.orientation + degrees) % 360;
				displayOrientation = (360 - displayOrientation) % 360;
			}
			else {
				displayOrientation = (cameraInfo.orientation - degrees + 360) % 360;
			}
		}
		return displayOrientation;
	}

	public static int getRotationAngle(Activity activity) {
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		int degrees = 0;
		switch (rotation) {
			case Surface.ROTATION_0:
				degrees = 0;
				break;
			case Surface.ROTATION_90:
				degrees = 90;
				break;
			case Surface.ROTATION_180:
				degrees = 180;
				break;
			case Surface.ROTATION_270:
				degrees = 270;
				break;
		}
		return degrees;
	}

	public static String createFinalPath(Context context) {
		long dateTaken = System.currentTimeMillis();
		String title = Constants.FILE_START_NAME + dateTaken;
		String filename = title + Constants.VIDEO_EXTENSION;
		String filePath = genrateFilePath(context, String.valueOf(dateTaken), true, null);

		ContentValues values = new ContentValues(7);
		values.put(Video.Media.TITLE, title);
		values.put(Video.Media.DISPLAY_NAME, filename);
		values.put(Video.Media.DATE_TAKEN, dateTaken);
		values.put(Video.Media.MIME_TYPE, "video/3gpp");
		values.put(Video.Media.DATA, filePath);
		videoContentValues = values;

		return filePath;
	}

	public static String genrateFilePath(Context context, String uniqueId, boolean isFinalPath, File tempFolderPath) {
		String fileName = Constants.FILE_START_NAME + uniqueId + Constants.VIDEO_EXTENSION;
		String dirPath = Environment.getExternalStorageDirectory() + "/Android/data/" + context.getPackageName() + Constants.VIDEO_FOLDER;
		if (isFinalPath) {
			File file = new File(dirPath);
			if (!file.exists() || !file.isDirectory())
				file.mkdirs();
		}
		else
			dirPath = tempFolderPath.getAbsolutePath();
		String filePath = dirPath + "/" + fileName;
		return filePath;
	}

	public static List<Camera.Size> getResolutionList(Camera camera) {
		Parameters parameters = camera.getParameters();
		List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
		return previewSizes;
	}

	public static RecorderParameters getRecorderParameter(int currentResolution) {
		RecorderParameters parameters = new RecorderParameters();
		if (currentResolution == Constants.RESOLUTION_HIGH_VALUE) {
			parameters.setAudioBitrate(128000);
			parameters.setVideoQuality(0);
		}
		else if (currentResolution == Constants.RESOLUTION_MEDIUM_VALUE) {
			parameters.setAudioBitrate(128000);
			parameters.setVideoQuality(5);
		}
		else if (currentResolution == Constants.RESOLUTION_LOW_VALUE) {
			parameters.setAudioBitrate(96000);
			parameters.setVideoQuality(20);
		}
		return parameters;
	}

	public static int getTimeStampInNsFromSampleCounted(int paramInt) {
		return (int)(paramInt / 0.0441D);
	}

	public static int roundOrientation(int orientation, int orientationHistory) {
		boolean changeOrientation = false;
		if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
			changeOrientation = true;
		}
		else {
			int dist = Math.abs(orientation - orientationHistory);
			dist = Math.min(dist, 360 - dist);
			changeOrientation = (dist >= 45 + ORIENTATION_HYSTERESIS);
		}
		if (changeOrientation) {
			return ((orientation + 45) / 90 * 90) % 360;
		}
		return orientationHistory;
	}

}
