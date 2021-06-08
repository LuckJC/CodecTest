package com.huangchao.codectest;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CodecTest";
    private final static String MIME_TYPE = "video/avc";
    private final static int VIDEO_WIDTH = 1920;
    private final static int VIDEO_HEIGHT = 1080;
    private final static int TIME_INTERNAL = 40;
    private final static int HEAD_OFFSET = 4;
    private final static int readBufferLen = 200000;
    private final static int workBufferLen = readBufferLen * 2;
    Thread readFileThread;
    boolean isInit = false;
    int mCount = 0;
    private File h264File;
    private InputStream is = null;
    private SurfaceView mSurfaceView;
    private MediaCodec mCodec;

    Runnable readRunnable = new Runnable() {
        @Override
        public void run() {
            int workFilledLen = 0;
            byte[] readBuffer = new byte[readBufferLen];
            byte[] workBuffer = new byte[workBufferLen];
            boolean readFlag = true;
            try {
                FileInputStream fs = new FileInputStream(h264File);
                is = new BufferedInputStream(fs);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            while (!Thread.interrupted() && readFlag) {
                try {
                    int length = is.available();
                    if (length > 0) {
                        int count = is.read(readBuffer);
                        if (workFilledLen + count >= workBufferLen) {
                            Log.e(TAG, "invalid data size: " + workFilledLen);
                            workFilledLen = 0;
                        }
                        System.arraycopy(readBuffer, 0, workBuffer,
                                workFilledLen, count);
                        workFilledLen += count;
                        // Find H264 head
                        int nextFrameIndex = findHead(workBuffer, workFilledLen);
                        while (nextFrameIndex > 0) {
                            if (checkHead(workBuffer, 0)) {
                                if (onFrame(workBuffer, 0, nextFrameIndex)) {
                                    //Log.d(TAG, "Loop: workFilledLen: " + workFilledLen + ", nextFrameIndex = " + nextFrameIndex);
                                    byte[] temp = workBuffer;
                                    workBuffer = new byte[workBufferLen];
                                    System.arraycopy(temp, nextFrameIndex, workBuffer,
                                            0, workFilledLen - nextFrameIndex);
                                    workFilledLen -= nextFrameIndex;
                                    nextFrameIndex = findHead(workBuffer, workFilledLen);
                                }
                            } else {
                                Log.e(TAG, "Should never run, skip a frame.");
                                Log.e(TAG, "Error: workFilledLen: " + workFilledLen + ", nextFrameIndex = " + nextFrameIndex);
                                byte[] temp = workBuffer;
                                workBuffer = new byte[workBufferLen];
                                System.arraycopy(temp, nextFrameIndex, workBuffer,
                                        0, workFilledLen - nextFrameIndex);
                                workFilledLen -= nextFrameIndex;
                                nextFrameIndex = findHead(workBuffer, workFilledLen);
                            }
                        }
                    } else {
                        workFilledLen = 0;
                        readFlag = false;
                        // 循环播放
                        //readFileThread = new Thread(readFile);
                        //readFileThread.start();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    static int findHead(byte[] buffer, int len) {
        int i;
        for (i = HEAD_OFFSET; i < len - 3; i++) {
            if (checkHead(buffer, i))
                break;
        }
        if (i == len - 3) {
            if (buffer[i] == 0 && buffer[i + 1] == 0
                    && buffer[i + 2] == 1) {
                return i;
            }
            return 0;
        }
        if (i == HEAD_OFFSET)
            return 0;
        return i;
    }

    static boolean checkHead(byte[] buffer, int offset) {
        // 00 00 00 01
        if (buffer[offset] == 0 && buffer[offset + 1] == 0
                && buffer[offset + 2] == 0 && buffer[3] == 1)
            return true;
        // 00 00 01
        return buffer[offset] == 0 && buffer[offset + 1] == 0
                && buffer[offset + 2] == 1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String h264Path = getExternalFilesDir(Environment.DIRECTORY_MOVIES) + "/tmp.h264";
        h264File = new File(h264Path);
        Log.d(TAG, "h264Path: " + h264Path);

        mSurfaceView = findViewById(R.id.surfaceView);
        Button mReadButton = findViewById(R.id.btn_readfile);
        mReadButton.setOnClickListener(v -> {
            if (h264File.exists()) {
                if (!isInit) {
                    initDecoder();
                    isInit = true;
                }

                if (readFileThread == null) {
                    readFileThread = new Thread(readRunnable);
                    readFileThread.start();
                }
            } else {
                Toast.makeText(getApplicationContext(),
                        "H264 file not found", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        readFileThread.interrupt();
    }

    public void initDecoder() {
        try {
            mCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE,
                    VIDEO_WIDTH, VIDEO_HEIGHT);
            mCodec.configure(mediaFormat, mSurfaceView.getHolder().getSurface(),
                    null, 0);
            mCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean onFrame(byte[] buf, int offset, int length) {
        //Log.d(TAG, "onFrame start");
        int inputBufferIndex = mCodec.dequeueInputBuffer(100);
        //Log.d(TAG, "onFrame index:" + inputBufferIndex);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = mCodec.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();
            inputBuffer.put(buf, offset, length);
            mCodec.queueInputBuffer(inputBufferIndex, 0, length, mCount
                    * TIME_INTERNAL * 1000, 0);
            mCount++;
        } else {
            return false;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mCodec.dequeueOutputBuffer(bufferInfo, 100);
        while (outputBufferIndex >= 0) {
            mCodec.releaseOutputBuffer(outputBufferIndex, true);
            //Log.d(TAG, "releaseOutputBuffer: " + outputBufferIndex);
            outputBufferIndex = mCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
        //Log.d(TAG, "onFrame end");
        return true;
    }
}