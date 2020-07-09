package com.kmewhort.funar.preprocessors;

import android.media.Image;

import com.kmewhort.funar.processors.ImageProcessor;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;

public abstract class ImagePreprocessor extends ImageProcessor {
    private boolean mAutoCallibrate;
    private MatOfPoint2f mCallibration;

    public ImagePreprocessor() {
        super();
        mAutoCallibrate = false;
    }

    public Mat process(Mat mat) {
        return null;
    }

    abstract public Mat process(Image img);

    abstract public int requiredInputFormat();

    public void recallibrate() { }

    public boolean isCallibrated() {
        return true;
    }
    public void setAutoCallibrate(boolean enable) {
        mAutoCallibrate = enable;
    }

    public boolean getAutoCallibrate() {
        return mAutoCallibrate;
    }

    public MatOfPoint2f getCallibration() {
        return mCallibration;
    }

    public void setCallibration(MatOfPoint2f callib) {
        mCallibration = callib;
    }

    public boolean supportsDepthCallibration() {
        return false;
    }

    public double getCallibratedMinDepth() {
        return 0;
    }

    public void setCallibratedMinDepth(double depth) {
    }

    public double getCallibratedMaxDepth() {
        return 0;
    }

    public void setCallibratedMaxDepth(double depth) {
    }
}
