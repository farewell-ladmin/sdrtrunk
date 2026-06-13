package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS trunking decoder using edacs-fm-style sync frame detection.
 * Demodulates complex I/Q samples to FM audio, digitizes via AFC threshold,
 * detects sync frame pattern, extracts and BCH-checks messages.
 */
public class EDACSDecoder extends Decoder implements IComplexSamplesListener, Listener<ComplexSamples>,
        ISourceEventListener, IDecoderStateEventProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoder.class);

    private IDemodulator mFMDemodulator;
    private EDACSSyncDetector mSyncDetector;
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mIDecimationFilter;
    private IRealDecimationFilter mQDecimationFilter;
    private Listener<DecoderStateEvent> mDecoderStateEventListener;
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();

    private float[] mResampleBuffer = new float[0];
    private double mResamplePhase = 0;
    private boolean mResampleReady = false;

    public EDACSDecoder()
    {
        mFMDemodulator = FmDemodulatorFactory.getFmDemodulator();
        mSyncDetector = new EDACSSyncDetector();
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

        float[] resampled = resample(demodulated, 50000.0, 24000.0);

        mSyncDetector.process(resampled, getMessageListener());

        if(mSyncDetector.hasSync() && mDecoderStateEventListener != null)
        {
            mDecoderStateEventListener.receive(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.CONTROL));
        }
    }

    private void setSampleRate(double sampleRate)
    {
        int decimation = 1;
        while((sampleRate / decimation) >= 96000)
        {
            decimation *= 2;
        }

        mIDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mQDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimation);

        double decimatedRate = sampleRate / decimation;

        float[] coefficients = FilterFactory.getLowPass(decimatedRate, 9600, 12000, 60,
                io.github.dsheirer.dsp.window.WindowType.HAMMING, true);

        mIBasebandFilter = FilterFactory.getRealFilter(coefficients);
        mQBasebandFilter = FilterFactory.getRealFilter(coefficients);

        mLog.info("EDACS decoder sample rate: " + decimatedRate + " (decimation: " + decimation + ")");

        mSyncDetector.setSampleRate(24000.0);
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
                setSampleRate(sourceEvent.getValue().doubleValue());
            }
        }
    }

    private float[] resample(float[] input, double inputRate, double outputRate)
    {
        double step = outputRate / inputRate;
        int estimateLen = (int)(input.length * step) + 2;
        if(mResampleBuffer.length < estimateLen)
            mResampleBuffer = new float[estimateLen];

        int outIdx = 0;
        for(int i = 0; i < input.length; i++)
        {
            mResamplePhase += step;
            while(mResamplePhase >= 1.0)
            {
                mResamplePhase -= 1.0;
                if(mResampleReady && outIdx < mResampleBuffer.length)
                    mResampleBuffer[outIdx++] = input[i];
                else
                    mResampleReady = true;
            }
        }

        float[] result = new float[outIdx];
        System.arraycopy(mResampleBuffer, 0, result, 0, outIdx);
        return result;
    }
}
