/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.metadata;

import com.datastax.oss.driver.api.core.CassandraVersion;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metrics.NodeMetricUpdater;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jcip.annotations.ThreadSafe;

/**
 * Implementation note: all the mutable state in this class is read concurrently, but only mutated
 * from {@link MetadataManager}'s admin thread.
 */
@ThreadSafe
public class DefaultNode implements Node {

  private final InetSocketAddress connectAddress;
  private final NodeMetricUpdater metricUpdater;

  volatile Optional<InetAddress> broadcastAddress;
  volatile Optional<InetAddress> listenAddress;
  volatile String datacenter;
  volatile String rack;
  volatile CassandraVersion cassandraVersion;
  // Keep a copy of the raw tokens, to detect if they have changed when we refresh the node
  volatile Set<String> rawTokens;
  volatile Map<String, Object> extras;
  volatile UUID hostId;
  volatile UUID schemaVersion;

  // These 3 fields are read concurrently, but only mutated on NodeStateManager's admin thread
  volatile NodeState state;
  volatile int openConnections;
  volatile int reconnections;

  volatile NodeDistance distance;

  public DefaultNode(InetSocketAddress connectAddress, InternalDriverContext context) {
    this.connectAddress = connectAddress;
    this.state = NodeState.UNKNOWN;
    this.distance = NodeDistance.IGNORED;
    this.rawTokens = Collections.emptySet();
    this.extras = Collections.emptyMap();
    // We leak a reference to a partially constructed object (this), but in practice this won't be a
    // problem because the node updater only needs the connect address to initialize.
    this.metricUpdater = context.metricsFactory().newNodeUpdater(this);
  }

  @Override
  public InetSocketAddress getConnectAddress() {
    return connectAddress;
  }

  @Override
  public Optional<InetAddress> getBroadcastAddress() {
    return broadcastAddress;
  }

  @Override
  public Optional<InetAddress> getListenAddress() {
    return listenAddress;
  }

  @Override
  public String getDatacenter() {
    return datacenter;
  }

  @Override
  public String getRack() {
    return rack;
  }

  @Override
  public CassandraVersion getCassandraVersion() {
    return cassandraVersion;
  }

  @Override
  public UUID getHostId() {
    return hostId;
  }

  @Override
  public UUID getSchemaVersion() {
    return schemaVersion;
  }

  @Override
  public Map<String, Object> getExtras() {
    return extras;
  }

  @Override
  public NodeState getState() {
    return state;
  }

  @Override
  public int getOpenConnections() {
    return openConnections;
  }

  @Override
  public boolean isReconnecting() {
    return reconnections > 0;
  }

  @Override
  public NodeDistance getDistance() {
    return distance;
  }

  public NodeMetricUpdater getMetricUpdater() {
    return metricUpdater;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof Node) {
      Node that = (Node) other;
      return this.connectAddress.equals(that.getConnectAddress());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return connectAddress.hashCode();
  }

  @Override
  public String toString() {
    return connectAddress.toString();
  }

  /** Note: deliberately not exposed by the public interface. */
  public Set<String> getRawTokens() {
    return rawTokens;
  }
}
