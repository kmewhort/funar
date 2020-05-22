package com.kmewhort.funar;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.CvType.CV_8U;
import static org.opencv.core.CvType.CV_8UC3;

public class ContourGenerator {
    Mat mMat;
    boolean mNormalize;

    ContourGenerator() {
    }

    Mat process(Mat gray3Input) {
       // convert the 3-channel gray input to gray, normalized
        mMat = new Mat();
        // TODO: use 16 bit?
        Imgproc.cvtColor(gray3Input, mMat, Imgproc.COLOR_GRAY2RGB);
        normalize(mMat, mMat, 0, 255, NORM_MINMAX, CV_8U);

        return contours();
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
