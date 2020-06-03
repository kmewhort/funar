package com.kmewhort.funar.preprocessors;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;

import java.nio.ByteBuffer;

import static org.opencv.core.Core.normalize;

public class DepthJpegProcessor extends ImagePreprocessor {
    protected Bitmap mRgbBitmap;
    protected Mat mDepthMat;
    protected byte[] mImageData;

    public DepthJpegProcessor() {
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
        decodeDepthImage();
        return mDepthMat;
    }

    public byte[] getRawImageData() {
        return mImageData;
    }

    private void decodeDepthImage() {
        try {
            ByteBuffer depthJpeg = (new JpegParser(mImageData)).getDepthMap();
            Bitmap bitmap = BitmapFactory.decodeByteArray(depthJpeg.array(), depthJpeg.arrayOffset(), depthJpeg.limit());

            mDepthMat = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3);
            Utils.bitmapToMat(bitmap, mDepthMat);
        } catch (JpegParser.JpegMarkerNotFound jpegMarkerNotFound) {
            jpegMarkerNotFound.printStackTrace();
        } catch (JpegParser.DepthImageNotFound depthImageNotFound) {
            depthImageNotFound.printStackTrace();
        }
    }
}
