// Reference: DSD-FME edacs-fme.c, MotorolaTypeIIDecoderState
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.channel.EDACSChannel;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoderState.class);
    private static final int STATS_LOG_INTERVAL = 1000;

    private final EDACSTrafficChannelManager mTrafficChannelManager;
    private final ChannelType mChannelType;
    private final java.util.function.IntFunction<Long> mFrequencyLookup;

    private int mSystemId;
    private int mSiteId;
    private int mAreaCode;
    private int mCcLcn;
    private boolean mSystemIdDecoded;
    private boolean mSiteIdDecoded;
    private boolean mCcLcnDecoded;

    private int mTotalMessagesDecoded;
    private int mBchErrors;

    public EDACSDecoderState(Channel channel, EDACSTrafficChannelManager trafficChannelManager)
    {
        mTrafficChannelManager = trafficChannelManager;
        mChannelType = channel.isTrafficChannel() ? ChannelType.TRAFFIC : ChannelType.STANDARD;

        java.util.function.IntFunction<Long> lookup = null;
        if(channel.getDecodeConfiguration() instanceof DecodeConfigEDACS edacsConfig)
        {
            lookup = edacsConfig::getFrequency;
        }
        mFrequencyLookup = lookup;
    }

    public int getSystemId()
    {
        return mSystemId;
    }

    public int getSiteId()
    {
        return mSiteId;
    }

    public int getAreaCode()
    {
        return mAreaCode;
    }

    public int getCcLcn()
    {
        return mCcLcn;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public void receive(IMessage message)
    {
        if(!(message instanceof EDACSMessage edacs))
        {
            return;
        }

        if(!edacs.isValid())
        {
            mBchErrors++;
            mTotalMessagesDecoded++;
            logStatsIfDue();
            return;
        }

        mTotalMessagesDecoded++;
        EDACSMessageType type = edacs.getMessageType();

        mLog.info("EDACS RX: {}", edacs);

        switch(type)
        {
            case GROUP_CALL:
            case DIGITAL_GROUP_CALL:
            case ANALOG_GROUP_CALL:
            case TDMA_GROUP_CALL:
            case ALL_CALL:
            case INDIVIDUAL_CALL:
                processChannelGrant(edacs);
                break;
            case SYSTEM_INFO:
                processSystemInfo(edacs);
                break;
            case EXTENDED_ADDRESSING:
                processExtendedAddressing(edacs);
                break;
            default:
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
                break;
        }

        logStatsIfDue();
    }

    private void processChannelGrant(EDACSMessage message)
    {
        int lcn = message.getLCN();
        if(lcn <= 0)
        {
            return;
        }

        EDACSChannel edacsChannel = new EDACSChannel(lcn);
        edacsChannel.setFrequencyLookup(mFrequencyLookup);

        MutableIdentifierCollection ic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
        ic.remove(IdentifierClass.USER);
        ic.update(message.getIdentifiers());

        String mode = message.isDigital() ? "DIGITAL" : "ANALOG";
        String callType = message.getMessageType() == EDACSMessageType.INDIVIDUAL_CALL ? "Private Call" :
                message.getMessageType() == EDACSMessageType.ALL_CALL ? "All-Call" : "Group Grant";
        long freq = edacsChannel.getDownlinkFrequency();
        String freqStr = freq > 0 ? String.format("%.4f MHz", freq / 1e6) : "unknown freq";

        mLog.info("EDACS {} {} TG:0x{} Src:0x{} LCN:{} ({})", mode, callType,
                Integer.toHexString(message.getGroup()),
                Integer.toHexString(message.getSource()),
                lcn, freqStr);

        if(mTrafficChannelManager != null)
        {
            mTrafficChannelManager.processChannelGrant(message, ic, edacsChannel);
        }

        // Control channel stays in CONTROL state - CALL is for traffic channels only
        broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
    }

    private void processSystemInfo(EDACSMessage message)
    {
        if(message.getSystemId() != 0)
        {
            mSystemId = message.getSystemId();
        }
        if(message.getCcLcn() != 0)
        {
            mCcLcn = message.getCcLcn();
        }

        if(!mSystemIdDecoded && mSystemId != 0)
        {
            mSystemIdDecoded = true;
            mLog.info("EDACS System ID decoded: 0x{}", Integer.toHexString(mSystemId));
        }
        if(!mCcLcnDecoded && mCcLcn != 0)
        {
            mCcLcnDecoded = true;
            mLog.info("EDACS CC LCN decoded: {}", mCcLcn);
        }

        broadcast(new DecoderStateEvent(this, Event.START, State.CONTROL));
    }

    private void processExtendedAddressing(EDACSMessage message)
    {
        mSiteId = message.getSiteId();
        mAreaCode = message.getArea();

        if(!mSiteIdDecoded && mSiteId != 0)
        {
            mSiteIdDecoded = true;
            mLog.info("EDACS Site ID decoded: 0x{} ({}), Area 0x{} ({})",
                    Integer.toHexString(mSiteId), mSiteId,
                    Integer.toHexString(mAreaCode), mAreaCode);
        }

        broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
    }

    private void logStatsIfDue()
    {
        if(mTotalMessagesDecoded > 0 && mTotalMessagesDecoded % STATS_LOG_INTERVAL == 0)
        {
            mLog.info("EDACS stats: messages={} BCH_errors={} sys=0x{} site={} area={} ccLcn={}",
                    mTotalMessagesDecoded, mBchErrors,
                    Integer.toHexString(mSystemId), mSiteId, mAreaCode, mCcLcn);
        }
    }

    @Override
    public void init()
    {
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Decoder:\tEDACS\n");
        sb.append("System ID:\t").append(String.format("0x%04X", mSystemId)).append("\n");
        sb.append("Site ID:\t").append(mSiteId).append("\n");
        sb.append("Area:\t").append(mAreaCode).append("\n");
        sb.append("CC LCN:\t").append(mCcLcn).append("\n");
        sb.append("Messages:\t").append(mTotalMessagesDecoded).append("\n");
        sb.append("BCH Errors:\t").append(mBchErrors).append("\n");
        return sb.toString();
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        if(event.getEvent() == Event.REQUEST_RESET)
        {
            resetState();
        }
    }

    @Override
    public void reset()
    {
        super.reset();
        resetState();
    }

    @Override
    public void start()
    {
        super.start();

        if(mChannelType == ChannelType.TRAFFIC)
        {
            broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
        }
    }

    protected void resetState()
    {
        super.resetState();
    }
}
