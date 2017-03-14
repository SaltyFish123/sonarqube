/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.application.cluster;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IAtomicReference;
import com.hazelcast.core.ILock;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.ReplicatedMap;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.application.AppState;
import org.sonar.application.AppStateListener;
import org.sonar.application.config.AppSettings;
import org.sonar.process.ProcessId;

import static java.lang.String.format;
import static java.util.Collections.list;
import static org.apache.commons.lang.StringUtils.isBlank;

public class AppStateClusterImpl implements AppState {
  private static Logger LOGGER = LoggerFactory.getLogger(AppStateClusterImpl.class);

  static final String OPERATIONAL_PROCESSES = "OPERATIONAL_PROCESSES";
  static final String LEADER = "LEADER";
  static final String HOSTNAME = "HOSTNAME";
  static final String SONARQUBE_VERSION = "SONARQUBE_VERSION";

  private final List<AppStateListener> listeners = new ArrayList<>();
  private final ReplicatedMap<ClusterProcess, Boolean> operationalProcesses;
  private final Map<ProcessId, Boolean> localProcesses = new EnumMap<>(ProcessId.class);
  private final String operationalProcessListenerUUID;

  final HazelcastInstance hzInstance;

  public AppStateClusterImpl(AppSettings appSettings) {
    ClusterProperties clusterProperties = new ClusterProperties(appSettings);
    clusterProperties.validate();

    if (!clusterProperties.isEnabled()) {
      throw new IllegalStateException("Cluster is not enabled on this instance");
    }

    Config hzConfig = new Config();
    hzConfig.getGroupConfig().setName(clusterProperties.getName());

    // Configure the network instance
    NetworkConfig netConfig = hzConfig.getNetworkConfig();
    netConfig
      .setPort(clusterProperties.getPort())
      .setReuseAddress(true);

    if (!clusterProperties.getNetworkInterfaces().isEmpty()) {
      netConfig.getInterfaces()
        .setEnabled(true)
        .setInterfaces(clusterProperties.getNetworkInterfaces());
    }

    // Only allowing TCP/IP configuration
    JoinConfig joinConfig = netConfig.getJoin();
    joinConfig.getAwsConfig().setEnabled(false);
    joinConfig.getMulticastConfig().setEnabled(false);
    joinConfig.getTcpIpConfig().setEnabled(true);
    joinConfig.getTcpIpConfig().setMembers(clusterProperties.getHosts());

    // Tweak HazelCast configuration
    hzConfig
      // Increase the number of tries
      .setProperty("hazelcast.tcp.join.port.try.count", "10")
      // Don't bind on all interfaces
      .setProperty("hazelcast.socket.bind.any", "false")
      // Don't phone home
      .setProperty("hazelcast.phone.home.enabled", "false")
      // Use slf4j for logging
      .setProperty("hazelcast.logging.type", "slf4j");

    // Trying to resolve the hostname
    hzConfig.getMemberAttributeConfig().setStringAttribute(HOSTNAME, getHostname());

    // We are not using the partition group of Hazelcast, so disabling it
    hzConfig.getPartitionGroupConfig().setEnabled(false);

    // Create the Hazelcast instance
    hzInstance = Hazelcast.newHazelcastInstance(hzConfig);

    // Get or create the replicated map
    operationalProcesses = hzInstance.getReplicatedMap(OPERATIONAL_PROCESSES);
    operationalProcessListenerUUID = operationalProcesses.addEntryListener(new OperationalProcessListener());

    // Log the members of the cluster
    String members = hzInstance.getCluster().getMembers().stream()
      .filter(m -> !m.localMember())
      .map(m -> m.getStringAttribute(HOSTNAME))
      .collect(Collectors.joining(","));
    LOGGER.info("Joined the cluster [{}] that contains the following hosts : [{}]", hzInstance.getConfig().getGroupConfig().getName(), members);
  }

  @Override
  public void addListener(@Nonnull AppStateListener listener) {
    listeners.add(listener);
  }

  @Override
  public boolean isOperational(@Nonnull ProcessId processId, boolean local) {
    if (local) {
      return localProcesses.computeIfAbsent(processId, p -> false);
    }
    for (Map.Entry<ClusterProcess, Boolean> entry : operationalProcesses.entrySet()) {
      if (entry.getKey().getProcessId().equals(processId) && entry.getValue()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void setOperational(@Nonnull ProcessId processId) {
    localProcesses.put(processId, true);
    operationalProcesses.put(new ClusterProcess(getLocalUuid(), processId), Boolean.TRUE);
  }

  @Override
  public boolean tryToLockWebLeader() {
    IAtomicReference<String> leader = hzInstance.getAtomicReference(LEADER);
    if (leader.get() == null) {
      ILock lock = hzInstance.getLock(LEADER);
      lock.lock();
      try {
        if (leader.get() == null) {
          leader.set(getLocalUuid());
          return true;
        } else {
          return false;
        }
      } finally {
        lock.unlock();
      }
    } else {
      return false;
    }
  }

  @Override
  public void reset() {
    throw new IllegalStateException("state reset is not supported in cluster mode");
  }

  @Override
  public void close() {
    if (hzInstance != null) {
      // Removing listeners
      operationalProcesses.removeEntryListener(operationalProcessListenerUUID);

      // Removing the operationalProcess from the replicated map
      operationalProcesses.keySet().forEach(
        clusterNodeProcess -> {
          if (clusterNodeProcess.getNodeUuid().equals(getLocalUuid())) {
            operationalProcesses.remove(clusterNodeProcess);
          }
        });

      // Shutdown Hazelcast properly
      hzInstance.shutdown();
    }
  }

  @Override
  public void registerSonarQubeVersion(String sonarqubeVersion) {
    IAtomicReference<String> sqVersion = hzInstance.getAtomicReference(SONARQUBE_VERSION);
    if (sqVersion.get() == null) {
      ILock lock = hzInstance.getLock(SONARQUBE_VERSION);
      lock.lock();
      try {
        if (sqVersion.get() == null) {
          sqVersion.set(sonarqubeVersion);
        }
      } finally {
        lock.unlock();
      }
    }

    String clusterVersion = sqVersion.get();
    if (!sqVersion.get().equals(sonarqubeVersion)) {
      hzInstance.shutdown();
      throw new IllegalStateException(
        String.format("The local version %s is not the same as the cluster %s", sonarqubeVersion, clusterVersion)
      );
    }
  }

  String getLocalUuid() {
    return hzInstance.getLocalEndpoint().getUuid();
  }

  String getHostname() {
    String hostname;
    String ips;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      hostname = "unresolved hostname";
    }

    try {
      ips = list(NetworkInterface.getNetworkInterfaces()).stream()
        .flatMap(netif ->
          list(netif.getInetAddresses()).stream()
            .filter(inetAddress ->
              // Removing IPv6 for the time being
              inetAddress instanceof Inet4Address &&
                // Removing loopback addresses, useless for identifying a server
                !inetAddress.isLoopbackAddress() &&
                // Removing interfaces without IPs
                !isBlank(inetAddress.getHostAddress())
            )
            .map(InetAddress::getHostAddress)
        )
        .filter(p -> !isBlank(p))
        .collect(Collectors.joining(","));
    } catch (SocketException e) {
      ips = "unresolved IPs";
    }

    return format("%s (%s)", hostname, ips);
  }

  /**
   * Only used for testing purpose
   *
   * @param logger
   */
  static void setLogger(Logger logger) {
    AppStateClusterImpl.LOGGER = logger;
  }

  private class OperationalProcessListener implements EntryListener<ClusterProcess, Boolean> {

    @Override
    public void entryAdded(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryRemoved(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void entryUpdated(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryEvicted(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void mapCleared(MapEvent event) {
      // Ignore it
    }

    @Override
    public void mapEvicted(MapEvent event) {
      // Ignore it
    }
  }
}
