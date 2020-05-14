package com.kmewhort.funar;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPIterator;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.properties.XMPPropertyInfo;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.xmp.XmpDirectory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JpegParser {
    public class JpegMarkerNotFound extends Exception {
        public JpegMarkerNotFound(String errorMessage) {
            super(errorMessage);
        }
    }
    public class DepthImageNotFound extends Exception {}

    public JpegParser(byte[] imageData) {
        mImageData = imageData;
    }

    public ByteBuffer getDepthMap() throws JpegMarkerNotFound, DepthImageNotFound {
        parseXmpTrailerMetadata();

        for(int i = 0; i < mTrailers.size(); i++) {
            Trailer t = mTrailers.get(i);
            if(t.dataURI.equals("android/depthmap")) {
                return ByteBuffer.wrap(mImageData, t.start, t.size).slice();
            }
        }

        throw new DepthImageNotFound();
    }

    private static final byte JPEG_MARKER = (byte)(Integer.parseInt("ff",16) & 0xff);
    private static final byte JPEG_START = (byte)(Integer.parseInt("d8",16) & 0xff);
    private static final byte JPEG_END = (byte)(Integer.parseInt("d9",16) & 0xff);

    private byte[] mImageData;
    private class Trailer {
        String dataURI;
        String mime;
        int size;
        int start;
        int end; // exclusive
    }
    private ArrayList<Trailer> mTrailers;


    private void parseXmpTrailerMetadata() throws JpegMarkerNotFound {
        mTrailers = new ArrayList<Trailer>();

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

            Pattern pattern = Pattern.compile("\\/Container\\:Directory\\[(\\d+)\\]\\/Item\\:(.*)");
            while (iterator.hasNext()) {
                XMPPropertyInfo xmpPropertyInfo = (XMPPropertyInfo)iterator.next();
                String path = xmpPropertyInfo.getPath();
                if(path == null) continue;

                Matcher matcher = pattern.matcher(path);
                if(matcher.find()) {
                    int index = Integer.parseInt(matcher.group(1))-1;
                    String key = matcher.group(2);
                    String value = xmpPropertyInfo.getValue();

                    Trailer t = null;
                    if(index > (mTrailers.size()-1)) {
                        t = new Trailer();
                        mTrailers.add(t);
                    } else {
                        t = mTrailers.get(index);
                    }

                    switch(key) {
                        case "Length":
                            t.size = Integer.parseInt(value);
                            break;
                        case "DataURI":
                            t.dataURI = value;
                            break;
                        case "Mime":
                            t.mime = value;
                            break;
                    }
                }

            }
        }

        // calculate start and end indexes by iterating back from the end
        int curPos = mImageData.length;
        for(int i = mTrailers.size()-1; i >= 0; i--) {
            Trailer t = mTrailers.get(i);
            t.end = curPos;
            t.start = curPos-t.size;
            curPos = t.start;

            // verify jpeg position markers
            if(t.mime.equals("image/jpeg")) {
                if (mImageData[t.start] != JPEG_MARKER || mImageData[t.start + 1] != JPEG_START) {
                    throw new JpegMarkerNotFound("No start of file marker");
                }
                if (mImageData[t.end - 2] != JPEG_MARKER || mImageData[t.start - 1] != JPEG_END) {
                    throw new JpegMarkerNotFound("No end of file marker");

                }
            }
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

    // don't actually need this, but leaving it in...helpful for
    // debugging if Google changes the standard again
    private ArrayList<Integer> mStartMarkers;
    private ArrayList<Integer> mEndMarkers;
    private void findStartAndEndMarkers() {
        mStartMarkers = new ArrayList<>();
        mEndMarkers = new ArrayList<>();

        for(int i = 0; i < mImageData.length; i++) {
            if(mImageData[i] == JPEG_MARKER) {
                if(mImageData[i+1] == JPEG_START) {
                    mStartMarkers.add(i);
                } else if(mImageData[i+1] == JPEG_END) {
                    mEndMarkers.add(i);
                }
            }
        }
    }
}
