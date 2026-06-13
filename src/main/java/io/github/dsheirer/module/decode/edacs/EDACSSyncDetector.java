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
    private static final int SYNC_BITS = 48;
    private static final int WORD_BITS = 40;
    private static final int DATA_BITS = 6 * WORD_BITS;

    private long mSyncSr = 0;
    private int mSyncCount = 0;

    private boolean[] mDataBuffer = new boolean[DATA_BITS];
    private int mDataCount = 0;

    private BCH_40_28_EDACS mBch = new BCH_40_28_EDACS();

    private double mSamplesPerSymbol;
    private int mSampleIndex = 0;
    private float mMaxDeviation;
    private float mMinDeviation;
    private float mCenterSample;
    private int mJitter = -1;
    private float mLastSample;
    private double mAfc = 0.0;
    private float mCenter;
    private float mMaxRef = 0.1f;
    private float mMinRef = -0.1f;

    private boolean mLocked = false;
    private long mLastStatsTime = 0;
    private int mMsgCount = 0;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleIndex = 0;
        mSkipCount = 200;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(int i = 0; i < demodulated.length; i++)
        {
            float sample = demodulated[i];
            mAfc = mAfc * 0.9995 + sample * 0.0005;
            mCenter = (float)mAfc;

            if(mDataCount > 0)
            {
                if(readSymbol(sample))
                    processData(messageListener);
            }
            else
            {
                if(readSymbol(sample))
                    checkSync();
            }

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.info("EDACS - locked: " + mLocked + " msgs: " + mMsgCount +
                          " afc: " + String.format("%.4f", mAfc));
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    private boolean readSymbol(float sample)
    {
        float dev = sample - mCenter;
        if(dev > mMaxDeviation) mMaxDeviation = dev;
        if(dev < mMinDeviation) mMinDeviation = dev;
        if(mSampleIndex == 2) mCenterSample = dev;

        //Jitter tracking: detect first zero-crossing in symbol window
        if(mJitter < 0)
        {
            if(mLastSample < mCenter && sample >= mCenter)
                mJitter = mSampleIndex;
            else if(mLastSample > mCenter && sample <= mCenter)
                mJitter = mSampleIndex;
        }

        mLastSample = sample;
        mSampleIndex++;

        if(mSampleIndex < mSamplesPerSymbol)
            return false;

        mSampleIndex = 0;

        //Apply jitter correction for next symbol (only when not already synced to data)
        if(mDataCount == 0 && !mLocked)
        {
            if(mJitter >= 1 && mJitter <= 2)
                mSampleIndex = -1;
            else if(mJitter >= 3 && mJitter <= 4)
                mSampleIndex = 1;
        }
        mJitter = -1;

        //Use center sample (index 2) for bit decision
        boolean bit = mCenterSample >= 0;
        mMaxDeviation = 0;
        mMinDeviation = 0;
        mCenterSample = 0;

        if(mDataCount > 0)
        {
            mDataBuffer[DATA_BITS - mDataCount] = bit;
            mDataCount--;
            return mDataCount == 0;
        }
        else
        {
            mSyncSr = (mSyncSr << 1) | (bit ? 1 : 0);
            mSyncCount++;
            return mSyncCount >= SYNC_BITS;
        }
    }

    private int mDebug = 3;
    private int mSkipCount = 200;

    private void checkSync()
    {
        if(mSkipCount > 0) { mSkipCount--; return; }
        long errors = Long.bitCount(mSyncSr ^ SYNC_WORD);
        if(mDebug > 0)
        {
            mLog.info("EDACS sync hex=" + String.format("%012X", mSyncSr) + " errors=" + errors);
            mDebug--;
        }
        if(errors <= 4)
        {
            if(!mLocked) { mLocked = true; mLog.info("EDACS sync acquired"); }
            mDataCount = DATA_BITS;
        }
    }

    private void processData(Listener<IMessage> messageListener)
    {
        //Extract 6 words
        boolean[][] rawWords = new boolean[6][WORD_BITS];
        for(int w = 0; w < 6; w++)
            for(int b = 0; b < WORD_BITS; b++)
                rawWords[w][b] = mDataBuffer[w * WORD_BITS + b];

        //3-copy majority vote: words 0,1,2 for msg1; words 3,4,5 for msg2
        //Word 1 and 4 are transmitted inverted
        CorrectedBinaryMessage voted1 = vote3(rawWords[0], rawWords[1], true, rawWords[2]);
        CorrectedBinaryMessage voted2 = vote3(rawWords[3], rawWords[4], true, rawWords[5]);

        if(voted1 == null && voted2 == null) return;

        mMsgCount++;

        CorrectedBinaryMessage data1 = voted1 != null ? mBch.decodeCodeword(voted1) : null;
        CorrectedBinaryMessage data2 = voted2 != null ? mBch.decodeCodeword(voted2) : null;

        if(data1 == null && data2 == null) return;

        EDACSMessage message;
        if(data1 != null)
            message = EDACSMessageFactory.create(data1, data2, System.currentTimeMillis());
        else
            message = EDACSMessageFactory.create(data2, System.currentTimeMillis());
        if(message == null) return;

        if(messageListener != null) messageListener.receive(message);
    }

    private CorrectedBinaryMessage vote3(boolean[] w0, boolean[] w1, boolean w1Inverted, boolean[] w2)
    {
        CorrectedBinaryMessage voted = new CorrectedBinaryMessage(WORD_BITS);
        for(int b = 0; b < WORD_BITS; b++)
        {
            int count = w0[b] ? 1 : 0;
            count += (w1Inverted ? !w1[b] : w1[b]) ? 1 : 0;
            count += w2[b] ? 1 : 0;
            voted.set(b, count > 1);
        }
        return voted;
    }
}
