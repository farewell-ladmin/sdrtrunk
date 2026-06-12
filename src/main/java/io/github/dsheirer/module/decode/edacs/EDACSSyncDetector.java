package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS dotting-based burst detector with subsampled 9600 bps bit extraction.
 * Looks for alternating dotting sequence, frames 240-bit data bursts,
 * extracts 6x40-bit words, votes and BCH-checks them.
 */
public class EDACSSyncDetector
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSSyncDetector.class);

    private static final int DOTTING_LENGTH = 48;
    private static final int BURST_LENGTH = 288;     //48 dotting + 240 data
    private static final int DATA_LENGTH = 240;
    private static final int WORD_LENGTH = 40;
    private static final int DOTTING_THRESHOLD = 30; //consecutive alternating bits to trigger

    private boolean[] mBuffer = new boolean[BURST_LENGTH];
    private int mBufferPointer = 0;
    private int mConsecutiveAlts = 0;
    private boolean mLastBit;
    private boolean mBurstArmed;
    private CorrectedBinaryMessage[] mBurstWords = new CorrectedBinaryMessage[6];

    private EDACSVoter mVoter = new EDACSVoter();
    private BCH_40_28_EDACS mBch = new BCH_40_28_EDACS();

    //Subsampling: digitize at 9600 bps
    private double mSamplesPerSymbol = 2.6;
    private double mSampleAccum = 0;

    //AFC offset tracking
    private short[] mAfcHistory = new short[960];
    private int mAfcHistoryPtr = 0;
    private short mAfc = 0;
    private int mSampleCount = 0;
    private static final int AFC_WINDOW = 960;

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / 9600.0;
        mSampleAccum = 0;
    }

    public void process(float[] demodulated, Listener<IMessage> messageListener)
    {
        for(float sample : demodulated)
        {
            //Subsample to ~9600 bps
            mSampleAccum += 1.0;
            if(mSampleAccum < mSamplesPerSymbol) continue;
            mSampleAccum -= mSamplesPerSymbol;

            //Digitize via AFC threshold
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

            mBuffer[mBufferPointer] = bit;
            mBufferPointer = (mBufferPointer + 1) % BURST_LENGTH;

            //Dotting detection
            if(bit != mLastBit) { mConsecutiveAlts++; }
            else { mConsecutiveAlts = 0; }
            mLastBit = bit;

            if(mConsecutiveAlts >= DOTTING_THRESHOLD && !mBurstArmed)
            {
                mBurstArmed = true;
            }

            if(mBurstArmed && mConsecutiveAlts < 2)
            {
                mBurstArmed = false;
                decodeBurst(messageListener);
            }
        }
    }

    private void decodeBurst(Listener<IMessage> messageListener)
    {
        int start = (mBufferPointer + BURST_LENGTH - DATA_LENGTH) % BURST_LENGTH;

        for(int wordNum = 0; wordNum < 6; wordNum++)
        {
            CorrectedBinaryMessage word = new CorrectedBinaryMessage(WORD_LENGTH);
            for(int bit = 0; bit < WORD_LENGTH; bit++)
            {
                int index = (start + wordNum * WORD_LENGTH + bit) % BURST_LENGTH;
                if(mBuffer[index]) word.set(bit);
            }
            mBurstWords[wordNum] = word;
        }

        try
        {
            CorrectedBinaryMessage msgA = mVoter.vote(mBurstWords[0], mBurstWords[1], mBurstWords[2]);
            CorrectedBinaryMessage msgB = mVoter.vote(mBurstWords[3], mBurstWords[4], mBurstWords[5]);

            if(msgA != null)
            {
                EDACSMessage message = EDACSMessageFactory.create(msgA, msgB, System.currentTimeMillis());
                if(messageListener != null) messageListener.receive(message);
            }
            else if(msgB != null)
            {
                EDACSMessage message = EDACSMessageFactory.create(msgB, System.currentTimeMillis());
                if(messageListener != null) messageListener.receive(message);
            }
        }
        catch(Exception e)
        {
            mLog.debug("Error processing EDACS burst", e);
        }
    }
}
