/*******************************************************************************
 * Copyright (c) 2018, TechEmpower, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name TechEmpower, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL TECHEMPOWER, INC. BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/

package com.techempower.gemini.cluster.jms;

import java.util.*;
import javax.jms.*;
import org.slf4j.*;
import com.techempower.cache.*;
import com.techempower.collection.relation.*;
import com.techempower.data.*;
import com.techempower.gemini.*;
import com.techempower.gemini.cluster.*;
import com.techempower.gemini.cluster.message.*;
import com.techempower.util.*;

/**
 * Handles cache maintenance messages for both sending and handling updates.
 * Does not repeat actions sent from self.
 * <p>
 * Currently only handles one async message at a time. This should be fine.
 * </p>
 */
public class CacheMessageManager
    implements CacheListener, CachedRelationListener, DistributionListener, Configurable
{
  public static final String      CACHE_TOPIC_DESTINATION = "CACHE.TOPIC";
  public static final String      MESSAGE_PROPERTY_UUID   = "Gemini.CacheMgr.ClientUUID";
  public static final long        DEFAULT_STATS_PERIOD_MINUTES = 10;
  public static final long        DEFAULT_STATS_LOG_MAX_THRESHOLD_MS = 10;

  //
  // Variables.
  //

  private final GeminiApplication application;
  private final Logger            log = LoggerFactory.getLogger(getClass());
  private final EntityStore       store;
  private ConnectionFactory       publishConnectionFactory;
  private Connection              publishConnection;
  private ConnectionFactory       subscribeConnectionFactory;
  private Connection              subscribeConnection;
  private GeminiPublisher         publisher;
  private AsyncSubscriber         subscriber;
  private String                  instanceID;
  private int                     maximumRelationSize     = 10000;
  private int                     deliveryMode            = DeliveryMode.PERSISTENT;
  private long                    statsPeriodMinutes      = DEFAULT_STATS_PERIOD_MINUTES;
  private long                    statsLogMaxThresholdMs  = DEFAULT_STATS_LOG_MAX_THRESHOLD_MS;

  //
  // Methods.
  //

  /**
   * Constructor.
   */
  public CacheMessageManager(GeminiApplication application,
      ConnectionFactory publishConnectionFactory, ConnectionFactory subscribeConnectionFactory)
  {
    this.application = application;
    this.store = application.getStore();
    this.publishConnectionFactory = publishConnectionFactory;
    this.subscribeConnectionFactory = subscribeConnectionFactory;
    this.application.getConfigurator().addConfigurable(this);
  }

  @Override
  public void configure(EnhancedProperties props)
  {
    String propsPrefix = "CacheMessageManager.";
    this.maximumRelationSize = props.getInt(
        propsPrefix + "MaximumRelationSize", this.maximumRelationSize);
    log.info("[CacheMessageManager.MaximumRelationSize: " + maximumRelationSize + "]");
    this.deliveryMode = props.getInt(propsPrefix + "DeliveryMode", this.deliveryMode);
    log.info("[CacheMessageManager.DeliveryMode: " + deliveryMode + "]");
    this.statsPeriodMinutes = props.getLong("StatsPeriodMinutes", DEFAULT_STATS_PERIOD_MINUTES);
    log.info("[CacheMessageManager.StatsPeriodMinutes: " + statsPeriodMinutes + "]");
    this.statsLogMaxThresholdMs = props.getLong("StatsLogMaxThresholdMs", DEFAULT_STATS_LOG_MAX_THRESHOLD_MS);
    log.info("[CacheMessageManager.StatsLogMaxThresholdMs: " + statsLogMaxThresholdMs + "]");
  }

  /**
   * Starts the publisher/subscriber to the cache message queue (topic).
   * 
   * @throws JMSException
   */
  public CacheMessageManager start() throws JMSException
  {
    connect(publishConnectionFactory, subscribeConnectionFactory);
    return this;
  }

  /**
   * Restarts a connection
   */
  public void connect(ConnectionFactory publishConnectionFactory, ConnectionFactory subscribeConnectionFactory) throws JMSException
  {
    // Close existing publisher or subscriber
    if (this.publisher != null)
    {
      this.publisher.close();
    }
    if (this.subscriber != null)
    {
      this.subscriber.close();
    }

    // Create publish connection
    this.publishConnection = publishConnectionFactory.createConnection();
    publishConnection.start();
    instanceID = publishConnection.getClientID();
    this.publisher = new GeminiPublisher(publishConnection,
        CacheMessageManager.CACHE_TOPIC_DESTINATION, deliveryMode);
    publisher.start();
    log.info("JMS publish connection established   @{}", instanceID);

    // Create subscribe connection
    this.subscribeConnection = subscribeConnectionFactory.createConnection();
    subscribeConnection.start();
    this.subscriber = new AsyncSubscriber(subscribeConnection,
        CacheMessageManager.CACHE_TOPIC_DESTINATION);
    subscriber.start(new CacheSignalListener(this.application));
    log.info("JMS subscribe connection established @{}", subscribeConnection.getClientID());
  }

  /**
   * Closes JMS connection
   */
  public void close()
  {
    log.info("CacheMessageManager is closing.");
    if (publisher != null)
    {
      publisher.close();
    }
    if (subscriber != null)
    {
      subscriber.close();
    }
  }

  /**
   * Send helper attaches the necessary properties to prevent this instance
   * from executing any commands sent from itself (everyone subscribes and can
   * publish to the topic queue).
   */
  private void send(BroadcastMessage message)
  {
    try
    {
      this.publisher.send(message, MESSAGE_PROPERTY_UUID,
          publishConnection.getClientID());
    }
    catch (JMSException e)
    {
      log.info("CacheMessageManager::send caught ", e);
    }
  }

  //
  // CacheListener methods
  //

  @Override
  public void cacheFullReset()
  {
    // This is not a good idea. All instances will be slamming the DB server.
    log.info("Distributing a full cache reset is disabled.");

//    final CacheMessage message = new CacheMessage();
//    message.setAction(CacheMessage.ACTION_FULL_RESET);
//    send(message);
  }

  @Override
  public <T extends Identifiable> void cacheTypeReset(Class<T> type)
  {
    final EntityGroup<T> group = this.store.getGroup(type);
    if (!group.distribute())
    {
      return; // Don't distribute notifications.
    }
    log.info("Sending 'cache type reset': {}", type.getSimpleName());

    final CacheMessage message = new CacheMessage();
    message.setAction(CacheMessage.ACTION_GROUP_RESET);
    message.setGroupId(group.getGroupNumber());
    send(message);
  }

  @Override
  public <T extends Identifiable> void cacheObjectExpired(Class<T> type,
      long identifier)
  {
    final EntityGroup<T> group = this.store.getGroup(type);
    if (!group.distribute())
    {
      return; // Don't distribute notifications.
    }
    log.info("Sending 'cache object expired': {}/{}",
        type.getSimpleName(), identifier);

    final T entity = group.get(identifier);

    // The entity could be null here if it was updated, and before the
    // listeners were notified of the update, it was removed from the cache.
    // In that case, don't bother sending an expiration message, because a
    // removal message will be sent.
    if (entity != null)
    {
      CacheMessage message = new CacheMessage();
      message.setAction(CacheMessage.ACTION_OBJECT_RESET);
      message.setGroupId(group.getGroupNumber());
      message.setObjectId(identifier);
      message.setObjectProperties(group.writeMap(entity));
      send(message);
    }
  }

  @Override
  public <T extends Identifiable> void removeFromCache(Class<T> type,
      long identifier)
  {
    final EntityGroup<T> group = this.store.getGroup(type);
    if (!group.distribute())
    {
      return; // Don't distribute notifications.
    }
    log.info("Sending 'remove from cache': {}/{}",
        type.getSimpleName(), identifier);

    final CacheMessage message = new CacheMessage();
    message.setAction(CacheMessage.ACTION_OBJECT_REMOVE);
    message.setGroupId(group.getGroupNumber());
    message.setObjectId(identifier);
    send(message);
  }

  //
  // CachedRelationListener methods
  //

  @Override
  public void add(long relationID, long leftID, long rightID)
  {
    log.info("Sending 'rel add': l{}/r{}", leftID, rightID);
    final CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_ADD);
    message.setRelationId(relationID);
    message.setLeftId(leftID);
    message.setRightId(rightID);
    send(message);
  }

  @Override
  public void addAll(long relationID, LongRelation relation)
  {
    if (relation.size() > this.maximumRelationSize)
    {
      reset(relationID);
    }
    else
    {
      log.info("Sending 'rel add all': rel{}", relationID);
      final CachedRelationMessage message = new CachedRelationMessage();
      message.setAction(CachedRelationMessage.ACTION_ADD_ALL);
      message.setRelationId(relationID);
      message.setRelation(relation);
      send(message);
    }
  }

  @Override
  public void clear(long relationID)
  {
    log.info("Sending 'rel clear': rel{}", relationID);
    final CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_CLEAR);
    message.setRelationId(relationID);
    send(message);
  }

  @Override
  public void remove(long relationID, long leftID, long rightID)
  {
    log.info("Sending 'rel remove': rel{}/l{}/r{}",
        relationID, leftID, rightID);
    CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_REMOVE);
    message.setRelationId(relationID);
    message.setLeftId(leftID);
    message.setRightId(rightID);
    send(message);
  }

  @Override
  public void removeAll(long relationID, LongRelation relation)
  {
    if (relation.size() > this.maximumRelationSize)
    {
      reset(relationID);
    }
    else
    {
      log.info("Sending 'rel remove all': rel{}", relationID);
      final CachedRelationMessage message = new CachedRelationMessage();
      message.setAction(CachedRelationMessage.ACTION_REMOVE_ALL);
      message.setRelationId(relationID);
      message.setRelation(relation);
      send(message);
    }
  }

  @Override
  public void removeLeftValue(long relationID, long leftID)
  {
    log.info("Sending 'rel remove left': rel{}/l{}", relationID, leftID);
    final CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_REMOVE_LEFT_VALUE);
    message.setRelationId(relationID);
    message.setLeftId(leftID);
    send(message);
  }

  @Override
  public void removeRightValue(long relationID, long rightID)
  {
    log.info("Sending 'rel remove right': rel{}/l{}", relationID, rightID);
    final CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_REMOVE_RIGHT_VALUE);
    message.setRelationId(relationID);
    message.setRightId(rightID);
    send(message);
  }

  @Override
  public void replaceAll(long relationID, LongRelation relation)
  {
    if (relation.size() > this.maximumRelationSize)
    {
      reset(relationID);
    }
    else
    {
      log.info("Sending 'rel replace all': rel{}", relationID);
      final CachedRelationMessage message = new CachedRelationMessage();
      message.setAction(CachedRelationMessage.ACTION_REPLACE_ALL);
      message.setRelationId(relationID);
      message.setRelation(relation);
      send(message);
    }
  }

  @Override
  public void reset(long relationID)
  {
    log.info("Sending 'rel reset': rel{}", relationID);
    final CachedRelationMessage message = new CachedRelationMessage();
    message.setAction(CachedRelationMessage.ACTION_RESET);
    message.setRelationId(relationID);
    send(message);
  }

  /**
   * Private inner class for listening to cache notifications
   */
  private class CacheSignalListener
      implements MessageListener
  {
    private long statsCollectionStart = System.currentTimeMillis();
    // No need for concurrent data structures since a listener will only get
    // one message at a time.
    private Map<String, Long> statsCount = new HashMap<>();
    private Map<String, Long> statsTxMin = new HashMap<>();
    private Map<String, Long> statsTxMax = new HashMap<>();
    private Map<String, Long> statsTxSum = new HashMap<>();
    private Map<String, Long> statsPxMin = new HashMap<>();
    private Map<String, Long> statsPxMax = new HashMap<>();
    private Map<String, Long> statsPxSum = new HashMap<>();
    private long statsCollectionMs = 0L;

    public CacheSignalListener(GeminiApplication application)
    {
    }

    /**
     * Asynchronously handles cache messages
     */
    @Override
    public void onMessage(javax.jms.Message message)
    {
      if (!store.isInitialized())
      {
        log.debug("EntityStore is not yet initialized. Ignoring message.");
        return;
      }
      long start = System.currentTimeMillis();
      String statsKey = null;
      BroadcastMessage broadcastMessage = null;
      // cast object to BroadcastMessage
      if (message instanceof ObjectMessage)
      {
        ObjectMessage obj = (ObjectMessage)message;
        try
        {
          if (obj.getObject() instanceof BroadcastMessage)
          {
            broadcastMessage = (BroadcastMessage)obj.getObject();
          }

          // ActiveMQ doesn't offer the ability to filter out messages sent
          // from self, so we do this check.
          // got the message, so verify it wasn't sent from self (this peer or
          // Supervisor)
          String senderUuid = message.getStringProperty(MESSAGE_PROPERTY_UUID);
          if (senderUuid == null)
          {
            log.info("Could not find the Unique Client ID sent from a cache update. Ignoring message.");
            return;
          }
          else if (senderUuid.equals(instanceID))
          {
            return;
          }
        }
        catch (JMSException | ClassCastException e)
        {
          log.info("CacheSignalListener::onMessage caught ", e);
          return;
        }
      }
      else
      {
        log.info("CacheSignalListener::onMessage: Someone sent a jms.Message of non-type ObjectMessage, so it cannot be converted to a CacheMessage.");
        return;
      }

      if (broadcastMessage instanceof CacheMessage)
      {
        final CacheMessage cacheMessage = (CacheMessage)broadcastMessage;
        statsKey = "g" + cacheMessage.getGroupId();

        // handle the message
        switch (cacheMessage.getAction())
        {
          case (CacheMessage.ACTION_FULL_RESET):
          {
            log.info("Receiving 'cache full reset': {}", cacheMessage);
            store.reset(true, false);
            break;
          }
          case (CacheMessage.ACTION_OBJECT_RESET):
          {
            @SuppressWarnings("unchecked")
            final EntityGroup<Identifiable> group = (EntityGroup<Identifiable>)store.getGroup(cacheMessage.getGroupId());
            if (group instanceof CacheGroup)
            {
              final CacheGroup<Identifiable> cg = (CacheGroup<Identifiable>)group;
              Identifiable entity = cg.get(cacheMessage.getObjectId());

              if (entity == null)
              {
                // This is a new entity, so create it and put it into the cache.
                entity = cg.newObjectFromMap(cacheMessage.getObjectProperties());
                cg.addToCache(entity);
                store.notifyListenersCacheObjectExpired(false, cg.getType(), entity.getId());
                log.info("Received 'cache object expired':{}", cacheMessage);
              }
              else
              {
                // This is an existing entity, so update it.
                cg.updateObjectFromMap(entity, cacheMessage.getObjectProperties());
                cg.reorder(entity.getId());
                store.notifyListenersCacheObjectExpired(false, cg.getType(), entity.getId());
                log.info(
                    "Received 'cache object expired': {}, existing entity: {}",
                    cacheMessage, entity);
              }
            }
            else if (group instanceof EntityGroup)
            {
              // No problem! Some instance has this as a CacheGroup, thus it is
              // sent over the message queue. But *this* instance does not have
              // it as a CacheGroup, only an EntityGroup, which means we have
              // nothing to update here.
            }
            else            {
              log.info("Receiving 'cache object expired' but group id is invalid:{}, group: {}",
                  cacheMessage, group);
            }
            // Now that the object is updated in the cache, update the method value cache if
            // needed.
            store.methodValueCacheUpdate(group.getType(), cacheMessage.getObjectId());

            break;
          }
          case (CacheMessage.ACTION_OBJECT_REMOVE):
          {
            @SuppressWarnings("unchecked")
            final EntityGroup<Identifiable> group = (EntityGroup<Identifiable>)store.getGroup(cacheMessage.getGroupId());
            if (group instanceof CacheGroup)
            {
              ((CacheGroup<Identifiable>)group).removeFromCache(cacheMessage.getObjectId());
              log.info("Received 'cache object remove' for: {}", cacheMessage);
            }
            else if (group instanceof EntityGroup)
            {
              // No problem! Some instance has this as a CacheGroup, thus it is
              // sent over the message queue. But *this* instance does not have
              // it as a CacheGroup, only an EntityGroup, which means we have
              // nothing to update here.
            }
            else
            {
              log.info("Received 'cache object remove' but group id is " +
                  "invalid: {}, group: {}, cacheMessage: {}",
                  cacheMessage.getGroupId(), group, cacheMessage);
            }
            // Now that the object is deleted from the cache, also delete from the method
            // value cache if needed.
            store.methodValueCacheDelete(group.getType(), cacheMessage.getObjectId());
            break;
          }
          case (CacheMessage.ACTION_GROUP_RESET):
          {
            store.reset(store.getGroup(cacheMessage.getGroupId()).type(), true, false);
            log.info("Received 'cache group reset' for group id {}, " +
                "cacheMessage: {}", cacheMessage.getGroupId(), cacheMessage);
            break;
          }
          default:
          {
            log.warn("Unknown CacheHandler action: {}, cacheMessage: {}",
                cacheMessage.getAction(), cacheMessage);
          }
        }
      }
      else if (broadcastMessage instanceof CachedRelationMessage)
      {
        final CachedRelationMessage cachedRelationMessage = (CachedRelationMessage)broadcastMessage;
        final CachedRelation<?, ?> relation = store.getCachedRelation(cachedRelationMessage.getRelationId());
        statsKey = "r" + cachedRelationMessage.getRelationId();
        switch (cachedRelationMessage.getAction())
        {
          case (CachedRelationMessage.ACTION_ADD):
          {
            relation.add(cachedRelationMessage.getLeftId(),
                cachedRelationMessage.getRightId(), false, true, false);
            log.info("Received 'rel add': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_ADD_ALL):
          {
            relation.addAll(cachedRelationMessage.getRelation(), false, true, false);
            log.info("Received 'rel add all': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_CLEAR):
          {
            relation.clear(false, true, false);
            log.info("Received 'rel clear': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_REMOVE):
          {
            relation.remove(cachedRelationMessage.getLeftId(),
                cachedRelationMessage.getRightId(), false, true, false);
            log.info("Received 'rel remove': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_REMOVE_ALL):
          {
            relation.removeAll(cachedRelationMessage.getRelation(), false,
                true, false);
            log.info("Received 'rel remove all': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_REMOVE_LEFT_VALUE):
          {
            relation.removeLeftValue(cachedRelationMessage.getLeftId(),
                false, true, false);
            log.info("Received 'rel remove left': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_REMOVE_RIGHT_VALUE):
          {
            relation.removeRightValue(cachedRelationMessage.getRightId(),
                false, true, false);
            log.info("Received 'rel remove right': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_REPLACE_ALL):
          {
            relation.replaceAll(cachedRelationMessage.getRelation(), false,
                true, false);
            log.info("Received 'rel replace all': {}", cachedRelationMessage);
            break;
          }
          case (CachedRelationMessage.ACTION_RESET):
          {
            relation.reset(true, false);
            log.info("Received 'rel reset': {}", cachedRelationMessage);
            break;
          }
          default:
          {
            log.info("Unknown CachedRelationHandler action: {}, " +
                "cachedRelationMessage: {}", cachedRelationMessage.getAction(),
                cachedRelationMessage);
          }
        }
      }

      if (statsKey != null) {
        // Gather statistics on transmission and receiver processing timings and periodically log a
        // summary.
        try {
          // Track how long it takes to gather the stats.
          long startStats = System.currentTimeMillis();

          // Processing time: How long it took to take action on the message.
          long pxTime = System.currentTimeMillis() - start;

          // Transmission time: How long it took to receive the message.
          long txTime = start - message.getJMSTimestamp();

          // Count: How many messages have been received during the reporting period.
          statsCount.merge(statsKey, 1L, Long::sum);

          // Transmission sum: Sum of transmission times during the reporting period.
          statsTxSum.merge(statsKey, txTime, Long::sum);

          // Transmission max: Longest recorded transmission time during the reporting period.
          long txMax = statsTxMax.getOrDefault(statsKey, -1L);
          if (txTime > txMax) {
            statsTxMax.put(statsKey, txTime);
            // Log the new max to help with investigating performance problems.
            if (txTime > statsLogMaxThresholdMs) {
              // To reduce logging noise, only log new max values that exceed the configured
              // threshold.
              log.info("Stats for " + statsKey + ": New transmission max of " + txTime + ": " + message);
            }
          }

          // Transmission min: Shortest recorded transmission time during the reporting period.
          long txMin = statsTxMin.getOrDefault(statsKey, -1L);
          if (txMin == -1L || txTime < txMin) {
            statsTxMin.put(statsKey, txTime);
          }

          // Processing sum: Sum of processing times during the reporting period.
          statsPxSum.merge(statsKey, pxTime, Long::sum);

          // Processing max: Longest recorded processing time during the reporting period.
          long pxMax = statsPxMax.getOrDefault(statsKey, -1L);
          if (pxTime > pxMax) {
            statsPxMax.put(statsKey, pxTime);
            // Log the new max to help with investigating performance problems.
            if (pxTime > statsLogMaxThresholdMs) {
              // To reduce logging noise, only log new max values that exceed the configured
              // threshold.
              log.info("Stats for " + statsKey + ": New receiver processing max of " + pxTime + ": " + message);
            }
          }

          // Processing min: Shortest recorded processing time during the reporting period.
          long pxMin = statsPxMin.getOrDefault(statsKey, -1L);
          if (pxMin == -1L || pxTime < pxMin) {
            statsPxMin.put(statsKey, pxTime);
          }

          // Record time spent gathering stats.
          statsCollectionMs += System.currentTimeMillis() - startStats;

          // Periodically log collected stats and reset accumulators.
          if ((System.currentTimeMillis() - statsCollectionStart) >
                (statsPeriodMinutes * UtilityConstants.MINUTE)) {

            // Log stats for each CacheGroup/CachedRelation that was received during the last period.
            for (String key : statsCount.keySet()) {
              // Count: How many messages have been received during the reporting period.
              long keyCount = statsCount.getOrDefault(key, -1L);

              // Transmission sum: Sum of transmission times during the reporting period.
              long keyTxSum = statsTxSum.getOrDefault(key, -1L);

              // Transmission avg: Average of transmission times during the reporting period.
              long keyTxAvg = keyCount > 0 ? keyTxSum / keyCount : 0;

              // Transmission max: Longest recorded transmission time during the reporting period.
              long keyTxMax = statsTxMax.getOrDefault(key, -1L);

              // Transmission min: Shortest recorded transmission time during the reporting period.
              long keyTxMin = statsTxMin.getOrDefault(key, -1L);;

              // Processing sum: Sum of processing times during the reporting period.
              long keyPxSum = statsPxSum.getOrDefault(key, -1L);;

              // Processing avg: Average of processing times during the reporting period.
              long keyPxAvg = keyCount > 0 ? keyPxSum / keyCount : 0;

              // Processing max: Longest recorded processing time during the reporting period.
              long keyPxMax = statsPxMax.getOrDefault(key, -1L);;

              // Processing min: Shortest recorded processing time during the reporting period.
              long keyPxMin = statsPxMin.getOrDefault(key, -1L);

              // Log summary of collected statistics.
              log.info("Stats summary for " + key + ": count " + keyCount
                  + " transmission (max/min/avg) " + keyTxMax
                  + " " + keyTxMin
                  + " " + keyTxAvg
                  + " receiver processing (max/min/avg) " + keyPxMax
                  + " " + keyPxMin
                  + " " + keyPxAvg);
            }

            // Count: How many messages have been received since last reset.
            long overallCount = statsCount.values().stream().mapToLong(Long::longValue).sum();

            // Transmission sum: Sum of transmission times during the reporting period.
            long overallTxSum = statsTxSum.values().stream().mapToLong(Long::longValue).sum();

            // Transmission avg: Average of transmission times during the reporting period.
            long overallTxAvg = overallCount > 0 ? overallTxSum / overallCount : 0;

            // Transmission max: Longest recorded transmission time during the reporting period.
            long overallTxMax = statsTxMax.values().stream().mapToLong(Long::longValue).max().orElse(-1);

            // Transmission min: Shortest recorded transmission time during the reporting period.
            long overallTxMin = statsTxMin.values().stream().mapToLong(Long::longValue).min().orElse(-1);

            // Processing sum: Sum of processing times during the reporting period.
            long overallPxSum = statsPxSum.values().stream().mapToLong(Long::longValue).sum();

            // Processing avg: Average of processing times during the reporting period.
            long overallPxAvg = overallCount > 0 ? overallPxSum / overallCount : 0;

            // Processing max: Longest recorded processing time during the reporting period.
            long overallPxMax = statsPxMax.values().stream().mapToLong(Long::longValue).max().orElse(-1);

            // Processing min: Shortest recorded processing time during the reporting period.
            long overallPxMin = statsPxMin.values().stream().mapToLong(Long::longValue).min().orElse(-1);

            // Log summary of collected statistics.
            log.info("Stats summary overall: count " + overallCount
                + " transmission (max/min/avg) " + overallTxMax
                + " " + overallTxMin
                + " " + overallTxAvg
                + " receiver processing (max/min/avg) " + overallPxMax
                + " " + overallPxMin
                + " " + overallPxAvg
                + " stats collection time: " + statsCollectionMs);

            // Reset all accumulators.
            statsCount.clear();
            statsTxMin.clear();
            statsTxMax.clear();
            statsTxSum.clear();
            statsPxMin.clear();
            statsPxMax.clear();
            statsPxSum.clear();
            statsCollectionMs = 0L;
            statsCollectionStart = System.currentTimeMillis();
          }
        } catch (Exception e) {
          log.error("Unable to gather statistics", e);
        }
      }
    }
  }
}
