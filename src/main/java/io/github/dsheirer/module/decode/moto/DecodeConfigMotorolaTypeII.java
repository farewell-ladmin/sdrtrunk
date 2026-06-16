package io.github.dsheirer.module.decode.moto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * Decode configuration for Motorola Type II trunking systems (Smartnet, SmartZone,
 * SmartZone OmniLink, Hybrid).
 */
public class DecodeConfigMotorolaTypeII extends DecodeConfiguration
{
    public static final int CHANNEL_ROTATION_DELAY_MINIMUM_MS = 500;
    public static final int CHANNEL_ROTATION_DELAY_DEFAULT_MS = 500;
    public static final int CHANNEL_ROTATION_DELAY_MAXIMUM_MS = 2000;

    private static final int TRAFFIC_CHANNEL_POOL_SIZE_DEFAULT = 4;
    private static final int TRAFFIC_CHANNEL_POOL_SIZE_MIN = 1;
    private static final int TRAFFIC_CHANNEL_POOL_SIZE_MAX = 16;

    private BandplanType mBandplanType = BandplanType.EIGHT_HUNDRED_REBANTED;
    private double mObtBaseFrequency;
    private double mObtSpacing;
    private int mObtOffset;
    private int mTrafficChannelPoolSize = TRAFFIC_CHANNEL_POOL_SIZE_DEFAULT;
    private VoiceMode mDefaultVoiceMode = VoiceMode.ANALOG;

    public DecodeConfigMotorolaTypeII()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.MOTOROLA_TYPE_II;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "bandplan_type")
    public BandplanType getBandplanType()
    {
        return mBandplanType;
    }

    public void setBandplanType(BandplanType bandplanType)
    {
        mBandplanType = bandplanType != null ? bandplanType : BandplanType.EIGHT_HUNDRED_REBANTED;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "obt_base_frequency")
    public double getObtBaseFrequency()
    {
        return mObtBaseFrequency;
    }

    public void setObtBaseFrequency(double obtBaseFrequency)
    {
        mObtBaseFrequency = obtBaseFrequency;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "obt_spacing")
    public double getObtSpacing()
    {
        return mObtSpacing;
    }

    public void setObtSpacing(double obtSpacing)
    {
        mObtSpacing = obtSpacing;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "obt_offset")
    public int getObtOffset()
    {
        return mObtOffset;
    }

    public void setObtOffset(int obtOffset)
    {
        mObtOffset = obtOffset;
    }

    @JacksonXmlProperty(isAttribute = false, localName = "traffic_channel_pool_size")
    public int getTrafficChannelPoolSize()
    {
        return mTrafficChannelPoolSize;
    }

    public void setTrafficChannelPoolSize(int poolSize)
    {
        if(poolSize < TRAFFIC_CHANNEL_POOL_SIZE_MIN)
        {
            mTrafficChannelPoolSize = TRAFFIC_CHANNEL_POOL_SIZE_MIN;
        }
        else if(poolSize > TRAFFIC_CHANNEL_POOL_SIZE_MAX)
        {
            mTrafficChannelPoolSize = TRAFFIC_CHANNEL_POOL_SIZE_MAX;
        }
        else
        {
            mTrafficChannelPoolSize = poolSize;
        }
    }

    @JacksonXmlProperty(isAttribute = false, localName = "default_voice_mode")
    public VoiceMode getDefaultVoiceMode()
    {
        return mDefaultVoiceMode;
    }

    public void setDefaultVoiceMode(VoiceMode voiceMode)
    {
        mDefaultVoiceMode = voiceMode != null ? voiceMode : VoiceMode.ANALOG;
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(25000.0, 12500, 6000.0, 7000.0);
    }
}
