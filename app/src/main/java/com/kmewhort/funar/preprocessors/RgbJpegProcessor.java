package com.kmewhort.funar.preprocessors;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;

public class RgbJpegProcessor extends ImagePreprocessor {
    protected byte[] mImageData;
    protected Bitmap mRgbBitmap;
    protected Mat mRgbMat;

    public RgbJpegProcessor() {
        super();
    }

    public int requiredInputFormat() {
        return ImageFormat.DEPTH_JPEG;
    }

    public Mat process(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        mImageData = new byte[buffer.remaining()];
        buffer.get(mImageData);
        img.close();

        return process(mImageData);
    }

    public Mat process(byte[] rawImageData) {
        mImageData = rawImageData;
        decodeRgbImage();
        return mRgbMat;
    }

    public byte[] getRawImageData() {
        return mImageData;
    }

    private void decodeRgbImage() {
        mRgbBitmap = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length);

        if(mRgbBitmap == null){
            mRgbMat = null;
            return;
        }

        // TODO: there may be a way to go straight to Mat through OpenCV decode
        mRgbMat = new Mat(mRgbBitmap.getHeight(), mRgbBitmap.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(mRgbBitmap, mRgbMat);
    }
}
