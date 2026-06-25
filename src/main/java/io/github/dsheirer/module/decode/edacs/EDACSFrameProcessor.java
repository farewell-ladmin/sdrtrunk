package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frame processor for EDACS control channel. Accepts a stream of demodulated
 * bits (0 or 1), detects frame boundaries via dotting (the 24-bit alternating
 * preamble at the start of every EDACS control channel frame), assembles
 * 6x 40-bit message words, applies 3-copy majority voting plus BCH(40,28)
 * error correction, and produces EDACSMessage instances via
 * {@link EDACSMessageFactory}.
 *
 * <p>Extracted from the original {@code EDACSSyncDetector} so that the
 * FM-demodulator stage can be bypassed when validating frames from a
 * pre-recorded symbol stream (e.g. DSD-FME's {@code .bin} capture).</p>
 *
 * <p>Wire format (EDACS-FM / DSD-FME reference, US patent US7546135B2):</p>
 * <ul>
 *   <li>Each outbound control channel frame = 48-bit sync + 6 * 40-bit
 *       message words = 288 bits total</li>
 *   <li>48-bit sync word: first 24 bits are alternating (dotting), last
 *       24 bits are the identifier. Read MSB-first on the wire the value
 *       is {@code 0x555557125555} (positive polarity) or its bitwise
 *       inverse {@code 0xAAAAEA8AAAAA} (negative polarity)</li>
 *   <li>The 6 message words are 3 copies of message-1 followed by 3
 *       copies of message-2; copies 2 and 5 (the middle copies) are
 *       bitwise-inverted on the wire for majority-vote resilience</li>
 *   <li>Each 40-bit word is a shortened BCH(40,28) codeword: 28 data
 *       bits + 12 BCH parity bits, capable of correcting up to 2 errors</li>
 * </ul>
 *
 * <p><b>Sync detection is dotting-based</b> (any 14+ consecutive
 * alternations trigger a frame decode). The 48-bit exact sync match
 * was tested but proved too strict for the sdrtrunk FM pipeline (it
 * fired zero times on the live WAV test). The original dotting detector
 * produces some false positives (the BCH pass rate is the gating
 * filter), but reliably decodes the control channel in the live UI.
 * An exact sync match is kept as a future enhancement (see
 * {@link #isSyncMatch}) but is not used by the live bit path.</p>
 */
public class EDACSFrameProcessor
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSFrameProcessor.class);

    private static final int SYNC_BITS = 48;
    private static final int WORD_BITS = 40;
    private static final int DATA_BITS = 6 * WORD_BITS;
    private static final int FRAME_BITS = SYNC_BITS + DATA_BITS;
    private static final int DOTTING_THRESHOLD = 14;

    /** 48-bit EDACS sync word (positive polarity), MSB-first. */
    private static final long EDACS_SYNC = 0x555557125555L;

    /** 48-bit EDACS sync word (negative / inverted polarity), MSB-first. */
    private static final long EDACS_SYNC_INV = 0xAAAAEA8AAAAAL;

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
    private int mDottingMatches = 0;
    private long mLastStatsTime = 0;

    /**
     * Feeds a single demodulated bit to the frame processor. Bit value 1
     * represents a positive FM deviation and 0 a negative. Idle / no-carrier
     * periods should be skipped by the caller.
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
            mDottingMatches++;
            mFramePending = FRAME_BITS - DOTTING_THRESHOLD;
        }

        if(System.currentTimeMillis() - mLastStatsTime > 10000)
        {
            mLog.info("EDACS FrameProcessor - dotting_matches: " + mDottingMatches +
                    " detected: " + mFramesDetected +
                    " decoded: " + mFramesDecoded +
                    " BCH pass: " + mBchPasses + " fail: " + mBchFails);
            mLastStatsTime = System.currentTimeMillis();
        }
    }

    /**
     * Tests whether the 48-bit register matches the EDACS sync word in
     * either polarity. Currently unused by the live bit path; retained
     * for future exact-sync enhancement.
     */
    @SuppressWarnings("unused")
    private boolean isSyncMatch(long register)
    {
        long xorPos = register ^ EDACS_SYNC;
        long xorNeg = register ^ EDACS_SYNC_INV;
        return Long.bitCount(xorPos) <= 2 || Long.bitCount(xorNeg) <= 2;
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

    public int getDottingMatches()
    {
        return mDottingMatches;
    }
}
