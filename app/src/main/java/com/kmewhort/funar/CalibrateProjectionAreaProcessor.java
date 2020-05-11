package com.kmewhort.funar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPIterator;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.properties.XMPPropertyInfo;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.xmp.XmpDirectory;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
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
    private byte[] mImageData;
    private Bitmap mRgbBitmap;
    private Mat mRgbMat;

    public Bitmap process(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        mImageData = new byte[buffer.remaining()];
        buffer.get(mImageData);
        mRgbBitmap = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length);

        // TODO: there may be a way to go straight to Mat through OpenCV decode
        mRgbMat = new Mat(mRgbBitmap.getHeight(), mRgbBitmap.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(mRgbBitmap, mRgbMat);

        // 1. Get the RGB image and find the bright quadrilateral
        // TODO: project an eg. green square and just find that
        //return findAndDrawScreen();

        // 2. Get the depth image from depth JPEG
        getXmp();
        return null;

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

    private Bitmap findAndDrawScreen() {
        MatOfPoint2f quad2f = findLargestBrightestQuad();
        if(quad2f == null)
            return null;

        MatOfPoint quad = new MatOfPoint();
        quad2f.convertTo(quad, CvType.CV_32S);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        contours.add(quad);
        Bitmap resultBmp = Bitmap.createBitmap(mRgbMat.width(), mRgbMat.height(), ARGB_8888);
        Imgproc.drawContours(mRgbMat, contours, 0, new Scalar(0, 255, 0), 5);
        Utils.matToBitmap(mRgbMat, resultBmp);
        return resultBmp;
    }

    // based loosely on Karl Phillip: https://stackoverflow.com/questions/8667818/opencv-c-obj-c-detecting-a-sheet-of-paper-square-detection/14368605#14368605
    private MatOfPoint2f findLargestBrightestQuad() {
        // value channel from HSV works best for brightness
        Mat gray8 = this.hsvValueChannel();

        // blur and downsample
        int SIZE_REDUCTION = 4;
        Imgproc.GaussianBlur(gray8, gray8, new Size(31,31), 0);
        Imgproc.resize(gray8, gray8, new Size(gray8.width()/SIZE_REDUCTION,gray8.height()/SIZE_REDUCTION));

        // find the quadrilaterals
        List<MatOfPoint2f> quads = findQuadContours(gray8);

        // find the biggest
        MatOfPoint2f largestSquare = null;
        double largestSquareArea = 0;
        for(int i = 0; i < quads.size(); i++) {
            MatOfPoint2f quad = quads.get(i);
            double area = Math.abs(Imgproc.contourArea(quad));
            if(area > largestSquareArea) {
                largestSquare = quad;
                largestSquareArea = area;
            }
        }

        MatOfPoint2f rescaled = new MatOfPoint2f();
        if(largestSquare != null) {
            Core.multiply(largestSquare, new Scalar(SIZE_REDUCTION, SIZE_REDUCTION), largestSquare);
        }
        return largestSquare;
    }

    private List<MatOfPoint2f> findQuadContours(Mat gray8) {
        List<MatOfPoint2f> result = new ArrayList<MatOfPoint2f>();

        // the projection should be by far the brightest area - use two high thresholds
        int[] thresholds = new int[]{200, 250};
        for(int i = 0; i < thresholds.length; i++) {
            Imgproc.threshold(gray8, gray8, thresholds[i], 255, 0);

            List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(gray8, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // find the contours that are actually quadrilaterals
            for (int j = 0; j < contours.size(); j++) {
                MatOfPoint2f contourf = new MatOfPoint2f(contours.get(j).toArray());

                // simplify the contour (proportional to arc length)
                double arclength = Imgproc.arcLength(new MatOfPoint2f(contourf), true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(
                        contourf,
                        approx,
                        arclength * 0.02,
                        true);

                if (approx.rows() == 4)
                    result.add(approx);
            }
        }
        return result;
    }

    private Mat hsvValueChannel() {
        // convert to hsv-space, then split the channels
        Mat hsv = new Mat(mRgbMat.height(), mRgbMat.width(), CV_8UC3);
        Imgproc.cvtColor(mRgbMat, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> splitMat = new ArrayList<Mat>(3);
        split(hsv,splitMat);
        Mat gray8 = splitMat.get(2);
        return gray8;
    }

    private double angle(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1 * dx2 + dy1 * dy2) / sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10);
    }

    private Bitmap contourDebug2f(List<MatOfPoint2f> contours2f) {
        if(contours2f.size() == 0)
            return null;

        List<MatOfPoint> contours = new ArrayList<>();

        for(int i = 0; i < contours2f.size(); i++) {
            MatOfPoint s = new MatOfPoint();
            contours2f.get(i).convertTo(s, CvType.CV_32S);
            contours.add(s);
        }

        return contourDebug(contours);
    }

    private Bitmap contourDebug(List<MatOfPoint> contours) {
        if(contours.size() == 0)
            return null;

        Bitmap resultBmp = Bitmap.createBitmap(mRgbMat.width(), mRgbMat.height(), ARGB_8888);
        //Imgproc.drawContours(mRgbMat, contours, -1, new Scalar(0, 255, 0), 30);
        for(int i = 0; i < contours.size(); i++) {
            Imgproc.drawContours(mRgbMat, contours, i, new Scalar(0, 255, 0), 30);
        }
        Utils.matToBitmap(mRgbMat, resultBmp);
        return resultBmp;
    }

    private void getXmp() {
        Metadata metadata = getExif();
        Collection<XmpDirectory> xmpDirectories = metadata.getDirectoriesOfType(XmpDirectory.class);
        for (XmpDirectory xmpDirectory : xmpDirectories) {
            XMPMeta xmpMeta = xmpDirectory.getXMPMeta();
            XMPIterator iterator = null;
            try {
                iterator = xmpMeta.iterator();
            } catch (XMPException e) {
                e.printStackTrace();
            }
            int largestStrLength = 0;
            String path = "";
            while (iterator.hasNext()) {
                XMPPropertyInfo xmpPropertyInfo = (XMPPropertyInfo)iterator.next();
                System.out.println(xmpPropertyInfo.getPath() + ":" + xmpPropertyInfo.getValue());
                if(xmpPropertyInfo.getValue() != null && (xmpPropertyInfo.getValue().length() > largestStrLength)) {
                    largestStrLength = xmpPropertyInfo.getValue().length();
                    path = xmpPropertyInfo.getPath();
                }
            }
            path = path + "a";
        }
    }

    private Metadata getExif() {
        Metadata metadata = null;
        try {
            metadata = ImageMetadataReader.readMetadata(
                    new BufferedInputStream(new ByteArrayInputStream(mImageData)),
                    mImageData.length);
        } catch (ImageProcessingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return metadata;
    }
}
