// Reference: OP25 tk_smartnet.py (boatbod/op25), MPT1327TrafficChannelManager
package io.github.dsheirer.module.decode.moto;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.moto.channel.MotorolaTypeIIChannel;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessage;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MotorolaTypeIITrafficChannelManager extends TrafficChannelManager implements IDecodeEventProvider,
    IChannelEventListener, IChannelEventProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(MotorolaTypeIITrafficChannelManager.class);

    public static final String CHANNEL_START_REJECTED = "CHANNEL START REJECTED";
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";

    private Queue<Channel> mAvailableTrafficChannelQueue = new ConcurrentLinkedQueue<>();
    private List<Channel> mManagedTrafficChannels;
    private Map<MotorolaTypeIIChannel,Channel> mAllocatedTrafficChannelMap = new ConcurrentHashMap<>();
    private Map<MotorolaTypeIIChannel,DecodeEvent> mChannelGrantEventMap = new ConcurrentHashMap<>();
    private TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private Listener<ChannelEvent> mChannelEventListener;
    private Listener<IDecodeEvent> mDecodeEventListener;
    private final Bandplan mBandplan;

    public MotorolaTypeIITrafficChannelManager(Channel parentChannel, DecodeConfigMotorolaTypeII decodeConfig)
    {
        mBandplan = new Bandplan(decodeConfig.getBandplanType(),
                decodeConfig.getObtBaseFrequency(), decodeConfig.getObtSpacing(), decodeConfig.getObtOffset());
        createTrafficChannels(parentChannel, decodeConfig);
    }

    @Override
    protected void processControlFrequencyUpdate(long previous, long current, Channel channel)
    {
        MotorolaTypeIIChannel toRemove = null;

        for(MotorolaTypeIIChannel ch : mAllocatedTrafficChannelMap.keySet())
        {
            if(ch.getDownlinkFrequency() == current)
            {
                toRemove = ch;
                break;
            }
        }

        if(toRemove != null)
        {
            broadcast(new ChannelEvent(mAllocatedTrafficChannelMap.get(toRemove), ChannelEvent.Event.REQUEST_DISABLE));
        }
    }

    public void processChannelGrant(MotorolaTypeIIMessage message, IdentifierCollection identifierCollection,
                                     MotorolaTypeIIChannel channel)
    {
        DecodeEvent existingEvent = mChannelGrantEventMap.get(channel);

        if(existingEvent != null)
        {
            if(isSameTalkgroup(identifierCollection, existingEvent.getIdentifierCollection()))
            {
                existingEvent.update(message.getTimestamp());
                broadcast(existingEvent);
                return;
            }
            else if(mAllocatedTrafficChannelMap.containsKey(channel))
            {
                Channel trafficChannel = mAllocatedTrafficChannelMap.get(channel);
                broadcast(new ChannelEvent(trafficChannel, ChannelEvent.Event.REQUEST_DISABLE));
            }
        }

        DecodeEvent grantEvent = DecodeEvent.builder(DecodeEventType.CALL, message.getTimestamp())
                .channel(channel)
                .details("Traffic Channel Grant")
                .identifiers(identifierCollection)
                .protocol(io.github.dsheirer.protocol.Protocol.MOTOROLA_TYPE_II)
                .build();

        mChannelGrantEventMap.put(channel, grantEvent);

        if(channel.getDownlinkFrequency() == 0)
        {
            grantEvent.setDetails("Invalid Bandplan - No Frequency For Channel " + channel.getChannelNumber());
            broadcast(grantEvent);
            return;
        }

        Channel trafficChannel = mAvailableTrafficChannelQueue.poll();

        if(trafficChannel == null)
        {
            grantEvent.setDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED);
            broadcast(grantEvent);
            return;
        }

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(channel.getDownlinkFrequency());
        trafficChannel.setSourceConfiguration(sourceConfig);
        mAllocatedTrafficChannelMap.put(channel, trafficChannel);
        getInterModuleEventBus().post(new ChannelStartProcessingRequest(trafficChannel, channel,
            identifierCollection, this));

        broadcast(grantEvent);
    }

    public void processGroupUpdate(MotorolaTypeIIMessage message, MotorolaTypeIIChannel channel)
    {
        DecodeEvent existingEvent = mChannelGrantEventMap.get(channel);

        if(existingEvent != null)
        {
            existingEvent.update(message.getTimestamp());
            broadcast(existingEvent);
        }
    }

    private void createTrafficChannels(Channel parentChannel, DecodeConfigMotorolaTypeII decodeConfig)
    {
        List<Channel> trafficChannelList = new ArrayList<>();
        int maxTrafficChannels = decodeConfig.getTrafficChannelPoolSize();

        for(int x = 0; x < maxTrafficChannels; x++)
        {
            Channel trafficChannel = new Channel("T-" + parentChannel.getName(), Channel.ChannelType.TRAFFIC);
            trafficChannel.setAliasListName(parentChannel.getAliasListName());
            trafficChannel.setSystem(parentChannel.getSystem());
            trafficChannel.setSite(parentChannel.getSite());
            trafficChannel.setDecodeConfiguration(decodeConfig);
            trafficChannel.setEventLogConfiguration(parentChannel.getEventLogConfiguration());
            trafficChannel.setRecordConfiguration(parentChannel.getRecordConfiguration());
            trafficChannelList.add(trafficChannel);
        }

        mAvailableTrafficChannelQueue.addAll(trafficChannelList);
        mManagedTrafficChannels = Collections.unmodifiableList(trafficChannelList);
    }

    public void broadcast(DecodeEvent decodeEvent)
    {
        if(mDecodeEventListener != null)
        {
            mDecodeEventListener.receive(decodeEvent);
        }
    }

    @Override
    public Listener<ChannelEvent> getChannelEventListener()
    {
        return mTrafficChannelTeardownMonitor;
    }

    private void broadcast(ChannelEvent channelEvent)
    {
        if(mChannelEventListener != null)
        {
            mChannelEventListener.receive(channelEvent);
        }
    }

    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventListener = listener;
    }

    @Override
    public void removeChannelEventListener()
    {
        mChannelEventListener = null;
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
        mAvailableTrafficChannelQueue.clear();
        List<Channel> channels = new ArrayList<>(mAllocatedTrafficChannelMap.values());

        for(Channel channel : channels)
        {
            mLog.debug("Stopping traffic channel: " + channel);
            broadcast(new ChannelEvent(channel, ChannelEvent.Event.REQUEST_DISABLE));
        }
    }

    private boolean isSameTalkgroup(IdentifierCollection collection1, IdentifierCollection collection2)
    {
        Identifier toIdentifier1 = getToIdentifier(collection1);
        Identifier toIdentifier2 = getToIdentifier(collection2);
        return Objects.equals(toIdentifier1, toIdentifier2);
    }

    private Identifier getToIdentifier(IdentifierCollection collection)
    {
        List<Identifier> identifiers = collection.getIdentifiers(Role.TO);

        if(identifiers.size() >= 1)
        {
            return identifiers.get(0);
        }

        return null;
    }

    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = null;
    }

    public class TrafficChannelTeardownMonitor implements Listener<ChannelEvent>
    {
        @Override
        public synchronized void receive(ChannelEvent channelEvent)
        {
            Channel channel = channelEvent.getChannel();

            if(channel.isTrafficChannel() && mManagedTrafficChannels.contains(channel))
            {
                switch(channelEvent.getEvent())
                {
                    case NOTIFICATION_PROCESSING_STOP:
                        final var toRemove = motoChannelForChannel(channel);

                        if(toRemove != null)
                        {
                            mAllocatedTrafficChannelMap.remove(toRemove);
                            mAvailableTrafficChannelQueue.add(channel);

                            final var event = mChannelGrantEventMap.remove(toRemove);

                            if(event != null)
                            {
                                event.end(System.currentTimeMillis());
                                broadcast(event);
                            }
                        }
                        break;
                    case NOTIFICATION_PROCESSING_START_REJECTED:
                        final var rejected = motoChannelForChannel(channel);

                        if(rejected != null)
                        {
                            mAllocatedTrafficChannelMap.remove(rejected);
                            mAvailableTrafficChannelQueue.add(channel);

                            final var event = mChannelGrantEventMap.remove(rejected);

                            if(event != null)
                            {
                                if(channelEvent.getDescription() != null)
                                {
                                    event.setDetails(channelEvent.getDescription() + " - " + event.getDetails());
                                }
                                else
                                {
                                    event.setDetails(CHANNEL_START_REJECTED + " - " + event.getDetails());
                                }
                                broadcast(event);
                            }
                        }
                        break;
                }
            }
        }

        private MotorolaTypeIIChannel motoChannelForChannel(final Channel channel)
        {
            for(final var entry : mAllocatedTrafficChannelMap.entrySet())
            {
                if(entry.getValue() == channel)
                {
                    return entry.getKey();
                }
            }

            return null;
        }
    }
}
