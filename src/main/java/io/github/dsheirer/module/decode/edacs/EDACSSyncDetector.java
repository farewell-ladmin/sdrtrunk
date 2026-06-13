package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    private static final long SYNC_WORD = 0x555557125555L;
    private static final int DOTTING_THRESHOLD = 14;
    private static final int SYNC_BITS = 48;
    private static final int WORD_BITS = 40;
    private static final int FRAME_BITS = SYNC_BITS + 6 * WORD_BITS;

    private float[] mBuffer = new float[FRAME_BITS + 80];
    private int mWritePtr = 0;
    private int mConsecutiveAlts = 0;
    private boolean mLastBit;
    private int mFramePending = 0;

    private static final float FM_GAIN = 2.0f;

    private EDACSVoter mVoter = new EDACSVoter();
    private static final int DATA_BITS = 6 * WORD_BITS;

    private double mSamplesPerSymbol = 2.6;
    private double mSampleAccum = 0;
    private float mDeviationSum = 0;
    private int mDeviationCount = 0;

    private double mAfc = 0.0;
    private static final double AFC_ALPHA = 0.0005;

    private boolean mLocked = false;
    private int mLockCount = 0;
    private int mMissCount = 0;
    private long mLastStatsTime = 0;
    private int mMsgCount = 0;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = 0;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(float sample : demodulated)
        {
            mAfc = mAfc * (1.0 - AFC_ALPHA) + sample * AFC_ALPHA;

            float dev = sample - (float)mAfc;
            mDeviationSum += dev;
            mDeviationCount++;
            mSampleAccum += 1.0;
            if(mSampleAccum < mSamplesPerSymbol) continue;
            mSampleAccum -= mSamplesPerSymbol;

            float avgDeviation = (mDeviationSum / mDeviationCount) * FM_GAIN;
            mDeviationSum = 0;
            mDeviationCount = 0;

            boolean bit = avgDeviation >= 0;

            mBuffer[mWritePtr] = avgDeviation;
            mWritePtr = (mWritePtr + 1) % mBuffer.length;

            if(bit != mLastBit) { mConsecutiveAlts++; }
            else { mConsecutiveAlts = 0; }
            mLastBit = bit;

            if(mFramePending > 0)
            {
                mFramePending--;
                if(mFramePending == 0)
                    decodeFrame(messageListener);
            }

            if(mConsecutiveAlts >= DOTTING_THRESHOLD && mFramePending <= 0)
            {
                mFramePending = FRAME_BITS - DOTTING_THRESHOLD;
            }

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.info("EDACS - locked: " + mLocked + " msgs: " + mMsgCount +
                          " afc: " + String.format("%.4f", mAfc));
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    private int mDebugCount = 0;
    private long mLastSyncTime = 0;

    public boolean hasSync()
    {
        return System.currentTimeMillis() - mLastSyncTime < 5000;
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        int readPtr = (mWritePtr + mBuffer.length - FRAME_BITS) % mBuffer.length;
        int dataStart = (readPtr + SYNC_BITS) % mBuffer.length;

        CorrectedBinaryMessage[] words = new CorrectedBinaryMessage[6];
        for(int w = 0; w < 6; w++)
        {
            CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_BITS);
            for(int b = 0; b < WORD_BITS; b++)
            {
                int idx = (dataStart + w * WORD_BITS + b) % mBuffer.length;
                if(mBuffer[idx] >= 0) word.set(b);
            }
            words[w] = word;
        }

        CorrectedBinaryMessage data1 = mVoter.vote(words[0], words[1], words[2]);
        CorrectedBinaryMessage data2 = mVoter.vote(words[3], words[4], words[5]);

        if(data1 == null && data2 == null) return;

        mMsgCount++;

        EDACSMessage message;
        if(data1 != null)
            message = EDACSMessageFactory.create(data1, data2, System.currentTimeMillis());
        else
            message = EDACSMessageFactory.create(data2, System.currentTimeMillis());
        if(message == null) return;

        if(messageListener != null) messageListener.receive(message);

        if(mLocked) {}
        else { mLockCount++; if(mLockCount >= 3) { mLocked = true; } }
    }

    private double correlation(int offset, long pattern, int bits)
    {
        double sum = 0;
        float center = (float)mAfc;
        for(int i = 0; i < bits; i++)
        {
            boolean patternBit = ((pattern >> (bits - 1 - i)) & 1) != 0;
            float sample = mBuffer[(offset + i) % mBuffer.length] - center;
            sum += patternBit ? -sample : sample;
        }
        return sum;
    }

}
