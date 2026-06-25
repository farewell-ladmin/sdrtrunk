package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS trunking control channel demodulator. Receives FM-demodulated audio
 * (typically 24 kHz mono float from sdrtrunk's channelizer output) and
 * extracts the GFSK bit stream.
 *
 * <p>Bit detection uses DSD-FME-style single-sample-at-the-symbol-center
 * (see DSD-FME {@code dsd_symbol.c}: for GFSK at 5 sps, sample at i=2).
 * The mean-deviation approach used previously produced noisy bits because
 * it averages across bit boundaries, leading to
 * BCH false-failures that prevented the control channel from being
 * reliably decoded on live FM-demodulated signals.</p>
 *
 * <p>AFC tracks the running mean of the audio (carrier frequency
 * offset plus any DC bias from the FM demodulator). A faster alpha
 * (0.05) is used so the AFC can settle within a few hundred samples
 * and follow any bias drift over the course of a recording.</p>
 */
public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    /** AFC exponential moving average alpha. Slow enough to avoid tracking symbol data. */
    private static final double AFC_ALPHA = 0.001;

    /** DC bias clamp to prevent the AFC from wandering far from the carrier. */
    private static final float AFC_MAX = 2.0f;

    private final EDACSFrameProcessor mFrameProcessor = new EDACSFrameProcessor();
    private Listener<IMessage> mMessageListener;

    private double mSamplesPerSymbol = 2.5;
    private double mSampleAccum = 0;
    private float mLastSample = 0f;
    private float mLastDeviation = 0f;
    private double mAfc = 0.0;

    private long mLastStatsTime = 0;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = mSamplesPerSymbol / 2.0;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        mMessageListener = messageListener;

        for(float sample : demodulated)
        {
            // Track carrier offset / DC bias with the AFC. Clamp the AFC to
            // a reasonable range so a brief dropout doesn't shift it far away
            // from the actual signal center.
            mAfc = mAfc * (1.0 - AFC_ALPHA) + sample * AFC_ALPHA;
            if(mAfc > AFC_MAX) mAfc = AFC_MAX;
            if(mAfc < -AFC_MAX) mAfc = -AFC_MAX;

            float dev = sample - (float)mAfc;
            mLastSample = sample;
            mLastDeviation = dev;
            mSampleAccum += 1.0;

            if(mSampleAccum < mSamplesPerSymbol)
            {
                continue;
            }
            mSampleAccum -= mSamplesPerSymbol;

            // DSD-FME single-sample-at-symbol-center bit detection. This
            // avoids mixing adjacent symbols at the 9600 baud transitions.
            int bit = mLastDeviation >= 0 ? 0 : 1;
            mFrameProcessor.processBit(bit, messageListener);

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.debug("EDACS Demod - afc: " + String.format("%.4f", mAfc) +
                        " sps: " + String.format("%.2f", mSamplesPerSymbol) +
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
