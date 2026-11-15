/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.record.wave;

import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.ConversionUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.real.IRealBufferListener;
import io.github.dsheirer.util.Dispatcher;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAVE recorder for mono real sample buffers, used for discriminator/demodulated audio artifacts.
 */
public class RealSamplesWaveRecorder extends Module implements IRealBufferListener, Listener<float[]>
{
    private final static Logger mLog = LoggerFactory.getLogger(RealSamplesWaveRecorder.class);

    private Dispatcher<float[]> mBufferProcessor = new Dispatcher<>("sdrtrunk real wave recorder", 250);
    private AtomicBoolean mRunning = new AtomicBoolean();
    private BufferWaveWriter mWriter;
    private String mFilePrefix;
    private Path mFile;
    private AudioFormat mAudioFormat;
    private float mGain = 1.0f;

    public RealSamplesWaveRecorder(float sampleRate, String filePrefix)
    {
        this(sampleRate, filePrefix, 1.0f);
    }

    public RealSamplesWaveRecorder(float sampleRate, String filePrefix, float gain)
    {
        mFilePrefix = filePrefix;
        mGain = gain;
        setSampleRate(sampleRate);
    }

    public void setSampleRate(float sampleRate)
    {
        if(mAudioFormat == null || mAudioFormat.getSampleRate() != sampleRate)
        {
            mAudioFormat = new AudioFormat(sampleRate, 16, 1, true, false);

            if(mRunning.get())
            {
                stop();
                start();
            }
        }
    }

    public Path getFile()
    {
        return mFile;
    }

    @Override
    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            try
            {
                mFile = Paths.get(mFilePrefix + ".wav");
                mWriter = new BufferWaveWriter(mAudioFormat, mFile);
                mBufferProcessor.setListener(mWriter);
                mBufferProcessor.start();
            }
            catch(IOException io)
            {
                mLog.error("Error starting real sample wave recorder", io);
            }
        }
    }

    @Override
    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mBufferProcessor != null)
            {
                mBufferProcessor.stop();
                mBufferProcessor.setListener(null);
            }

            if(mWriter != null)
            {
                ThreadPool.CACHED.submit(() -> {
                    try
                    {
                        mWriter.close();
                    }
                    catch(IOException ioe)
                    {
                        mLog.error("Error closing real sample wave recorder", ioe);
                    }
                });
            }
        }
    }

    @Override
    public Listener<float[]> getBufferListener()
    {
        return this;
    }

    @Override
    public void receive(float[] buffer)
    {
        mBufferProcessor.receive(buffer);
    }

    @Override
    public void reset()
    {
    }

    /**
     * Wave writer implementation for real buffers delivered from the buffer processor.
     */
    public class BufferWaveWriter extends WaveWriter implements Listener<float[]>
    {
        public BufferWaveWriter(AudioFormat format, Path file) throws IOException
        {
            super(format, file);
        }

        @Override
        public void receive(float[] samples)
        {
            try
            {
                mWriter.writeData(ConversionUtils.convertToSigned16BitSamples(applyGain(samples)));
            }
            catch(IOException ioe)
            {
                mLog.error("IOException while writing real sample buffers to wave recorder - stopping recorder", ioe);
                stop();
            }
        }

        private float[] applyGain(float[] samples)
        {
            if(mGain == 1.0f)
            {
                return samples;
            }

            float[] scaled = new float[samples.length];
            for(int x = 0; x < samples.length; x++)
            {
                scaled[x] = samples[x] * mGain;
            }
            return scaled;
        }
    }
}
