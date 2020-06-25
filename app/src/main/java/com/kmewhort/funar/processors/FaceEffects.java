package com.kmewhort.funar.processors;

import android.graphics.Bitmap;
import android.media.FaceDetector;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static org.opencv.core.CvType.CV_16UC1;
import static org.opencv.core.CvType.CV_8U;

public class FaceEffects {
    private static final int MAX_FACES = 5;

    Mat mMat;
    Bitmap mBitmap;
    FaceDetector mFaceDetector;
    FaceDetector.Face[] mFaces;

    public FaceEffects() {
        mFaceDetector = null;
        mFaces = new FaceDetector.Face[MAX_FACES];
    }

    public Mat process(Mat input) {
        mMat = input;

        // TODO: we end up with an unnecessary conversion back/forth between Mat/Bitmap
        mBitmap = Bitmap.createBitmap(input.width(), input.height(), ARGB_8888);
        Utils.matToBitmap(mMat, mBitmap);

        detectFaces();
        return mMat;
    }

    public void detectFaces() {
        if(mFaceDetector == null) {
            mFaceDetector = new FaceDetector(mMat.width(), mMat.height(), MAX_FACES);
        }

        mFaceDetector.findFaces(mBitmap, mFaces);
    }
}
