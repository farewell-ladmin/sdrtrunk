package io.github.dsheirer.module.decode.edacs.channel;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;

/**
 * EDACS channel descriptor. Maps a Logical Channel Number (LCN) to a downlink frequency
 * using a user-provided ChannelMap.
 */
public class EDACSChannel implements IChannelDescriptor
{
    private int mChannelNumber;
    private ChannelMap mChannelMap;

    public EDACSChannel(int channelNumber)
    {
        mChannelNumber = channelNumber;
    }

    public void setChannelMap(ChannelMap channelMap)
    {
        mChannelMap = channelMap;
    }

    public int getChannelNumber()
    {
        return mChannelNumber;
    }

    @Override
    public long getDownlinkFrequency()
    {
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
