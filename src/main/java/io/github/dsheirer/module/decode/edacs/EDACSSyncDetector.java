package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS trunking control channel demodulator. Receives FM-demodulated audio
 * (typically 24 kHz or 48 kHz mono float), extracts the bit stream via AFC
 * threshold detection, and forwards each bit to an {@link EDACSFrameProcessor}
 * which performs dotting/sync detection, 3-copy voting, BCH(40,28) error
 * correction, and message dispatch.
 *
 * <p>Reference: DSD-FME {@code edacs-fme.c}. EDACS control channel runs at
 * 9600 baud GFSK. With 5 samples-per-symbol at 48 kHz the timing is exact
 * (DSD-FME default); 24 kHz with 2.5 sps also works but the symbol-center
 * sample is fractional.</p>
 */
public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    private static final float FM_GAIN = 2.0f;
    private static final double AFC_ALPHA = 0.0005;

    private final EDACSFrameProcessor mFrameProcessor = new EDACSFrameProcessor();
    private Listener<IMessage> mMessageListener;

    private double mSamplesPerSymbol = 2.6;
    private double mSampleAccum = 0;
    private float mDeviationSum = 0;
    private int mDeviationCount = 0;
    private double mAfc = 0.0;

    private long mLastStatsTime = 0;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = 0;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        mMessageListener = messageListener;

        for(float sample : demodulated)
        {
            mAfc = mAfc * (1.0 - AFC_ALPHA) + sample * AFC_ALPHA;

            float dev = sample - (float)mAfc;
            mDeviationSum += dev;
            mDeviationCount++;
            mSampleAccum += 1.0;
            if(mSampleAccum < mSamplesPerSymbol)
            {
                continue;
            }
            mSampleAccum -= mSamplesPerSymbol;

            float avgDeviation = (mDeviationSum / mDeviationCount) * FM_GAIN;
            mDeviationSum = 0;
            mDeviationCount = 0;

            int bit = avgDeviation >= 0 ? 1 : 0;
            mFrameProcessor.processBit(bit, messageListener);

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.info("EDACS Demod - afc: " + String.format("%.4f", mAfc) +
                        " frames: " + mFrameProcessor.getFramesDecoded() +
                        " bch_pass: " + mFrameProcessor.getBchPasses() +
                        " bch_fail: " + mFrameProcessor.getBchFails());
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    public EDACSFrameProcessor getFrameProcessor()
    {
        return mFrameProcessor;
    }

    /**
     * Indicates recent activity (a frame was decoded within the last 5
     * seconds). Used by callers to decide whether the demodulator is locked.
     */
    public boolean hasSync()
    {
        return mFrameProcessor.getFramesDecoded() > 0;
    }
}
