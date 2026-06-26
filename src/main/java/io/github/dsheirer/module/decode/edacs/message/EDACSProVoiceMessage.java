package io.github.dsheirer.module.decode.edacs.message;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.protocol.Protocol;

import java.util.ArrayList;
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
    private final List<Identifier> mIdentifiers;

    public EDACSProVoiceMessage(byte[] imbeFrame1, byte[] imbeFrame2, byte[] imbeFrame3, byte[] imbeFrame4,
                                int[][][] imbeGrids, boolean[] frameBits, int lid, int bfMarker,
                                String syncPattern, long timestamp)
    {
        this(imbeFrame1, imbeFrame2, imbeFrame3, imbeFrame4, imbeGrids, frameBits, lid, bfMarker, syncPattern,
                timestamp, Collections.emptyList());
    }

    public EDACSProVoiceMessage(byte[] imbeFrame1, byte[] imbeFrame2, byte[] imbeFrame3, byte[] imbeFrame4,
                                int[][][] imbeGrids, boolean[] frameBits, int lid, int bfMarker,
                                String syncPattern, long timestamp, List<Identifier> identifiers)
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
        mIdentifiers = identifiers != null ? Collections.unmodifiableList(new ArrayList<>(identifiers)) :
                Collections.emptyList();
    }

    public byte[][] getImbeFrames()
    {
        return mImbeFrames;
    }

    /**
     * ProVoice IMBE7100 grids packed row-major, MSB first, for the JMBE PROVOICE codec.
     */
    public byte[][] getPackedImbe7100Frames()
    {
        byte[][] frames = new byte[4][];

        for(int x = 0; x < frames.length; x++)
        {
            frames[x] = packGrid(mImbeGrids[x]);
        }

        return frames;
    }

    private byte[] packGrid(int[][] grid)
    {
        byte[] bytes = new byte[21];
        int bit = 0;

        for(int row = 0; row < grid.length; row++)
        {
            for(int column = 0; column < grid[row].length; column++)
            {
                if(grid[row][column] != 0)
                {
                    bytes[bit / 8] |= (byte)(0x80 >> (bit % 8));
                }

                bit++;
            }
        }

        return bytes;
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
        return mIdentifiers;
    }
}
