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
    public static final int TRAFFIC_CHANNEL_LIMIT_DEFAULT_EDACS = 4;
    private String mLcnFrequencies = "";
    private VoiceMode mVoiceMode = VoiceMode.ANALOG;
    private int mTrafficChannelPoolSize = TRAFFIC_CHANNEL_LIMIT_DEFAULT_EDACS;

    public enum VoiceMode
    {
        /** Analog FM only (NBFM) */
        ANALOG("Analog (NBFM)"),
        /** ProVoice digital (IMBE 7100 via JMBE) */
        PROVOICE("ProVoice (Digital)"),
        /** Auto: ANALOG for MT1=0x06 grants, PROVOICE for MT1=0x03 grants */
        AUTO("Auto (Analog/Digital by grant)");

        private final String mLabel;
        VoiceMode(String label) { mLabel = label; }
        @Override public String toString() { return mLabel; }
    }

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

    @JacksonXmlProperty(isAttribute = false, localName = "voiceMode")
    public VoiceMode getVoiceMode()
    {
        return mVoiceMode;
    }

    public void setVoiceMode(VoiceMode mode)
    {
        mVoiceMode = mode != null ? mode : VoiceMode.ANALOG;
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

    /**
     * Resolves the effective voice mode for a single channel grant. In
     * AUTO mode this is selected by the digital flag on the grant
     * (MT1=0x03 -> ProVoice, MT1=0x06 -> analog).
     */
    public VoiceMode resolveVoiceMode(boolean isDigitalGrant)
    {
        if(mVoiceMode == VoiceMode.AUTO)
        {
            return isDigitalGrant ? VoiceMode.PROVOICE : VoiceMode.ANALOG;
        }
        return mVoiceMode;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "traffic_channel_pool_size")
    public int getTrafficChannelPoolSize()
    {
        return mTrafficChannelPoolSize;
    }

    /**
     * Maximum simultaneous traffic channels to allocate. EDACS ProVoice is
     * CPU-heavy, so keep the default conservative for busy sites.
     */
    public void setTrafficChannelPoolSize(int size)
    {
        mTrafficChannelPoolSize = Math.max(0, size);
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(50000.0, 12500, 6000.0, 7000.0);
    }
}
