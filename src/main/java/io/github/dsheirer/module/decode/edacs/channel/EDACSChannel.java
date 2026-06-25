package io.github.dsheirer.module.decode.edacs.channel;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;

/**
 * EDACS channel descriptor. Maps a Logical Channel Number (LCN) to a
 * downlink frequency.
 *
 * <p>Two ways to populate frequencies:</p>
 * <ul>
 *   <li>{@link #setChannelMap} - sdrtrunk {@link
 *       io.github.dsheirer.controller.channel.map.ChannelMap} (general
 *       multi-protocol channel maps)</li>
 *   <li>{@link #setFrequencyLookup} - a direct LCN-to-frequency lookup
 *       (used by the EDACS traffic channel manager, which gets the
 *       frequencies from the {@link DecodeConfigEDACS} config)</li>
 * </ul>
 */
public class EDACSChannel implements IChannelDescriptor
{
    private final int mChannelNumber;

    private io.github.dsheirer.controller.channel.map.ChannelMap mChannelMap;
    private java.util.function.IntFunction<Long> mFrequencyLookup;

    public EDACSChannel(int channelNumber)
    {
        mChannelNumber = channelNumber;
    }

    public void setChannelMap(io.github.dsheirer.controller.channel.map.ChannelMap channelMap)
    {
        mChannelMap = channelMap;
    }

    /**
     * Sets a function that returns the downlink frequency in Hz for a
     * given 1-based LCN. Used when the LCN-to-frequency mapping is
     * stored in a {@link DecodeConfigEDACS} rather than a
     * general {@link ChannelMap}.
     */
    public void setFrequencyLookup(java.util.function.IntFunction<Long> lookup)
    {
        mFrequencyLookup = lookup;
    }

    public int getChannelNumber()
    {
        return mChannelNumber;
    }

    @Override
    public long getDownlinkFrequency()
    {
        if(mFrequencyLookup != null)
        {
            Long freq = mFrequencyLookup.apply(mChannelNumber);
            return freq != null ? freq : 0L;
        }
        if(mChannelMap != null)
        {
            return mChannelMap.getFrequency(mChannelNumber);
        }
        return 0;
    }

    @Override
    public long getUplinkFrequency()
    {
        return 0;
    }

    @Override
    public int[] getFrequencyBandIdentifiers()
    {
        return new int[0];
    }

    @Override
    public void setFrequencyBand(IFrequencyBand bandIdentifier)
    {
    }

    @Override
    public boolean isTDMAChannel()
    {
        return false;
    }

    @Override
    public int getTimeslotCount()
    {
        return 1;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }

    @Override
    public int hashCode()
    {
        return Integer.hashCode(mChannelNumber);
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o) return true;
        if(!(o instanceof EDACSChannel that)) return false;
        return mChannelNumber == that.mChannelNumber;
    }
}
