package io.github.dsheirer.dsp.fsk;

import io.github.dsheirer.dsp.fm.ScalarFMDemodulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GFSK 9600 bps decoder for EDACS control channel.
 *
 * Uses FM demodulation of complex I/Q samples followed by symbol-rate
 * integration to produce hard bit decisions at 9600 bps.
 */
public class GFSK9600Decoder
{
    private final static Logger mLog = LoggerFactory.getLogger(GFSK9600Decoder.class);

    private ScalarFMDemodulator mDemodulator = new ScalarFMDemodulator();
    private double mSamplesPerSymbol;
    private int mSymbolSamples;
    private float mAccumulator;
    private int mSampleCount;

    public GFSK9600Decoder(double sampleRate)
    {
        setSampleRate(sampleRate);
    }

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSymbolSamples = 0;
        mAccumulator = 0;
        mSampleCount = 0;
        mLog.info("GFSK9600Decoder: sampleRate=" + sampleRate + " samplesPerSymbol=" + mSamplesPerSymbol);
    }

    /**
     * Processes one buffer of demodulated FM audio and extracts bits at 9600 bps.
     * @param demodulated FM-demodulated float samples (frequency deviation per sample)
     * @param bitListener receives extracted bits
     * @return number of bits extracted from this buffer
     */
    public int process(float[] demodulated, BitListener bitListener)
    {
        int bitsExtracted = 0;

        for(float sample : demodulated)
        {
            mAccumulator += sample;
            mSampleCount++;

            if(mSampleCount >= Math.round(mSamplesPerSymbol * (bitsExtracted + 1)))
            {
                bitListener.receive(mAccumulator >= 0);
                mAccumulator = 0;
                bitsExtracted++;
            }
        }

        //Reset per-buffer state so next buffer starts fresh
        mSampleCount = 0;
        mAccumulator = 0;

        return bitsExtracted;
    }

    /**
     * Demodulates complex I/Q samples and extracts bits.
     * @param i in-phase samples
     * @param q quadrature samples
     * @param bitListener receives extracted bits
     * @return number of bits extracted
     */
    public int demodulate(float[] i, float[] q, BitListener bitListener)
    {
        float[] demodulated = mDemodulator.demodulate(i, q);
        return process(demodulated, bitListener);
    }

    /**
     * Resets the decoder state for a new acquisition.
     */
    public void reset()
    {
        mAccumulator = 0;
        mSampleCount = 0;
    }

    /**
     * Interface for receiving decoded bits.
     */
    public interface BitListener
    {
        void receive(boolean bit);
    }
}
