package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static android.bluetooth.BluetoothHidDeviceAppQosSettings.MAX;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static java.lang.Math.sqrt;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.normalize;
import static org.opencv.core.Core.split;
import static org.opencv.core.CvType.CV_16UC1;
import static org.opencv.core.CvType.CV_8U;
import static org.opencv.core.CvType.CV_8UC3;
import static org.opencv.imgproc.Imgproc.medianBlur;

public class CalibrateProjectionAreaProcessor implements ImageProcessor {
    private Bitmap mRgbBitmap;
    private Mat mRgbMat;

    public Bitmap process(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        mRgbBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

        // TODO: there may be a way to go straight to Mat through OpenCV decode
        mRgbMat = new Mat(mRgbBitmap.getHeight(), mRgbBitmap.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(mRgbBitmap, mRgbMat);

        //TODO: project an eg. green square and just find that
        return findAndDrawSquare();


        // we want to:
        // 1. Get the RGB image and find the bright quadrilateral
        // - Jeru Luke here: https://stackoverflow.com/questions/7263621/how-to-find-corners-on-a-image-using-opencv
        // - Or Karl Phillip here: https://stackoverflow.com/questions/8667818/opencv-c-obj-c-detecting-a-sheet-of-paper-square-detection/14368605#14368605

        // 2. Get the Depth image that matches from the depth JPEG
        // 3. Find a match for the depth image in the Depth16 image
        // 4. determine the co-ordinates of the depth16 image, which is where we want to trim
        // our depth to

        // useful..?
        // getPerspectiveTransform() https://docs.opencv.org/3.4.10/da/d54/group__imgproc__transform.html#ga15302cbff82bdcddb70158a58b73d981
        // drawChessBoardCorners

        // TODO: get depth; Exif reader -> TAG_XMP -> parse
    }

    public int requiredInputFormat() {
        return ImageFormat.DEPTH_JPEG;
    }

    private Bitmap findAndDrawSquare() {
        MatOfPoint2f square2f = findSquare();
        if(square2f == null)
            return null;

        MatOfPoint square = new MatOfPoint();
        square2f.convertTo(square, CvType.CV_32S);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        contours.add(square);
        Bitmap resultBmp = Bitmap.createBitmap(mRgbMat.width(), mRgbMat.height(), ARGB_8888);
        Imgproc.drawContours(mRgbMat, contours, 0, new Scalar(0, 255, 0), 30);
        Utils.matToBitmap(mRgbMat, resultBmp);
        return resultBmp;
    }

    // based on Karl Phillip: https://stackoverflow.com/questions/8667818/opencv-c-obj-c-detecting-a-sheet-of-paper-square-detection/14368605#14368605
    private MatOfPoint2f findSquare() {
        // another possible solution if this doesn't work: Jeru Luke here: https://stackoverflow.com/questions/7263621/how-to-find-corners-on-a-image-using-opencv
        MatOfPoint2f largestSquare = null;
        double largestSquareArea = 0;

        //Mat gray8 = new Mat(mRgbMat.height(), mRgbMat.width(), CV_8U);
        //Imgproc.cvtColor(mRgbMat, gray8, Imgproc.COLOR_RGB2GRAY);
        //normalize(gray8, gray8, 0, 255, NORM_MINMAX, CV_8U);
        //Imgproc.GaussianBlur(gray8, gray8, new Size(7,7), 0);

        // convert to hsv-space, then split the channels
        Mat hsv = new Mat(mRgbMat.height(), mRgbMat.width(), CV_8UC3);
        Imgproc.cvtColor(mRgbMat, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> splitMat = new ArrayList<Mat>(3);
        split(hsv,splitMat);
        Mat gray8 = splitMat.get(2);
        medianBlur(gray8, gray8, 9);


        // the projection should be by far the brightest area - normalize and set a high threshold
        int[] thresholds = new int[]{200, 250};
        for(int i = 0; i < thresholds.length; i++) {
            Imgproc.threshold(gray8, gray8, thresholds[i], 255, 0);

            //Imgproc.Canny(gray8, gray8, 0, 50, 5);
            // Dilate helps to remove potential holes between edge segments
            //Imgproc.dilate(gray8, gray8, new Mat(), new Point(-1,-1));

            // debug
            Mat rgbMat = new Mat();
            Imgproc.cvtColor(gray8, rgbMat, Imgproc.COLOR_GRAY2RGBA);
            Bitmap resultBmp = Bitmap.createBitmap(rgbMat.width(), rgbMat.height(), ARGB_8888);
            Utils.matToBitmap(rgbMat, resultBmp);

            List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(gray8, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // Test contours
            MatOfPoint2f approx = new MatOfPoint2f();
            for (int j = 0; j < contours.size(); j++) {
                // approximate contour with accuracy proportional
                // to the contour perimeter
                MatOfPoint2f contourf = new MatOfPoint2f(contours.get(j).toArray());

                double arclength = Imgproc.arcLength(new MatOfPoint2f(contourf), true);
                Imgproc.approxPolyDP(
                        contourf,
                        approx,
                        arclength * 0.02,  // initially 0.02
                        true);

                // Note: absolute value of an area is used because
                // area may be positive or negative - in accordance with the
                // contour orientation
                double area = 0;
                if (approx.rows() == 4 &&
                        (area = Math.abs(Imgproc.contourArea(approx))) > 500)
                //Imgproc.isContourConvex(approx)
                {
                    if(area > largestSquareArea) {

                        double maxCosine = 0;

                        /*
                        for (int k = 2; k < 5; k++) {
                            Point[] points = approx.toArray();
                            double cosine = Math.abs(angle(points[k % 4], points[k - 2], points[k - 1]));
                            maxCosine = Math.max(maxCosine, cosine);

                            if (maxCosine < 0.3) {
                                largestSquare = approx;
                                largestSquareArea = area;
                            }
                        }*/

                        //largestSquare = approx;
                        //largestSquareArea = area;
                    }

                }
            }
        }

        return largestSquare;
    }

    double angle(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1 * dx2 + dy1 * dy2) / sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10);
    }

}
