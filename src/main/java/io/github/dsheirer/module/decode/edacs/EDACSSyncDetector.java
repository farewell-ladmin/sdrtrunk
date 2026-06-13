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

    private static final int DOTTING_THRESHOLD = 14;
    private static final int SYNC_BITS = 48;
    private static final int WORD_BITS = 40;
    private static final int DATA_BITS = 6 * WORD_BITS;
    private static final int FRAME_BITS = SYNC_BITS + DATA_BITS;

    private float[] mSymbols;
    private int mSymWrite = 0;
    private int mSymCount = 0;

    private int mConsecutiveAlts = 0;
    private boolean mLastBit;
    private int mFramePending = 0;

    private EDACSVoter mVoter = new EDACSVoter();

    private double mAfc = 0.0;
    private static final double AFC_ALPHA = 0.0005;

    //Zero-crossing tracking
    private float mLastDev;
    private float mCrossDevSum;
    private int mCrossDevCount;
    private int mTicksSinceCross;
    private int mLastCrossTicks;

    private boolean mLocked = false;
    private int mLockCount = 0;
    private long mLastStatsTime = 0;
    private int mMsgCount = 0;

    public void setSampleRate(double sampleRate)
    {
        mAfc = 0;
        mLastDev = 0;
        mTicksSinceCross = 0;
        mLastCrossTicks = 0;
        mCrossDevSum = 0;
        mCrossDevCount = 0;
        mSymWrite = 0;
        mSymCount = 0;
        int bufSize = FRAME_BITS + 60;
        mSymbols = new float[bufSize];
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(int i = 0; i < demodulated.length; i++)
        {
            float sample = demodulated[i];
            mAfc = mAfc * (1.0 - AFC_ALPHA) + sample * AFC_ALPHA;
            float dev = sample - (float)mAfc;

            mCrossDevSum += dev;
            mCrossDevCount++;
            mTicksSinceCross++;

            boolean crossed = (mLastDev < 0 && dev >= 0) || (mLastDev > 0 && dev <= 0);
            mLastDev = dev;

            if(crossed && mCrossDevCount > 1)
            {
                int period = mLastCrossTicks + mTicksSinceCross;

                if(mLastCrossTicks > 0 && period > 0)
                {
                    float symbol = mCrossDevSum / mCrossDevCount;

                    mSymbols[mSymWrite % mSymbols.length] = symbol;
                    mSymWrite++;

                    boolean bit = symbol >= 0;

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
                }

                mLastCrossTicks = mTicksSinceCross;
                mTicksSinceCross = 0;
                mCrossDevSum = 0;
                mCrossDevCount = 0;
            }

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                float maxD = 0, minD = 0;
                for(int j = Math.max(0, mSymWrite - 100); j < mSymWrite && j < mSymbols.length; j++)
                {
                    if(mSymbols[j] > maxD) maxD = mSymbols[j];
                    if(mSymbols[j] < minD) minD = mSymbols[j];
                }
                mLog.info("EDACS - locked: " + mLocked + " msgs: " + mMsgCount +
                          " afc: " + String.format("%.4f", mAfc) + " devMax=" + String.format("%.4f", maxD) +
                          " devMin=" + String.format("%.4f", minD));
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        int readPtr = (mSymWrite + mSymbols.length - FRAME_BITS) % mSymbols.length;
        int dataStart = (readPtr + SYNC_BITS) % mSymbols.length;

        CorrectedBinaryMessage[] words = new CorrectedBinaryMessage[6];
        for(int w = 0; w < 6; w++)
        {
            CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_BITS);
            for(int b = 0; b < WORD_BITS; b++)
            {
                int idx = (dataStart + w * WORD_BITS + b) % mSymbols.length;
                if(mSymbols[idx] >= 0) word.set(b);
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
        if(!mLocked) { mLockCount++; if(mLockCount >= 3) { mLocked = true; } }
    }
}
