package io.github.dsheirer.module.decode.edacs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * EDACS decode configuration with per-LCN frequency list.
 * Frequencies are stored as a comma-separated string in XML, index = LCN-1.
 * Example: "851175000,851225000,851425000,..." for MBTA.
 */
public class DecodeConfigEDACS extends DecodeConfiguration
{
    private static final int MAX_LCN = 25;
    private String mLcnFrequencies = "";

    public DecodeConfigEDACS()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "lcnFrequencies")
    public String getLcnFrequencies()
    {
        return mLcnFrequencies;
    }

    public void setLcnFrequencies(String frequencies)
    {
        mLcnFrequencies = frequencies != null ? frequencies : "";
    }

    /**
     * Gets the frequency in Hz for the given LCN (1-based).
     * @param lcn logical channel number (1-25)
     * @return frequency in Hz or 0 if not set
     */
    public long getFrequency(int lcn)
    {
        if(lcn < 1 || lcn > MAX_LCN || mLcnFrequencies == null || mLcnFrequencies.isEmpty())
        {
            return 0;
        }

        String[] parts = mLcnFrequencies.split(",");
        if(lcn - 1 < parts.length)
        {
            try
            {
                return Long.parseLong(parts[lcn - 1].trim());
            }
            catch(NumberFormatException e)
            {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Gets all LCN frequencies as an array of Hz values (25 elements).
     * Unset LCNs will have 0.
     */
    public long[] getFrequencies()
    {
        long[] freqs = new long[MAX_LCN];
        if(mLcnFrequencies != null && !mLcnFrequencies.isEmpty())
        {
            String[] parts = mLcnFrequencies.split(",");
            for(int i = 0; i < Math.min(parts.length, MAX_LCN); i++)
            {
                try
                {
                    freqs[i] = Long.parseLong(parts[i].trim());
                }
                catch(NumberFormatException e)
                {
                    freqs[i] = 0;
                }
            }
        }
        return freqs;
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(50000.0, 12500, 6000.0, 7000.0);
    }
}
