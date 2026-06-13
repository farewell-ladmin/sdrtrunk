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

        //Feed to sync-frame detector (edacs-fm style)
        mSyncDetector.process(demodulated, getMessageListener());

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

        mSyncDetector.setSampleRate(decimatedRate);
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
}
