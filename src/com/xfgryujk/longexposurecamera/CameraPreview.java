package com.xfgryujk.longexposurecamera;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.Toast;

public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback, OnClickListener {
	private static final String TAG = "CameraPreview";
	
	protected SurfaceHolder mHolder;
	protected MainActivity mActivity;
	protected Camera mCamera;
	protected int mPictureWidth, mPictureHeight;
	
	protected volatile boolean mIsExposing = false;
	
	/** YUV */
	protected byte[] mPreviewData;
	/** R1 G1 B1 R2 G2 B2 ... */
	protected int[] mPictureData;
	protected int mFrameCount;
	
	Bitmap mResultBitmap;
	
	
	public CameraPreview(Context context) {
		super(context);
		mActivity = (MainActivity)context;
		mHolder = getHolder();
		mHolder.addCallback(this);
	}
	
	public CameraPreview(Context context, AttributeSet attrs) {
		super(context, attrs);
		mActivity = (MainActivity)context;
		mHolder = getHolder();
		mHolder.addCallback(this);
	}
	
	public Camera getCamera() {
		return mCamera;
	}
	
	public boolean isExposing() {
		return mIsExposing;
	}
	
	@Override
	public void surfaceChanged(SurfaceHolder _holder, int format, int width, int height) {
		Log.i(TAG, "surfaceChanged()");
	}
	
	/** Reset camera */
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.i(TAG, "surfaceCreated()");
		resetCamera();
	}
	
	/** Release camera */
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(TAG, "surfaceDestroyed()");
		releaseCamera();
	}
	
	private void resize(int centreX, int centreY, int maxWidth, int maxHeight) {
		FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)getLayoutParams();
		float scale1  = (float)maxWidth / (float)mPictureWidth;
		float scale2  = (float)maxHeight / (float)mPictureHeight;
		float scale   = scale1 < scale2 ? scale1 : scale2;
		lp.width      = (int)(mPictureWidth * scale);
		lp.height     = (int)(mPictureHeight * scale);
		lp.leftMargin = centreX - lp.width / 2;
		lp.topMargin  = centreY - lp.height / 2;
		setLayoutParams(lp);
	}

	/** Open camera, set parameters */
	public void resetCamera() {
		releaseCamera();
		
		// Open camera
		try {
		mCamera = Camera.open();
		} catch (Exception e) {
			e.printStackTrace();
			mCamera = null;
		}
		if(mCamera == null)
		{
			new AlertDialog.Builder(getContext())
				.setTitle(getResources().getString(R.string.error))
				.setMessage(getResources().getString(R.string.failed_to_open_camera))
				.setPositiveButton(getResources().getString(R.string.ok),
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							mActivity.finish();
						}
					}
				)
				.show();
			return;
		}
		
		// Set parameters
		Camera.Parameters params = mCamera.getParameters();
		
		params.setExposureCompensation(SettingsManager.mEV);
		params.setWhiteBalance(SettingsManager.mWhiteBalance);
		List<Camera.Size> sizes = params.getSupportedPreviewSizes();
		mPictureWidth  = sizes.get(SettingsManager.mResolution).width;
		mPictureHeight = sizes.get(SettingsManager.mResolution).height;
		params.setPreviewSize(mPictureWidth, mPictureHeight);
		params.set("iso", SettingsManager.mISO);
		params.set("iso-speed", SettingsManager.mISO);
		params.set("nv-picture-iso", SettingsManager.mISO);
		
		Log.i(TAG, "EV " + SettingsManager.mEV);
		Log.i(TAG, "white balance " + SettingsManager.mWhiteBalance);
		Log.i(TAG, "preview size " + mPictureWidth + " * " + mPictureHeight);
		Log.i(TAG, "ISO " + SettingsManager.mISO);

		mCamera.setParameters(params);
		
		// Resize
		if(mIsExposing)
			resize(120, 120, 200, 200);
		else
		{
			DisplayMetrics dm = new DisplayMetrics();
			mActivity.getWindowManager().getDefaultDisplay().getMetrics(dm);
			resize(dm.widthPixels / 2, dm.heightPixels / 2, dm.widthPixels, dm.heightPixels);
		}
		
		// Set preview display
		try {
			mCamera.setPreviewDisplay(mHolder);
		} catch(IOException e) {
			e.printStackTrace();
		}
		
		// Set preview callback
		mCamera.setPreviewCallback(new PreviewCallback() {
            public void onPreviewFrame(byte[] data, Camera camera) {
            	if(mIsExposing)
            	{
            		synchronized (CameraPreview.this)
            		{
            			mPreviewData = data;
            			CameraPreview.this.notify();
                    }
            	}
            }
        });
        
        // Start preview
        mCamera.startPreview();
	}
	
	public void releaseCamera() {
		if(mCamera != null)
		{
			mCamera.stopPreview();
			mCamera.setPreviewCallback(null);
			mCamera.release();
			mCamera = null;
		}
	}
	
	/** Auto focus */
	@Override
	public void onClick(View view) { 
		if(!mIsExposing)
			mCamera.autoFocus(null);
	}
	
	/** Start or stop exposing */
	public void onShutterClick() {
		if(mIsExposing)
		{
			Log.i(TAG, "Stop exposing");
			mIsExposing = false;
			// Wait for exposing threads
			mActivity.mButtonShutter.setVisibility(INVISIBLE);
    		synchronized (this)
    		{
    			notifyAll();
    		}
		}
		else
		{
			Log.i(TAG, "Start exposing");
			mPictureData    = new int[mPictureWidth * mPictureHeight * 3];
			mResultBitmap   = Bitmap.createBitmap(mPictureWidth, mPictureHeight, Config.RGB_565);
			mActivity.mResultPreview.setImageBitmap(mResultBitmap);
			mFrameCount     = 0;
			
			mLastFPSTime    = System.currentTimeMillis();
			mLastFrameCount = 0;
			
			// Start exposing threads
			mIsExposing     = true;
	        for(int i = 0; i < mMaxThreadCount; i++)
		        (new Thread(new ExposingThread())).start();
	        
	        mActivity.mResultPreview.setVisibility(VISIBLE);
	        mActivity.mOutputText.setVisibility(VISIBLE);
	        mActivity.mButtonSetting.setVisibility(INVISIBLE);
			resize(120, 120, 200, 200);
		}
	}
	
	protected static final int mMaxThreadCount = Runtime.getRuntime().availableProcessors();
	protected int mThreadCount = 0;
	protected class ExposingThread implements Runnable {
		@SuppressLint("SimpleDateFormat")
		@Override
		public void run() {
			long id = Thread.currentThread().getId();
			synchronized (CameraPreview.this) {
				mThreadCount++;
				Log.i(TAG, id + " starts, mThreadCount = " + mThreadCount);
			}
			/** YUV */
			byte[] previewData;
			/** RGB1 RGB2 ... */
			int[] previewRGBData = new int[mPictureWidth * mPictureHeight];
			int frameCount;
			
			while(mIsExposing)
			{
		        // Get preview data in RGB
				synchronized (CameraPreview.this) {
					//Log.i(TAG, id + " is getting preview data");
		            try {
		            	CameraPreview.this.wait();
		            } catch(InterruptedException e) {
		                e.printStackTrace();
		            }
		            if(!mIsExposing && mFrameCount > 0)
		            	break;
		            
		            frameCount = ++mFrameCount;
		            previewData  = mPreviewData;
		        }
				//Log.i(TAG, id + " is decoding");
		        decodeYUV420SP(previewRGBData, previewData, mPictureWidth, mPictureHeight);
				
				// Blend pictures and show
				int[] data;
				synchronized (mPictureData) {
					//Log.i(TAG, id + " is blending, frameCount = " + frameCount);
			        data = mPictureBlender[SettingsManager.mBlendingMode].blend(previewRGBData, frameCount);
				}
				synchronized (mResultBitmap) {
					//Log.i(TAG, id + " is setting pixels");
					mResultBitmap.setPixels(data, 0, mPictureWidth, 0, 0, mPictureWidth, mPictureHeight);
					if(!mHandler.hasMessages(MSG_UPDATE_PREVIEW))
						mHandler.sendEmptyMessage(MSG_UPDATE_PREVIEW);
				}
			}
			synchronized (CameraPreview.this) {
				Log.i(TAG, id + " is exiting, mThreadCount = " + mThreadCount);
				if(--mThreadCount > 0) // Is not the last thread
					return;
			}
			previewData    = null;
			previewRGBData = null;
			
			// Exposing finish
			mHandler.sendEmptyMessage(MSG_EXPOSING_FINISH);

			// Save the picture
			try {
	        	Bundle bundle = new Bundle();
	        	Message msg = new Message();
	        	msg.what = MSG_TOAST;
	        	
	        	// Create file
	            File dir = new File(SettingsManager.mPath);
	            if(!dir.exists())
	                if(!dir.mkdirs()) {
	                    bundle.putString("msg", getResources().getString(R.string.failed_to_create_directory));
	                	msg.setData(bundle);
	                	mHandler.sendMessage(msg);
	                    return;
	                }
	            File file = new File(SettingsManager.mPath + "/" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg");
	            
	            // Write file
	            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));

	            if(mResultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos))
	            {
		            bos.flush();
		            bundle.putString("msg", getResources().getString(R.string.saved_to) + " " + file.getPath());
	            }
	            else
	            	bundle.putString("msg", getResources().getString(R.string.failed_to_write_file) + file.getPath());
	        	msg.setData(bundle);
	        	mHandler.sendMessageDelayed(msg, 2000);

	            bos.close();
	            
	            // Restore
	        	mHandler.sendEmptyMessageDelayed(MSG_RESTORE, 2000);
	        } catch(Exception e) {
	            e.printStackTrace();
	        }
		}
	}
	
	protected static final int MSG_UPDATE_PREVIEW = 1;
	protected static final int MSG_EXPOSING_FINISH = 2;
	protected static final int MSG_RESTORE = 3;
	protected static final int MSG_TOAST = 4;
	private long mLastFPSTime;
	private int mLastFrameCount;
	private String mFPSString = "";
	@SuppressLint("HandlerLeak")
	protected Handler mHandler = new Handler() {
		@Override
        public void handleMessage(Message msg) {
			switch(msg.what)
			{
			case MSG_UPDATE_PREVIEW:
				long time = System.currentTimeMillis();
				if(time - mLastFPSTime >= 3000) {
					mFPSString = String.format("  %.2fFPS", 
							(float)(mFrameCount - mLastFrameCount) / (float)(time - mLastFPSTime) * 1000);
					mLastFPSTime    = time;
					mLastFrameCount = mFrameCount;
				}
				mActivity.mOutputText.setText(Integer.toString(mFrameCount) + mFPSString);
				synchronized (mResultBitmap) {
					mActivity.mResultPreview.invalidate();
				}
				break;
				
			case MSG_EXPOSING_FINISH:
				// Preview
				CameraPreview.this.setVisibility(INVISIBLE);
		        mActivity.mOutputText.setVisibility(INVISIBLE);
		        mFPSString = "";
		        mActivity.mOutputText.setText("");
				
				// Resize
				DisplayMetrics dm = new DisplayMetrics();
				mActivity.getWindowManager().getDefaultDisplay().getMetrics(dm);
				resize(dm.widthPixels / 2, dm.heightPixels / 2, dm.widthPixels, dm.heightPixels);
				break;
				
			case MSG_RESTORE:
				CameraPreview.this.setVisibility(VISIBLE);
				mActivity.mResultPreview.setVisibility(INVISIBLE);
				mActivity.mResultPreview.setImageBitmap(null);
		        mActivity.mButtonSetting.setVisibility(VISIBLE);
				mActivity.mButtonShutter.setVisibility(VISIBLE);
				
				// Release
				mPreviewData     = null;
				mPictureData     = null;
				mResultBitmap.recycle();
				mResultBitmap    = null;
				break;
				
			case MSG_TOAST:
				Toast.makeText(getContext(), msg.getData().getString("msg"), 
	            		Toast.LENGTH_SHORT).show();
				break;
			}
		}
	};
	
	protected interface PictureBlender {
		/** Called by exposing thread */
		public int[] blend(int[] previewRGBData, int frameCount);
	}
	protected final PictureBlender[] mPictureBlender = {
		// Average
		new PictureBlender() {
			@Override
			public int[] blend(int[] previewRGBData, int frameCount) {
				return blendAverage(mPictureWidth, mPictureHeight, mPictureData, previewRGBData, frameCount);
			}
		},

		// Max
		new PictureBlender() {
			@Override
			public int[] blend(int[] previewRGBData, int frameCount) {
				return blendMax(mPictureWidth, mPictureHeight, mPictureData, previewRGBData, frameCount);
			}
		},

		// Screen
		new PictureBlender() {
			@Override
			public int[] blend(int[] previewRGBData, int frameCount) {
				return blendScreen(mPictureWidth, mPictureHeight, mPictureData, previewRGBData, frameCount);
			}
		}
	};

	
	static {
		System.loadLibrary("ImageJni");
	}
	
	protected static final native void decodeYUV420SP(int[] rgb, final byte[] yuv420sp, int width, int height);
	protected static final native int[] blendAverage(int width, int height, int[] mPictureData, final int[] previewRGBData, int frameCount);
	protected static final native int[] blendMax(int width, int height, int[] mPictureData, final int[] previewRGBData, int frameCount);
	protected static final native int[] blendScreen(int width, int height, int[] mPictureData, final int[] previewRGBData, int frameCount);
}
