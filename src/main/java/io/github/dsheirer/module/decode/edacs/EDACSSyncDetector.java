package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS sync-frame-based detector inspired by edacs-fm.
 *
 * Digitizes FM-demodulated audio via AFC zero-crossing threshold,
 * feeds into sliding shift registers, detects the EDACS sync frame
 * pattern (0x555557125555), extracts message words, BCH-checks them.
 */
public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    //EDACS sync frame pattern from edacs-fm: 48-bit pattern
    private static final long SYNC_FRAME = 0x555557125555L;
    private static final long SYNC_MASK  = 0xFFFFFFFFFFFFL;

    //5x 64-bit shift registers (edacs-fm sr_0..sr_4)
    private long mSr0, mSr1, mSr2, mSr3, mSr4;

    //AFC offset tracking
    private short[] mAfcHistory = new short[960];
    private int mAfcHistoryPtr = 0;
    private short mAfc = 0;
    private int mSampleCount = 0;
    private static final int AFC_WINDOW = 960;

    private BCH_40_28_EDACS mBch = new BCH_40_28_EDACS();

    /**
     * Processes FM-demodulated audio. Digitizes to bits via AFC threshold,
     * feeds shift registers, detects sync frames, extracts and decodes messages.
     */
    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(float sample : demodulated)
        {
            short s = (short)(sample * 32767.0f);

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

            if((mSr0 & SYNC_MASK) == SYNC_FRAME)
            {
                decodeFrame(messageListener);
            }
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        //Extract fr_1 and fr_4 (only first copy of each message, like edacs-fm)
        long fr1Raw = ((mSr0 & 0xFFFFL) << 24) | ((mSr1 & 0xFFFFFF0000000000L) >>> 40);
        long fr4Raw = ((mSr2 & 0xFFFFFFL) << 16) | ((mSr3 & 0xFFFF000000000000L) >>> 48);

        //BCH check each 40-bit word
        CorrectedBinaryMessage cbm1 = toCBM(fr1Raw, 40);
        CorrectedBinaryMessage cbm4 = toCBM(fr4Raw, 40);

        CorrectedBinaryMessage data1 = mBch.decodeCodeword(cbm1);
        CorrectedBinaryMessage data4 = mBch.decodeCodeword(cbm4);

        if(data1 != null || data4 != null)
        {
            CorrectedBinaryMessage msg1Data = data1 != null ? data1 : null;
            CorrectedBinaryMessage msg2Data = data4 != null ? data4 : null;

            EDACSMessage message = EDACSMessageFactory.create(
                msg1Data != null ? msg1Data : msg2Data,
                data1 != null && data4 != null ? msg2Data : null,
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
