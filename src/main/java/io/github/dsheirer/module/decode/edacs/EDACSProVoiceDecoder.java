// Reference: DSD-FME edacs-fme.c provoice.c (lwvmobile/edacs-fm).
// Algorithm: ProVoice is the digital voice option for EDACS, used by
// 3600-baud-capable radios on a 9600-baud channel slot. Each outbound
// ProVoice frame carries 4 IMBE 7100 voice frames (20 ms each = 80 ms
// of audio) interleaved two-at-a-time.
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageProvider;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
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
public class EDACSProVoiceDecoder extends Module implements IComplexSamplesListener, Listener<ComplexSamples>,
        ISourceEventListener, IMessageProvider
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

    private IDemodulator mFMDemodulator = FmDemodulatorFactory.getFmDemodulator();
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mIDecimationFilter;
    private IRealDecimationFilter mQDecimationFilter;
    private double mDecimatedRate = 48000.0;
    private float[] mResampleBuffer = new float[0];
    private double mResamplePhase = 0;
    private boolean mResampleReady = false;
    private Listener<IMessage> mMessageListener;
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventProcessor;
    }

    @Override
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    @Override
    public void removeMessageListener()
    {
        mMessageListener = null;
    }

    @Override
    public void receive(ComplexSamples samples)
    {
        if(mIDecimationFilter == null)
        {
            return;
        }

        float[] decimatedI = mIDecimationFilter.decimateReal(samples.i());
        float[] decimatedQ = mQDecimationFilter.decimateReal(samples.q());
        float[] filteredI = mIBasebandFilter.filter(decimatedI);
        float[] filteredQ = mQBasebandFilter.filter(decimatedQ);
        float[] demodulated = mFMDemodulator.demodulate(filteredI, filteredQ);
        process(resample(demodulated, mDecimatedRate, 48000.0), mMessageListener);
    }

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = 0;
    }

    private void setSourceSampleRate(double sampleRate)
    {
        int decimation = 1;
        while((sampleRate / decimation) >= 96000)
        {
            decimation *= 2;
        }

        mIDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mQDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimatedRate = sampleRate / decimation;

        float[] coefficients = FilterFactory.getLowPass(mDecimatedRate, 9600, 12000, 60,
                io.github.dsheirer.dsp.window.WindowType.HAMMING, true);

        mIBasebandFilter = FilterFactory.getRealFilter(coefficients);
        mQBasebandFilter = FilterFactory.getRealFilter(coefficients);
        setSampleRate(48000.0);
        mLog.info("EDACS ProVoice decoder sample rate: " + mDecimatedRate +
                " (decimation: " + decimation + ") -> 48 kHz");
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

        if(mFrameBitCount < FRAME_BITS)
        {
            mDecodeErrors++;
            return;
        }

        int lid = readLid();
        int[] offset = new int[] { DOTTING_BITS + SYNC_BITS + HEADER_BITS };
        int[][] grid1 = new int[7][24];
        int[][] grid2 = new int[7][24];
        fillImbePair(offset, grid1, grid2);
        offset[0] += BF_BITS;
        int[][] grid3 = new int[7][24];
        int[][] grid4 = new int[7][24];
        fillImbePair(offset, grid3, grid4);

        byte[] imbe1 = EDACSProVoiceInterleave.toImbeFrame(grid1);
        byte[] imbe2 = EDACSProVoiceInterleave.toImbeFrame(grid2);
        byte[] imbe3 = EDACSProVoiceInterleave.toImbeFrame(grid3);
        byte[] imbe4 = EDACSProVoiceInterleave.toImbeFrame(grid4);

        EDACSProVoiceMessage message = new EDACSProVoiceMessage(imbe1, imbe2, imbe3, imbe4, lid, System.currentTimeMillis());
        mFramesDecoded++;
        if(messageListener != null)
        {
            messageListener.receive(message);
        }
    }

    /**
     * Fills two IMBE frames from the ProVoice paired interleave layout.
     * This mirrors DSD-FME processProVoice() using the pW/pX grid tables.
     */
    private void fillImbePair(int[] bitOffset, int[][] frame1, int[][] frame2)
    {
        int tableOffset = 0;

        for(int i = 0; i < 11; i++)
        {
            fillBothFrames(bitOffset, frame1, frame2, tableOffset, 6);
            tableOffset += 6;
        }

        fillFrame(bitOffset, frame1, tableOffset, 6);
        fillFrame(bitOffset, frame2, tableOffset, 4);
        tableOffset += 4;

        bitOffset[0] += SPACER_BITS;

        fillFrame(bitOffset, frame2, tableOffset, 2);
        tableOffset += 2;

        for(int i = 0; i < 3; i++)
        {
            fillBothFrames(bitOffset, frame1, frame2, tableOffset, 6);
            tableOffset += 6;
        }

        fillBothFrames(bitOffset, frame1, frame2, tableOffset, 5);
        tableOffset += 5;

        for(int i = 0; i < 7; i++)
        {
            fillBothFrames(bitOffset, frame1, frame2, tableOffset, 6);
            tableOffset += 6;
        }

        fillBothFrames(bitOffset, frame1, frame2, tableOffset, 5);
        bitOffset[0] += SPACER_BITS;
    }

    private void fillBothFrames(int[] bitOffset, int[][] frame1, int[][] frame2, int tableOffset, int count)
    {
        fillFrame(bitOffset, frame1, tableOffset, count);
        fillFrame(bitOffset, frame2, tableOffset, count);
    }

    private void fillFrame(int[] bitOffset, int[][] frame, int tableOffset, int count)
    {
        for(int i = 0; i < count; i++)
        {
            int index = tableOffset + i;
            frame[EDACSProVoiceInterleave.pW[index]][EDACSProVoiceInterleave.pX[index]] = readFrameBit(bitOffset[0]++) ? 1 : 0;
        }
    }

    private boolean readFrameBit(int bitOffset)
    {
        int startIndex = (mFrameBitCount - FRAME_BITS) % mFrameBits.length;
        return mFrameBits[(startIndex + bitOffset) % mFrameBits.length];
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

    private float[] resample(float[] input, double inputRate, double outputRate)
    {
        double step = outputRate / inputRate;
        int estimateLen = (int)(input.length * step) + 2;
        if(mResampleBuffer.length < estimateLen)
        {
            mResampleBuffer = new float[estimateLen];
        }

        int outIdx = 0;
        for(float sample : input)
        {
            mResamplePhase += step;
            while(mResamplePhase >= 1.0)
            {
                mResamplePhase -= 1.0;
                if(mResampleReady && outIdx < mResampleBuffer.length)
                {
                    mResampleBuffer[outIdx++] = sample;
                }
                else
                {
                    mResampleReady = true;
                }
            }
        }

        float[] result = new float[outIdx];
        System.arraycopy(mResampleBuffer, 0, result, 0, outIdx);
        return result;
    }

    public class SourceEventProcessor implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                mResamplePhase = 0;
                mResampleReady = false;
                setSourceSampleRate(sourceEvent.getValue().doubleValue());
            }
        }
    }

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
