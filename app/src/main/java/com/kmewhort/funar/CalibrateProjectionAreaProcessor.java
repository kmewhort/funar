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
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static android.bluetooth.BluetoothHidDeviceAppQosSettings.MAX;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static java.lang.Math.sqrt;
import static org.opencv.core.Core.NORM_MINMAX;
import static org.opencv.core.Core.normalize;
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

        return findAndDrawSquares();


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

    private Bitmap findAndDrawSquares() {
        List<MatOfPoint2f> squares2f = findSquares();
        List<MatOfPoint> squares = new ArrayList<>();

        for(int i = 0; i < squares2f.size(); i++) {
            MatOfPoint s = new MatOfPoint();
            squares2f.get(i).convertTo(s, CvType.CV_32S);
            squares.add(s);
        }

        Mat resultMat = new Mat(mRgbMat.height(), mRgbMat.width(), CV_8UC3);
        Imgproc.drawContours(resultMat, squares, -1, new Scalar(0, 255, 0), 1);

        Bitmap resultBmp = Bitmap.createBitmap(mRgbMat.width(), mRgbMat.height(), ARGB_8888);
        Utils.matToBitmap(resultMat, resultBmp);
        return resultBmp;
    }

    // based on Karl Phillip: https://stackoverflow.com/questions/8667818/opencv-c-obj-c-detecting-a-sheet-of-paper-square-detection/14368605#14368605
    private List<MatOfPoint2f> findSquares()
    {
        // another possible solution if this doesn't work:         // - Jeru Luke here: https://stackoverflow.com/questions/7263621/how-to-find-corners-on-a-image-using-opencv

        List<MatOfPoint2f> squares = new ArrayList<MatOfPoint2f>();

        Mat blurredRgb = new Mat();
        medianBlur(mRgbMat, blurredRgb, 9);

        Mat gray8 = new Mat(blurredRgb.height(), blurredRgb.width(), CV_8U);
        Imgproc.cvtColor(blurredRgb, gray8, Imgproc.COLOR_RGB2GRAY);

        // try several threshold levels
        // TODO: just use Canny, or one threshold?
        int THRESHOLD_LEVEL = 2;
        for (int l = 0; l < THRESHOLD_LEVEL; l++)
        {
            Mat thresholded = new Mat();
            // Use Canny instead of zero threshold level!
            // Canny helps to catch squares with gradient shading
            if (l == 0)
            {
                Imgproc.Canny(gray8, thresholded, 10, 20, 3); //

                // Dilate helps to remove potential holes between edge segments
                Imgproc.dilate(thresholded, thresholded, new Mat(), new Point(-1,-1));
            }
            else {
                Imgproc.threshold(gray8, thresholded, (l+1)*255/THRESHOLD_LEVEL, 255, 0);
            }

            List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(thresholded, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            // Test contours
            MatOfPoint2f approx = new MatOfPoint2f();
            for (int i = 0; i < contours.size(); i++)
            {
                // approximate contour with accuracy proportional
                // to the contour perimeter
                MatOfPoint2f contourf = new MatOfPoint2f(contours.get(i).toArray());
                double arclength = Imgproc.arcLength(new MatOfPoint2f(contourf), true);
                Imgproc.approxPolyDP(
                        contourf,
                        approx,
                        arclength*0.02,
                        true);

                // Note: absolute value of an area is used because
                // area may be positive or negative - in accordance with the
                // contour orientation
                if (approx.cols() == 4 &&
                        Math.abs(Imgproc.contourArea(approx)) > 1000) //&& // TODO: is 1000 correct here?
                        //Imgproc.isContourConvex(approx)
                {
                    double maxCosine = 0;

                    for (int j = 2; j < 5; j++)
                    {
                        double cosine = Math.abs(angle(approx.col(j%4), approx.col(j-2), approx.col(j-1)));
                        maxCosine = Math.max(maxCosine, cosine);

                        if (maxCosine < 0.3)
                            squares.add(approx);
                    }
                }
            }
        }

        return squares;
    }

    double angle(Mat pt1, Mat pt2, Mat pt0)
    {
        // TODO: this is weird
        double dx1 = pt1.get(0,0)[0] - pt0.get(0,0)[0];
        double dy1 = pt1.get(1,0)[0] - pt0.get(1,0)[0];
        double dx2 = pt2.get(0,0)[0] - pt0.get(0,0)[0];
        double dy2 = pt2.get(1,0)[0] - pt0.get(1,0)[0];
        return (dx1*dx2 + dy1*dy2)/sqrt((dx1*dx1 + dy1*dy1)*(dx2*dx2 + dy2*dy2) + 1e-10);
    }

}
