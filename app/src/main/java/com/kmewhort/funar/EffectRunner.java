package com.kmewhort.funar;

import android.media.Image;

import com.kmewhort.funar.preprocessors.DepthJpegProcessor;
import com.kmewhort.funar.preprocessors.ImagePreprocessor;
import com.kmewhort.funar.preprocessors.ProjectionAreaProcessor;
import com.kmewhort.funar.processors.ContourGenerator;
import com.kmewhort.funar.processors.ImageProcessor;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;

import java.util.ArrayList;

// this class runs a group of effects and provides for selecting an effect group
public class EffectRunner extends ImagePreprocessor {
    private class EffectGroup {
        EffectGroup(String name, ImagePreprocessor effect) {
            mName = name;
            processors = new ArrayList<ImageProcessor>();
            processors.add(effect);
        }
        EffectGroup(String name, ImagePreprocessor preprocessor, ImageProcessor effect) {
            this(name, preprocessor);
            processors.add(effect);
        }
        EffectGroup(String name, ImagePreprocessor preprocessor, ImageProcessor effect1, ImageProcessor effect2) {
            this(name, preprocessor, effect1);
            processors.add(effect2);
        }
        public ImagePreprocessor getPreprocessor() {
            return (ImagePreprocessor)processors.get(0);
        }
        public String getName() { return mName; };
        public ArrayList<ImageProcessor> processors;
        private String mName;

    }
    private EffectGroup mCurrentGroup;
    private ArrayList<EffectGroup> mAllEffectGroups;

    public EffectRunner() {
        initializeProcessors();
        mCurrentGroup = mAllEffectGroups.get(0);
    }

    public EffectGroup prevEffectGroup() {
        int currentIndex = mAllEffectGroups.indexOf(mCurrentGroup);
        int prevIndex = currentIndex-1;
        if(prevIndex <= -1) prevIndex = mAllEffectGroups.size()-1;
        EffectGroup prevGroup = mAllEffectGroups.get(prevIndex);

        if(mCurrentGroup.getPreprocessor().getCallibration() != null)
            prevGroup.getPreprocessor().setCallibration(mCurrentGroup.getPreprocessor().getCallibration());

        mCurrentGroup = mAllEffectGroups.get(prevIndex);
        return mCurrentGroup;
    }

    public EffectGroup nextEffectGroup() {
        int currentIndex = mAllEffectGroups.indexOf(mCurrentGroup);
        int nextIndex = currentIndex+1;
        if(nextIndex >= mAllEffectGroups.size()) nextIndex = 0;
        EffectGroup nextGroup = mAllEffectGroups.get(nextIndex);

        if(mCurrentGroup.getPreprocessor().getCallibration() != null)
            nextGroup.getPreprocessor().setCallibration(mCurrentGroup.getPreprocessor().getCallibration());

        mCurrentGroup = mAllEffectGroups.get(nextIndex);
        return mCurrentGroup;
    }

    private void initializeProcessors() {
        mAllEffectGroups = new ArrayList<>();

        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                "Projection area depth heatmap",
                new ProjectionAreaProcessor(false),
                new ContourGenerator()
        ));

        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                "Full input depth heatmap",
                new DepthJpegProcessor(),
                new ContourGenerator()
        ));

        mAllEffectGroups.add(new EffectRunner.EffectGroup(
                "Projection mirror",
                new ProjectionAreaProcessor(true)
        ));
    }

    @Override
    public Mat process(Image img) {
        ImagePreprocessor preprocessor = mCurrentGroup.getPreprocessor();
        Mat output = preprocessor.process(img);
        if(output == null) return null;
        if(!preprocessor.isCallibrated())
            return output;

        for(int i = 1; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
        }
        return output;
    }

    public Mat process(Mat mat) {
        Mat output = null;
        for(int i = 0; i < mCurrentGroup.processors.size(); i++) {
            ImageProcessor processor = mCurrentGroup.processors.get(i);
            output = processor.process(output);
            if(output == null) return null;
        }
        return output;
    }

    public String currentEffectName() {
        return mCurrentGroup.getName();
    }

    @Override
    public void recallibrate() {
        mCurrentGroup.getPreprocessor().recallibrate();
    }

    @Override
    public boolean isCallibrated() {
        return mCurrentGroup.getPreprocessor().isCallibrated();
    }

    @Override
    public void setAutoCallibrate(boolean enable) {
        mCurrentGroup.getPreprocessor().setAutoCallibrate(enable);
    }

    @Override
    public boolean getAutoCallibrate() {
        return mCurrentGroup.getPreprocessor().getAutoCallibrate();
    }

    @Override
    public int requiredInputFormat() {
        return mCurrentGroup.getPreprocessor().requiredInputFormat();
    }

    @Override
    public MatOfPoint2f getCallibration() {
        return mCurrentGroup.getPreprocessor().getCallibration();
    }

    @Override
    public void setCallibration(MatOfPoint2f callib) {
        mCurrentGroup.getPreprocessor().setCallibration(callib);
    }
}
