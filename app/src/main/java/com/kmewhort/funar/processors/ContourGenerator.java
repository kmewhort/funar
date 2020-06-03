package com.kmewhort.funar.processors;

import android.media.Image;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import static org.opencv.core.Core.normalize;
import static org.opencv.core.CvType.CV_16UC1;
import static org.opencv.core.CvType.CV_8U;
import static org.opencv.core.CvType.CV_8UC3;

public class ContourGenerator extends ImageProcessor {
    Mat mMat;
    boolean mNormalize;

    public ContourGenerator() {
    }

    public Mat process(Mat input) {
        // convert the 3-channel gray input to gray, normalized
        if(input.channels() != 1) {
            // TODO: use 16 bit?
            mMat = new Mat();
            Imgproc.cvtColor(input, mMat, Imgproc.COLOR_RGB2GRAY);
        } else {
            mMat = input;
        }
        if(mMat.type() == CV_16UC1) {
            // input range is 0 to 8191, but chop off really far away
            Imgproc.threshold(mMat, mMat, 2047, 8191, Imgproc.THRESH_TRUNC);
            //Core.add(mMat, new Scalar(-2047), mMat);
            mMat.convertTo(mMat, CV_8U, 256.0/2047.0);
            //normalize(mMat, mMat, 0, 255, NORM_MINMAX, CV_8U);

        }
        //normalize(mMat, mMat, 0, 255, NORM_MINMAX, CV_8U);

        return contours();
    }

    public Mat process(Image img) {
        // not supported
        return null;
    }


    protected Mat contours() {
        Mat result = new Mat(mMat.height(), mMat.width(), CV_8UC3);

        // start with a colormap
        Imgproc.applyColorMap(mMat, result, Imgproc.COLORMAP_JET);

        // for each spread of 8 between 0 and 256
        for(int i = 0; i < 32; i++) {
            Mat threshold = new Mat();
            Imgproc.threshold(mMat, threshold, i * 8, (i + 1) * 8, 0);

            List<MatOfPoint> contours = new ArrayList();
            Mat hierarchy = new Mat();
            Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

            // draw all contours
            Imgproc.drawContours(result, contours, -1, new Scalar(0, 255, 0), 1);
        }
        return result;
    }
}
