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
    private static final int FRAME_BITS = SYNC_BITS + 4 * WORD_BITS;

    private float[] mBuffer = new float[FRAME_BITS + 80];
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

        float center = (float)mAfc;
        double corr = correlation(readPtr, SYNC_WORD, SYNC_BITS);
        double energy = 0;
        for(int i = 0; i < SYNC_BITS; i++)
            energy += Math.abs(mBuffer[(readPtr + i) % mBuffer.length] - center);

        if(mDebugCount < 1)
        {
            float maxD = 0, minD = 0;
            for(int i = 0; i < FRAME_BITS; i++) { float d = mBuffer[(readPtr + i) % mBuffer.length]; if(d > maxD) maxD = d; if(d < minD) minD = d; }
            mLog.info("EDACS corr=" + String.format("%.3f", corr) + " ratio=" + String.format("%.3f", Math.abs(corr/energy)) +
                      " devMax=" + String.format("%.4f", maxD) + " devMin=" + String.format("%.4f", minD));
            mDebugCount++;
        }

        if(energy <= 0 || Math.abs(corr / energy) < 0.35) return;

        mLastSyncTime = System.currentTimeMillis();
        if(mLocked) { mMissCount = 0; }

        int dataStart = (readPtr + SYNC_BITS) % mBuffer.length;

        CorrectedBinaryMessage data1 = readRawWord(dataStart, 0);
        CorrectedBinaryMessage data2 = readRawWord(dataStart, 2);

        if(data1 == null && data2 == null) return;

        EDACSMessage message;
        if(data1 != null)
            message = EDACSMessageFactory.create(data1, data2, System.currentTimeMillis());
        else if(data2 != null)
            message = EDACSMessageFactory.create(data2, System.currentTimeMillis());
        else
            return;

        if(message == null) return;

        mMsgCount++;
        if(messageListener != null) messageListener.receive(message);

        if(mLocked) { mMissCount = 0; }
        else { mLockCount++; if(mLockCount >= 3) { mLocked = true; mMissCount = 0; } }
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

    private CorrectedBinaryMessage readRawWord(int dataStart, int wordNum)
    {
        CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_BITS);
        for(int b = 0; b < WORD_BITS; b++)
        {
            int idx = (dataStart + wordNum * WORD_BITS + b) % mBuffer.length;
            if(mBuffer[idx] >= (float)mAfc) word.set(b);
        }
        return mBch.decodeCodeword(word);
    }
}
