package io.github.dsheirer.module.decode.edacs.message;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.protocol.Protocol;

import java.util.Collections;
import java.util.List;

/**
 * EDACS ProVoice message carrying 4 IMBE 7100 voice frames (one per
 * 20 ms of audio) decoded from a single outbound ProVoice frame.
 */
public class EDACSProVoiceMessage implements IMessage
{
    private final byte[][] mImbeFrames = new byte[4][];
    private final int[][][] mImbeGrids;
    private final boolean[] mFrameBits;
    private final int mLid;
    private final int mBfMarker;
    private final String mSyncPattern;
    private final long mTimestamp;

    public EDACSProVoiceMessage(byte[] imbeFrame1, byte[] imbeFrame2, byte[] imbeFrame3, byte[] imbeFrame4,
                                int[][][] imbeGrids, boolean[] frameBits, int lid, int bfMarker,
                                String syncPattern, long timestamp)
    {
        mImbeFrames[0] = imbeFrame1;
        mImbeFrames[1] = imbeFrame2;
        mImbeFrames[2] = imbeFrame3;
        mImbeFrames[3] = imbeFrame4;
        mImbeGrids = imbeGrids;
        mFrameBits = frameBits;
        mLid = lid;
        mBfMarker = bfMarker;
        mSyncPattern = syncPattern;
        mTimestamp = timestamp;
    }

    public byte[][] getImbeFrames()
    {
        return mImbeFrames;
    }

    public int getLid()
    {
        return mLid;
    }

    /**
     * Four IMBE 7100 7x24 grids before packing into JMBE's 18-byte IMBE input format.
     */
    public int[][][] getImbeGrids()
    {
        return mImbeGrids;
    }

    public boolean[] getFrameBits()
    {
        return mFrameBits;
    }

    public int getBfMarker()
    {
        return mBfMarker;
    }

    public String getSyncPattern()
    {
        return mSyncPattern;
    }

    @Override
    public long getTimestamp()
    {
        return mTimestamp;
    }

    @Override
    public boolean isValid()
    {
        for(byte[] frame : mImbeFrames)
        {
            if(frame == null || frame.length != 18)
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }

    @Override
    public int getTimeslot()
    {
        return 0;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
