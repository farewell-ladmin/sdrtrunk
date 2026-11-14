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
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceEndMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.sample.real.IRealBufferProvider;
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
 * bits over the air. The decoder frames from sync, so the internal frame omits
 * the 24 dotting bits and is 772 bits.</p>
 */
public class EDACSProVoiceDecoder extends Decoder implements IComplexSamplesListener, Listener<ComplexSamples>,
        ISourceEventListener, IdentifierUpdateListener, IRealBufferProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSProVoiceDecoder.class);

    private static final int SYNC_BITS = 32;
    private static final int HEADER_BITS = 64 + 16 + 64;
    private static final int IMBE_BITS_PER_FRAME = 142;
    private static final int SPACER_BITS = 2;
    private static final int BF_BITS = 16;
    private static final int BITS_PER_IMBE = IMBE_BITS_PER_FRAME;
    private static final int IMBE_PAIR_BITS = (SPACER_BITS + IMBE_BITS_PER_FRAME) * 2;
    private static final int BF_OFFSET = SYNC_BITS + HEADER_BITS + IMBE_PAIR_BITS;
    private static final int FRAME_BITS = SYNC_BITS + HEADER_BITS +
            (SPACER_BITS + IMBE_BITS_PER_FRAME) * 4 + BF_BITS + SPACER_BITS * 2;

    /** Reference: DSD-FME dsd.h ProVoice sync constants. */
    private static final String[] PROVOICE_SYNC_PATTERNS = new String[] {
            "13131333111311311133113311331133",
            "31313111333133133311331133113311",
            "31131311331331111133131311311133",
            "13313133113113333311313133133311"
    };

    /**
     * ProVoice has no end-of-transmission terminator in the decoded stream, so the end of an over is detected as
     * missing frames.  5 frames (~400 ms) is shorter than the typical gap between message-trunked overs.
     */
    private static final int SYNC_LOSS_BITS = FRAME_BITS * 5;

    private int mFramePending = 0;
    private int mFrameBitCount = 0;
    private int mBitsSinceFrame = 0;
    private boolean mFrameSyncActive = false;

    /**
     * When the matched sync is an inverted variant (INV_PROVOICE_SYNC /
     * INV_PROVOICE_EA_SYNC, indices 1 and 3), the demodulated signal is
     * polarity-inverted relative to the canonical bit mapping, so every
     * payload bit must be complemented before it is placed in the IMBE grid.
     * This mirrors DSD-FME digitize() (dsd_dibit.c), which uses synctype 14
     * for "+PV" and synctype 15 for "-PV" and inverts the returned dibit on
     * synctype 15. sdrtrunk's slicer ({@code dev >= 0 -> 1}) matches the
     * synctype-15 convention, so it inverts on the non-inverted ("+PV")
     * variants — which are matched here as the INV_ patterns.
     */
    private boolean mInvertPayload = false;
    private int mSyncIndex = -1;

    private boolean[] mFrameBits = new boolean[FRAME_BITS + 80];

    /**
     * The traffic channel is resampled to 48 kHz, or 5 samples/symbol.  A traffic channel starts on an arbitrary
     * sample and the symbol clock can drift during an over, so (as EDACSSyncDetector does for the control channel)
     * a framer runs on each of the 5 sample phases.  mFrameBits, mFrameBitCount and mFramePending are the state of
     * the phase currently being processed, swapped in and out of these arrays for each sample.
     */
    private static final int PHASES = 5;
    private final boolean[][] mPhaseFrameBits = new boolean[PHASES][FRAME_BITS + 80];
    private final int[] mPhaseFrameBitCount = new int[PHASES];
    private final int[] mPhaseFramePending = new int[PHASES];

    /** Sum of the absolute deviation at each bit of the frame in progress: largest at the centre of the symbol. */
    private final float[] mPhaseEye = new float[PHASES];
    private int mPhase = 0;

    /**
     * Phases near the symbol centre all decode the same frame within 5 samples of each other.  The frame from the
     * phase with the widest eye is held here until the other phases have had their turn, then sent once.
     */
    private EDACSProVoiceMessage mCandidate;
    private float mCandidateEye;
    private int mCandidateSamplesRemaining;

    /**
     * A single framer does not look for sync while it is reading a frame (as DSD-FME processProVoice), so frames
     * are never closer together than this.  With 5 framers the same limit is applied across all of them, otherwise
     * an idle phase picks up the extra sync words in the burst that precedes the voice frames of each over.
     */
    private static final int MINIMUM_FRAME_SPACING_SAMPLES = (FRAME_BITS - SYNC_BITS) * PHASES;
    /**
     * The site ends a transmission with dotting (alternating bits) on the working channel.  This many alternating
     * bits in a row on any phase, once voice has been decoded, is reported as the end of the transmission.  Voice and
     * the burst that starts an over do not contain a run this long.
     */
    private static final int END_DOTTING_BITS = 96;
    private final boolean[] mPhaseLastBit = new boolean[PHASES];
    private final int[] mPhaseDottingRun = new int[PHASES];
    private boolean mVoiceActive = false;

    private long mSampleCount = 0;
    private long mLastFrameSample = -MINIMUM_FRAME_SPACING_SAMPLES;

    private int mFramesDetected = 0;
    private int mFramesDecoded = 0;
    private int mDecodeErrors = 0;
    private int mSyncRejects = 0;

    private float mLastSample = 0f;
    private float mLastDeviation = 0f;
    private float mDeviationPrev1 = 0f;
    private float mDeviationPrev2 = 0f;
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
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();
    private final MutableIdentifierCollection mIdentifierCollection = new MutableIdentifierCollection();
    private final IdentifierUpdateProcessor mIdentifierUpdateProcessor = new IdentifierUpdateProcessor();
    private Listener<float[]> mDemodulatedBufferListener;
    private final int mVoiceMarker;

    /**
     * Constructs an instance
     * @param voiceMarker that voice frames carry on this system, or DecodeConfigEDACS.PROVOICE_VOICE_MARKER_NONE to
     * treat every frame as voice.
     */
    public EDACSProVoiceDecoder(int voiceMarker)
    {
        mVoiceMarker = voiceMarker;
    }

    /**
     * Constructs an instance that uses the default voice marker.
     */
    public EDACSProVoiceDecoder()
    {
        this(Integer.parseInt(DecodeConfigEDACS.PROVOICE_VOICE_MARKER_DEFAULT, 16));
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

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
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mIdentifierUpdateProcessor;
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
        float[] resampled = resample(demodulated, mDecimatedRate, 48000.0);

        if(mDemodulatedBufferListener != null)
        {
            mDemodulatedBufferListener.receive(resampled);
        }

        process(resampled, getMessageListener());
    }

    @Override
    public void setBufferListener(Listener<float[]> listener)
    {
        mDemodulatedBufferListener = listener;
    }

    @Override
    public void removeBufferListener()
    {
        mDemodulatedBufferListener = null;
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
        mLog.debug("EDACS ProVoice decoder sample rate: " + mDecimatedRate +
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
            // Slice on a short trailing 3-tap average of the FM deviation rather
            // than a single instantaneous sample. The symbol-sampling instant
            // (the accumulator crossing) is the well-aligned phase point that
            // sync locks to; averaging it with the two preceding samples (still
            // inside the same 5-sample symbol) reduces per-sample noise that
            // otherwise flips marginal bits, without the symbol-straddle that a
            // full-window boxcar suffers from at an arbitrary window phase.
            float smoothedDeviation = dev + mDeviationPrev1 + mDeviationPrev2;
            mDeviationPrev2 = mDeviationPrev1;
            mDeviationPrev1 = dev;

            //Each sample is the next bit for one of the 5 phase framers
            int phase = mPhase;
            mPhase = (mPhase + 1) % PHASES;
            mSampleCount++;

            //Report the end of the over (as the P25 framer reports sync loss) when frames stop arriving.  Counted
            //once per symbol.
            if(phase == 0 && mFrameSyncActive && ++mBitsSinceFrame >= SYNC_LOSS_BITS)
            {
                mFrameSyncActive = false;
                mVoiceActive = false;

                if(messageListener != null)
                {
                    messageListener.receive(new SyncLossMessage(System.currentTimeMillis(), mBitsSinceFrame,
                        Protocol.EDACS));
                }
            }

            mFrameBits = mPhaseFrameBits[phase];
            mFrameBitCount = mPhaseFrameBitCount[phase];
            mFramePending = mPhaseFramePending[phase];

            boolean bit = smoothedDeviation >= 0;
            mFrameBits[mFrameBitCount % mFrameBits.length] = bit;
            mFrameBitCount++;
            mPhaseEye[phase] += Math.abs(smoothedDeviation);

            //Report the end of the transmission (as the P25 decoder reports a call termination) on dotting
            if(bit == mPhaseLastBit[phase])
            {
                mPhaseDottingRun[phase] = 0;
            }
            else if(++mPhaseDottingRun[phase] >= END_DOTTING_BITS && mVoiceActive)
            {
                mVoiceActive = false;
                mFrameSyncActive = false;

                if(messageListener != null)
                {
                    messageListener.receive(new EDACSProVoiceEndMessage(System.currentTimeMillis()));
                }
            }

            mPhaseLastBit[phase] = bit;

            if(mFramePending > 0)
            {
                mFramePending--;
                if(mFramePending == 0)
                {
                    decodeFrame(phase);
                }
            }

            if(mFramePending <= 0 && hasSyncAtCurrentBit())
            {
                mFramePending = FRAME_BITS - SYNC_BITS;
                mPhaseEye[phase] = 0f;
            }

            mPhaseFrameBitCount[phase] = mFrameBitCount;
            mPhaseFramePending[phase] = mFramePending;

            if(mCandidate != null && --mCandidateSamplesRemaining <= 0)
            {
                EDACSProVoiceMessage message = mCandidate;
                mCandidate = null;
                mLastFrameSample = mSampleCount;
                mFramesDecoded++;
                mFrameSyncActive = true;
                mBitsSinceFrame = 0;

                if(message.isVoice())
                {
                    mVoiceActive = true;
                }

                if(messageListener != null)
                {
                    messageListener.receive(message);
                }
            }
        }
    }

    /**
     * Decodes the frame that just completed on the specified phase and keeps it as the candidate to send if no
     * other phase has offered the same frame with a wider eye.
     */
    private void decodeFrame(int phase)
    {
        mFramesDetected++;

        if(mCandidate == null && mSampleCount - mLastFrameSample < MINIMUM_FRAME_SPACING_SAMPLES)
        {
            return;
        }

        if(mFrameBitCount < FRAME_BITS)
        {
            mDecodeErrors++;
            return;
        }

        String syncPattern = getSyncPattern();
        if(syncPattern == null)
        {
            mSyncRejects++;
            return;
        }

        mInvertPayload = needsInversion(mSyncIndex);

        int lid = readLid();
        int bfMarker = readInt(BF_OFFSET, BF_BITS);
        boolean[] frameBits = readFrameBits();
        int[] offset = new int[] { SYNC_BITS + HEADER_BITS };
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

        EDACSProVoiceMessage message = new EDACSProVoiceMessage(imbe1, imbe2, imbe3, imbe4,
                new int[][][] { grid1, grid2, grid3, grid4 }, frameBits, lid, bfMarker, syncPattern,
                System.currentTimeMillis(), mIdentifierCollection.getIdentifiers(), mVoiceMarker);

        if(mCandidate == null)
        {
            mCandidate = message;
            mCandidateEye = mPhaseEye[phase];
            mCandidateSamplesRemaining = PHASES;
        }
        else if(mPhaseEye[phase] > mCandidateEye)
        {
            mCandidate = message;
            mCandidateEye = mPhaseEye[phase];
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
            frame[EDACSProVoiceInterleave.pW[index]][EDACSProVoiceInterleave.pX[index]] = readDataBit(bitOffset[0]++) ? 1 : 0;
        }
    }

    private boolean readFrameBit(int bitOffset)
    {
        int startIndex = (mFrameBitCount - FRAME_BITS) % mFrameBits.length;
        return mFrameBits[(startIndex + bitOffset) % mFrameBits.length];
    }

    /**
     * Reads a payload (non-sync) frame bit, applying the polarity inversion
     * determined by the matched sync variant. The sync match itself uses raw
     * {@link #readFrameBit(int)} so the pattern comparison sees the bits as
     * received.
     */
    private boolean readDataBit(int bitOffset)
    {
        return readFrameBit(bitOffset) ^ mInvertPayload;
    }

    private boolean[] readFrameBits()
    {
        boolean[] result = new boolean[FRAME_BITS];
        int startIndex = (mFrameBitCount - FRAME_BITS) % mFrameBits.length;
        for(int i = 0; i < FRAME_BITS; i++)
        {
            result[i] = mFrameBits[(startIndex + i) % mFrameBits.length];
        }
        return result;
    }

    private int readInt(int bitOffset, int bitCount)
    {
        int value = 0;
        for(int i = 0; i < bitCount; i++)
        {
            if(readDataBit(bitOffset + i))
            {
                value |= (1 << (bitCount - 1 - i));
            }
        }
        return value;
    }

    private int readLid()
    {
        if(mFrameBitCount < FRAME_BITS)
        {
            return 0;
        }
        // LID is at bit offset 32 (sync) + 64 (initial data) = 96.
        int startIndex = (mFrameBitCount - FRAME_BITS + 96) % mFrameBits.length;
        int lid = 0;
        for(int i = 0; i < 16; i++)
        {
            if(mFrameBits[(startIndex + i) % mFrameBits.length] ^ mInvertPayload)
            {
                lid |= (1 << (15 - i));
            }
        }
        return lid;
    }

    private String getSyncPattern()
    {
        // The four sync patterns form two inverse pairs (0/1 standard, 2/3 EA),
        // so every received polarity matches exactly one pattern non-inverted;
        // checking the inverted forms would only re-detect the pair partner.
        for(int x = 0; x < PROVOICE_SYNC_PATTERNS.length; x++)
        {
            if(matchesSync(PROVOICE_SYNC_PATTERNS[x], false))
            {
                mSyncIndex = x;
                return "SYNC" + x + (needsInversion(x) ? "_INVERTED" : "");
            }
        }

        mSyncIndex = -1;
        return null;
    }

    /**
     * Indices 1 and 3 are the INV_PROVOICE_SYNC / INV_PROVOICE_EA_SYNC
     * patterns; matching them means the payload must be bit-inverted.
     * See {@link #mInvertPayload}.
     */
    private boolean needsInversion(int syncIndex)
    {
        return syncIndex == 1 || syncIndex == 3;
    }

    private boolean matchesSync(String pattern, boolean invert)
    {
        for(int x = 0; x < SYNC_BITS; x++)
        {
            boolean expected = pattern.charAt(x) == '3';

            if(invert)
            {
                expected = !expected;
            }

            if(readFrameBit(x) != expected)
            {
                return false;
            }
        }

        return true;
    }

    private boolean hasSyncAtCurrentBit()
    {
        if(mFrameBitCount < SYNC_BITS)
        {
            return false;
        }

        for(String pattern : PROVOICE_SYNC_PATTERNS)
        {
            if(matchesTrailingSync(pattern, false) || matchesTrailingSync(pattern, true))
            {
                return true;
            }
        }

        return false;
    }

    private boolean matchesTrailingSync(String pattern, boolean invert)
    {
        int start = mFrameBitCount - SYNC_BITS;
        for(int x = 0; x < SYNC_BITS; x++)
        {
            boolean expected = pattern.charAt(x) == '3';

            if(invert)
            {
                expected = !expected;
            }

            if(mFrameBits[(start + x) % mFrameBits.length] != expected)
            {
                return false;
            }
        }

        return true;
    }

    public int getFramesDetected() { return mFramesDetected; }
    public int getFramesDecoded() { return mFramesDecoded; }
    public int getDecodeErrors() { return mDecodeErrors; }
    public int getSyncRejects() { return mSyncRejects; }

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

    private class IdentifierUpdateProcessor implements Listener<IdentifierUpdateNotification>
    {
        @Override
        public void receive(IdentifierUpdateNotification notification)
        {
            mIdentifierCollection.receive(notification);
        }
    }
}
