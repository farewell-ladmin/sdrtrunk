// Reference: OP25 tk_smartnet.py (boatbod/op25), Trunk Recorder smartnet_parser.cc
package io.github.dsheirer.module.decode.moto;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.moto.channel.MotorolaTypeIIChannel;
import io.github.dsheirer.module.decode.moto.identifier.MotorolaTypeIITalkgroup;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessage;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MotorolaTypeIIDecoderState extends DecoderState
{
    private final static Logger mLog = LoggerFactory.getLogger(MotorolaTypeIIDecoderState.class);

    private final MotorolaTypeIITrafficChannelManager mTrafficChannelManager;
    private final ChannelType mChannelType;
    private final Bandplan mBandplan;

    private int mSystemId;
    private int mSiteId;

    public MotorolaTypeIIDecoderState(Channel channel, MotorolaTypeIITrafficChannelManager trafficChannelManager)
    {
        mTrafficChannelManager = trafficChannelManager;
        mChannelType = channel.isTrafficChannel() ? ChannelType.TRAFFIC : ChannelType.STANDARD;

        DecodeConfigMotorolaTypeII config = null;

        if(channel.getDecodeConfiguration() instanceof DecodeConfigMotorolaTypeII dcm)
        {
            config = dcm;
        }

        mBandplan = config != null ? new Bandplan(config.getBandplanType(),
                config.getObtBaseFrequency(), config.getObtSpacing(), config.getObtOffset()) :
                new Bandplan(BandplanType.EIGHT_HUNDRED_REBANTED);
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.MOTOROLA_TYPE_II;
    }

    @Override
    public void receive(IMessage message)
    {
        if(!message.isValid())
        {
            return;
        }

        if(!(message instanceof MotorolaTypeIIMessage moto))
        {
            return;
        }

        MotorolaTypeIIMessageType type = moto.getMessageType();

        switch(type)
        {
            case ANALOG_GROUP_GRANT:
            case DIGITAL_GROUP_GRANT:
                processChannelGrant(moto);
                break;
            case GROUP_UPDATE:
                processGroupUpdate(moto);
                break;
            case SYSTEM_ID:
                processSystemId(moto);
                break;
            case AMSS:
                processAmss(moto);
                break;
            case NETWORK_STATUS:
            case SYSTEM_STATUS:
                processStatus(moto);
                break;
            case IDLE:
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
                break;
            default:
                mLog.debug("Moto T2: {}", moto);
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
                break;
        }
    }

    private void processChannelGrant(MotorolaTypeIIMessage message)
    {
        int channelNumber = message.getChannelNumber();
        int talkgroup = message.getAddress();

        MutableIdentifierCollection ic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
        ic.remove(IdentifierClass.USER);
        ic.update(new MotorolaTypeIITalkgroup(talkgroup));

        MotorolaTypeIIChannel channel = new MotorolaTypeIIChannel(channelNumber, mBandplan);

        String mode = message.getMessageType() == MotorolaTypeIIMessageType.DIGITAL_GROUP_GRANT ? "DIGITAL" : "ANALOG";
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, message.getTimestamp())
                .channel(channel)
                .identifiers(ic)
                .details(mode + " Group Grant CH:" + channelNumber + " TG:" + talkgroup)
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();
        broadcast(event);

        if(mTrafficChannelManager != null)
        {
            mTrafficChannelManager.processChannelGrant(message, ic, channel);
        }

        broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
    }

    private void processGroupUpdate(MotorolaTypeIIMessage message)
    {
        int channelNumber = message.getAddress();

        MotorolaTypeIIChannel channel = new MotorolaTypeIIChannel(channelNumber, mBandplan);

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_IN_PROGRESS, message.getTimestamp())
                .channel(channel)
                .details("Group Update CH:" + channelNumber)
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();
        broadcast(event);

        if(mTrafficChannelManager != null)
        {
            mTrafficChannelManager.processGroupUpdate(message, channel);
        }

        broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
    }

    private void processSystemId(MotorolaTypeIIMessage message)
    {
        mSystemId = message.getAddress();
        mLog.info("Moto T2 System ID: 0x{}", Integer.toHexString(mSystemId));

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.STATUS, message.getTimestamp())
                .details("System ID: " + String.format("0x%04X", mSystemId))
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();
        broadcast(event);

        broadcast(new DecoderStateEvent(this, Event.START, State.CONTROL));
    }

    private void processAmss(MotorolaTypeIIMessage message)
    {
        mSiteId = message.getCommand() - 0x360 + 1;
        mLog.info("Moto T2 Site ID: {}", mSiteId);

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.STATUS, message.getTimestamp())
                .details("AMSS Site ID: " + mSiteId)
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();
        broadcast(event);

        broadcast(new DecoderStateEvent(this, Event.START, State.CONTROL));
    }

    private void processStatus(MotorolaTypeIIMessage message)
    {
        mLog.debug("Moto T2 Status: {}", message);

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.STATUS, message.getTimestamp())
                .details(message.getMessageType().name())
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();
        broadcast(event);

        broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
    }

    @Override
    public void init()
    {
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Decoder:\tMotorola Type II\n");
        sb.append("System ID:\t").append(String.format("0x%04X", mSystemId)).append("\n");
        sb.append("Site ID:\t").append(mSiteId).append("\n");
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
