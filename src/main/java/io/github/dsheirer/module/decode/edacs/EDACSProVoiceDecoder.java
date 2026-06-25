// Reference: DSD-FME edacs-fme.c provoice.c (lwvmobile/edacs-fm).
// Algorithm: ProVoice is the digital voice option for EDACS, used by
// 3600-baud-capable radios on a 9600-baud channel slot. Each outbound
// ProVoice frame carries 4 IMBE 7100 voice frames (20 ms each = 80 ms
// of audio) interleaved two-at-a-time.
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProVoice traffic channel decoder. Receives FM-demodulated audio
 * (typically 48 kHz mono float, 5 samples per symbol at 9600 baud)
 * and produces one {@link EDACSProVoiceMessage} per outbound ProVoice
 * frame. The message carries 4 de-interleaved IMBE 7100 frames that
 * the audio module feeds to JMBE.
 *
 * <p>Wire format (per DSD-FME provoice.c):</p>
 * <ul>
 *   <li>24 alternating dotting bits (preamble)</li>
 *   <li>32-bit sync word: 16-bit fixed prefix (0x0EBF) + 8-bit TX + 8-bit RX</li>
 *   <li>64-bit initial data + 16-bit LID + 64-bit secondary data (header)</li>
 *   <li>2-bit spacer</li>
 *   <li>IMBE 1,2 first half (interleaved, 142 bits per IMBE frame)</li>
 *   <li>2-bit spacer + 16-bit BF marker + 2-bit spacer</li>
 *   <li>IMBE 3,4 first half (interleaved, 142 bits per IMBE frame)</li>
 *   <li>2-bit spacer</li>
 *   <li>IMBE 1,2 second half (interleaved, 142 bits per IMBE frame)</li>
 *   <li>2-bit spacer</li>
 *   <li>IMBE 3,4 second half (interleaved, 142 bits per IMBE frame)</li>
 * </ul>
 *
 * <p>Total per frame: 24 + 32 + 144 + 2 + 142 + 16 + 2 + 142 + 2 + 142 + 2 + 142 = 792
 * bits. At 9600 baud that's 82.5 ms; at 5 samples/symbol with a 48 kHz
 * sample rate that's 3960 samples per ProVoice frame.</p>
 */
public class EDACSProVoiceDecoder extends Module
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSProVoiceDecoder.class);

    private static final int DOTTING_BITS = 24;
    private static final int SYNC_BITS = 32;
    private static final int HEADER_BITS = 64 + 16 + 64;
    private static final int IMBE_BITS_PER_FRAME = 142;
    private static final int SPACER_BITS = 2;
    private static final int BF_BITS = 16;
    private static final int BITS_PER_IMBE = IMBE_BITS_PER_FRAME;
    private static final int FRAME_BITS = DOTTING_BITS + SYNC_BITS + HEADER_BITS +
            (SPACER_BITS + IMBE_BITS_PER_FRAME) * 4 + BF_BITS + SPACER_BITS * 2;

    /** Dotting detector: count consecutive bit alternations. */
    private int mConsecutiveAlts = 0;
    private boolean mLastBit = false;
    private int mFramePending = 0;
    private int mFrameBitCount = 0;

    private boolean[] mFrameBits = new boolean[FRAME_BITS + 80];

    private int mFramesDetected = 0;
    private int mFramesDecoded = 0;
    private int mDecodeErrors = 0;
    private long mLastStatsTime = 0;

    private double mSamplesPerSymbol = 5.0;
    private double mSampleAccum = 0;
    private float mLastSample = 0f;
    private float mLastDeviation = 0f;
    private double mAfc = 0.0;

    private static final double AFC_ALPHA = 0.05;
    private static final float AFC_MAX = 2.0f;

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

            boolean bit = mLastDeviation >= 0;
            mFrameBits[mFrameBitCount % mFrameBits.length] = bit;
            mFrameBitCount++;

            if(bit != mLastBit)
            {
                mConsecutiveAlts++;
            }
            else
            {
                mConsecutiveAlts = 0;
            }
            mLastBit = bit;

            if(mFramePending > 0)
            {
                mFramePending--;
                if(mFramePending == 0)
                {
                    decodeFrame(messageListener);
                }
            }

            if(mConsecutiveAlts >= DOTTING_BITS && mFramePending <= 0)
            {
                mFramePending = FRAME_BITS - DOTTING_BITS;
            }

            if(System.currentTimeMillis() - mLastStatsTime > 10000)
            {
                mLog.info("EDACS ProVoice - detected: " + mFramesDetected +
                        " decoded: " + mFramesDecoded +
                        " errors: " + mDecodeErrors);
                mLastStatsTime = System.currentTimeMillis();
            }
        }
    }

    private void decodeFrame(Listener<IMessage> messageListener)
    {
        mFramesDetected++;

        // The 24-bit dotting is at the end of the 792-bit window.
        // Read the 4 IMBE frames at their offsets within the frame.
        boolean[] fr1 = readImbeBits(192, IMBE_BITS_PER_FRAME);
        boolean[] fr2 = readImbeBits(192 + IMBE_BITS_PER_FRAME + SPACER_BITS, IMBE_BITS_PER_FRAME);
        boolean[] fr3 = readImbeBits(192 + IMBE_BITS_PER_FRAME * 2 + SPACER_BITS * 2, IMBE_BITS_PER_FRAME);
        boolean[] fr4 = readImbeBits(192 + IMBE_BITS_PER_FRAME * 3 + SPACER_BITS * 3, IMBE_BITS_PER_FRAME);

        if(fr1 == null || fr2 == null || fr3 == null || fr4 == null)
        {
            mDecodeErrors++;
            return;
        }

        int lid = readLid();
        byte[] imbe1 = EDACSProVoiceInterleave.toImbeFrame(fr1);
        byte[] imbe2 = EDACSProVoiceInterleave.toImbeFrame(fr2);
        byte[] imbe3 = EDACSProVoiceInterleave.toImbeFrame(fr3);
        byte[] imbe4 = EDACSProVoiceInterleave.toImbeFrame(fr4);

        EDACSProVoiceMessage message = new EDACSProVoiceMessage(imbe1, imbe2, imbe3, imbe4, lid, System.currentTimeMillis());
        mFramesDecoded++;
        if(messageListener != null)
        {
            messageListener.receive(message);
        }
    }

    /**
     * Read {@code count} bits starting {@code bitOffset} bits before the
     * most recent dotting trigger. Returns null if there aren't enough
     * bits yet in the ring buffer.
     */
    private boolean[] readImbeBits(int bitOffset, int count)
    {
        if(mFrameBitCount < FRAME_BITS)
        {
            return null;
        }
        boolean[] result = new boolean[count];
        int startIndex = (mFrameBitCount - FRAME_BITS + bitOffset) % mFrameBits.length;
        for(int i = 0; i < count; i++)
        {
            result[i] = mFrameBits[(startIndex + i) % mFrameBits.length];
        }
        return result;
    }

    private int readLid()
    {
        if(mFrameBitCount < FRAME_BITS)
        {
            return 0;
        }
        // LID is at bit offset 24 (dotting) + 32 (sync) + 64 (initial data) = 120
        int startIndex = (mFrameBitCount - FRAME_BITS + 120) % mFrameBits.length;
        int lid = 0;
        for(int i = 0; i < 16; i++)
        {
            if(mFrameBits[(startIndex + i) % mFrameBits.length])
            {
                lid |= (1 << (15 - i));
            }
        }
        return lid;
    }

    public int getFramesDetected() { return mFramesDetected; }
    public int getFramesDecoded() { return mFramesDecoded; }
    public int getDecodeErrors() { return mDecodeErrors; }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
    }
}
