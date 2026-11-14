// Reference: DSD-FME edacs-fme.c, MotorolaTypeIITrafficChannelManager
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.edacs.channel.EDACSChannel;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traffic channel manager for EDACS. Maintains a pool of reusable traffic
 * channel objects (each tied to an LCN via {@link EDACSChannel}), allocates
 * them in response to voice call grants from the control channel, and
 * tears them down on channel-stop notifications.
 *
 * <p>Pattern: DSD-FME {@code edacs-fme.c} eot_cc() cleanup + MPT1327
 * traffic manager. Decoding the call (analog NBFM or ProVoice) happens on
 * the traffic channel itself, not here; this manager only handles the
 * channel allocation and decode-event emission.</p>
 */
public class EDACSTrafficChannelManager extends TrafficChannelManager implements IDecodeEventProvider,
    IChannelEventListener, IChannelEventProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSTrafficChannelManager.class);

    public static final String CHANNEL_START_REJECTED = "CHANNEL START REJECTED";
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";
    private static final long CALL_STALE_MS = 3000;
    private static final long CALL_TEARDOWN_MS = 5000;
    private static final long CALL_UPDATE_BROADCAST_INTERVAL_MS = 1000;

    private final int mTrafficChannelPoolSize;
    private DecodeConfigEDACS mDecodeConfig;

    private Queue<Channel> mAvailableTrafficChannelQueue = new ConcurrentLinkedQueue<>();
    private Set<Channel> mAvailableTrafficChannelSet = ConcurrentHashMap.newKeySet();
    private List<Channel> mManagedTrafficChannels;
    private Map<EDACSChannel, Channel> mAllocatedTrafficChannelMap = new ConcurrentHashMap<>();
    private Map<EDACSChannel, DecodeEvent> mChannelGrantEventMap = new ConcurrentHashMap<>();
    private Map<EDACSChannel, Long> mLastGrantTimestampMap = new ConcurrentHashMap<>();
    private Map<EDACSChannel, Long> mLastGrantBroadcastTimestampMap = new ConcurrentHashMap<>();
    private Map<EDACSChannel, PendingChannelGrant> mPendingChannelGrantMap = new ConcurrentHashMap<>();
    private Set<EDACSChannel> mStaleCallSet = ConcurrentHashMap.newKeySet();
    private Set<EDACSChannel> mPendingTeardownSet = ConcurrentHashMap.newKeySet();
    private final TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private ScheduledFuture<?> mStaleCallMonitorFuture;
    private Listener<ChannelEvent> mChannelEventListener;
    private Listener<IDecodeEvent> mDecodeEventListener;

    public EDACSTrafficChannelManager(int trafficChannelPoolSize)
    {
        mTrafficChannelPoolSize = trafficChannelPoolSize;
    }

    @Override
    protected void processControlFrequencyUpdate(long previous, long current, Channel channel)
    {
        // If the control channel rotates to a frequency that is currently
        // hosting a traffic call, we don't have a way to know that from
        // the EDACSChannel (it doesn't track uplink frequency) - so this
        // is a no-op for now. The teardown monitor handles end-of-call.
    }

    /**
     * Process a voice call grant. Allocates a traffic channel, builds the
     * decode event, and starts processing.
     *
     * @param message the EDACS message carrying the grant
     * @param identifierCollection the parsed TO (talkgroup/target) and
     *        FROM (source radio) identifiers
     * @param edacsChannel the LCN-to-frequency channel descriptor
     */
    public synchronized void processChannelGrant(EDACSMessage message, IdentifierCollection identifierCollection,
                                     EDACSChannel edacsChannel)
    {
        if(edacsChannel == null || identifierCollection == null)
        {
            return;
        }

        DecodeEvent existingEvent = mChannelGrantEventMap.get(edacsChannel);

        if(existingEvent != null)
        {
            if(isSameTalkgroup(identifierCollection, existingEvent.getIdentifierCollection()))
            {
                // Same talkgroup on same LCN - this is a call update, not a new call.
                mLastGrantTimestampMap.put(edacsChannel, message.getTimestamp());
                mStaleCallSet.remove(edacsChannel);
                mPendingTeardownSet.remove(edacsChannel);
                existingEvent.update(message.getTimestamp());
                broadcastGrantUpdate(edacsChannel, existingEvent, message.getTimestamp());
                return;
            }
            else
            {
                // Different talkgroup on the same LCN - end the previous call.
                if(mAllocatedTrafficChannelMap.containsKey(edacsChannel))
                {
                    Channel previousTrafficChannel = mAllocatedTrafficChannelMap.get(edacsChannel);
                    mPendingChannelGrantMap.put(edacsChannel,
                        new PendingChannelGrant(message, identifierCollection, edacsChannel));
                    mPendingTeardownSet.add(edacsChannel);
                    broadcast(new ChannelEvent(previousTrafficChannel, ChannelEvent.Event.REQUEST_DISABLE));
                    return;
                }
            }
        }

        DecodeEvent grantEvent = DecodeEvent.builder(DecodeEventType.CALL, message.getTimestamp())
                .channel(edacsChannel)
                .details("Traffic Channel Grant - LCN " + edacsChannel.getChannelNumber())
                .identifiers(identifierCollection)
                .protocol(Protocol.EDACS)
                .build();

        mChannelGrantEventMap.put(edacsChannel, grantEvent);
        mLastGrantTimestampMap.put(edacsChannel, message.getTimestamp());
        mLastGrantBroadcastTimestampMap.put(edacsChannel, message.getTimestamp());
        mStaleCallSet.remove(edacsChannel);
        mPendingTeardownSet.remove(edacsChannel);

        if(edacsChannel.getDownlinkFrequency() == 0)
        {
            grantEvent.setDetails("Invalid Channel Map - No Frequency For LCN " +
                    edacsChannel.getChannelNumber());
            broadcast(grantEvent);
            clearChannelState(edacsChannel);
            return;
        }

        Channel trafficChannel = pollAvailableTrafficChannel();

        if(trafficChannel == null)
        {
            grantEvent.setDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED);
            broadcast(grantEvent);
            clearChannelState(edacsChannel);
            return;
        }

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(edacsChannel.getDownlinkFrequency());
        trafficChannel.setSourceConfiguration(sourceConfig);
        trafficChannel.setDecodeConfiguration(getTrafficDecodeConfiguration(message));
        mAllocatedTrafficChannelMap.put(edacsChannel, trafficChannel);
        getInterModuleEventBus().post(new ChannelStartProcessingRequest(trafficChannel, edacsChannel,
            identifierCollection, this));

        broadcast(grantEvent);

        mLog.debug("EDACS GRANT: TG/Target=0x{} Source=0x{} LCN={} Freq={} ({})",
                Integer.toHexString(message.getGroup()),
                Integer.toHexString(message.getSource()),
                edacsChannel.getChannelNumber(),
                edacsChannel.getDownlinkFrequency(),
                message.isDigital() ? "Digital" : "Analog");
    }

    /**
     * Build the pool of traffic channel objects once. Each channel can
     * be reused for any EDACS call (the source configuration is replaced
     * per grant).
     */
    public void createTrafficChannels(Channel parentChannel, io.github.dsheirer.module.decode.config.DecodeConfiguration decodeConfig)
    {
        if(decodeConfig instanceof DecodeConfigEDACS edacsConfig)
        {
            mDecodeConfig = edacsConfig;
        }

        List<Channel> trafficChannelList = new ArrayList<>();

        for(int x = 0; x < mTrafficChannelPoolSize; x++)
        {
            Channel trafficChannel = new Channel("EDACS-T-" + parentChannel.getName(),
                    Channel.ChannelType.TRAFFIC);
            trafficChannel.setAliasListName(parentChannel.getAliasListName());
            trafficChannel.setSystem(parentChannel.getSystem());
            trafficChannel.setSite(parentChannel.getSite());
            trafficChannel.setDecodeConfiguration(decodeConfig);
            trafficChannel.setEventLogConfiguration(parentChannel.getEventLogConfiguration());
            trafficChannel.setRecordConfiguration(parentChannel.getRecordConfiguration());
            trafficChannelList.add(trafficChannel);
        }

        for(Channel trafficChannel: trafficChannelList)
        {
            offerAvailableTrafficChannel(trafficChannel);
        }

        mManagedTrafficChannels = Collections.unmodifiableList(trafficChannelList);
    }

    private Channel pollAvailableTrafficChannel()
    {
        Channel channel = mAvailableTrafficChannelQueue.poll();

        if(channel != null)
        {
            mAvailableTrafficChannelSet.remove(channel);
        }

        return channel;
    }

    private void offerAvailableTrafficChannel(Channel channel)
    {
        if(mAvailableTrafficChannelSet.add(channel))
        {
            mAvailableTrafficChannelQueue.add(channel);
        }
        else
        {
            mLog.debug("Ignoring duplicate EDACS traffic channel pool return: {}", channel);
        }
    }

    private DecodeConfigEDACS getTrafficDecodeConfiguration(EDACSMessage message)
    {
        DecodeConfigEDACS config = new DecodeConfigEDACS();

        if(mDecodeConfig != null)
        {
            config.setLcnFrequencies(mDecodeConfig.getLcnFrequencies());
            config.setVoiceMode(mDecodeConfig.resolveVoiceMode(message.isDigital()));
        }
        else
        {
            config.setVoiceMode(message.isDigital() ? DecodeConfigEDACS.VoiceMode.PROVOICE :
                    DecodeConfigEDACS.VoiceMode.ANALOG);
        }

        return config;
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
        mLastGrantTimestampMap.clear();
        mLastGrantBroadcastTimestampMap.clear();
        mPendingChannelGrantMap.clear();
        mStaleCallSet.clear();
        mPendingTeardownSet.clear();
    }

    @Override
    public void start()
    {
        if(mStaleCallMonitorFuture == null || mStaleCallMonitorFuture.isCancelled())
        {
            mStaleCallMonitorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::checkStaleCalls,
                    1, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop()
    {
        if(mStaleCallMonitorFuture != null)
        {
            mStaleCallMonitorFuture.cancel(true);
            mStaleCallMonitorFuture = null;
        }

        mAvailableTrafficChannelQueue.clear();
        mAvailableTrafficChannelSet.clear();
        List<Channel> channels = new ArrayList<>(mAllocatedTrafficChannelMap.values());

        for(Channel channel : channels)
        {
            mLog.debug("Stopping EDACS traffic channel: " + channel);
            broadcast(new ChannelEvent(channel, ChannelEvent.Event.REQUEST_DISABLE));
        }
    }

    private void checkStaleCalls()
    {
        long now = System.currentTimeMillis();

        for(Map.Entry<EDACSChannel, Long> entry : mLastGrantTimestampMap.entrySet())
        {
            EDACSChannel edacsChannel = entry.getKey();
            long lastGrantTimestamp = entry.getValue();
            long age = now - lastGrantTimestamp;

            if(age >= CALL_STALE_MS && mStaleCallSet.add(edacsChannel))
            {
                DecodeEvent event = mChannelGrantEventMap.get(edacsChannel);

                if(event != null)
                {
                    event.end(lastGrantTimestamp);
                    broadcast(event);
                }
            }

            if(age >= CALL_TEARDOWN_MS && mPendingTeardownSet.add(edacsChannel))
            {
                Channel trafficChannel = mAllocatedTrafficChannelMap.get(edacsChannel);

                if(trafficChannel != null)
                {
                    broadcast(new ChannelEvent(trafficChannel, ChannelEvent.Event.REQUEST_DISABLE));
                }
            }
        }
    }

    private void broadcastGrantUpdate(EDACSChannel edacsChannel, DecodeEvent event, long timestamp)
    {
        long lastBroadcastTimestamp = mLastGrantBroadcastTimestampMap.getOrDefault(edacsChannel, 0L);

        if(timestamp - lastBroadcastTimestamp >= CALL_UPDATE_BROADCAST_INTERVAL_MS)
        {
            mLastGrantBroadcastTimestampMap.put(edacsChannel, timestamp);
            broadcast(event);
        }
    }

    private void clearChannelState(EDACSChannel edacsChannel)
    {
        mAllocatedTrafficChannelMap.remove(edacsChannel);
        mChannelGrantEventMap.remove(edacsChannel);
        mLastGrantTimestampMap.remove(edacsChannel);
        mLastGrantBroadcastTimestampMap.remove(edacsChannel);
        mPendingChannelGrantMap.remove(edacsChannel);
        mStaleCallSet.remove(edacsChannel);
        mPendingTeardownSet.remove(edacsChannel);
    }

    private void clearCompletedChannelState(EDACSChannel edacsChannel)
    {
        mAllocatedTrafficChannelMap.remove(edacsChannel);
        mLastGrantTimestampMap.remove(edacsChannel);
        mLastGrantBroadcastTimestampMap.remove(edacsChannel);
        mStaleCallSet.remove(edacsChannel);
        mPendingTeardownSet.remove(edacsChannel);
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

    /**
     * Receives channel-stop notifications and returns the underlying
     * traffic channel to the pool. Mirrors the moto manager's pattern.
     */
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
                        EDACSChannel toRemove = edacsChannelForChannel(channel);

                        if(toRemove != null)
                        {
                            mAllocatedTrafficChannelMap.remove(toRemove);
                            PendingChannelGrant pendingGrant = mPendingChannelGrantMap.remove(toRemove);
                            clearCompletedChannelState(toRemove);
                            offerAvailableTrafficChannel(channel);

                            DecodeEvent event = mChannelGrantEventMap.remove(toRemove);

                            if(event != null)
                            {
                                event.end(System.currentTimeMillis());
                                broadcast(event);
                            }

                            if(pendingGrant != null)
                            {
                                processChannelGrant(pendingGrant.message(), pendingGrant.identifierCollection(),
                                    pendingGrant.edacsChannel());
                            }
                        }
                        break;
                    case NOTIFICATION_PROCESSING_START_REJECTED:
                        EDACSChannel rejected = edacsChannelForChannel(channel);

                        if(rejected != null)
                        {
                            mAllocatedTrafficChannelMap.remove(rejected);
                            PendingChannelGrant pendingGrant = mPendingChannelGrantMap.remove(rejected);
                            clearCompletedChannelState(rejected);
                            offerAvailableTrafficChannel(channel);

                            DecodeEvent event = mChannelGrantEventMap.remove(rejected);

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

                            if(pendingGrant != null)
                            {
                                processChannelGrant(pendingGrant.message(), pendingGrant.identifierCollection(),
                                    pendingGrant.edacsChannel());
                            }
                        }
                        break;
                }
            }
        }

        private EDACSChannel edacsChannelForChannel(Channel channel)
        {
            for(Map.Entry<EDACSChannel, Channel> entry : mAllocatedTrafficChannelMap.entrySet())
            {
                if(entry.getValue() == channel)
                {
                    return entry.getKey();
                }
            }
            return null;
        }
    }

    private record PendingChannelGrant(EDACSMessage message, IdentifierCollection identifierCollection,
                                       EDACSChannel edacsChannel)
    {
    }
}
