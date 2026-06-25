package io.github.dsheirer.module.decode.moto.channel;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.module.decode.moto.Bandplan;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;

import java.util.Objects;

public class MotorolaTypeIIChannel implements IChannelDescriptor
{
    private final int mChannelNumber;
    private final Bandplan mBandplan;

    public MotorolaTypeIIChannel(int channelNumber, Bandplan bandplan)
    {
        mChannelNumber = channelNumber;
        mBandplan = bandplan;
    }

    public int getChannelNumber()
    {
        return mChannelNumber;
    }

    @Override
    public long getDownlinkFrequency()
    {
        if(mBandplan == null)
        {
            return 0;
        }

        double freqMHz = mBandplan.getDownlinkFrequency(mChannelNumber);
        return freqMHz > 0 ? (long)(freqMHz * 1e6) : 0;
    }

    @Override
    public long getUplinkFrequency()
    {
        if(mBandplan == null)
        {
            return 0;
        }

        double freqMHz = mBandplan.getUplinkFrequency(mChannelNumber);
        return freqMHz > 0 ? (long)(freqMHz * 1e6) : 0;
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
        return Protocol.MOTOROLA_TYPE_II;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(o == null || getClass() != o.getClass())
        {
            return false;
        }

        MotorolaTypeIIChannel that = (MotorolaTypeIIChannel)o;
        return mChannelNumber == that.mChannelNumber;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mChannelNumber);
    }

    @Override
    public String toString()
    {
        return String.valueOf(mChannelNumber);
    }
}
