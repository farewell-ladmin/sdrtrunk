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
    private final int mLid;
    private final long mTimestamp;

    public EDACSProVoiceMessage(byte[] imbeFrame1, byte[] imbeFrame2, byte[] imbeFrame3, byte[] imbeFrame4,
                               int lid, long timestamp)
    {
        mImbeFrames[0] = imbeFrame1;
        mImbeFrames[1] = imbeFrame2;
        mImbeFrames[2] = imbeFrame3;
        mImbeFrames[3] = imbeFrame4;
        mLid = lid;
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
