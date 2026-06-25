package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frame processor for EDACS control channel. Accepts a stream of demodulated
 * bits (0 or 1) and detects dotting/sync boundaries, assembles 6x 40-bit
 * message words per frame, applies 3-copy majority voting plus BCH(40,28)
 * error correction, and produces EDACSMessage instances via
 * {@link EDACSMessageFactory}.
 *
 * <p>This class was extracted from the original {@code EDACSSyncDetector} so
 * that the bit-detection stage (FM demod + AFC + symbol timing) can be
 * skipped when validating frames from a pre-recorded symbol stream (e.g.
 * DSD-FME's {@code .bin} capture).</p>
 *
 * <p>Wire format reference (from EDACS-FM / DSD-FME docs):</p>
 * <ul>
 *   <li>Each outbound control channel frame = 48-bit sync + 6 * 40-bit
 *       message words = 288 bits</li>
 *   <li>The first 24 bits of the sync word are alternating (dotting);
 *       the remaining 24 bits are the identifying pattern
 *       {@code 0x555557125555} (read MSB-first from the wire)</li>
 *   <li>The 6 message words are 3 copies of message-1 followed by 3
 *       copies of message-2; copies 2 and 5 (the middle copies) are
 *       bitwise-inverted on the wire for majority-vote resilience</li>
 *   <li>Each 40-bit word is a shortened BCH(40,28) codeword: 28 data
 *       bits + 12 BCH parity bits, capable of correcting up to 2 errors</li>
 * </ul>
 */
public class EDACSFrameProcessor
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSFrameProcessor.class);

    private static final int SYNC_BITS = 48;
    private static final int WORD_BITS = 40;
    private static final int FRAME_BITS = SYNC_BITS + 6 * WORD_BITS;

    /**
     * Number of consecutive bit-alternations required to declare the start of
     * a sync / dotting pattern. The EDACS sync word begins with 24 alternating
     * bits, so 14 is comfortably inside the dotting preamble.
     */
    private static final int DOTTING_THRESHOLD = 14;

    private final boolean[] mBitBuffer = new boolean[FRAME_BITS + 80];
    private int mWritePtr = 0;
    private int mConsecutiveAlts = 0;
    private boolean mLastBit;
    private int mFramePending = 0;

    private final EDACSVoter mVoter = new EDACSVoter();

    private int mFramesDetected = 0;
    private int mFramesDecoded = 0;
    private int mBchPasses = 0;
    private int mBchFails = 0;
    private long mLastStatsTime = 0;

    /**
     * Feeds a single demodulated bit to the frame processor. Bit values are
     * 1 for a positive FM deviation and 0 for a negative FM deviation. Bits
     * with indeterminate polarity (e.g. idle / no carrier) should be skipped
     * by the caller.
     */
    public void processBit(int bit, Listener<IMessage> messageListener)
    {
        boolean currentBit = (bit != 0);

        mBitBuffer[mWritePtr] = currentBit;
        mWritePtr = (mWritePtr + 1) % mBitBuffer.length;

        if(currentBit != mLastBit)
        {
            mConsecutiveAlts++;
        }
        else
        {
            mConsecutiveAlts = 0;
        }
        mLastBit = currentBit;

        if(mFramePending > 0)
        {
            mFramePending--;
            if(mFramePending == 0)
            {
                decodeFrame(messageListener);
            }
        }

        if(mConsecutiveAlts >= DOTTING_THRESHOLD && mFramePending <= 0)
        {
            mFramePending = FRAME_BITS - DOTTING_THRESHOLD;
        }

        if(System.currentTimeMillis() - mLastStatsTime > 10000)
        {
            mLog.info("EDACS FrameProcessor - detected: " + mFramesDetected +
                    " decoded: " + mFramesDecoded +
                    " BCH pass: " + mBchPasses + " fail: " + mBchFails);
            mLastStatsTime = System.currentTimeMillis();
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        mFramesDetected++;

        int readPtr = (mWritePtr + mBitBuffer.length - FRAME_BITS) % mBitBuffer.length;
        int dataStart = (readPtr + SYNC_BITS) % mBitBuffer.length;

        CorrectedBinaryMessage[] words = new CorrectedBinaryMessage[6];
        for(int w = 0; w < 6; w++)
        {
            CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_BITS);
            for(int b = 0; b < WORD_BITS; b++)
            {
                int idx = (dataStart + w * WORD_BITS + b) % mBitBuffer.length;
                if(mBitBuffer[idx])
                {
                    word.set(b);
                }
            }
            words[w] = word;
        }

        CorrectedBinaryMessage data1 = mVoter.vote(words[0], words[1], words[2]);
        CorrectedBinaryMessage data2 = mVoter.vote(words[3], words[4], words[5]);

        if(data1 == null && data2 == null)
        {
            mBchFails++;
            return;
        }

        mBchPasses++;
        mFramesDecoded++;

        EDACSMessage message;
        if(data1 != null)
        {
            message = EDACSMessageFactory.create(data1, data2, System.currentTimeMillis());
        }
        else
        {
            message = EDACSMessageFactory.create(data2, System.currentTimeMillis());
        }

        if(message == null)
        {
            return;
        }

        if(messageListener != null)
        {
            messageListener.receive(message);
        }
    }

    public int getFramesDetected()
    {
        return mFramesDetected;
    }

    public int getFramesDecoded()
    {
        return mFramesDecoded;
    }

    public int getBchPasses()
    {
        return mBchPasses;
    }

    public int getBchFails()
    {
        return mBchFails;
    }
}
