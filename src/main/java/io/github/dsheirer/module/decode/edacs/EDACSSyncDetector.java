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
    private static final int FRAME_BITS = SYNC_BITS + 4 * WORD_BITS;

    private boolean[] mBuffer = new boolean[FRAME_BITS + 80];
    private int mWritePtr = 0;
    private int mConsecutiveAlts = 0;
    private boolean mLastBit;
    private int mFramePending = 0;

    private BCH_40_28_EDACS mBch = new BCH_40_28_EDACS();

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
            mDeviationSum += sample;
            mDeviationCount++;
            mSampleAccum += 1.0;
            if(mSampleAccum < mSamplesPerSymbol) continue;
            mSampleAccum -= mSamplesPerSymbol;

            float avgDeviation = mDeviationSum / mDeviationCount;
            mDeviationSum = 0;
            mDeviationCount = 0;

            mAfc = mAfc * (1.0 - AFC_ALPHA) + avgDeviation * AFC_ALPHA;

            boolean bit = avgDeviation >= mAfc;

            mBuffer[mWritePtr] = bit;
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
                mFramePending = FRAME_BITS;
            }

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.info("EDACS dotting - locked: " + mLocked + " msgs: " + mMsgCount +
                          " afc: " + String.format("%.4f", mAfc));
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        int readPtr = (mWritePtr + mBuffer.length - FRAME_BITS) % mBuffer.length;

        long syncCheck = 0;
        for(int i = 0; i < SYNC_BITS; i++)
            syncCheck = (syncCheck << 1) | (mBuffer[(readPtr + i) % mBuffer.length] ? 1 : 0);

        if(Long.bitCount(syncCheck ^ 0x555557125555L) > 12) return;
        int dataStart = (readPtr + SYNC_BITS) % mBuffer.length;

        CorrectedBinaryMessage[] words = new CorrectedBinaryMessage[4];
        for(int w = 0; w < 4; w++)
        {
            CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_BITS);
            for(int b = 0; b < WORD_BITS; b++)
                if(mBuffer[(dataStart + w * WORD_BITS + b) % mBuffer.length])
                    word.set(b);
            words[w] = mBch.decodeCodeword(word);
        }

        boolean gotMsg1 = words[0] != null || words[1] != null;
        boolean gotMsg2 = words[2] != null || words[3] != null;

        if(!gotMsg1 && !gotMsg2)
        {
            if(mLocked) { mMissCount++; if(mMissCount >= 5) { mLocked = false; mLog.info("EDACS sync lost"); } }
            return;
        }

        CorrectedBinaryMessage data1 = words[0] != null ? words[0] : words[1];
        CorrectedBinaryMessage data2 = words[2] != null ? words[2] : words[3];

        EDACSMessage message;
        if(data1 != null)
            message = EDACSMessageFactory.create(data1, data2, System.currentTimeMillis());
        else
            message = EDACSMessageFactory.create(data2, System.currentTimeMillis());

        if(message == null) return;

        mMsgCount++;

        if(messageListener != null) messageListener.receive(message);

        if(mLocked) { mMissCount = 0; }
        else { mLockCount++; if(mLockCount >= 2) { mLocked = true; mMissCount = 0; } }
    }

}
