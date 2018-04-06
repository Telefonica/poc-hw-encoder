/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
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

package com.telefonica.myapplication2;


import android.graphics.ImageFormat;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import android.service.textservice.SpellCheckerService.Session;
import android.util.Base64;
import android.util.Log;

import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A class for streaming H.264 from the camera of an android device using RTP.
 * You should use a {@link Session} instantiated with {@link SessionBuilder} instead of using this class directly.
 * Call {@link #setDestinationAddress(InetAddress)}, {@link #setDestinationPorts(int)} and {@link #setVideoQuality(VideoQuality)}
 * to configure the stream. You can then call {@link #start()} to start the RTP stream.
 * Call {@link #stop()} to stop the stream.
 */
public class MyH264Stream extends H264VideoStream {

    public final static String TAG = "H264Stream";

    private static final int TEST_U = 160;
    private static final int TEST_V = 200;
    private static final int TEST_Y = 120;

    private MP4Config mConfig;

    /**
     * Constructs the H.264 stream.
     * Uses CAMERA_FACING_BACK by default.
     */
    public MyH264Stream() {
        super();
        mMimeType = "video/avc";
        mCameraImageFormat = ImageFormat.NV21;
        mVideoEncoder = MediaRecorder.VideoEncoder.H264;
        mPacketizer = new H264Packetizer();
    }

    /**
     * Returns a description of the stream using SDP. It can then be included in an SDP file.
     */
    public synchronized String getSessionDescription() throws IllegalStateException {
        if (mConfig == null)
            throw new IllegalStateException("You need to call configure() first !");
        return "m=video " + String.valueOf(getDestinationPorts()[0]) + " RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id=" + mConfig.getProfileLevel() + ";sprop-parameter-sets=" + mConfig.getB64SPS() + "," + mConfig.getB64PPS() + ";\r\n";
    }

    /**
     * Starts the stream.
     * This will also open the camera and display the preview if {@link #startPreview()} has not already been called.
     */
    public synchronized void start() throws IllegalStateException, IOException {
        if (!mStreaming) {
            configure();
            byte[] pps = Base64.decode(mConfig.getB64PPS(), Base64.NO_WRAP);
            byte[] sps = Base64.decode(mConfig.getB64SPS(), Base64.NO_WRAP);
            ((H264Packetizer) mPacketizer).setStreamParameters(pps, sps);
            try {
                super.start();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    @Override
    protected void encodeWithMediaRecorder() throws IOException {

    }

    /**
     * Configures the stream. You need to call this before calling {@link #getSessionDescription()} to apply
     * your configuration of the stream.
     */
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        mMode = MODE_MEDIACODEC_API_2;
        mQuality = mRequestedQuality.clone();
        mConfig = testH264();
    }

    /**
     * Tests if streaming with the given configuration (bit rate, frame rate, resolution) is possible
     * and determines the pps and sps. Should not be called by the UI thread.
     **/
    private MP4Config testH264() throws IllegalStateException, IOException {
        MediaCodec encoder = null;

        MediaCodecInfo selectCodec = selectCodec(H264VideoStream.MIME_TYPE, H264VideoStream.ENCODER_NAME);
        if (selectCodec == null) {
            Log.e(TAG, "Unable to find an appropriate codec for video/avc");
            Log.d(TAG, "releasing codecs");
            return null;
        }
        Log.d(TAG, "found codec: " + selectCodec.getName());
        int selectColorFormat = selectColorFormat(selectCodec, H264VideoStream.MIME_TYPE);


        Log.d(TAG, "found colorFormat: " + selectColorFormat);
        MediaFormat createVideoFormat = MediaFormat.createVideoFormat(H264VideoStream.MIME_TYPE, this.mQuality.resX, this.mQuality.resY);
        createVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectColorFormat);
        createVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, this.mQuality.bitrate);
        createVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, this.mQuality.framerate);
        createVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, H264VideoStream.IFRAME_INTERVAL);


        Log.d(TAG, "format: " + createVideoFormat);
        try {
            encoder = MediaCodec.createByCodecName(selectCodec.getName());
            if (encoder == null) {
                return null;
            }

            encoder.configure(createVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            String[] base64pps_sps = searchSPSandPPS(encoder, this.mQuality.resX, this.mQuality.resY, this.mQuality.framerate, selectColorFormat);
            MP4Config mp4Config = new MP4Config(base64pps_sps[1], base64pps_sps[0]);
            Log.d(TAG, "releasing codecs");

            return mp4Config;

        } finally {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        }
    }


    private String[] searchSPSandPPS(MediaCodec encoder, int width, int height, int framerate, int encoderColorFormat) {
        ByteBuffer[] inputBuffers = encoder.getInputBuffers();
        ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] csd = new byte[128];
        int len = 0, p = 4, q = 4;
        long elapsed = 0, now = timestamp();
        byte[] sps = null, pps = null;
        int generateIndex = 0;

        // The size of a frame of video data, in the formats we handle, is stride*sliceHeight
        // for Y, and (stride/2)*(sliceHeight/2) for each of the Cb and Cr channels.  Application
        // of algebra and assuming that stride==width and sliceHeight==height yields:
        byte[] frameData = new byte[width * height * 3 / 2];

        while (elapsed < 3000000 && (sps == null || pps == null)) {

            int dequeueInputBuffer = encoder.dequeueInputBuffer((long) (1000000 / framerate));
            Log.d(TAG, "inputBufIndex=" + dequeueInputBuffer);
            if (dequeueInputBuffer >= 0) {
                long computePresentationTime = computePresentationTime(generateIndex, framerate);
                generateFrame(generateIndex, encoderColorFormat, frameData, width, height);
                ByteBuffer byteBuffer = inputBuffers[dequeueInputBuffer];
                check(byteBuffer.capacity() >= frameData.length, "The input buffer is not big enough.");
                byteBuffer.clear();
                byteBuffer.put(frameData);
                encoder.queueInputBuffer(dequeueInputBuffer, 0, frameData.length, computePresentationTime, 0);
                Log.d(TAG, "submitted frame " + generateIndex + " to enc");
                generateIndex++;
            } else {
                Log.d(TAG, "input buffer not available");
            }


            // We are looking for the SPS and the PPS here. As always, Android is very inconsistent, I have observed that some
            // encoders will give those parameters through the MediaFormat object (that is the normal behaviour).
            // But some other will not, in that case we try to find a NAL unit of type 7 or 8 in the byte stream outputed by the encoder...
            int index = encoder.dequeueOutputBuffer(info, 1000000 / framerate);

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Recovering SPS and PPS from Media Format");
                // The SPS and PPS shoud be there
                MediaFormat format = encoder.getOutputFormat();
                ByteBuffer spsb = format.getByteBuffer("csd-0");
                ByteBuffer ppsb = format.getByteBuffer("csd-1");
                sps = new byte[spsb.capacity() - 4];
                spsb.position(4);
                spsb.get(sps, 0, sps.length);
                pps = new byte[ppsb.capacity() - 4];
                ppsb.position(4);
                ppsb.get(pps, 0, pps.length);
                break;

            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = encoder.getOutputBuffers();
            } else if (index >= 0) {
                Log.d(TAG, "Recovering SPS and PPS from Output Buffers");
                len = info.size;
                if (len < 128) {
                    outputBuffers[index].get(csd, 0, len);
                    if (len > 0 && csd[0] == 0 && csd[1] == 0 && csd[2] == 0 && csd[3] == 1) {
                        // Parses the SPS and PPS, they could be in two different packets and in a different order
                        //depending on the phone so we don't make any assumption about that
                        while (p < len) {
                            while (!(csd[p + 0] == 0 && csd[p + 1] == 0 && csd[p + 2] == 0 && csd[p + 3] == 1) && p + 3 < len)
                                p++;
                            if (p + 3 >= len) p = len;
                            if ((csd[q] & 0x1F) == 7) {
                                sps = new byte[p - q];
                                System.arraycopy(csd, q, sps, 0, p - q);
                            } else {
                                pps = new byte[p - q];
                                System.arraycopy(csd, q, pps, 0, p - q);
                            }
                            p += 4;
                            q = p;
                        }
                    }
                }
                encoder.releaseOutputBuffer(index, false);
            }

            elapsed = timestamp() - now;
        }

        check(pps != null & sps != null, "Could not determine the SPS & PPS.");

        return new String[]{Base64.encodeToString(pps, 0, pps.length, Base64.NO_WRAP),
                Base64.encodeToString(sps, 0, sps.length, Base64.NO_WRAP)};
    }


    /**
     * Generates data for frame N into the supplied buffer.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     *   0 1 2 3
     *   7 6 5 4
     * </pre>
     * We draw one of the eight rectangles and leave the rest set to the zero-fill color.
     */
    private void generateFrame(int frameIndex, int colorFormat, byte[] frameData,
                               int width, int height) {
        final int HALF_WIDTH = width / 2;
        boolean semiPlanar = isSemiPlanarYUV(colorFormat);

        // Set to zero.  In YUV this is a dull green.
        Arrays.fill(frameData, (byte) 0);

        int startX, startY, countX, countY;

        frameIndex %= 8;
        //frameIndex = (frameIndex / 8) % 8;    // use this instead for debug -- easier to see
        if (frameIndex < 4) {
            startX = frameIndex * (width / 4);
            startY = 0;
        } else {
            startX = (7 - frameIndex) * (width / 4);
            startY = height / 2;
        }

        for (int y = startY + (height / 2) - 1; y >= startY; --y) {
            for (int x = startX + (width / 4) - 1; x >= startX; --x) {
                if (semiPlanar) {
                    // full-size Y, followed by UV pairs at half resolution
                    // e.g. Nexus 4 OMX.qcom.video.encoder.avc COLOR_FormatYUV420SemiPlanar
                    // e.g. Galaxy Nexus OMX.TI.DUCATI1.VIDEO.H264E
                    //        OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
                    frameData[y * width + x] = (byte) TEST_Y;
                    if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                        frameData[width * height + y * HALF_WIDTH + x] = (byte) TEST_U;
                        frameData[width * height + y * HALF_WIDTH + x + 1] = (byte) TEST_V;
                    }
                } else {
                    // full-size Y, followed by quarter-size U and quarter-size V
                    // e.g. Nexus 10 OMX.Exynos.AVC.Encoder COLOR_FormatYUV420Planar
                    // e.g. Nexus 7 OMX.Nvidia.h264.encoder COLOR_FormatYUV420Planar
                    frameData[y * width + x] = (byte) TEST_Y;
                    if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                        frameData[width * height + (y / 2) * HALF_WIDTH + (x / 2)] = (byte) TEST_U;
                        frameData[width * height + HALF_WIDTH * (height / 2) +
                                (y / 2) * HALF_WIDTH + (x / 2)] = (byte) TEST_V;
                    }
                }
            }
        }
    }

    /**
     * Returns true if the specified color format is semi-planar YUV.  Throws an exception
     * if the color format is not recognized (e.g. not YUV).
     */
    private static boolean isSemiPlanarYUV(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                return false;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                throw new RuntimeException("unknown format " + colorFormat);
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private static MediaCodecInfo selectCodec(String mimeType, String encoderName) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            if (!codecInfo.getName().equals(encoderName)) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }

    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }


    private void check(boolean cond, String message) {
        if (!cond) {
            Log.e(TAG, message);
            throw new IllegalStateException(message);
        }
    }

    private long timestamp() {
        return System.nanoTime() / 1000;
    }

    private static long computePresentationTime(int frameIndex, int framerate) {
        return 132 + frameIndex * 1000000 / framerate;
    }


}
