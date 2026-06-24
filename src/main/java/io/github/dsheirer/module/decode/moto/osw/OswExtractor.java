// Reference: OP25 rx_smartnet.cc (boatbod/op25)
package io.github.dsheirer.module.decode.moto.osw;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.moto.Bandplan;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessage;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessageFactory;
import io.github.dsheirer.module.decode.moto.message.OswQueue;
import io.github.dsheirer.module.decode.moto.message.OswQueue.OswEntry;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class OswExtractor
{
    private static final Logger mLog = LoggerFactory.getLogger(OswExtractor.class);

    private static final double BAUD_RATE = 3600.0;
    private static final int SYNC_WORD = 0xAC;
    private static final int PAYLOAD_BITS = 76;
    private static final int INFO_BITS = 38;
    private static final int DATA_BITS = 27;
    private static final int CRC_BITS = 10;
    private static final int INTERLEAVE_ROWS = 19;
    private static final int INTERLEAVE_COLS = 4;

    private static final int ID_XOR = 0xCC38;
    private static final int CMD_XOR = 0xCD5;
    private static final int CRC_INIT = 0x0393;
    private static final int CRC_POLY = 0x0225;
    private static final int CRC_MASK = 0x3FF;

    private static final double AFC_ALPHA = 0.0005;
    private static final int MAX_BAD_OSW = 3;

    private double mSamplesPerSymbol;
    private double mClockPhase;
    private float mSampleAccum;
    private double mAfc;

    private int mSyncShiftReg;
    private int mPayloadBitCount;
    private final int[] mPayloadBuffer = new int[PAYLOAD_BITS];
    private boolean mCollecting;

    private int mConsecutiveBadOsw;
    private final OswQueue mOswQueue;
    private final Bandplan mBandplan;

    public OswExtractor(Bandplan bandplan)
    {
        mBandplan = bandplan;
        mOswQueue = new OswQueue(bandplan);
    }

    public void setSampleRate(double sampleRate)
    {
        mSamplesPerSymbol = sampleRate / BAUD_RATE;
        mClockPhase = 0;
        mSampleAccum = 0;
        mAfc = 0;
        mSyncShiftReg = 0;
        mPayloadBitCount = 0;
        mCollecting = false;
        mConsecutiveBadOsw = 0;
        mLog.info("OSW extractor sample rate: " + sampleRate + " samples/symbol: " + mSamplesPerSymbol);
    }

    public void process(float[] samples, Listener<IMessage> messageListener)
    {
        for(float sample : samples)
        {
            mSampleAccum += (sample - mAfc);
            mClockPhase += 1.0;

            if(mClockPhase >= mSamplesPerSymbol)
            {
                int bit = (mSampleAccum >= 0) ? 1 : 0;
                mAfc += AFC_ALPHA * mSampleAccum;
                mSampleAccum = 0;
                mClockPhase -= mSamplesPerSymbol;

                processBit(bit, messageListener);
            }
        }
    }

    private void processBit(int bit, Listener<IMessage> messageListener)
    {
        if(mCollecting)
        {
            mPayloadBuffer[mPayloadBitCount++] = bit;

            if(mPayloadBitCount >= PAYLOAD_BITS)
            {
                processFrame(messageListener);
                mCollecting = false;
            }
        }
        else
        {
            mSyncShiftReg = ((mSyncShiftReg << 1) | bit) & 0xFF;

            if(mSyncShiftReg == SYNC_WORD)
            {
                mCollecting = true;
                mPayloadBitCount = 0;
            }
        }
    }

    private void processFrame(Listener<IMessage> messageListener)
    {
        int[] deinterleaved = deinterleave();
        int[] info = new int[INFO_BITS];
        int[] parity = new int[INFO_BITS];

        for(int i = 0; i < INFO_BITS; i++)
        {
            info[i] = deinterleaved[2 * i];
            parity[i] = deinterleaved[2 * i + 1];
        }

        int correctedBits = correctErrors(info, parity);

        int crcAccum = computeCrc(info);
        int receivedCrc = extractReceivedCrc(info);

        if(crcAccum != receivedCrc)
        {
            mConsecutiveBadOsw++;
            mOswQueue.addReset();
            if(mConsecutiveBadOsw >= MAX_BAD_OSW)
            {
                mLog.warn("OSW: " + mConsecutiveBadOsw + " consecutive CRC failures");
            }
            processQueue(messageListener);
            return;
        }

        mConsecutiveBadOsw = 0;

        int address = 0;
        for(int i = 0; i < 16; i++)
        {
            address = (address << 1) | info[i];
        }
        address ^= ID_XOR;
        address &= 0xFFFF;

        boolean isGroup = (info[16] ^ 1) != 0;

        int command = 0;
        for(int i = 17; i < 27; i++)
        {
            command = (command << 1) | info[i];
        }
        command ^= CMD_XOR;
        command &= 0x3FF;

        OswEntry entry = new OswEntry(address, isGroup, command, System.currentTimeMillis(), mBandplan);
        mOswQueue.add(entry);
        processQueue(messageListener);
    }

    private void processQueue(Listener<IMessage> messageListener)
    {
        if(!mOswQueue.isFull())
        {
            return;
        }

        List<MotorolaTypeIIMessage> messages = MotorolaTypeIIMessageFactory.process(mOswQueue, mBandplan);

        for(MotorolaTypeIIMessage message : messages)
        {
            messageListener.receive(message);
        }
    }

    private int[] deinterleave()
    {
        int[] deinterleaved = new int[PAYLOAD_BITS];
        for(int i = 0; i < PAYLOAD_BITS; i++)
        {
            deinterleaved[i] = mPayloadBuffer[(i % INTERLEAVE_COLS) * INTERLEAVE_ROWS + (i / INTERLEAVE_COLS)];
        }
        return deinterleaved;
    }

    private int correctErrors(int[] info, int[] parity)
    {
        int[] syndrome = new int[INFO_BITS];
        int corrected = 0;

        for(int i = 0; i < INFO_BITS; i++)
        {
            int expected = (i == 0) ? info[0] : (info[i] ^ info[i - 1]);
            syndrome[i] = expected ^ parity[i];
        }

        for(int i = 0; i < INFO_BITS - 1; i++)
        {
            if(syndrome[i] == 1 && syndrome[i + 1] == 1)
            {
                info[i] ^= 1;
                corrected++;
            }
        }

        return corrected;
    }

    private int computeCrc(int[] info)
    {
        // OP25 CRC algorithm - shift right with polynomial 0x0225
        int crcAccum = CRC_INIT;  // 0x0393
        int crcOp = 0x036E;

        for(int j = 0; j < DATA_BITS; j++)
        {
            if((crcOp & 0x01) != 0)
            {
                crcOp = (crcOp >> 1) ^ CRC_POLY;  // 0x0225
            }
            else
            {
                crcOp >>= 1;
            }

            if((info[j] & 0x01) != 0)
            {
                crcAccum ^= crcOp;
            }
        }

        return crcAccum;
    }

    private int extractReceivedCrc(int[] info)
    {
        // CRC bits are inverted in the frame
        int crcGiven = 0;
        for(int j = 0; j < CRC_BITS; j++)
        {
            crcGiven <<= 1;
            crcGiven += (~info[DATA_BITS + j]) & 0x01;
        }
        return crcGiven;
    }
}
