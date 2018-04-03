/*
 * Copyright (C) 2011-2015 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.video;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Semaphore;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Stream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;

/** 
 * Don't use this class directly.
 */
public abstract class VideoStream extends MediaStream {

	protected final static String TAG = "VideoStream";

	protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
	protected VideoQuality mQuality = mRequestedQuality.clone(); 
	protected SurfaceHolder.Callback mSurfaceHolderCallback = null;
	protected SurfaceView mSurfaceView = null;
	protected SharedPreferences mSettings = null;
	protected int mVideoEncoder, mCameraId = 0;
	protected int mRequestedOrientation = 0, mOrientation = 0;
	protected Thread mCameraThread;
	protected Looper mCameraLooper;

	protected boolean mCameraOpenedManually = true;
	protected boolean mFlashEnabled = false;
	protected boolean mSurfaceReady = false;
	protected boolean mUnlocked = false;
	protected boolean mPreviewStarted = false;
	protected boolean mUpdated = false;
	
	protected String mMimeType;
	protected int mCameraImageFormat;

	private static final boolean VERBOSE = false;           // lots of logging

	// where to put the output file (note: /sdcard requires WRITE_EXTERNAL_STORAGE permission)
	private static final File OUTPUT_DIR = Environment.getExternalStorageDirectory();

	// parameters for the encoder
	private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
	private static final int FRAME_RATE = 30;               // 30fps
	private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

	// encoder
	private MediaCodec mEncoder;
	private CodecInputSurface mInputSurface;

	// camera state
	protected Camera mCamera;
	private SurfaceTextureManager mStManager;
	/** 
	 * Don't use this class directly
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	@SuppressLint("InlinedApi")
	public VideoStream(int camera) {
		super();
		setCamera(camera);
	}

	/**
	 * Sets the camera that will be used to capture video.
	 * You can call this method at any time and changes will take effect next time you start the stream.
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	public void setCamera(int camera) {
		CameraInfo cameraInfo = new CameraInfo();
		int numberOfCameras = Camera.getNumberOfCameras();
		for (int i=0;i<numberOfCameras;i++) {
			Camera.getCameraInfo(i, cameraInfo);
			if (cameraInfo.facing == camera) {
				mCameraId = i;
				break;
			}
		}
	}

	/**
	 * Returns the id of the camera currently selected. 
	 * Can be either {@link CameraInfo#CAMERA_FACING_BACK} or 
	 * {@link CameraInfo#CAMERA_FACING_FRONT}.
	 */
	public int getCamera() {
		return mCameraId;
	}

	/**
	 * Sets a Surface to show a preview of recorded media (video). 
	 * You can call this method at any time and changes will take effect next time you call {@link #start()}.
	 */
	public synchronized void setSurfaceView(SurfaceView view) {
		mSurfaceView = view;
		if (mSurfaceHolderCallback != null && mSurfaceView != null && mSurfaceView.getHolder() != null) {
			mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
		}
		if (mSurfaceView.getHolder() != null) {
			mSurfaceHolderCallback = new Callback() {
				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					mSurfaceReady = false;
					stopPreview();
					Log.d(TAG,"Surface destroyed !");
				}
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					mSurfaceReady = true;
				}
				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
					Log.d(TAG,"Surface Changed !");
				}
			};
			mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
			mSurfaceReady = true;
		}
	}

	/** 
	 * Sets the orientation of the preview.
	 * @param orientation The orientation of the preview
	 */
	public void setPreviewOrientation(int orientation) {
		mRequestedOrientation = orientation;
		mUpdated = false;
	}
	
	/** 
	 * Sets the configuration of the stream. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param videoQuality Quality of the stream
	 */
	public void setVideoQuality(VideoQuality videoQuality) {
		if (!mRequestedQuality.equals(videoQuality)) {
			mRequestedQuality = videoQuality.clone();
			mUpdated = false;
		}
	}

	/** 
	 * Returns the quality of the stream.  
	 */
	public VideoQuality getVideoQuality() {
		return mRequestedQuality;
	}

	/**
	 * Some data (SPS and PPS params) needs to be stored when {@link #getSessionDescription()} is called 
	 * @param prefs The SharedPreferences that will be used to save SPS and PPS parameters
	 */
	public void setPreferences(SharedPreferences prefs) {
		mSettings = prefs;
	}

	/**
	 * Configures the stream. You need to call this before calling {@link #getSessionDescription()} 
	 * to apply your configuration of the stream.
	 */
	public synchronized void configure() throws IllegalStateException, IOException {
		super.configure();
		mOrientation = mRequestedOrientation;
	}	
	
	/**
	 * Starts the stream.
	 * This will also open the camera and display the preview 
	 * if {@link #startPreview()} has not already been called.
	 */
	public synchronized void start() throws Throwable {
		if (!mPreviewStarted) mCameraOpenedManually = false;
		super.start();
		Log.d(TAG,"Stream configuration: FPS: "+mQuality.framerate+" Width: "+mQuality.resX+" Height: "+mQuality.resY);
	}

	/** Stops the stream. */
	public synchronized void stop() {
		if (mCamera != null) {
			if (mMode == MODE_MEDIACODEC_API) {
				mCamera.setPreviewCallbackWithBuffer(null);
			}
			if (mMode == MODE_MEDIACODEC_API_2) {
				((SurfaceView)mSurfaceView).removeMediaCodecSurface();
			}
			super.stop();
			// We need to restart the preview
			if (!mCameraOpenedManually) {
				destroyCamera();
			} else {
				try {
					startPreview();
				} catch (RuntimeException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public synchronized void startPreview() 
			throws CameraInUseException, 
			InvalidSurfaceException, 
			RuntimeException {
		
		mCameraOpenedManually = true;
		if (!mPreviewStarted) {
			createCamera();
			updateCamera();
		}
	}

	/**
	 * Stops the preview.
	 */
	public synchronized void stopPreview() {
		mCameraOpenedManually = false;
		stop();
	}



	/**
	 * Video encoding is done by a MediaCodec.
	 */
	protected void encodeWithMediaCodec() throws Throwable {
		encodeCameraToMpeg();
	}	

	/**
	 * Returns a description of the stream using SDP. 
	 * This method can only be called after {@link Stream#configure()}.
	 * @throws IllegalStateException Thrown when {@link Stream#configure()} wa not called.
	 */	
	public abstract String getSessionDescription() throws IllegalStateException;

	/**
	 * Opens the camera in a new Looper thread so that the preview callback is not called from the main thread
	 * If an exception is thrown in this Looper thread, we bring it back into the main thread.
	 * @throws RuntimeException Might happen if another app is already using the camera.
	 */
	private void openCamera() throws RuntimeException {
		final Semaphore lock = new Semaphore(0);
		final RuntimeException[] exception = new RuntimeException[1];
		mCameraThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Looper.prepare();
				mCameraLooper = Looper.myLooper();
				try {
					mCamera = Camera.open(mCameraId);
				} catch (RuntimeException e) {
					exception[0] = e;
				} finally {
					lock.release();
					Looper.loop();
				}
			}
		});
		mCameraThread.start();
		lock.acquireUninterruptibly();
		if (exception[0] != null) throw new CameraInUseException(exception[0].getMessage());
	}

	protected synchronized void createCamera() throws RuntimeException {
		if (mSurfaceView == null)
			throw new InvalidSurfaceException("Invalid surface !");
		if (mSurfaceView.getHolder() == null || !mSurfaceReady) 
			throw new InvalidSurfaceException("Invalid surface !");

		if (mCamera == null) {
			openCamera();
			mUpdated = false;
			mUnlocked = false;
			mCamera.setErrorCallback(new Camera.ErrorCallback() {
				@Override
				public void onError(int error, Camera camera) {
					// On some phones when trying to use the camera facing front the media server will die
					// Whether or not this callback may be called really depends on the phone
					if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
						// In this case the application must release the camera and instantiate a new one
						Log.e(TAG,"Media server died !");
						// We don't know in what thread we are so stop needs to be synchronized
						mCameraOpenedManually = false;
						stop();
					} else {
						Log.e(TAG,"Error unknown with the camera: "+error);
					}	
				}
			});

			try {

				// If the phone has a flash, we turn it on/off according to mFlashEnabled
				// setRecordingHint(true) is a very nice optimization if you plane to only use the Camera for recording
				Parameters parameters = mCamera.getParameters();
				if (parameters.getFlashMode()!=null) {
					parameters.setFlashMode(mFlashEnabled?Parameters.FLASH_MODE_TORCH:Parameters.FLASH_MODE_OFF);
				}
				parameters.setRecordingHint(true);
				mCamera.setParameters(parameters);
				mCamera.setDisplayOrientation(mOrientation);

				try {
					if (mMode == MODE_MEDIACODEC_API_2) {
						mSurfaceView.startGLThread();
						mCamera.setPreviewTexture(mSurfaceView.getSurfaceTexture());
					} else {
						mCamera.setPreviewDisplay(mSurfaceView.getHolder());
					}
				} catch (IOException e) {
					throw new InvalidSurfaceException("Invalid surface !");
				}

			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}

		}
	}

	protected synchronized void destroyCamera() {
		if (mCamera != null) {
			if (mStreaming) super.stop();
			lockCamera();
			mCamera.stopPreview();
			try {
				mCamera.release();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage()!=null?e.getMessage():"unknown error");
			}
			mCamera = null;
			mCameraLooper.quit();
			mUnlocked = false;
			mPreviewStarted = false;
		}	
	}

	protected synchronized void updateCamera() throws RuntimeException {
		
		// The camera is already correctly configured
		if (mUpdated) return;
		
		if (mPreviewStarted) {
			mPreviewStarted = false;
			mCamera.stopPreview();
		}

		Parameters parameters = mCamera.getParameters();
		mQuality = VideoQuality.determineClosestSupportedResolution(parameters, mQuality);
		int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);
		
		double ratio = (double)mQuality.resX/(double)mQuality.resY;
		mSurfaceView.requestAspectRatio(ratio);
		
		parameters.setPreviewFormat(mCameraImageFormat);
		parameters.setPreviewSize(mQuality.resX, mQuality.resY);
		parameters.setPreviewFpsRange(max[0], max[1]);

		try {
			mCamera.setParameters(parameters);
			mCamera.setDisplayOrientation(mOrientation);
			mCamera.startPreview();
			mPreviewStarted = true;
			mUpdated = true;
		} catch (RuntimeException e) {
			destroyCamera();
			throw e;
		}
	}

	protected void lockCamera() {
		if (mUnlocked) {
			Log.d(TAG,"Locking camera");
			try {
				mCamera.reconnect();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = false;
		}
	}

	protected void unlockCamera() {
		if (!mUnlocked) {
			Log.d(TAG,"Unlocking camera");
			try {
				mCamera.unlock();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = true;
		}
	}


	/**
	 * Tests encoding of AVC video from Camera input.  The output is saved as an MP4 file.
	 */
	private void encodeCameraToMpeg() {
		// arbitrary but popular values
		int encWidth = 1920;
		int encHeight = 1080;
		int encBitRate = 8000000;      // 8 Mbps
		Log.d(TAG, MIME_TYPE + " output " + encWidth + "x" + encHeight + " @" + encBitRate);

		try {
			prepareCamera(encWidth, encHeight);
			prepareEncoder(encWidth, encHeight, encBitRate);
			mInputSurface.makeCurrent();
			prepareSurfaceTexture();

			mCamera.startPreview();

		}catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			// release everything we grabbed
			releaseCamera();
			releaseEncoder();
			releaseSurfaceTexture();
		}
	}

	/**
	 * Configures Camera for video capture.  Sets mCamera.
	 * <p>
	 * Opens a Camera and sets parameters.  Does not start preview.
	 */
	private void prepareCamera(int encWidth, int encHeight) {
		if (mCamera != null) {
			throw new RuntimeException("camera already initialized");
		}

		Camera.Parameters parms = mCamera.getParameters();

		choosePreviewSize(parms, encWidth, encHeight);
		// leave the frame rate set to default
		mCamera.setParameters(parms);

		Camera.Size size = parms.getPreviewSize();
		Log.d(TAG, "Camera preview size is " + size.width + "x" + size.height);
	}

	/**
	 * Attempts to find a preview size that matches the provided width and height (which
	 * specify the dimensions of the encoded video).  If it fails to find a match it just
	 * uses the default preview size.
	 * <p>
	 * TODO: should do a best-fit match.
	 */
	private static void choosePreviewSize(Camera.Parameters parms, int width, int height) {
		// We should make sure that the requested MPEG size is less than the preferred
		// size, and has the same aspect ratio.
		Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
		if (VERBOSE && ppsfv != null) {
			Log.d(TAG, "Camera preferred preview size for video is " +
					ppsfv.width + "x" + ppsfv.height);
		}

		for (Camera.Size size : parms.getSupportedPreviewSizes()) {
			if (size.width == width && size.height == height) {
				parms.setPreviewSize(width, height);
				return;
			}
		}

		Log.w(TAG, "Unable to set preview size to " + width + "x" + height);
		if (ppsfv != null) {
			parms.setPreviewSize(ppsfv.width, ppsfv.height);
		}
	}

	/**
	 * Stops camera preview, and releases the camera to the system.
	 */
	private void releaseCamera() {
		if (VERBOSE) Log.d(TAG, "releasing camera");
		if (mCamera != null) {
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
	}

	/**
	 * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
	 * associated SurfaceTexture as the Camera's "preview texture".
	 * <p>
	 * Configure the EGL surface that will be used for output before calling here.
	 */
	private void prepareSurfaceTexture() {
		mStManager = new SurfaceTextureManager();
		SurfaceTexture st = mStManager.getSurfaceTexture();
		try {
			mCamera.setPreviewTexture(st);
		} catch (IOException ioe) {
			throw new RuntimeException("setPreviewTexture failed", ioe);
		}
	}

	/**
	 * Releases the SurfaceTexture.
	 */
	private void releaseSurfaceTexture() {
		if (mStManager != null) {
			mStManager.release();
			mStManager = null;
		}
	}

	/**
	 * Configures encoder and muxer state, and prepares the input Surface.  Initializes
	 * mEncoder, mMuxer, mInputSurface, mBufferInfo, mTrackIndex, and mMuxerStarted.
	 */
	private void prepareEncoder(int width, int height, int bitRate) {

		MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

		// Set some properties.  Failing to specify some of these can cause the MediaCodec
		// configure() call to throw an unhelpful exception.
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
		if (VERBOSE) Log.d(TAG, "format: " + format);

		// Create a MediaCodec encoder, and configure it with our format.  Get a Surface
		// we can use for input and wrap it with a class that handles the EGL work.
		//
		// If you want to have two EGL contexts -- one for display, one for recording --
		// you will likely want to defer instantiation of CodecInputSurface until after the
		// "display" EGL context is created, then modify the eglCreateContext call to
		// take eglGetCurrentContext() as the share_context argument.
		try {
			mEncoder = MediaCodec.createByCodecName("OMX.Intel.hw_ve.h264");
		} catch (IOException e) {
			e.printStackTrace();
		}
		mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mInputSurface = new CodecInputSurface(mEncoder.createInputSurface());
		Surface surface = mEncoder.createInputSurface();
		mSurfaceView.addMediaCodecSurface(surface);
		mEncoder.start();

		// Output filename.  Ideally this would use Context.getFilesDir() rather than a
		// hard-coded output directory.
		String outputPath = new File(OUTPUT_DIR,
				"test." + width + "x" + height + ".mp4").toString();
		Log.i(TAG, "Output file is " + outputPath);

		mPacketizer.setInputStream(new MediaCodecInputStream(mEncoder));
		mPacketizer.start();

		mStreaming = true;
	}

	/**
	 * Releases encoder resources.
	 */
	private void releaseEncoder() {
		if (VERBOSE) Log.d(TAG, "releasing encoder objects");
		if (mEncoder != null) {
			mEncoder.stop();
			mEncoder.release();
			mEncoder = null;
		}
		if (mInputSurface != null) {
			mInputSurface.release();
			mInputSurface = null;
		}
	}

	/**
	 * Holds state associated with a Surface used for MediaCodec encoder input.
	 * <p>
	 * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
	 * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
	 * be sent to the video encoder.
	 * <p>
	 * This object owns the Surface -- releasing this will release the Surface too.
	 */
	private static class CodecInputSurface {
		private static final int EGL_RECORDABLE_ANDROID = 0x3142;

		private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
		private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
		private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

		private Surface mSurface;

		/**
		 * Creates a CodecInputSurface from a Surface.
		 */
		public CodecInputSurface(Surface surface) {
			if (surface == null) {
				throw new NullPointerException();
			}
			mSurface = surface;

			eglSetup();
		}

		/**
		 * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
		 */
		private void eglSetup() {
			mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
				throw new RuntimeException("unable to get EGL14 display");
			}
			int[] version = new int[2];
			if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
				throw new RuntimeException("unable to initialize EGL14");
			}

			// Configure EGL for recording and OpenGL ES 2.0.
			int[] attribList = {
					EGL14.EGL_RED_SIZE, 8,
					EGL14.EGL_GREEN_SIZE, 8,
					EGL14.EGL_BLUE_SIZE, 8,
					EGL14.EGL_ALPHA_SIZE, 8,
					EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
					EGL_RECORDABLE_ANDROID, 1,
					EGL14.EGL_NONE
			};
			EGLConfig[] configs = new EGLConfig[1];
			int[] numConfigs = new int[1];
			EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
					numConfigs, 0);
			checkEglError("eglCreateContext RGB888+recordable ES2");

			// Configure context for OpenGL ES 2.0.
			int[] attrib_list = {
					EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
					EGL14.EGL_NONE
			};
			mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
					attrib_list, 0);
			checkEglError("eglCreateContext");

			// Create a window surface, and attach it to the Surface we received.
			int[] surfaceAttribs = {
					EGL14.EGL_NONE
			};
			mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
					surfaceAttribs, 0);
			checkEglError("eglCreateWindowSurface");
		}

		/**
		 * Discards all resources held by this class, notably the EGL context.  Also releases the
		 * Surface that was passed to our constructor.
		 */
		public void release() {
			if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
				EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
						EGL14.EGL_NO_CONTEXT);
				EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
				EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
				EGL14.eglReleaseThread();
				EGL14.eglTerminate(mEGLDisplay);
			}
			mSurface.release();

			mEGLDisplay = EGL14.EGL_NO_DISPLAY;
			mEGLContext = EGL14.EGL_NO_CONTEXT;
			mEGLSurface = EGL14.EGL_NO_SURFACE;

			mSurface = null;
		}

		/**
		 * Makes our EGL context and surface current.
		 */
		public void makeCurrent() {
			EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
			checkEglError("eglMakeCurrent");
		}


		/**
		 * Checks for EGL errors.  Throws an exception if one is found.
		 */
		private void checkEglError(String msg) {
			int error;
			if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
				throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
			}
		}
	}


	/**
	 * Manages a SurfaceTexture.  Creates SurfaceTexture and TextureRender objects, and provides
	 * functions that wait for frames and render them to the current EGL surface.
	 * <p>
	 * The SurfaceTexture can be passed to Camera.setPreviewTexture() to receive camera output.
	 */
	private static class SurfaceTextureManager
			implements SurfaceTexture.OnFrameAvailableListener {
		private SurfaceTexture mSurfaceTexture;
		private VideoStream.STextureRender mTextureRender;

		private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
		private boolean mFrameAvailable;

		/**
		 * Creates instances of TextureRender and SurfaceTexture.
		 */
		public SurfaceTextureManager() {
			mTextureRender = new VideoStream.STextureRender();
			mTextureRender.surfaceCreated();

			if (VERBOSE) Log.d(TAG, "textureID=" + mTextureRender.getTextureId());
			mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());

			// This doesn't work if this object is created on the thread that CTS started for
			// these test cases.
			//
			// The CTS-created thread has a Looper, and the SurfaceTexture constructor will
			// create a Handler that uses it.  The "frame available" message is delivered
			// there, but since we're not a Looper-based thread we'll never see it.  For
			// this to do anything useful, OutputSurface must be created on a thread without
			// a Looper, so that SurfaceTexture uses the main application Looper instead.
			//
			// Java language note: passing "this" out of a constructor is generally unwise,
			// but we should be able to get away with it here.
			mSurfaceTexture.setOnFrameAvailableListener(this);
		}

		public void release() {
			// this causes a bunch of warnings that appear harmless but might confuse someone:
			//  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
			//mSurfaceTexture.release();

			mTextureRender = null;
			mSurfaceTexture = null;
		}

		/**
		 * Returns the SurfaceTexture.
		 */
		public SurfaceTexture getSurfaceTexture() {
			return mSurfaceTexture;
		}

		@Override
		public void onFrameAvailable(SurfaceTexture st) {
			if (VERBOSE) Log.d(TAG, "new frame available");
			synchronized (mFrameSyncObject) {
				if (mFrameAvailable) {
					throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
				}
				mFrameAvailable = true;
				mFrameSyncObject.notifyAll();
			}
		}
	}


	/**
	 * Code for rendering a texture onto a surface using OpenGL ES 2.0.
	 */
	private static class STextureRender {
		private static final int FLOAT_SIZE_BYTES = 4;
		private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
		private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
		private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
		private final float[] mTriangleVerticesData = {
				// X, Y, Z, U, V
				-1.0f, -1.0f, 0, 0.f, 0.f,
				1.0f, -1.0f, 0, 1.f, 0.f,
				-1.0f, 1.0f, 0, 0.f, 1.f,
				1.0f, 1.0f, 0, 1.f, 1.f,
		};

		private FloatBuffer mTriangleVertices;

		private static final String VERTEX_SHADER =
				"uniform mat4 uMVPMatrix;\n" +
						"uniform mat4 uSTMatrix;\n" +
						"attribute vec4 aPosition;\n" +
						"attribute vec4 aTextureCoord;\n" +
						"varying vec2 vTextureCoord;\n" +
						"void main() {\n" +
						"    gl_Position = uMVPMatrix * aPosition;\n" +
						"    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
						"}\n";

		private static final String FRAGMENT_SHADER =
				"#extension GL_OES_EGL_image_external : require\n" +
						"precision mediump float;\n" +      // highp here doesn't seem to matter
						"varying vec2 vTextureCoord;\n" +
						"uniform samplerExternalOES sTexture;\n" +
						"void main() {\n" +
						"    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
						"}\n";

		private float[] mMVPMatrix = new float[16];
		private float[] mSTMatrix = new float[16];

		private int mProgram;
		private int mTextureID = -12345;
		private int muMVPMatrixHandle;
		private int muSTMatrixHandle;
		private int maPositionHandle;
		private int maTextureHandle;

		public STextureRender() {
			mTriangleVertices = ByteBuffer.allocateDirect(
					mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
					.order(ByteOrder.nativeOrder()).asFloatBuffer();
			mTriangleVertices.put(mTriangleVerticesData).position(0);

			Matrix.setIdentityM(mSTMatrix, 0);
		}

		public int getTextureId() {
			return mTextureID;
		}

		public void drawFrame(SurfaceTexture st, MediaCodec mediaCodec) {
			checkGlError("onDrawFrame start");
			st.getTransformMatrix(mSTMatrix);

			// (optional) clear to green so we can see if we're failing to set pixels
			GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
			GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

			GLES20.glUseProgram(mProgram);
			checkGlError("glUseProgram");

			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

			mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
			GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
					TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
			checkGlError("glVertexAttribPointer maPosition");
			GLES20.glEnableVertexAttribArray(maPositionHandle);
			checkGlError("glEnableVertexAttribArray maPositionHandle");

			mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
			GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
					TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
			checkGlError("glVertexAttribPointer maTextureHandle");
			GLES20.glEnableVertexAttribArray(maTextureHandle);
			checkGlError("glEnableVertexAttribArray maTextureHandle");

			Matrix.setIdentityM(mMVPMatrix, 0);
			GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
			GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
			checkGlError("glDrawArrays");

			// IMPORTANT: on some devices, if you are sharing the external texture between two
			// contexts, one context may not see updates to the texture unless you un-bind and
			// re-bind it.  If you're not using shared EGL contexts, you don't need to bind
			// texture 0 here.
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

			ByteBuffer mPixelBuf;
			mPixelBuf = ByteBuffer.allocateDirect(1920 * 1080 * 4);
			mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);

		}

		/**
		 * Initializes GL state.  Call this after the EGL surface has been created and made current.
		 */
		public void surfaceCreated() {
			mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
			if (mProgram == 0) {
				throw new RuntimeException("failed creating program");
			}
			maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
			checkLocation(maPositionHandle, "aPosition");
			maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
			checkLocation(maTextureHandle, "aTextureCoord");

			muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
			checkLocation(muMVPMatrixHandle, "uMVPMatrix");
			muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
			checkLocation(muSTMatrixHandle, "uSTMatrix");

			int[] textures = new int[1];
			GLES20.glGenTextures(1, textures, 0);

			mTextureID = textures[0];
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
			checkGlError("glBindTexture mTextureID");

			GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
					GLES20.GL_NEAREST);
			GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
					GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
					GLES20.GL_CLAMP_TO_EDGE);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
					GLES20.GL_CLAMP_TO_EDGE);
			checkGlError("glTexParameter");
		}

		/**
		 * Replaces the fragment shader.  Pass in null to reset to default.
		 */
		public void changeFragmentShader(String fragmentShader) {
			if (fragmentShader == null) {
				fragmentShader = FRAGMENT_SHADER;
			}
			GLES20.glDeleteProgram(mProgram);
			mProgram = createProgram(VERTEX_SHADER, fragmentShader);
			if (mProgram == 0) {
				throw new RuntimeException("failed creating program");
			}
		}

		private int loadShader(int shaderType, String source) {
			int shader = GLES20.glCreateShader(shaderType);
			checkGlError("glCreateShader type=" + shaderType);
			GLES20.glShaderSource(shader, source);
			GLES20.glCompileShader(shader);
			int[] compiled = new int[1];
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
			if (compiled[0] == 0) {
				Log.e(TAG, "Could not compile shader " + shaderType + ":");
				Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
				GLES20.glDeleteShader(shader);
				shader = 0;
			}
			return shader;
		}

		private int createProgram(String vertexSource, String fragmentSource) {
			int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
			if (vertexShader == 0) {
				return 0;
			}
			int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
			if (pixelShader == 0) {
				return 0;
			}

			int program = GLES20.glCreateProgram();
			if (program == 0) {
				Log.e(TAG, "Could not create program");
			}
			GLES20.glAttachShader(program, vertexShader);
			checkGlError("glAttachShader");
			GLES20.glAttachShader(program, pixelShader);
			checkGlError("glAttachShader");
			GLES20.glLinkProgram(program);
			int[] linkStatus = new int[1];
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
			if (linkStatus[0] != GLES20.GL_TRUE) {
				Log.e(TAG, "Could not link program: ");
				Log.e(TAG, GLES20.glGetProgramInfoLog(program));
				GLES20.glDeleteProgram(program);
				program = 0;
			}
			return program;
		}

		public void checkGlError(String op) {
			int error;
			while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
				Log.e(TAG, op + ": glError " + error);
				throw new RuntimeException(op + ": glError " + error);
			}
		}

		public static void checkLocation(int location, String label) {
			if (location < 0) {
				throw new RuntimeException("Unable to locate '" + label + "' in program");
			}
		}
	}

}
