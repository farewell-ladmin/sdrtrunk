package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fsk.GFSK9600Decoder;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.MessageDirection;
import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS trunking decoder. Demodulates complex I/Q samples, performs GFSK decoding at 9600 bps,
 * synchronizes to EDACS control channel bursts, performs majority voting across triple-transmitted
 * message words, applies BCH(40,28) error correction, and produces decoded EDACS messages.
 */
public class EDACSDecoder extends Decoder implements IComplexSamplesListener, Listener<ComplexSamples>,
        ISourceEventListener, IDecoderStateEventProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoder.class);

    private static final int SYMBOL_RATE = 9600;
    private static final int MIN_SAMPLES_PER_SYMBOL = 2;

    private IDemodulator mFMDemodulator;
    private GFSK9600Decoder mGFSKDecoder;
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mIDecimationFilter;
    private IRealDecimationFilter mQDecimationFilter;
    private EDACSVoter mVoter = new EDACSVoter();
    private Listener<DecoderStateEvent> mDecoderStateEventListener;
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();
    private final EDACSBurstDetector mBurstDetector = new EDACSBurstDetector();

    private double mSampleRate;

    public EDACSDecoder()
    {
        mFMDemodulator = FmDemodulatorFactory.getFmDemodulator();
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateEventListener = listener;
    }

    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateEventListener = null;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventProcessor;
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
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

        mGFSKDecoder.process(demodulated, bit -> mBurstDetector.receive(bit));
    }

    private void setSampleRate(double sampleRate)
    {
        mSampleRate = sampleRate;

        int decimation = 1;
        int minRate = SYMBOL_RATE * MIN_SAMPLES_PER_SYMBOL;
        while((sampleRate / decimation) >= (minRate * 2))
        {
            decimation *= 2;
        }

        mIDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mQDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);

        double decimatedRate = sampleRate / decimation;

        float[] coefficients = FilterFactory.getLowPass(decimatedRate, 5000, 7000, 60,
                io.github.dsheirer.dsp.window.WindowType.HAMMING, true);

        mIBasebandFilter = FilterFactory.getRealFilter(coefficients);
        mQBasebandFilter = FilterFactory.getRealFilter(coefficients);

        mGFSKDecoder = new GFSK9600Decoder(decimatedRate);

        mLog.info("EDACS decoder sample rate: " + decimatedRate + " (decimation: " + decimation + ")");
    }

    public class SourceEventProcessor implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                setSampleRate(sourceEvent.getValue().doubleValue());
            }
        }
    }

    /**
     * Detects EDACS control channel bursts (48-bit dotting sequence followed by data).
     * Accumulates raw bits and attempts to decode them into EDACS messages.
     */
    private class EDACSBurstDetector
    {
        private static final int BURST_LENGTH = 288; //Total bits in an EDACS burst (48 dotting + 240 data)
        private static final int DOTTING_THRESHOLD = 30;  //Out of 48 bits of alternating dotting
        private static final int DATA_LENGTH = 240;
        private static final int WORD_LENGTH = 40;

        private boolean[] mBuffer = new boolean[BURST_LENGTH];
        private int mBufferPointer = 0;
        private int mConsecutiveAlts = 0;
        private boolean mLastBit;
        private boolean mBurstArmed;

        public void receive(boolean bit)
        {
            mBuffer[mBufferPointer] = bit;
            mBufferPointer = (mBufferPointer + 1) % BURST_LENGTH;

            //Detect dotting pattern: alternating bits (48 bits of 010101... or 101010...)
            if(bit != mLastBit)
            {
                mConsecutiveAlts++;
            }
            else
            {
                mConsecutiveAlts = 0;
            }

            mLastBit = bit;

            //Burst detected when we see enough alternating bits (dotting sequence)
            if(mConsecutiveAlts >= DOTTING_THRESHOLD && !mBurstArmed)
            {
                mBurstArmed = true;
            }

            if(mBurstArmed && mConsecutiveAlts < 2)
            {
                //End of burst — extract the data portion
                mBurstArmed = false;
                decodeBurst();
            }
        }

        private void decodeBurst()
        {
            //The data portion starts after the 48-bit dotting and spans 240 bits (6 words of 40 bits each)
            int start = (mBufferPointer + BURST_LENGTH - DATA_LENGTH) % BURST_LENGTH;

            //Extract 6 words of 40 bits each
            for(int wordNum = 0; wordNum < 6; wordNum++)
            {
                CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_LENGTH);
                for(int bit = 0; bit < WORD_LENGTH; bit++)
                {
                    int index = (start + wordNum * WORD_LENGTH + bit) % BURST_LENGTH;
                    if(mBuffer[index])
                    {
                        word.set(bit);
                    }
                }
                mBurstWords[wordNum] = word;
            }

            processWords();
        }

        private CorrectedBinaryMessage[] mBurstWords = new CorrectedBinaryMessage[6];

        private void processWords()
        {
            //EDACS transmits each logical message 3 times. Words 0-2 are message A (normal, inverted, normal).
            //Words 3-5 are message B (normal, inverted, normal).
            try
            {
                CorrectedBinaryMessage msgA = mVoter.vote(mBurstWords[0], mBurstWords[1], mBurstWords[2]);
                if(msgA != null)
                {
                    EDACSMessage message = new EDACSMessage(EDACSMessageType.UNKNOWN, msgA, System.currentTimeMillis());
                    getMessageListener().receive(message);
                }

                CorrectedBinaryMessage msgB = mVoter.vote(mBurstWords[3], mBurstWords[4], mBurstWords[5]);
                if(msgB != null)
                {
                    EDACSMessage message = new EDACSMessage(EDACSMessageType.UNKNOWN, msgB, System.currentTimeMillis());
                    getMessageListener().receive(message);
                }
            }
            catch(Exception e)
            {
                mLog.debug("Error processing EDACS burst", e);
            }
        }
    }
}
