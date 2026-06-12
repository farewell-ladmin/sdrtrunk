package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS sync frame detector using edacs-fm's approach.
 * Digitizes FM audio via AFC threshold, feeds 5x64-bit shift registers,
 * detects the exact SYNC_FRAME pattern, extracts fr_1/fr_4 and BCH-checks.
 */
public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    //EDACS sync frame pattern from edacs-fm: 48-bit pattern at top of shift register
    private static final long SYNC_FRAME = 0x555557125555L;
    private static final long SYNC_MASK  = 0xFFFFFFFFFFFFL;

    //5x 64-bit shift registers (edacs-fm sr_0..sr_4)
    private long mSr0, mSr1, mSr2, mSr3, mSr4;

    private EDACSVoter mVoter = new EDACSVoter();
    private BCH_40_28_EDACS mBch = new BCH_40_28_EDACS();

    //AFC offset tracking
    private short[] mAfcHistory = new short[960];
    private int mAfcHistoryPtr = 0;
    private short mAfc = 0;
    private int mSampleCount = 0;
    private static final int AFC_WINDOW = 960;

    //Subsampling/integration
    private double mSamplesPerSymbol = 5.0;
    private double mSampleAccum = 0;
    private float mDeviationSum = 0;
    private int mDeviationCount = 0;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = 0;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(float sample : demodulated)
        {
            //Integrate FM deviation over symbol period
            mDeviationSum += sample;
            mDeviationCount++;
            mSampleAccum += 1.0;

            if(mSampleAccum < mSamplesPerSymbol)
                continue;

            mSampleAccum -= mSamplesPerSymbol;

            float avgDeviation = mDeviationSum / mDeviationCount;
            mDeviationSum = 0;
            mDeviationCount = 0;

            short s = (short)(avgDeviation * 32767.0f);

            //AFC tracking
            mAfcHistory[mAfcHistoryPtr] = s;
            mAfcHistoryPtr = (mAfcHistoryPtr + 1) % AFC_WINDOW;
            mSampleCount++;

            if(mSampleCount >= AFC_WINDOW)
            {
                mSampleCount = 0;
                short min = Short.MAX_VALUE;
                short max = Short.MIN_VALUE;
                for(int i = 0; i < AFC_WINDOW; i++)
                {
                    if(mAfcHistory[i] < min) min = mAfcHistory[i];
                    if(mAfcHistory[i] > max) max = mAfcHistory[i];
                }
                mAfc = (short)((min + max) / 2);
            }

            boolean bit = s >= mAfc;

            //Shift registers (edacs-fm style)
            mSr0 = (mSr0 << 1) | ((mSr1 >>> 63) & 1);
            mSr1 = (mSr1 << 1) | ((mSr2 >>> 63) & 1);
            mSr2 = (mSr2 << 1) | ((mSr3 >>> 63) & 1);
            mSr3 = (mSr3 << 1) | ((mSr4 >>> 63) & 1);
            mSr4 = (mSr4 << 1) | (bit ? 1 : 0);

            //Hard sync frame detection
            if((mSr0 & SYNC_MASK) == SYNC_FRAME)
            {
                decodeFrame(messageListener);
            }
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        //Extract fr_1 and fr_4 from shift registers (edacs-fm bit positions)
        //fr_1 = ((sr_0 & 0xFFFF) << 24) | ((sr_1 & 0xFFFFFF0000000000) >> 40)
        long fr1Raw = ((mSr0 & 0xFFFFL) << 24) | ((mSr1 & 0xFFFFFF0000000000L) >>> 40);
        long fr4Raw = ((mSr2 & 0xFFFFFFL) << 16) | ((mSr3 & 0xFFFF000000000000L) >>> 48);

        //BCH check each 40-bit word
        CorrectedBinaryMessage cbm1 = toCBM(fr1Raw, 40);
        CorrectedBinaryMessage cbm4 = toCBM(fr4Raw, 40);

        CorrectedBinaryMessage data1 = mBch.decodeCodeword(cbm1);
        CorrectedBinaryMessage data4 = mBch.decodeCodeword(cbm4);

        if(data1 != null || data4 != null)
        {
            EDACSMessage message = EDACSMessageFactory.create(
                data1 != null ? data1 : data4,
                data1 != null && data4 != null ? data4 : null,
                System.currentTimeMillis());

            if(messageListener != null)
            {
                messageListener.receive(message);
            }
        }
    }

    private CorrectedBinaryMessage toCBM(long value, int bits)
    {
        CorrectedBinaryMessage cbm = new CorrectedBinaryMessage(bits);
        for(int i = 0; i < bits; i++)
        {
            if((value & (1L << i)) != 0)
            {
                cbm.set(bits - 1 - i);
            }
        }
        return cbm;
    }
}
