/*
 * Copyright (C) 2017-2017 DataStax Inc.
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
package com.datastax.oss.driver.internal.core.context;

import com.datastax.oss.driver.api.core.addresstranslation.AddressTranslator;
import com.datastax.oss.driver.api.core.auth.AuthProvider;
import com.datastax.oss.driver.api.core.config.CoreDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfig;
import com.datastax.oss.driver.api.core.connection.ReconnectionPolicy;
import com.datastax.oss.driver.api.core.loadbalancing.LoadBalancingPolicy;
import com.datastax.oss.driver.api.core.ssl.SslEngineFactory;
import com.datastax.oss.driver.internal.core.ProtocolVersionRegistry;
import com.datastax.oss.driver.internal.core.channel.ChannelFactory;
import com.datastax.oss.driver.internal.core.channel.DefaultWriteCoalescer;
import com.datastax.oss.driver.internal.core.channel.WriteCoalescer;
import com.datastax.oss.driver.internal.core.control.ControlConnection;
import com.datastax.oss.driver.internal.core.metadata.DefaultTopologyMonitor;
import com.datastax.oss.driver.internal.core.metadata.LoadBalancingPolicyWrapper;
import com.datastax.oss.driver.internal.core.metadata.MetadataManager;
import com.datastax.oss.driver.internal.core.metadata.TopologyMonitor;
import com.datastax.oss.driver.internal.core.protocol.ByteBufPrimitiveCodec;
import com.datastax.oss.driver.internal.core.ssl.JdkSslHandlerFactory;
import com.datastax.oss.driver.internal.core.ssl.SslHandlerFactory;
import com.datastax.oss.driver.internal.core.util.Reflection;
import com.datastax.oss.driver.internal.core.util.concurrent.CycleDetector;
import com.datastax.oss.driver.internal.core.util.concurrent.LazyReference;
import com.datastax.oss.protocol.internal.Compressor;
import com.datastax.oss.protocol.internal.FrameCodec;
import io.netty.buffer.ByteBuf;
import java.util.Optional;

/**
 * Default implementation of the driver context.
 *
 * <p>All non-constant components are initialized lazily. Some components depend on others, so there
 * might be deadlocks or stack overflows if the dependency graph is badly designed. This can be
 * checked automatically with the system property {@code
 * -Dcom.datastax.oss.driver.DETECT_CYCLES=true} (this might have a slight impact on startup time,
 * so the check is disabled by default).
 *
 * <p>This is DIY dependency injection. We stayed away from DI frameworks for simplicity, to avoid
 * an extra dependency, and because end users might want to access some of these components in their
 * own implementations (which wouldn't work well with compile-time approaches like Dagger).
 *
 * <p>This also provides extension points for stuff that is too low-level for the driver
 * configuration: the intent is that someone can extend this class, override one (or more) of the
 * buildXxx methods, and initialize the cluster with this new implementation.
 */
public class DefaultDriverContext implements InternalDriverContext {

  private final CycleDetector cycleDetector =
      new CycleDetector("Detected cycle in context initialization");

  private final LazyReference<LoadBalancingPolicy> loadBalancingPolicyRef =
      new LazyReference<>("loadBalancingPolicy", this::buildLoadBalancingPolicy, cycleDetector);
  private final LazyReference<ReconnectionPolicy> reconnectionPolicyRef =
      new LazyReference<>("reconnectionPolicy", this::buildReconnectionPolicy, cycleDetector);
  private final LazyReference<AddressTranslator> addressTranslatorRef =
      new LazyReference<>("addressTranslator", this::buildAddressTranslator, cycleDetector);
  private final LazyReference<Optional<AuthProvider>> authProviderRef =
      new LazyReference<>("authProvider", this::buildAuthProvider, cycleDetector);
  private final LazyReference<Optional<SslEngineFactory>> sslEngineFactoryRef =
      new LazyReference<>("sslEngineFactory", this::buildSslEngineFactory, cycleDetector);

  private final LazyReference<EventBus> eventBusRef =
      new LazyReference<>("eventBus", this::buildEventBus, cycleDetector);
  private final LazyReference<Compressor<ByteBuf>> compressorRef =
      new LazyReference<>("compressor", this::buildCompressor, cycleDetector);
  private final LazyReference<FrameCodec<ByteBuf>> frameCodecRef =
      new LazyReference<>("frameCodec", this::buildFrameCodec, cycleDetector);
  private final LazyReference<ProtocolVersionRegistry> protocolVersionRegistryRef =
      new LazyReference<>(
          "protocolVersionRegistry", this::buildProtocolVersionRegistry, cycleDetector);
  private final LazyReference<NettyOptions> nettyOptionsRef =
      new LazyReference<>("nettyOptions", this::buildNettyOptions, cycleDetector);
  private final LazyReference<WriteCoalescer> writeCoalescerRef =
      new LazyReference<>("writeCoalescer", this::buildWriteCoalescer, cycleDetector);
  private final LazyReference<Optional<SslHandlerFactory>> sslHandlerFactoryRef =
      new LazyReference<>("sslHandlerFactory", this::buildSslHandlerFactory, cycleDetector);
  private final LazyReference<ChannelFactory> channelFactoryRef =
      new LazyReference<>("channelFactory", this::buildChannelFactory, cycleDetector);
  private final LazyReference<TopologyMonitor> topologyMonitorRef =
      new LazyReference<>("topologyMonitor", this::buildTopologyMonitor, cycleDetector);
  private final LazyReference<MetadataManager> metadataManagerRef =
      new LazyReference<>("metadataManager", this::buildMetadataManager, cycleDetector);
  private final LazyReference<LoadBalancingPolicyWrapper> loadBalancingPolicyWrapperRef =
      new LazyReference<>(
          "loadBalancingPolicyWrapper", this::buildLoadBalancingPolicyWrapper, cycleDetector);
  private final LazyReference<ControlConnection> controlConnectionRef =
      new LazyReference<>("controlConnection", this::buildControlConnection, cycleDetector);

  private final DriverConfig config;

  public DefaultDriverContext(DriverConfig config) {
    this.config = config;
  }

  protected LoadBalancingPolicy buildLoadBalancingPolicy() {
    CoreDriverOption classOption = CoreDriverOption.LOAD_BALANCING_POLICY_CLASS;
    return Reflection.buildFromConfig(this, classOption, LoadBalancingPolicy.class)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "Missing load balancing policy, check your configuration (%s)",
                        classOption)));
  }

  protected ReconnectionPolicy buildReconnectionPolicy() {
    CoreDriverOption classOption = CoreDriverOption.RECONNECTION_POLICY_CLASS;
    return Reflection.buildFromConfig(this, classOption, ReconnectionPolicy.class)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "Missing reconnection policy, check your configuration (%s)",
                        classOption)));
  }

  protected AddressTranslator buildAddressTranslator() {
    CoreDriverOption classOption = CoreDriverOption.ADDRESS_TRANSLATOR_CLASS;
    return Reflection.buildFromConfig(this, classOption, AddressTranslator.class)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "Missing address translator, check your configuration (%s)", classOption)));
  }

  protected Optional<AuthProvider> buildAuthProvider() {
    return Reflection.buildFromConfig(
        this, CoreDriverOption.AUTHENTICATION_PROVIDER_CLASS, AuthProvider.class);
  }

  protected Optional<SslEngineFactory> buildSslEngineFactory() {
    return Reflection.buildFromConfig(
        this, CoreDriverOption.SSL_FACTORY_CLASS, SslEngineFactory.class);
  }

  protected EventBus buildEventBus() {
    return new EventBus();
  }

  protected Compressor<ByteBuf> buildCompressor() {
    // TODO build alternate implementation if specified in conf
    return Compressor.none();
  }

  protected FrameCodec<ByteBuf> buildFrameCodec() {
    return FrameCodec.defaultClient(
        new ByteBufPrimitiveCodec(nettyOptions().allocator()), compressor());
  }

  protected ProtocolVersionRegistry buildProtocolVersionRegistry() {
    return new ProtocolVersionRegistry();
  }

  protected NettyOptions buildNettyOptions() {
    return new DefaultNettyOptions();
  }

  protected Optional<SslHandlerFactory> buildSslHandlerFactory() {
    // If a JDK-based factory was provided through the public API, wrap it
    return buildSslEngineFactory().map(JdkSslHandlerFactory::new);

    // For more advanced options (like using Netty's native OpenSSL support instead of the JDK),
    // extend DefaultDriverContext and override this method
  }

  protected WriteCoalescer buildWriteCoalescer() {
    return new DefaultWriteCoalescer(5);
  }

  protected ChannelFactory buildChannelFactory() {
    return new ChannelFactory(this);
  }

  protected TopologyMonitor buildTopologyMonitor() {
    return new DefaultTopologyMonitor(this);
  }

  protected MetadataManager buildMetadataManager() {
    return new MetadataManager(this);
  }

  protected LoadBalancingPolicyWrapper buildLoadBalancingPolicyWrapper() {
    return new LoadBalancingPolicyWrapper(this, loadBalancingPolicy());
  }

  protected ControlConnection buildControlConnection() {
    return new ControlConnection(this);
  }

  @Override
  public DriverConfig config() {
    return config;
  }

  @Override
  public LoadBalancingPolicy loadBalancingPolicy() {
    return loadBalancingPolicyRef.get();
  }

  @Override
  public ReconnectionPolicy reconnectionPolicy() {
    return reconnectionPolicyRef.get();
  }

  @Override
  public AddressTranslator addressTranslator() {
    return addressTranslatorRef.get();
  }

  @Override
  public Optional<AuthProvider> authProvider() {
    return authProviderRef.get();
  }

  @Override
  public Optional<SslEngineFactory> sslEngineFactory() {
    return sslEngineFactoryRef.get();
  }

  @Override
  public EventBus eventBus() {
    return eventBusRef.get();
  }

  @Override
  public Compressor<ByteBuf> compressor() {
    return compressorRef.get();
  }

  @Override
  public FrameCodec<ByteBuf> frameCodec() {
    return frameCodecRef.get();
  }

  @Override
  public ProtocolVersionRegistry protocolVersionRegistry() {
    return protocolVersionRegistryRef.get();
  }

  @Override
  public NettyOptions nettyOptions() {
    return nettyOptionsRef.get();
  }

  @Override
  public WriteCoalescer writeCoalescer() {
    return writeCoalescerRef.get();
  }

  @Override
  public Optional<SslHandlerFactory> sslHandlerFactory() {
    return sslHandlerFactoryRef.get();
  }

  @Override
  public ChannelFactory channelFactory() {
    return channelFactoryRef.get();
  }

  @Override
  public TopologyMonitor topologyMonitor() {
    return topologyMonitorRef.get();
  }

  @Override
  public MetadataManager metadataManager() {
    return metadataManagerRef.get();
  }

  @Override
  public LoadBalancingPolicyWrapper loadBalancingPolicyWrapper() {
    return loadBalancingPolicyWrapperRef.get();
  }

  @Override
  public ControlConnection controlConnection() {
    return controlConnectionRef.get();
  }
}
